"""Diagnostic only: test proposed scraper fixes against the live sites."""
import json, re, sys, time, requests
from pathlib import Path
sys.path.insert(0, str(Path('android-tv-launcher/app/src/main/python').resolve()))
import moviehub_vod as vod
from moviehub_compat import client
out = {}
films = [
    {'imdb':'tt15398776','tmdb':872585,'title':'Oppenheimer','year':'2023','season':-1,'episode':-1,'http':False},
    {'imdb':'tt0468569','tmdb':155,'title':'The Dark Knight','year':'2008','season':-1,'episode':-1,'http':False},
]
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'
# 1. fzmovies final file host: which headers make it answer with the file?
try:
    s = requests.Session()
    page = s.get('https://www.fzmovies.live/advancedsearch.php?category=Hollywood&year=2023&year2=2023&moviename=Oppenheimer', headers={'User-Agent': UA}, timeout=20).text
    m = re.search(r"href='(movie-Oppenheimer[^']+)'", page) or re.search(r'href="(movie-Oppenheimer[^"]+)"', page)
    movie = s.get('https://www.fzmovies.live/' + m.group(1), headers={'User-Agent': UA}, timeout=20).text
    d1 = re.findall(r"""href=['"]([^'"]*download1\.php[^'"]*)""", movie)[0]
    p1 = s.get('https://www.fzmovies.live/' + d1.lstrip('/'), headers={'User-Agent': UA}, timeout=20).text
    d2 = re.findall(r"""href=['"]([^'"]*download\.php[^'"]*)""", p1)[0]
    p2 = s.get('https://www.fzmovies.live/' + d2.lstrip('/'), headers={'User-Agent': UA}, timeout=20).text
    d3 = re.findall(r"""href=['"]([^'"]*dlink\.php[^'"]*)""", p2)[0]
    p3 = s.get('https://www.fzmovies.live/' + d3.lstrip('/'), headers={'User-Agent': UA}, timeout=20).text
    files = sorted(set(re.findall(r"""(https://[a-z0-9]+\.[a-z0-9]+\.cyou/[^'"\s]+)""", p3)))
    out['fz_files'] = [f.split('/')[2] for f in files]
    trials = {}
    for f in files[:3]:
        host = f.split('/')[2]
        for name, h in {'none': {}, 'ua': {'User-Agent': UA}, 'ua+referer': {'User-Agent': UA, 'Referer': 'https://www.fzmovies.live/'},
                        'ua+referer+range': {'User-Agent': UA, 'Referer': 'https://www.fzmovies.live/', 'Range': 'bytes=0-1023'}}.items():
            try:
                r = requests.get(f, headers=h, timeout=20, stream=True)
                first = next(r.iter_content(64), b'')
                trials[f'{host} {name}'] = f"{r.status_code} len={r.headers.get('Content-Length')} type={r.headers.get('Content-Type')} first={first[:12].hex()}"
                r.close()
            except Exception as e:
                trials[f'{host} {name}'] = type(e).__name__ + ': ' + str(e)[:80]
    out['fz_trials'] = trials
except Exception as e:
    out['fz_error'] = repr(e)[:300]
# 2. Moved sites with the new address (and stagatv's own header copy).
def run(pid, base, own_headers=False):
    rec = vod._MODULES[pid]
    import importlib
    mod = importlib.import_module(rec['module'])
    orig = mod.source.__init__
    def init(self, *a, **k):
        orig(self, *a, **k)
        self.base_link = base
        self.domains = [base.split('/')[2]]
        if own_headers: self.headers = dict(self.headers)
    mod.source.__init__ = init
    res = {}
    for f in films:
        t = time.monotonic()
        r = json.loads(vod.query(pid, json.dumps(f), lambda: time.monotonic() - t > 60))
        res[f['title']] = {'status': r['status'], 'streams': len(r['sources']), 'extracted': r.get('extracted', 0)}
    mod.source.__init__ = orig
    return res
before = dict(client.dnt_headers) if hasattr(client, 'dnt_headers') else None
out['pressplayz'] = run('scrubs:pressplay_top', 'https://pressplayz.top')
out['stagatv_www9'] = run('free99:stagatv_com', 'https://www9.stagatv.com', own_headers=True)
json.dump(out, open('fixcheck.json', 'w'), indent=1)
print('::notice title=done::' + json.dumps({k: v for k, v in out.items() if k != 'fz_trials'})[:2000])
