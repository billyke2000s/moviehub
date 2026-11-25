#!/usr/bin/env python3
"""Keep proposed edits and independently compare the base and candidate before requesting review."""
import contextlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import check_sources as health
import playback_evidence as evidence
import isolated_worker

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


def allowed(path, source, vod_paths):
    if source.startswith('vod:'):
        return path in vod_paths
    provider = source.split(':', 1)[1]
    return ((path.startswith('provider-engine/upstream/modules/' + provider + '/') and path.endswith('.ts'))
            or (provider == 'freelivesports' and path == 'provider-engine/upstream/freelivesports.ts'))


def check_changes(cwd, base, source):
    # Check deletions, both halves of a rename, modes, staged edits and any commits the worker made.
    vod_paths = set()
    if source.startswith('vod:'):
        registry = json.loads(git('show', base + ':' + VOD_FOLDER + 'moviehub_providers.json', cwd=cwd))
        for row in registry:
            if row['id'] == source[4:] and re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*', row['module']):
                vod_paths.add(VOD_FOLDER + row['module'].replace('.', '/') + '.py')
        # Shared native engines and infrastructure changes require owner review outside this automation.
        if re.fullmatch(r'vod:native:[a-z][a-z0-9_]*', source):
            name = source.split(':')[-1]
            if not name.startswith('_'):
                vod_paths.add(VOD_FOLDER + 'moviehub_native/' + name + '.py')
    rows = git('diff', '--raw', '-z', '--no-renames', '--cached', base, cwd=cwd).split(b'\0')[:-1]
    paths = []
    for meta, raw in zip(rows[::2], rows[1::2]):
        path = os.fsdecode(raw)
        old, new = meta[1:].split()[:2]
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', path) or not allowed(path, source, vod_paths) or {old, new} - {b'000000', b'100644'}:
            raise ValueError('candidate touches an unsupported path or file mode: ' + repr(path))
        paths.append(path)
    return paths


def propose(source, issue, base, branch, previous=''):
    prompt = ('Prepare a local change for %s. The wrapper owns verification, commits and review.\n%s'
              % (source, issue['body'][:8000]))
    if previous:
        prompt += '\nSaved diagnosis from the earlier attempt:\n' + previous
    patch, diagnosis = isolated_worker.proposal(ROOT, base, prompt)
    if not patch:
        diagnosis = re.sub(r'https?://\S+', '[address omitted]', diagnosis)
        raise NoPatch(diagnosis or 'The worker returned no diagnosis.')
    if b'GIT binary patch' in patch.splitlines():
        raise ValueError('binary provider patches require manual review')
    with checkout(base) as clean:
        # Only touch a temporary index until every changed path and file mode has been checked.
        git('apply', '--cached', '--binary', '--whitespace=nowarn', '-', input=patch, cwd=clean)
        if not check_changes(clean, base, source):
            raise NoPatch('The patch contains no source change.')
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
            if any(passed for passed, _ in before.values()):
                raise ValueError('the original build already plays the measured case; no repair established')
            raise ValueError('the original case has no conclusive playback measurement')
        after = evidence.measure(patched, source)
    if set(before) != cases or any(passed for passed, _ in before.values()):
        raise ValueError('not every reported case reproduced on the original build')
    if len(evidence.transition(before, after, before)) != len(cases):
        raise ValueError('the candidate did not pass a case that failed on the base')
    with checkout(base) as control:
        repeated = evidence.measure(control, source)
    observations = evidence.transition(before, after, repeated)
    if len(observations) != len(cases):
        raise ValueError('the original failure did not repeat on the base; no repair established')
    return paths, observations


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
        reported = affected_cases(source, issue['body'])
        paths, cases = verify(base, candidate, source, reported)
        latest = forgejo.call('GET', issue_path)
        if latest['state'] != 'open' or latest['title'] != issue['title'] or affected_cases(source, latest['body']) != reported:
            raise ValueError('the incident closed or its playback cases changed during verification')
        refs = git('ls-remote', '--heads', 'origin', 'refs/heads/main', 'refs/heads/' + branch).decode()
        heads = {ref: sha for sha, ref in (line.split() for line in refs.splitlines())}
        if heads.get('refs/heads/main') != base or heads.get('refs/heads/' + branch) != candidate:
            raise ValueError('base or candidate moved during verification; the newer revision needs testing')
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
    review = 'review/issue-%d-%s' % (number, candidate)
    # A pending retry can change the proposal branch. Keep the reviewed PR attached to
    # the object that passed. A divergent review ref is never overwritten.
    ref = 'refs/heads/' + review
    current = git('ls-remote', '--heads', 'origin', ref).split()
    if not current:
        git('push', '--force-with-lease=' + ref + ':', 'origin', candidate + ':' + ref)
    elif current[0].decode() != candidate:
        comment(PENDING + ' The review reference no longer matches the measured commit.')
        return False
    existing = forgejo.call('GET', '/pulls?state=open&head=' + forgejo.repo.split('/')[0] + ':' + review) or []
    if any(pr['head']['ref'] == review and pr['base']['ref'] == 'main' for pr in existing):
        comment(body)
    else:
        forgejo.call('POST', '/pulls', {'title': 'Review playback change for ' + source,
                                        'head': review, 'base': 'main', 'body': body})
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
