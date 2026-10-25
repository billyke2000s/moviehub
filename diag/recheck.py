"""Diagnostic only: re-check candidate dead sites three times, a few minutes apart."""
import json, time, requests
sites = """123moviestv.me 1movies.la 9pm.to couchtuner.show levidia.ch movies4fun.net sflixz.watch watchug.com www1.movie4u.live
123-movies.golf dopebox.to mp4hydra.top projectfreetv.lol soap2dayz.top goojara.life cinebox.cc ev01.to filmxy.online flixhq.to
flixwave.me hdbest.net hdtoday.cc m4ufree.se movies2watch.tv putlocker.gives streamlord.com vumoo.mx gogoanime3.co gogoanimes.to myflixer.pw tvids.net""".split()
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"}
def one(site):
    try:
        r = requests.get("https://" + site + "/", headers=UA, timeout=20, allow_redirects=True)
        return f"HTTP {r.status_code}" + (f" -> {r.url.split('/')[2]}" if r.url.split('/')[2] != site else "")
    except Exception as e:
        return type(e).__name__
rounds = []
for n in range(3):
    if n: time.sleep(180)
    rounds.append({s: one(s) for s in sites})
out = {s: [r[s] for r in rounds] for s in sites}
json.dump(out, open("recheck.json", "w"), indent=1)
for s, v in out.items():
    print(f"::notice title={s}::{' | '.join(v)}")
