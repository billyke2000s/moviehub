"""Diagnostic only: record each scraper's HTTP requests and responses for two films."""
import json, sys, time, threading, requests
from pathlib import Path
sys.path.insert(0, str(Path('android-tv-launcher/app/src/main/python').resolve()))
import moviehub_vod as vod
local = threading.local()
original = requests.Session.request
def traced(self, method, url, *a, **k):
    entry = {'m': method, 'url': url[:300], 'data': str(k.get('data'))[:300] if k.get('data') else ''}
    try:
        r = original(self, method, url, *a, **k)
        entry['status'] = r.status_code
        entry['location'] = r.headers.get('Location', '')[:300]
        log = getattr(local, 'log', None)
        if log is not None: log.append(entry)
        entry['_r'] = r
        return r
    except Exception as e:
        entry['error'] = type(e).__name__ + ': ' + str(e)[:150]
        log = getattr(local, 'log', None)
        if log is not None: log.append(entry)
        raise
requests.Session.request = traced
films = [
    {'imdb':'tt0468569','tmdb':155,'title':'The Dark Knight','year':'2008','season':-1,'episode':-1,'http':False},
    {'imdb':'tt15398776','tmdb':872585,'title':'Oppenheimer','year':'2023','season':-1,'episode':-1,'http':False},
]
ids = sys.argv[1].split(',')
out = []
for pid in ids:
    for info in films:
        local.log = []
        started = time.monotonic()
        try:
            res = json.loads(vod.query(pid, json.dumps(info), lambda: time.monotonic() - started > 60))
        except BaseException as e:
            res = {'status': type(e).__name__, 'sources': []}
        steps = []
        for e in local.log:
            r = e.pop('_r', None)
            if r is not None:
                body = (r._content or b'')
                e['size'] = len(body)
                e['hasTitle'] = info['title'].lower().encode() in body.lower()
                e['body'] = body[:60000].decode('utf-8', 'replace')
            steps.append(e)
        out.append({'id': pid, 'film': info['title'], 'status': res.get('status'), 'streams': len(res.get('sources', [])),
                    'extracted': res.get('extracted', 0), 'steps': steps})
        print(f"::notice title={pid} {info['title']}::{res.get('status')} streams={len(res.get('sources', []))} requests={len(steps)}")
json.dump(out, open('trace.json', 'w'))
