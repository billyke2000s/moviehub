"""Diagnostic only: per-website connectivity plus the app's real search, from a GitHub runner."""
import concurrent.futures, json, socket, ssl, sys, time, urllib.request
from pathlib import Path
sys.path.insert(0, str(Path('android-tv-launcher/app/src/main/python').resolve()))
import moviehub_vod as vod
rows = json.loads(vod.search_providers())
def reach(site):
    out = {'site': site}
    try:
        out['ips'] = sorted({a[4][0] for a in socket.getaddrinfo(site, 443, proto=socket.IPPROTO_TCP)})[:3]
    except Exception as e:
        out['dns'] = type(e).__name__; return out
    try:
        req = urllib.request.Request('https://' + site + '/', headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'})
        with urllib.request.urlopen(req, timeout=15, context=ssl.create_default_context()) as r:
            out['http'] = r.status; out['final'] = r.url.split('/')[2]
    except urllib.error.HTTPError as e:
        out['http'] = e.code
    except Exception as e:
        out['err'] = type(e).__name__ + ': ' + str(e)[:80]
    return out
films = [
    {'imdb':'tt0468569','tmdb':155,'title':'The Dark Knight','year':'2008','season':-1,'episode':-1,'http':False},
    {'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead','year':'1968','season':-1,'episode':-1,'http':False},
]
started = time.monotonic()
def search(row, info):
    b = time.monotonic()
    try:
        r = json.loads(vod.query_site(row['id'], json.dumps(info), lambda: time.monotonic() - started > 600))
        return {'id': row['id'], 'site': row['site'], 'film': info['title'], 'status': r['status'], 'streams': len(r['sources']),
                'extracted': r.get('extracted', 0), 'attempts': [(a['adapter'], a['status'], a.get('stage', '')) for a in r.get('attempts', [])], 's': round(time.monotonic() - b, 1)}
    except BaseException as e:
        return {'id': row['id'], 'site': row['site'], 'film': info['title'], 'status': type(e).__name__, 'streams': 0}
with concurrent.futures.ThreadPoolExecutor(16) as pool:
    reachability = list(pool.map(reach, sorted({r['site'] for r in rows})))
with concurrent.futures.ThreadPoolExecutor(8) as pool:
    jobs = [pool.submit(search, r, f) for f in films for r in rows if r['movies']]
    searches = [j.result() for j in jobs]
json.dump({'reach': reachability, 'search': searches}, open('vod-sites.json', 'w'), indent=1)
ok = sum(1 for s in searches if s['streams'])
print(f"::notice title=summary::sites {len(reachability)}, searches {len(searches)}, with streams {ok}")
