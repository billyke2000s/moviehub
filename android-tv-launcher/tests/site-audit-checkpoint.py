"""Save redacted per-site coverage counts; technical samples never certify content."""
from collections import Counter
import json
from pathlib import Path
import re
import sys


def read_rows(path):
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def summarize(root, site, commit, run_url):
    if not re.fullmatch(r'(free99|scrubs|native):[a-z0-9_]+', site):
        raise ValueError('Invalid site ID')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Invalid source commit')
    if not re.fullmatch(r'https://github.com/[^/]+/[^/]+/actions/runs/[0-9]+', run_url):
        raise ValueError('Invalid run URL')
    output = dict(site=site, sourceCommit=commit, evidenceRun=run_url, playbackAuditVersion=2,
                  fullPlaybackVerified=False, contentReview='pending', media={})
    for media in ('movies', 'series'):
        directory = root / media
        try:
            rows = read_rows(directory / 'vod-live-audit.jsonl')
            reviews = read_rows(directory / 'vod-evidence/review.jsonl')
            inventory = [row for row in rows if row.get('kind') == 'inventory']
            finished = [row for row in rows if row.get('kind') == 'site_finished']
            complete = (len(inventory) == 1 and inventory[0].get('sites') == [site] and
                        inventory[0].get('media') == media and len(finished) == 1 and
                        finished[0].get('website') == site and finished[0].get('media') == media and
                        rows[-1] == dict(kind='finished', shard=0, shards=1))
            relevant = [row for row in rows if row.get('website') == site]
            lookups = [row for row in relevant if row.get('kind') == 'lookup']
            playback = [row for row in relevant if row.get('kind') == 'playback']
            complete = (complete and bool(lookups) and
                        all(row.get('website', site) == site for row in rows) and
                        finished[0].get('testedSources') == len(playback))
            reviewed = [row for row in reviews if row.get('kind') == 'playback' and
                        row.get('website') == site]
            # Keep only counts/status codes. Never copy playback URLs, headers,
            # tokens, exception messages or arbitrary website text into git.
            def counts(items, key):
                return dict(Counter(str(row.get(key, 'missing')) if
                                    re.fullmatch(r'[a-zA-Z0-9_]+', str(row.get(key, 'missing'))) else
                                    'invalid_status' for row in items))
            output['media'][media] = dict(
                complete=complete,
                lookupOutcomes=counts(lookups, 'status'),
                candidatesTested=len(playback),
                playbackOutcomes=counts(playback, 'status'),
                technicalPasses=sum(row.get('status') == 'verified' for row in playback),
                sourceLimitMayHaveBeenReached=len(playback) >= 200,
                evidenceOutcomes=counts(reviewed, 'verification'),
                candidatesAwaitingContentReview=sum(row.get('verification') == 'needs_content_review'
                                                   for row in reviewed),
                fullPlaybackVerified=False)
        except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
            output['media'][media] = dict(complete=False, error=type(error).__name__,
                                          fullPlaybackVerified=False)
    return output


if __name__ == '__main__':
    root, site, commit, run_url = sys.argv[1:]
    result = summarize(Path(root), site, commit, run_url)
    Path(root, 'checkpoint.json').write_text(json.dumps(result, indent=4) + '\n')
