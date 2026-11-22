"""On-demand public-source health check; never prints playback URLs, bodies or credentials."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import sys
import threading
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import moviehub_vod as vod
from audit_transport import block_external_decoder

block_external_decoder()

parser=argparse.ArgumentParser()
media = parser.add_mutually_exclusive_group()
media.add_argument('--series', action='store_true')
media.add_argument('--movie', nargs=4, metavar=('TITLE', 'YEAR', 'IMDB', 'TMDB'),
                   help='Probe another film using its complete catalogue identity')
parser.add_argument('--include-retired', action='store_true',
                    help='Audit excluded adapters too, without changing app discovery')
parser.add_argument('--shard', help='Audit one INDEX/COUNT partition of the website inventory')
parser.add_argument('--timeout-seconds', type=int, default=400,
                    help='Per-website cancellation budget for scheduled checks (1-400)')
parser.add_argument('--provider', action='append', default=[],
                    help='Audit a website id (repeat to select several)')
parser.add_argument('--stop-after-first', action='store_true',
                    help='Diagnostic only: stop a single provider after its first validated media source')
args=parser.parse_args()
info = {'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
                   'year':'1968','season':-1,'episode':-1,'http':False}
if args.series:
    info.update(imdb='tt0060028', tmdb=253, title='Star Trek', year='1966', season=1, episode=1,
                premiered='1966-09-08', episodeTitle='The Man Trap')
if args.movie:
    title, year, imdb, tmdb = args.movie
    if not year.isdigit() or not tmdb.isdigit() or not imdb.startswith('tt'):
        parser.error('--movie requires numeric YEAR/TMDB and an IMDb tt identifier')
    info.update(title=title, year=year, imdb=imdb, tmdb=int(tmdb))
query=json.dumps(info)
started = time.monotonic()
results = []
def probe(row):
    begin = time.monotonic()
    try:
        found = threading.Event()
        class Found:
            def ready(self, payload):
                found.set()
        cancel = lambda: (time.monotonic()-started > 420 or
                         time.monotonic()-begin > args.timeout_seconds or
                         (args.stop_after_first and found.is_set()))
        result = json.loads(vod.query_site(
            row['id'], query, cancel, Found() if args.stop_after_first else None))
        return {'provider':row['id'], 'status':result['status'], 'streams':len(result['sources']),
                'seconds':round(time.monotonic()-begin, 1), 'extracted':result.get('extracted',0),
                'rejected':result.get('rejected',0),'resolutionErrors':result.get('resolutionErrors',0),
                'unsupportedHosts':result.get('unsupportedHosts',0), 'attempts':result.get('attempts',[]),
                'sourceProviders':sorted({x.get('providerName', '') for x in result['sources']}),
                'hostAttempts':result.get('hostAttempts', [])}
    except BaseException as error:
        return {'provider':row['id'], 'status':type(error).__name__, 'streams':0}

rows = json.loads(vod.search_providers(include_retired=args.include_retired))
if args.provider:
    unknown = set(args.provider) - {row['id'] for row in rows}
    if unknown: parser.error('Unknown or excluded website id: ' + ', '.join(sorted(unknown)))
    rows = [row for row in rows if row['id'] in args.provider]
if not 1 <= args.timeout_seconds <= 400:
    parser.error('--timeout-seconds must be between 1 and 400')
if args.shard:
    try:
        part, count = (int(x) for x in args.shard.split('/'))
    except (ValueError, TypeError):
        parser.error('--shard requires INDEX/COUNT, for example 0/4')
    if count < 1 or not 0 <= part < count:
        parser.error('--shard index must be within COUNT')
    rows = [row for i, row in enumerate(rows) if i % count == part]
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
    pending = [workers.submit(probe,row) for row in rows if row['series' if args.series else 'movies']]
    for future in concurrent.futures.as_completed(pending):
        result=future.result()
        results.append(result)
        print(json.dumps(result), flush=True)
print(json.dumps({'providers':len(results),'providersWithStreams':sum(r['streams']>0 for r in results),
                  'streams':sum(r['streams'] for r in results)}), flush=True)
