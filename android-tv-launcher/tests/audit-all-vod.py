"""Audit every bundled adapter independently, including pending/excluded adapters.

One result per inventory record for each media type. No website grouping, fallback
adapter or unsupported capability can silently remove an inventory entry.
Output contains diagnostic categories and counts, never source URLs or tokens.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import moviehub_vod as vod
from moviehub_compat import client
from audit_transport import block_external_decoder


def inventory(shard, shards):
    records = sorted(vod._RECORDS, key=lambda row: row['id'])
    if len({row['id'] for row in records}) != len(records):
        raise ValueError('Duplicate adapter identity')
    return [row for row in records if int.from_bytes(
        hashlib.sha256(row['id'].encode()).digest()[:4], 'big') % shards == shard]


def probe(record, media, timeout):
    common = dict(adapter=record['id'], media=media, pending=bool(record.get('pending')))
    if record['name'] in ('library', 'filepursuit'):
        return dict(common, status='local_library' if record['name'] == 'library' else 'requires_api_key',
                    sources=0, attempted=False)
    if not record[media]:
        return dict(common, status='unsupported_media', sources=0, attempted=False)
    query = dict(imdb='tt1375666', tmdb=27205, title='Inception', year='2010',
                 season=-1, episode=-1, http=False, cinestreamAudit=True)
    if media == 'series':
        query.update(imdb='tt0903747', tmdb=1396, title='Breaking Bad', year='2008',
                     season=1, episode=1, premiered='2008-01-20', episodeTitle='Pilot')
    started = time.monotonic()
    try:
        # query_site deliberately is not used: it can hide one failing adapter
        # behind a different adapter sharing its website.
        result = json.loads(vod.query(record['id'], json.dumps(query),
                            lambda: time.monotonic() - started >= timeout,
                            deadline=started + timeout))
        return dict(common, status=result.get('status', 'missing_status'), attempted=True,
                    sources=len(result.get('sources') or []),
                    extracted=result.get('extracted', 0), rejected=result.get('rejected', 0),
                    resolutionErrors=result.get('resolutionErrors', 0),
                    unsupportedHosts=result.get('unsupportedHosts', 0),
                    seconds=round(time.monotonic() - started, 2),
                    stage=result.get('stage', ''), hostAttempts=result.get('hostAttempts', []))
    except client.Cancelled:
        return dict(common, status='timeout', sources=0, attempted=True,
                    seconds=round(time.monotonic() - started, 2))
    except Exception as error:
        return dict(common, status=type(error).__name__, sources=0, attempted=True,
                    seconds=round(time.monotonic() - started, 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--media', choices=('movies', 'series'), required=True)
    parser.add_argument('--shard', type=int, default=0)
    parser.add_argument('--shards', type=int, default=1)
    parser.add_argument('--timeout', type=int, default=60)
    args = parser.parse_args()
    if args.shards < 1 or not 0 <= args.shard < args.shards or not 1 <= args.timeout <= 420:
        parser.error('Invalid shard or timeout')
    block_external_decoder()
    records = inventory(args.shard, args.shards)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
        tasks = [workers.submit(probe, record, args.media, args.timeout) for record in records]
        for task in concurrent.futures.as_completed(tasks):
            result = task.result()
            results.append(result)
            print(json.dumps(result), flush=True)
    if {row['adapter'] for row in results} != {row['id'] for row in records}:
        raise SystemExit('Incomplete adapter coverage')
    print(json.dumps(dict(summary=True, media=args.media, shard=args.shard, shards=args.shards,
                         inventory=len(vod._RECORDS), covered=len(results),
                         attempted=sum(row['attempted'] for row in results),
                         withSources=sum(row['sources'] > 0 for row in results))), flush=True)


if __name__ == '__main__':
    main()
