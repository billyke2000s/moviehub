"""Diagnostic only: the app's real website search for several films, as one process like the app."""
import concurrent.futures, json, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(sys.argv[1]).resolve()))
import moviehub_vod as vod
films = [
    {'imdb':'tt15398776','tmdb':872585,'title':'Oppenheimer','year':'2023','season':-1,'episode':-1,'http':False},
    {'imdb':'tt0468569','tmdb':155,'title':'The Dark Knight','year':'2008','season':-1,'episode':-1,'http':False},
    {'imdb':'tt1375666','tmdb':27205,'title':'Inception','year':'2010','season':-1,'episode':-1,'http':False},
    {'imdb':'tt6718170','tmdb':502356,'title':'The Super Mario Bros. Movie','year':'2023','season':-1,'episode':-1,'http':False},
]
rows = [r for r in json.loads(vod.search_providers()) if r['movies']]
result = {}
for f in films:
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        got = list(pool.map(lambda r: (r['id'], json.loads(vod.query_site(r['id'], json.dumps(f), lambda: time.monotonic() - started > 300))), rows))
    result[f['title']] = {pid: {'status': r['status'], 'streams': len(r['sources'])} for pid, r in got}
    ok = {pid: v['streams'] for pid, v in result[f['title']].items() if v['streams']}
    print(f"::notice title={sys.argv[2]} {f['title']}::sites with links {len(ok)}: {ok}  ({round(time.monotonic()-started)} s)")
json.dump(result, open(sys.argv[2] + '.json', 'w'), indent=1)
