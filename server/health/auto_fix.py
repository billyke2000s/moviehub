#!/usr/bin/env python3
"""Keep proposed edits and independently compare the base and candidate before requesting review."""
import contextlib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

import check_sources as health
import playback_evidence as evidence

ROOT = health.ROOT
SOURCE_ID = re.compile(r'(?:vod|live):[a-zA-Z0-9_][a-zA-Z0-9_.:-]*')
VOD_FOLDER = 'android-tv-launcher/app/src/main/python/'


def git(*args, cwd=None, **kwargs):
    return subprocess.run(['git', *args], cwd=ROOT if cwd is None else cwd, stdout=subprocess.PIPE, check=True, **kwargs).stdout


@contextlib.contextmanager
def checkout(revision):
    with tempfile.TemporaryDirectory(prefix='moviehub-check-') as temp:
        folder = Path(temp)
        git('worktree', 'add', '--quiet', '--detach', temp, revision)
        try:
            yield folder
        finally:
            git('worktree', 'remove', '--force', temp)


def allowed(path, source):
    if source.startswith('vod:'):
        return path.startswith(VOD_FOLDER) and path.endswith(('.py', '.json'))
    provider = source.split(':', 1)[1]
    return ((path.startswith('provider-engine/upstream/modules/' + provider + '/') and path.endswith('.ts'))
            or (provider == 'freelivesports' and path == 'provider-engine/upstream/freelivesports.ts'))


def check_changes(cwd, base, source):
    # Check deletions, both halves of a rename, modes, staged edits and any commits the worker made.
    rows = git('diff', '--raw', '-z', '--no-renames', '--cached', base, cwd=cwd).split(b'\0')[:-1]
    paths = []
    for meta, raw in zip(rows[::2], rows[1::2]):
        path = os.fsdecode(raw)
        old, new = meta[1:].split()[:2]
        if not allowed(path, source) or {old, new} - {b'000000', b'100644'}:
            raise ValueError('candidate touches an unsupported path or file mode: ' + repr(path))
        paths.append(path)
    return paths


def propose(source, issue, base, branch, previous=''):
    # The worker gets a disposable clone with no remote or checkout credentials. Construct the
    # candidate from its allowed file diff in a separate clean checkout.
    with tempfile.TemporaryDirectory(prefix='moviehub-proposal-') as temp:
        git('clone', '--quiet', '--no-local', '--no-checkout', str(ROOT), temp)
        git('remote', 'remove', 'origin', cwd=temp)
        git('checkout', '--quiet', '--detach', base, cwd=temp)
        prompt = ('Prepare a local change for %s. The wrapper owns verification, commits and review.\n%s'
                  % (source, issue['body'][:8000]))
        if previous:
            prompt += '\nSaved diagnosis from the earlier attempt:\n' + previous
        env = {key: value for key, value in os.environ.items()
               if not (key.startswith(('FORGEJO_', 'GITHUB_', 'GIT_CONFIG_', 'ACTIONS_', 'RUNNER_'))
                       or key in ('GH_TOKEN', 'NTFY_URL'))}
        cmd = shlex.split(env.get('CODEX_CMD', 'codex exec --full-auto')) + [prompt]
        try:
            worker = subprocess.run(cmd, cwd=temp, env=env, capture_output=True, text=True,
                                    timeout=60 * int(env.get('CODEX_MINUTES', '30')), check=False)
        except subprocess.TimeoutExpired:
            raise ValueError('worker timed out; no completed proposal')
        if worker.returncode:
            raise ValueError('worker exited %d; no completed proposal' % worker.returncode)
        git('add', '-A', cwd=temp)
        paths = check_changes(temp, base, source)
        if not paths:
            # Quote the diagnosis as data; never use its wording as an issue-closing PR body.
            diagnosis = (worker.stdout or '').strip()[-2000:]
            diagnosis = re.sub(r'https?://\S+', '[address omitted]', diagnosis)
            raise NoPatch(diagnosis or 'The worker returned no diagnosis.')
        patch = git('diff', '--cached', '--binary', '--no-renames', base, cwd=temp)
    with checkout(base) as clean:
        git('apply', '--index', '--binary', '-', input=patch, cwd=clean)
        git('commit', '--quiet', '-m', 'Propose provider change for ' + source, cwd=clean)
        candidate = git('rev-parse', 'HEAD', cwd=clean).decode().strip()
        git('push', 'origin', 'HEAD:refs/heads/' + branch, cwd=clean)
    return candidate


class NoPatch(Exception):
    pass


def affected_cases(source, body):
    match = re.search(r'^<!-- moviehub-playback-cases (.+) -->$', body, re.MULTILINE)
    if match:
        keys = json.loads(match[1])
        return {tuple(key) if isinstance(key, list) else key for key in keys}
    # Older film issues came from the same two named movies. Older Live TV
    # reports have no stable channel IDs, so they cannot identify the original case.
    return {(int(film[3]), -1, -1) for film in health.FILMS} if source.startswith('vod:') else set()


def verify(base, candidate, source, cases):
    # Verify the saved object, never an uncommitted work tree or a previous run's report.
    with checkout(candidate) as patched:
        paths = check_changes(patched, base, source)
        if not paths:
            raise ValueError('candidate contains no provider change')
        with checkout(base) as original:
            before = {key: observation for key, observation in evidence.measure(original, source).items()
                      if key in cases}
        if not any(not passed for passed, _ in before.values()):
            raise ValueError('base produced no failed playback case; no repair established')
        after = evidence.measure(patched, source)
    if not evidence.transition(before, after, before):
        raise ValueError('the candidate did not pass a case that failed on the base')
    with checkout(base) as control:
        repeated = evidence.measure(control, source)
    cases = evidence.transition(before, after, repeated)
    if not cases:
        raise ValueError('the original failure did not repeat on the base; no repair established')
    return paths, cases


DIAGNOSIS = 'Diagnosis only. No code changed.'
PENDING = 'Playback unverified.'


def handle(forgejo, number, retry_diagnosis=True):
    issue_path = '/issues/%d' % number
    issue = forgejo.call('GET', issue_path)
    source = issue['title'].removeprefix(health.TITLE).strip()
    if not issue['title'].startswith(health.TITLE) or issue['state'] != 'open' or not SOURCE_ID.fullmatch(source):
        raise ValueError('expected an open broken-source issue with a valid provider ID')
    branch = 'repair/issue-%d' % number
    base = git('rev-parse', 'HEAD').decode().strip()
    saved = git('ls-remote', '--heads', 'origin', 'refs/heads/' + branch).split()
    candidate = None

    def comment(message):
        forgejo.call('POST', issue_path + '/comments', {'body': message})

    try:
        if saved:
            git('fetch', '--quiet', 'origin', 'refs/heads/' + branch)
            candidate = git('rev-parse', 'FETCH_HEAD').decode().strip()
            parents = git('show', '-s', '--format=%P', candidate).decode().split()
            if parents != [base]:
                raise ValueError('saved proposal has a different base; rebase the proposal before retrying')
        else:
            previous = ''
            if issue.get('comments'):
                comments = forgejo.call('GET', issue_path + '/comments?limit=50') or []
                previous = next((item['body'][len(DIAGNOSIS):].strip()[:2200] for item in reversed(comments)
                                 if item.get('body', '').startswith(DIAGNOSIS)), '')
                if previous and not retry_diagnosis:
                    print('A diagnosis is already saved. Manually dispatch this issue to resume it.')
                    return False
            candidate = propose(source, issue, base, branch, previous)
        paths, cases = verify(base, candidate, source, affected_cases(source, issue['body']))
    except NoPatch as error:
        comment(DIAGNOSIS + '\n\n' + '\n'.join('> ' + line for line in str(error).splitlines()))
        return False
    except (OSError, UnicodeError, KeyError, TypeError, ValueError, subprocess.SubprocessError) as error:
        reason = ('verification command failed or timed out' if isinstance(error, subprocess.SubprocessError)
                  else str(error))
        note = (' Saved candidate `%s` on `%s`. Re-running this issue verifies that commit without '
                'invoking the AI again.' % (candidate, branch)) if candidate else ''
        comment('%s %s.%s' % (PENDING, reason.rstrip('.'), note))
        return False

    measurement = 'Android LibVLC playback and midpoint seek' if source.startswith('vod:') else 'server FFmpeg audio/video decode'
    body = ('Related to #%d.\n\n%s failed on base, passed on the candidate, and failed on a fresh base run. '
            'The content identity still needs review.\n\nBase: `%s`\n\nCandidate: `%s`\n\nCases:\n%s\n\nChanged files:\n%s'
            % (number, measurement, base, candidate, '\n'.join('- ' + repr(case) for case in cases),
               '\n'.join('- `%s`' % p for p in paths)))
    # Forgejo enforces one open PR per head/base pair. Rechecking an existing PR only adds evidence.
    existing = forgejo.call('GET', '/pulls?state=open&head=' + forgejo.repo.split('/')[0] + ':' + branch) or []
    if any(pr['head']['ref'] == branch and pr['base']['ref'] == 'main' for pr in existing):
        comment(body)
    else:
        forgejo.call('POST', '/pulls', {'title': 'Review playback change for ' + source,
                                        'head': branch, 'base': 'main', 'body': body})
    health.notify(os.environ.get('NTFY_URL'), 'MovieHub: playback sample passed', 'Review the candidate for ' + source)
    return True


def main():
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit('usage: auto_fix.py <issue number>')
    env = os.environ
    forgejo = health.Forgejo(env['FORGEJO_URL'], env['FORGEJO_TOKEN'], env['FORGEJO_REPO'])
    if not handle(forgejo, int(sys.argv[1]), env.get('REPAIR_TRIGGER', 'workflow_dispatch') == 'workflow_dispatch'):
        sys.exit('No verified playback change; see the issue for evidence.')


if __name__ == '__main__':
    main()
