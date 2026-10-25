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

parser=argparse.ArgumentParser()
parser.add_argument('--series', action='store_true')
args=parser.parse_args()
info = {'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
                   'year':'1968','season':-1,'episode':-1,'http':False}
if args.series:
    info.update(imdb='tt0060028', tmdb=253, title='Star Trek', year='1966', season=1, episode=1,
                premiered='1966-09-08', episodeTitle='The Man Trap')
query=json.dumps(info)
started = time.monotonic()
results = []
def probe(row):
    begin = time.monotonic()
    try:
        result = json.loads(vod.query_site(row['id'], query, lambda: time.monotonic()-started > 420))
        return {'provider':row['id'], 'status':result['status'], 'streams':len(result['sources']),
                'seconds':round(time.monotonic()-begin, 1), 'extracted':result.get('extracted',0),
                'rejected':result.get('rejected',0),'resolutionErrors':result.get('resolutionErrors',0),
                'unsupportedHosts':result.get('unsupportedHosts',0), 'attempts':result.get('attempts',[])}
    except BaseException as error:
        return {'provider':row['id'], 'status':type(error).__name__, 'streams':0}

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
    pending = [workers.submit(probe,row) for row in json.loads(vod.search_providers()) if row['series' if args.series else 'movies']]
    for future in concurrent.futures.as_completed(pending):
        result=future.result()
        results.append(result)
        print(json.dumps(result), flush=True)
print(json.dumps({'providers':len(results),'providersWithStreams':sum(r['streams']>0 for r in results),
                  'streams':sum(r['streams'] for r in results)}), flush=True)
