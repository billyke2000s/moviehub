"""On-demand public-source health check; never prints playback URLs, bodies or credentials."""
import concurrent.futures
import json
from pathlib import Path
import sys
import threading
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import moviehub_vod as vod

query = json.dumps({'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
                   'year':'1968','season':-1,'episode':-1,'http':False})
started = time.monotonic()
results = []
def probe(row):
    begin = time.monotonic()
    try:
        result = json.loads(vod.query(row['id'], query, lambda: time.monotonic()-started > 160))
        return {'provider':row['id'], 'status':result['status'], 'streams':len(result['sources']),
                'seconds':round(time.monotonic()-begin, 1)}
    except BaseException as error:
        return {'provider':row['id'], 'status':type(error).__name__, 'streams':0}

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
    pending = [workers.submit(probe,row) for row in json.loads(vod.providers())]
    for future in concurrent.futures.as_completed(pending):
        result=future.result()
        results.append(result)
        print(json.dumps(result), flush=True)
print(json.dumps({'providers':len(results),'providersWithStreams':sum(r['streams']>0 for r in results),
                  'streams':sum(r['streams'] for r in results)}), flush=True)
