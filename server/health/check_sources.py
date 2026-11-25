#!/usr/bin/env python3
"""Source health checker: runs the existing probes, opens an issue per broken source, pings your phone.

Run from the repository root on the server (the Forgejo workflow .forgejo/workflows/source-health.yml
does this every few hours):

    python3 server/health/check_sources.py

What it does
  1. Runs the Live TV probe (provider-engine/tools/live-probe.mjs) and, unless SKIP_VOD=1, the
     film/series probe (android-tv-launcher/tests/probe-free-vod.py) for two well-known films.
     The workflow runs Live TV every hour and films every 6 hours.
  2. A Live TV source that fails is tested again LIVE_RETRY_SECONDS later (default 300) in the same
     run, so a blip is ignored but a real break is reported within about an hour. Film sites count
     as broken after VOD_FAIL_RUNS failed runs in a row (default 2); Live TV after LIVE_FAIL_RUNS
     (default 1, because of the retry).
  3. Broken: opens one Forgejo issue titled "[broken source] <id>" with the probe result and the saved
     page, labelled "broken-source", and sends one push notification. Already open: adds a short
     comment at most once a day. A later successful sample leaves the incident open for review.

Settings (environment variables)
  FORGEJO_URL     e.g. http://forgejo:3000   (empty = print only, no issues)
  FORGEJO_TOKEN   token with issue write access
  FORGEJO_REPO    owner/name, e.g. billy/moviehub
  NTFY_URL        e.g. https://ntfy.sh/moviehub-<long random words>   (empty = no notifications)
  STATE_FILE      default /srv/moviehub/health/state.json
  EVIDENCE_DIR    default /srv/moviehub/health/evidence
  LIVE_FAIL_RUNS  default 1        VOD_FAIL_RUNS  default 2       LIVE_RETRY_SECONDS  default 300
  SKIP_LIVE / SKIP_VOD   set to 1 to skip a probe
"""
import datetime
import fcntl
import tempfile
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VOD_PROBE = ROOT / 'android-tv-launcher/tests/probe-free-vod.py'
LIVE_PROBE = ROOT / 'provider-engine/tools/live-probe.mjs'
LIVE_RESULT = ROOT / 'build/provider-tests/live-probe.json'
FILMS = [
    ['Night of the Living Dead', '1968', 'tt0063350', '10331'],
    ['Inception', '2010', 'tt1375666', '27205'],
]
LABEL = 'broken-source'
TITLE = '[broken source] '


# ----------------------------------------------------------------------------- deciding health

def vod_results(runs):
    """runs: list of JSONL outputs (one per film). Links returned by discovery have unknown playback health."""
    sites = {}
    for output in runs:
        for line in output.splitlines():
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if not isinstance(row, dict) or not isinstance(row.get('provider'), str):
                continue
            key = 'vod:' + row['provider']
            entry = sites.setdefault(key, {'id': key, 'kind': 'film/series site', 'ok': False, 'records': []})
            if type(row.get('streams')) is not int or row['streams'] > 0:
                entry['ok'] = None
            entry['records'].append(row)
    for entry in sites.values():
        statuses = sorted({str(r.get('status')) for r in entry['records']})
        entry['summary'] = ('playback unverified (discovery only)' if entry['ok'] is None else
                            'no streams for either test film (status: %s)' % ', '.join(statuses))
    return sites


def live_results(results):
    """results: parsed live-probe.json. Healthy = catalogue loads and at least one sample plays."""
    sources = {}
    for r in results:
        key = 'live:' + r['id']
        samples = r.get('samples', [])
        catalogue_ok = r.get('catalogue') == 'ok'
        plays = [s for s in samples if s.get('play') == 'ok' and
                 type(s.get('audioFrames')) is int and s['audioFrames'] >= 10 and
                 type(s.get('videoFrames')) is int and s['videoFrames'] >= 10]
        ok = catalogue_ok and bool(plays)
        if not catalogue_ok:
            summary = 'channel list failed: %s' % r.get('catalogue')
        elif not samples or r.get('playbackScope') == 'not_tested':
            ok = None
            summary = 'channel list loaded; playback was not tested'
        elif not ok and any(s.get('play') == 'ok' for s in samples):
            ok = None
            summary = 'decode counters missing or insufficient'
        elif not ok:
            summary = 'channel list loads (%s channels) but no sample played' % r.get('channels')
        else:
            summary = '%d of %d samples played' % (len(plays), len(samples))
        sources[key] = {'id': key, 'kind': 'Live TV provider', 'ok': ok, 'summary': summary, 'records': [r]}
    return sources


def decide(state, results, fail_runs, now):
    """Updates per-source counters; returns (newly_broken, still_broken, recovered) lists of ids.

    fail_runs: a number, or a function of the source id giving the number for that source.
    Sources not in results (e.g. films on an hourly Live TV run) are left untouched.
    """
    runs_for = fail_runs if callable(fail_runs) else (lambda key: fail_runs)
    newly, still, recovered = [], [], []
    for key, result in results.items():
        entry = state.setdefault(key, {'fails': 0, 'broken': False, 'lastComment': 0})
        if result['ok'] is None:
            continue
        if result['ok']:
            if entry['broken'] and not entry.get('sampleResumed'):
                recovered.append(key)
                entry['sampleResumed'] = True
            entry['fails'] = 0
            continue
        entry.pop('sampleResumed', None)
        entry['fails'] += 1
        if entry['broken']:
            still.append(key)
        elif entry['fails'] >= runs_for(key):
            entry['broken'] = True
            entry['lastComment'] = now
            newly.append(key)
    return newly, still, recovered


# ----------------------------------------------------------------------------- running probes

def run_vod():
    outputs = []
    for film in FILMS:
        command = [sys.executable, str(VOD_PROBE), '--timeout-seconds', '75', '--movie', *film]
        done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=1800)
        if done.returncode == 0:
            lines = []
            for line in done.stdout.splitlines():
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict) and 'provider' in row:
                    row.update(tmdb=int(film[3]), season=-1, episode=-1)
                    lines.append(json.dumps(row))
            outputs.append('\n'.join(lines))
        else:
            sys.stderr.write('Film probe exited %s: %s\n' % (done.returncode, done.stderr[-500:]))
    return outputs


def run_live(ids=()):
    """Runs the Live TV probe for all providers, or only the given provider ids."""
    rhino = os.environ.get('RHINO_JAR', '')
    if not rhino or not Path(rhino).is_file():
        sys.stderr.write('RHINO_JAR not set; skipping Live TV probe\n')
        return []
    LIVE_RESULT.unlink(missing_ok=True)
    done = subprocess.run(['node', str(LIVE_PROBE), *ids], cwd=ROOT, capture_output=True, text=True,
                          timeout=3600, env={**os.environ, 'RHINO_JAR': rhino})
    if done.returncode != 0:
        sys.stderr.write('Live probe exited %s: %s\n' % (done.returncode, done.stderr[-500:]))
        return []
    try:
        return json.loads(LIVE_RESULT.read_text())
    except (OSError, ValueError):
        return []


def site_of(provider_id):
    """The website address for a film site id, from the app's own list."""
    sys.path.insert(0, str(ROOT / 'android-tv-launcher/app/src/main/python'))
    try:
        import moviehub_vod
        for row in json.loads(moviehub_vod.search_providers(include_retired=True)):
            if row['id'] == provider_id:
                return row['site']
    except Exception:
        return None
    return None


def live_with_retry(run, wait, sleep=time.sleep):
    """Live TV results; failing providers are probed once more after `wait` seconds."""
    results = live_results(run())
    failing = [key[5:] for key, r in results.items() if r['ok'] is False]
    if failing and wait > 0:
        sleep(wait)
        retried = live_results(run(failing))
        for key, result in retried.items():
            if result['ok'] is False:
                result['summary'] += ' (failed twice, %d s apart)' % wait
        # A missing retry is inconclusive, not a second observed failure.
        for provider in failing:
            key = 'live:' + provider
            if key in retried:
                results[key] = retried[key]
            else:
                results[key]['ok'] = None
                results[key]['summary'] = 'retry produced no result'
    return results


def save_evidence(evidence_dir, result, stamp):
    """Stores the probe record and, for film sites, the site's current front page."""
    folder = Path(evidence_dir) / re.sub(r'[^A-Za-z0-9_.-]', '_', result['id']) / stamp
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'probe.json').write_text(json.dumps(result['records'], indent=2))
    if result['id'].startswith('vod:'):
        site = site_of(result['id'][4:])
        if site:
            try:
                request = urllib.request.Request('https://' + site + '/', headers={
                    'User-Agent': 'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36'})
                with urllib.request.urlopen(request, timeout=20) as response:
                    (folder / 'front-page.html').write_bytes(response.read(1024 * 1024))
                    (folder / 'front-page.txt').write_text('final address: %s\nstatus: %s\n'
                                                           % (response.geturl(), response.status))
            except Exception as error:
                (folder / 'front-page.txt').write_text('could not load https://%s/: %s\n' % (site, error))
    return folder


# ----------------------------------------------------------------------------- Forgejo and ntfy

class Forgejo:
    def __init__(self, url, token, repo):
        self.url, self.token, self.repo = url.rstrip('/'), token, repo

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request('%s/api/v1/repos/%s%s' % (self.url, self.repo, path), data=data,
                                         method=method, headers={'Authorization': 'token ' + self.token,
                                                                 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return json.loads(text) if text else None

    def open_issues(self):
        found = {}
        page = 1
        while True:
            rows = self.call('GET', '/issues?state=open&type=issues&limit=50&page=%d&labels=%s' % (page, LABEL)) or []
            found.update({i['title'][len(TITLE):]: i for i in rows if i['title'].startswith(TITLE)})
            if len(rows) < 50:
                return found
            page += 1

    def label_id(self):
        for label in self.call('GET', '/labels?limit=50') or []:
            if label['name'] == LABEL:
                return label['id']
        return self.call('POST', '/labels', {'name': LABEL, 'color': '#d73a4a',
                                             'description': 'Opened by the source health checker'})['id']


def notify(url, title, message):
    if not url:
        return
    request = urllib.request.Request(url, data=message.encode(), method='POST',
                                     headers={'Title': title, 'Tags': 'tv'})
    try:
        urllib.request.urlopen(request, timeout=15).close()
    except Exception as error:
        sys.stderr.write('Notification failed: %s\n' % error)


def issue_body(result, folder):
    record = json.dumps(result['records'], indent=2)
    if len(record) > 6000:
        record = record[:6000] + '\n… (full record in probe.json)'
    reproduce = ('python3 android-tv-launcher/tests/probe-free-vod.py --provider %s --movie "Inception" '
                 '2010 tt1375666 27205' % result['id'][4:] if result['id'].startswith('vod:') else
                 'RHINO_JAR=$RHINO_JAR node provider-engine/tools/live-probe.mjs %s' % result['id'][5:])
    if result['id'].startswith('vod:'):
        cases = [[r['tmdb'], r['season'], r['episode']] for r in result['records']
                 if 'tmdb' in r and r.get('streams') == 0]
    else:
        cases = [s['caseId'] for r in result['records'] for s in r.get('samples', [])
                 if s.get('play') != 'ok' and re.fullmatch('[a-f0-9]{64}', s.get('caseId', ''))]
    return ('**%s** (%s) is failing: %s.\n\n'
            '<!-- moviehub-playback-cases %s -->\n\n'
            'Evidence saved on the server: `%s`\n\n'
            'Reproduce:\n```\n%s\n```\n\n'
            'Fix it by following [docs/FIX_A_BROKEN_SOURCE.md](../src/branch/main/docs/FIX_A_BROKEN_SOURCE.md). '
            'This diagnostic alone cannot confirm a repair. Verify the affected playback case independently.\n\n'
            '<details><summary>Probe result</summary>\n\n```json\n%s\n```\n</details>\n'
            % (result['id'], result['kind'], result['summary'], json.dumps(cases), folder, reproduce, record))


# ----------------------------------------------------------------------------- main

def main():
    env = os.environ
    state_file = Path(env.get('STATE_FILE', '/srv/moviehub/health/state.json'))
    state_file.parent.mkdir(parents=True, exist_ok=True)
    with state_file.with_suffix(state_file.suffix + '.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            sys.exit('Another source health check is running')
        check(env, state_file)


def check(env, state_file):
    evidence = env.get('EVIDENCE_DIR', '/srv/moviehub/health/evidence')
    live_runs = int(env.get('LIVE_FAIL_RUNS', '1'))
    vod_runs = int(env.get('VOD_FAIL_RUNS', '2'))
    def fail_runs(key):
        return live_runs if key.startswith('live:') else vod_runs
    results = {}
    if env.get('SKIP_LIVE') != '1':
        results.update(live_with_retry(run_live, int(env.get('LIVE_RETRY_SECONDS', '300'))))
    if env.get('SKIP_VOD') != '1':
        results.update(vod_results(run_vod()))
    if not results:
        sys.exit('No probe produced results; check the logs above.')

    state = json.loads(state_file.read_text()) if state_file.is_file() else {}
    now = int(time.time())
    stamp = datetime.datetime.now().strftime('%Y-%m-%d_%H%M')

    forgejo = None
    if env.get('FORGEJO_URL') and env.get('FORGEJO_TOKEN') and env.get('FORGEJO_REPO'):
        forgejo = Forgejo(env['FORGEJO_URL'], env['FORGEJO_TOKEN'], env['FORGEJO_REPO'])
    issues = forgejo.open_issues() if forgejo else {}
    # A reviewer can close an incident after confirming the affected content on TV.
    # Re-arm its counter for the next outage, including state from older server versions.
    if forgejo:
        for key in results:
            if state.get(key, {}).get('broken') and key not in issues:
                state.pop(key)
    newly, still, recovered = decide(state, results, fail_runs, now)

    for key in newly:
        folder = save_evidence(evidence, results[key], stamp)
        if forgejo and key not in issues:
            forgejo.call('POST', '/issues', {'title': TITLE + key, 'body': issue_body(results[key], folder),
                                             'labels': [forgejo.label_id()]})
        if key not in issues:
            notify(env.get('NTFY_URL'), 'MovieHub: %s broken' % key, results[key]['summary'])
    for key in still:
        entry = state[key]
        if forgejo and key in issues and now - entry.get('lastComment', 0) > 23 * 3600:
            folder = save_evidence(evidence, results[key], stamp)
            forgejo.call('POST', '/issues/%d/comments' % issues[key]['number'],
                         {'body': 'Still failing (%s). New evidence: `%s`' % (results[key]['summary'], folder)})
            entry['lastComment'] = now
    for key in recovered:
        if forgejo and key in issues:
            forgejo.call('POST', '/issues/%d/comments' % issues[key]['number'],
                         {'body': 'A scheduled server sample decoded: %s. The originally reported '
                                  'channel or title still needs confirmation.' % results[key]['summary']})

    with tempfile.NamedTemporaryFile('w', dir=state_file.parent, delete=False) as temp:
        try:
            json.dump(state, temp, indent=2, sort_keys=True)
            temp.flush()
            os.fsync(temp.fileno())
            os.replace(temp.name, state_file)
        finally:
            Path(temp.name).unlink(missing_ok=True)
    healthy = sum(r['ok'] is True for r in results.values())
    unverified = sum(r['ok'] is None for r in results.values())
    print(json.dumps({'checked': len(results), 'samplesPlayed': healthy, 'unverified': unverified, 'newlyBroken': newly,
                      'stillBroken': still, 'sampleResumed': recovered}))


if __name__ == '__main__':
    main()
