#!/usr/bin/env python3
"""Hands a "[broken source]" issue to Codex, then checks the result itself.

    python3 server/health/auto_fix.py <issue number>

Run from a clean checkout of main on the server (the Forgejo workflow auto-fix.yml does this when the
health checker opens an issue). Steps:

  1. Reads the issue, works out the source id, makes branch fix/<id>-<date>.
  2. Runs Codex (CODEX_CMD, default "codex exec --full-auto") with docs/FIX_A_BROKEN_SOURCE.md.
  3. Runs the live probe ITSELF. Codex saying "fixed" counts for nothing: only streams > 0 (film site)
     or a Live TV sample that plays AND keeps playing for FIX_SOAK_SECONDS (default 90) does.
  4. Passed: commits only the changed source files, pushes, opens a pull request with the real probe
     output and the soak pictures. Failed: comments on the issue with the probe result and Codex's last
     words, opens nothing.

Codex may not change the probe itself or the block-screen pictures: a fix that touches them is
rejected, so a check can never be weakened to make a fix pass.

Settings: FORGEJO_URL, FORGEJO_TOKEN, FORGEJO_REPO (as check_sources.py), RHINO_JAR, CODEX_CMD,
CODEX_MINUTES (default 30), FIX_SOAK_SECONDS (default 90), EVIDENCE_DIR.
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

ROOT = health.ROOT
ALLOWED = ('android-tv-launcher/app/src/main/python/', 'provider-engine/')
# Inside ALLOWED but part of the checks, not of any scraper: a fix may never change these.
PROTECTED = ('provider-engine/tools/live-probe.mjs', 'provider-engine/tools/soak.mjs')


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
    return [f for f in files if not f.startswith(ALLOWED) or f in PROTECTED or
            f.startswith('server/health/bait-frames/')]


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


def main():
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit('usage: auto_fix.py <issue number>')
    number = int(sys.argv[1])
    env = os.environ
    forgejo = health.Forgejo(env['FORGEJO_URL'], env['FORGEJO_TOKEN'], env['FORGEJO_REPO'])
    issue = forgejo.call('GET', '/issues/%d' % number)
    if not issue['title'].startswith(health.TITLE) or issue['state'] != 'open':
        sys.exit('Not an open broken-source issue')
    source = issue['title'][len(health.TITLE):].strip()
    if not (source.startswith('vod:') or source.startswith('live:')):
        sys.exit('Unknown source id: ' + source)

    ok, before, _ = probe(source)
    if ok:
        forgejo.call('POST', '/issues/%d/comments' % number,
                     {'body': 'Auto-fix: the source works again without changes (temporary outage).\n\n```\n%s\n```'
                              % before})
        return

    branch = 'fix/%s-%s' % (source.replace(':', '-').replace('_', '-'), datetime.date.today().isoformat())
    git('checkout', '-B', branch)
    prompt = ('Fix the broken source "%s" (Forgejo issue #%d). Follow docs/FIX_A_BROKEN_SOURCE.md exactly. '
              'Issue text:\n\n%s' % (source, number, issue['body'][:8000]))
    command = shlex.split(env.get('CODEX_CMD', 'codex exec --full-auto')) + [prompt]
    try:
        codex = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                               timeout=60 * int(env.get('CODEX_MINUTES', '30')))
        codex_said = (codex.stdout + codex.stderr)[-2500:]
    except subprocess.TimeoutExpired:
        codex_said = 'Codex ran out of time.'

    files = changed_files()
    outside = rejected(files)
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
