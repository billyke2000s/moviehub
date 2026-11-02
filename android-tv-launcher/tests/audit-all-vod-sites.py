"""Audit each enabled website for both media types; output contains no playback URLs or tokens."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import moviehub_vod as vod
from audit_transport import block_external_decoder


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--media', choices=('movies', 'series', 'both'), default='both')
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--provider', action='append', default=[])
    args = parser.parse_args()
    if not 15 <= args.seconds <= 400 or not 1 <= args.workers <= 12:
        parser.error('Use 15–400 seconds and 1–12 workers')
    block_external_decoder()
    vod.SITE_BUDGET_S = args.seconds
    rows = json.loads(vod.search_providers())
    if args.provider:
        unknown = set(args.provider) - {row['id'] for row in rows}
        if unknown:
            parser.error('Unknown enabled provider ids: ' + ', '.join(sorted(unknown)))
        rows = [row for row in rows if row['id'] in args.provider]
    media = ('movies', 'series') if args.media == 'both' else (args.media,)
    cases = json.loads((Path(__file__).resolve().parents[1] /
                        'app/src/androidTest/assets/vod-audit-cases.json').read_text())
    jobs = [(row, kind) for row in rows for kind in media]
    print(json.dumps(dict(kind='inventory', sites=len(rows), cases=len(jobs), media=media)), flush=True)

    def audit(row, kind):
        start = time.monotonic()
        profile = cases['sites'].get(row['id'], 'general')
        query = dict(cases['profiles'][profile][kind][0], http=False)
        result = dict(kind='lookup', website=row['id'], site=row['site'], media=kind,
                      title=query['title'], adapterSupportsType=row[kind])
        try:
            report = json.loads(vod.query_site(row['id'], json.dumps(query),
                                              lambda: time.monotonic() - start >= args.seconds))
            result.update(status=report['status'], sources=len(report['sources']),
                          extracted=report.get('extracted', 0), rejected=report.get('rejected', 0),
                          resolutionErrors=report.get('resolutionErrors', 0),
                          unsupportedHosts=report.get('unsupportedHosts', 0), attempts=report.get('attempts', []))
        except Exception as error:
            result.update(status=type(error).__name__, sources=0)
        result['seconds'] = round(time.monotonic() - start, 1)
        return result

    complete = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(audit, *job) for job in jobs]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            complete.append(result)
            print(json.dumps(result), flush=True)
    print(json.dumps(dict(kind='finished', expectedCases=len(jobs), completedCases=len(complete),
                          sitesWithSources=len({row['website'] for row in complete if row['sources']}))), flush=True)
    if len(complete) != len(jobs):
        raise SystemExit('Incomplete site audit')


if __name__ == '__main__':
    main()
