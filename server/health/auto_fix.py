#!/usr/bin/env python3
"""Hands a "[broken source]" issue to Codex, then checks the result itself.

    python3 server/health/auto_fix.py <issue number>
    python3 server/health/auto_fix.py --next      # the next waiting issue (the hourly sweep)

Run from a clean checkout of main on the server (the Forgejo workflow auto-fix.yml does this when the
health checker opens an issue, and every hour for anything left waiting). Steps:

  1. Picks the issue (Live TV before films), skips it if it is "on-hold" or already "auto-fix-tried",
     and marks it tried so it is never attempted twice.
  2. Runs Codex on its own Linux machine, the sandbox (sandbox.py, set up by server/bootstrap/sandbox.sh):
     reset to a clean snapshot, given a copy of the repository and a prompt with the scraper's current
     code, the failure and the test command. Only its patch comes back.
  3. Refuses the patch if it touches anything outside the scraper folders, or the checks themselves
     (live-probe.mjs, soak.mjs, bait-frames/).
  4. Runs the live probe ITSELF. Codex saying "fixed" counts for nothing: only streams > 0 (film site)
     or a Live TV sample that plays AND keeps playing for FIX_SOAK_SECONDS (default 90) does.
  5. Passed: commits only the changed source files, pushes, opens a pull request with the real probe
     output and the soak pictures. Failed: comments on the issue, opens nothing.

Without the sandbox it refuses to run Codex, unless CODEX_UNSANDBOXED=1: Codex would then run on this
server as the same user that owns the signing key.

Settings: FORGEJO_URL, FORGEJO_TOKEN, FORGEJO_REPO (as check_sources.py), RHINO_JAR, CODEX_CMD,
CODEX_MINUTES (default 30), FIX_SOAK_SECONDS (default 90), EVIDENCE_DIR, the sandbox settings in
sandbox.py, CODEX_UNSANDBOXED.
"""
import datetime
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_sources as health  # noqa: E402
import sandbox  # noqa: E402

ROOT = health.ROOT
ALLOWED = ('android-tv-launcher/app/src/main/python/', 'provider-engine/')
# Inside ALLOWED but part of the checks, not of any scraper: a fix may never change these.
PROTECTED = ('provider-engine/tools/live-probe.mjs', 'provider-engine/tools/soak.mjs')
# Built from provider-engine/ by `npm run build`. Codex's copies are ignored; this script rebuilds them,
# so the probe tests the changed Live TV code (it reads builtin.json) and the commit stays consistent.
GENERATED = ('android-tv-launcher/app/src/main/assets/providers/builtin.json',
             'android-tv-launcher/app/src/main/assets/providers/LICENSE.masqueradarr.txt')


def git(*args, check=True):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, check=check).stdout


def probe(source):
    """Returns (ok, output, result) by running the real probe for one source."""
    if source.startswith('vod:'):
        film = health.FILMS[1]
        done = subprocess.run([sys.executable, str(health.VOD_PROBE), '--provider', source[4:],
                               '--timeout-seconds', '120', '--movie', *film],
                              cwd=ROOT, capture_output=True, text=True, timeout=900)
        results = health.vod_results([done.stdout])
    else:
        env = {**os.environ, 'MOVIEHUB_SOAK_SECONDS': os.environ.get('FIX_SOAK_SECONDS', '90'),
               'MOVIEHUB_BAIT_DIR': str(health.BAIT_DIR)}
        done = subprocess.run(['node', str(health.LIVE_PROBE), source[5:]], cwd=ROOT, capture_output=True,
                              text=True, timeout=1800, env=env)
        try:
            results = health.live_results(json.loads(health.LIVE_RESULT.read_text()))
        except (OSError, ValueError):
            results = {}
    result = results.get(source)
    output = (done.stdout or '')[-3000:]
    return bool(result and result['ok']), (result['summary'] if result else 'no result') + '\n' + output, result


def rejected(files):
    """Changed files a fix may not touch: outside the scraper folders, or the checks themselves."""
    return [f for f in files if f not in GENERATED and (
        not f.startswith(ALLOWED) or f in PROTECTED or f.startswith('provider-engine/dist/') or
        f.startswith('server/health/bait-frames/'))]


def rebuild_providers():
    """Rebuilds builtin.json from provider-engine/ after a Live TV change. Returns '' or the error."""
    if not (ROOT / 'provider-engine/node_modules').is_dir():
        done = subprocess.run(['npm', 'ci', '--ignore-scripts', '--prefix', 'provider-engine'], cwd=ROOT,
                              capture_output=True, text=True, timeout=600)
        if done.returncode != 0:
            return 'npm ci failed: ' + done.stderr[-400:]
    done = subprocess.run(['npm', 'run', 'build', '--prefix', 'provider-engine'], cwd=ROOT,
                          capture_output=True, text=True, timeout=600)
    return '' if done.returncode == 0 else 'provider build failed: ' + (done.stdout + done.stderr)[-600:]


def pictures(result, folder):
    """Copies the soak pictures into the evidence folder; returns a Markdown list for the pull request."""
    frames = health.soak_frames(result) if result else []
    if not frames:
        return 'No soak pictures (film site, or the probe saved none).'
    folder.mkdir(parents=True, exist_ok=True)
    for frame in frames:
        shutil.copy(frame, folder / frame.name)
    return ('Soak pictures, one every 20 seconds. **Look at them before merging**: they must show the '
            'channel, not a block screen. Saved on the server in `%s`:\n%s'
            % (folder, '\n'.join('- `%s`' % frame.name for frame in frames)))


def changed_files():
    names = [line[3:] for line in git('status', '--porcelain').splitlines()]
    return [n for n in names if n]


# ----------------------------------------------------------------------------- which issue

def skip_reason(issue):
    """Why auto-fix leaves this issue alone, or '' to try it."""
    names = {label['name'] for label in issue.get('labels') or []}
    if not issue['title'].startswith(health.TITLE) or issue['state'] != 'open':
        return 'not an open broken-source issue'
    if health.HOLD in names:
        return 'on hold (remove the "%s" label to let auto-fix try)' % health.HOLD
    if health.TRIED in names:
        return 'auto-fix has already tried it'
    return ''


def choose_next(issues):
    """The next issue to try: Live TV before films (films have backups), oldest first."""
    waiting = [i for i in issues if not skip_reason(i)]
    waiting.sort(key=lambda i: (not i['title'][len(health.TITLE):].startswith('live:'), i['number']))
    return waiting[0] if waiting else None


# ----------------------------------------------------------------------------- what Codex is given

def scraper_files(source):
    """The files that make up one source, as paths relative to the repository."""
    if source.startswith('vod:'):
        python = ROOT / 'android-tv-launcher/app/src/main/python'
        for row in json.loads((python / 'moviehub_providers.json').read_text()):
            if row['id'] == source[4:] and row.get('module'):
                path = python / (row['module'].replace('.', '/') + '.py')
                return [str(path.relative_to(ROOT))] if path.is_file() else []
        return []
    name = source[5:]
    engine = ROOT / 'provider-engine'
    found = []
    folder = engine / 'upstream/modules' / name
    if folder.is_dir():
        found += sorted(str(p.relative_to(ROOT)) for p in folder.rglob('*.ts'))
    for path in (engine / 'upstream' / (name + '.ts'), engine / 'tools' / (name + '.js')):
        if path.is_file():
            found.append(str(path.relative_to(ROOT)))
    found += sorted(str(p.relative_to(ROOT)) for p in (engine / 'tools').glob(name + '-*.js'))
    return found


def site_of(source):
    if source.startswith('vod:'):
        return health.site_of(source[4:]) or 'see the scraper code'
    return 'see the scraper code (Live TV provider "%s")' % source[5:]


CODE_LIMIT = 40000


def build_prompt(source, number, summary, issue_body, files):
    code = ''
    for name in files:
        text = (ROOT / name).read_text(errors='replace')
        if len(code) + len(text) > CODE_LIMIT:
            code += '\n--- %s (too long to include; open the file) ---\n' % name
            continue
        code += '\n--- %s ---\n%s\n' % (name, text)
    if source.startswith('vod:'):
        test = ('python3 android-tv-launcher/tests/probe-free-vod.py --provider %s --timeout-seconds 120 '
                '--movie "Inception" 2010 tt1375666 27205' % source[4:])
        passes = 'it reports "streams" greater than 0'
    else:
        test = ('MOVIEHUB_SOAK_SECONDS=90 RHINO_JAR=/srv/moviehub/tools/rhino-1.9.1.jar '
                'node provider-engine/tools/live-probe.mjs %s' % source[5:])
        passes = 'a sample shows "play ok" and "soak ok", and the pictures in build/provider-tests/frames/ show the channel'
    return """You are fixing one broken source in MovieHub, a family Android TV app. The source stopped working
because the website changed. Your job: go to the site, see what changed compared with what the code
below expects, and change the code so it works in our app again.

This is your own Linux machine. You are root and have the internet, Chromium with Playwright, Node 22,
Python 3, Java and ffmpeg. Use a real browser when a page is built by JavaScript: a plain download
often looks different from what a viewer sees. The repository is a copy in /work. Only your changes to
these folders are kept: %(allowed)s. Changes anywhere else are thrown away, and changing
provider-engine/tools/live-probe.mjs, provider-engine/tools/soak.mjs or server/health/bait-frames/ makes
the whole fix rejected.

Broken source: %(source)s (issue #%(number)s)
Website: %(site)s
What the health check saw: %(summary)s

Live TV code in provider-engine/ only reaches the test after a build. Once:
  npm ci --ignore-scripts --prefix provider-engine
and after every change:
  npm run build --prefix provider-engine
(the build writes builtin.json; that file is rebuilt by our script, so do not edit it by hand).

Before testing Live TV, compile the probe's interpreter once:
  mkdir -p build/provider-tests && javac -cp /srv/moviehub/tools/rhino-1.9.1.jar -d build/provider-tests android-tv-launcher/app/src/main/java/local/tv/home/{ProviderScript,ProviderCodec,ProviderSignature,ProviderUrls,ProviderSegments}.java android-tv-launcher/tests/ProviderProbeCli.java

Test command (run it first to see the failure, then after every change):
  %(test)s
It passes only when %(passes)s.

Work in a loop: look at the site, change the scraper, run the test, read the result, repeat.
Rules (also in docs/FIX_A_BROKEN_SOURCE.md):
- Change only this source's files. Do not reformat or tidy other code.
- If the site now blocks us, is gone, or shows a "go away" / captcha screen instead of the channel,
  change nothing and say so plainly. A fake fix is worse than none.
- Never weaken a check, a test, a size limit or URL validation to make it pass.
- Finish with: what changed on the site, what you changed, and the last test output.

Current code for this source:%(code)s

Issue text:
%(issue)s
""" % {'allowed': ', '.join(ALLOWED), 'source': source, 'number': number, 'site': site_of(source),
       'summary': summary, 'test': test, 'passes': passes, 'code': code or '\n(no files found: search the scraper folders for the id)',
       'issue': issue_body[:6000]}


# ----------------------------------------------------------------------------- running Codex

SECRETS = ('FORGEJO_TOKEN', 'NTFY_URL', 'PVE_TOKEN', 'MOVIEHUB_FEED_KEY', 'HEALTH_TOKEN')


def run_codex(env, prompt):
    """Runs Codex in the sandbox and applies its change here. Returns (what Codex said, refused paths)."""
    command = shlex.split(env.get('CODEX_CMD', 'codex exec --full-auto'))
    minutes = int(env.get('CODEX_MINUTES', '30'))
    if sandbox.configured(env):
        box = sandbox.Sandbox(env)
        box.reset()
        box.send(ROOT)
        said = box.run(command, prompt, minutes)
        patch = box.take()
        if not patch.strip():
            return said, []
        refused = rejected(sandbox.patch_paths(patch))
        if refused:
            return said, refused
        excludes = ['--exclude=' + path for path in GENERATED]
        applied = subprocess.run(['git', 'apply', '--binary', *excludes, '-'], cwd=ROOT, input=patch, text=True,
                                 capture_output=True)
        if applied.returncode != 0:
            return said + '\n\nThe change could not be applied: ' + applied.stderr[-400:], []
        return said, []
    # Old way, on this server: only when the owner has explicitly allowed it. Codex could then read
    # anything this user can, including the signing key, so secrets are at least kept out of its env.
    clean = {k: v for k, v in env.items() if k not in SECRETS}
    try:
        done = subprocess.run(command + [prompt], cwd=ROOT, capture_output=True, text=True,
                              timeout=60 * minutes, env=clean)
        return (done.stdout + done.stderr)[-2500:], []
    except subprocess.TimeoutExpired:
        return 'Codex ran out of time.', []


# ----------------------------------------------------------------------------- main

def main():
    args = sys.argv[1:]
    if args != ['--next'] and not (len(args) == 1 and args[0].isdigit()):
        sys.exit('usage: auto_fix.py <issue number> | --next')
    env = os.environ
    forgejo = health.Forgejo(env['FORGEJO_URL'], env['FORGEJO_TOKEN'], env['FORGEJO_REPO'])
    if args == ['--next']:
        issue = choose_next(forgejo.open_issues().values())
        if not issue:
            print('Nothing waiting for auto-fix.')
            return
    else:
        issue = forgejo.call('GET', '/issues/%d' % int(args[0]))
    number = issue['number']
    why = skip_reason(issue)
    if why:
        print('Issue #%d skipped: %s' % (number, why))
        return
    source = issue['title'][len(health.TITLE):].strip()
    if not (source.startswith('vod:') or source.startswith('live:')):
        sys.exit('Unknown source id: ' + source)
    if not sandbox.configured(env) and env.get('CODEX_UNSANDBOXED') != '1':
        forgejo.call('POST', '/issues/%d/comments' % number, {'body': (
            'Auto-fix did not run: the Codex sandbox is not set up (see "Codex sandbox" in server/README.md). '
            'Without it Codex would run on the server itself, where it could read the signing key.')})
        forgejo.add_label(number, health.TRIED)
        return
    # Marked first, so the "opened" and "labeled" events and the hourly sweep never try it twice.
    forgejo.add_label(number, health.TRIED)

    ok, before, _ = probe(source)
    if ok:
        forgejo.call('POST', '/issues/%d/comments' % number,
                     {'body': 'Auto-fix: the source works again without changes (temporary outage).\n\n```\n%s\n```'
                              % before})
        return

    branch = 'fix/%s-%s' % (source.replace(':', '-').replace('_', '-'), datetime.date.today().isoformat())
    git('checkout', '-B', branch)
    summary = before.split('\n', 1)[0]
    prompt = build_prompt(source, number, summary, issue['body'] or '', scraper_files(source))
    try:
        codex_said, refused = run_codex(env, prompt)
    except (sandbox.SandboxError, OSError, subprocess.SubprocessError) as error:
        codex_said, refused = 'The sandbox failed: %s' % error, []

    if any(f.startswith('provider-engine/') for f in changed_files()):
        git('checkout', '--', *GENERATED, check=False)
        problem = rebuild_providers()
        if problem:
            codex_said += '\n\n' + problem
            git('checkout', '--', '.', check=False)
            git('clean', '-fd', '--', *ALLOWED, check=False)
    files = changed_files()
    outside = refused or rejected(files)
    ok, after, result = probe(source) if files and not outside else (False, 'not run', None)
    if not ok:
        reason = ('Codex changed files it may not touch (only scrapers, never the checks): %s'
                  % ', '.join(outside) if outside else
                  'Codex made no changes.' if not files else
                  'The live probe still finds nothing, or the stream did not keep playing.')
        git('checkout', '--', '.', check=False)
        git('clean', '-fd', '--', *ALLOWED, check=False)
        git('checkout', 'main', check=False)
        forgejo.call('POST', '/issues/%d/comments' % number, {'body': (
            'Auto-fix attempt failed, nothing opened. %s\n\n**Probe after:**\n```\n%s\n```\n\n'
            '<details><summary>Codex output (last part)</summary>\n\n```\n%s\n```\n</details>'
            % (reason, after, codex_said))})
        return

    stamp = datetime.datetime.now().strftime('%Y-%m-%d_%H%M')
    evidence = (Path(env.get('EVIDENCE_DIR', '/srv/moviehub/health/evidence')) /
                ('fix-' + source.replace(':', '_')) / stamp)
    shown = pictures(result, evidence)
    git('add', '--', *files)
    git('commit', '-m', 'Fix %s\n\nAutomated fix for #%d. Live probe passed on the home server.' % (source, number))
    git('push', '-f', 'origin', branch)
    forgejo.call('POST', '/pulls', {
        'title': 'Fix %s' % source, 'head': branch, 'base': 'main',
        'body': ('Fixes #%d\n\n**Live probe on the home server after the change (run by the script, not Codex):**'
                 '\n```\n%s\n```\n\n%s\n\nFiles: %s\n\nMerging publishes the scrapers to every box within about '
                 '6 hours.\n\n<details><summary>Codex output (last part)</summary>\n\n```\n%s\n```\n</details>'
                 % (number, after, shown, ', '.join(files), codex_said))})
    health.notify(env.get('NTFY_URL'), 'MovieHub: fix ready for %s' % source,
                  'Live probe and soak passed. Check the pictures in the pull request, then merge.')


if __name__ == '__main__':
    main()
