"""
streams.py — multi-scraper source finding + multi-debrid resolution.

Sources: Torrentio + Comet + MediaFusion (merged, deduped by infoHash) so you
always find something. Debrid: whichever of Premiumize / Real-Debrid / AllDebrid
the user has keys for (see debrid.py). All device-side.
"""

import re
import threading

import requests
import xbmcaddon

from . import debrid

ADDON = xbmcaddon.Addon()

_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                  "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "application/json, text/plain, */*",
}

# Plain scraper endpoints (no debrid in URL -> not the protected endpoints).
SCRAPERS = {
    "torrentio": "https://torrentio.strem.fun",
    "comet": "https://comet.elfhosted.com",
    "mediafusion": "https://mediafusion.elfhosted.com",
}


class StreamError(Exception):
    pass


class NotCachedError(StreamError):
    pass


def _size_mb(title):
    m = re.search(r"(\d+(?:\.\d+)?)\s*(GB|MB|KB)", title or "", re.I)
    if not m: return 0.0
    val, unit = float(m.group(1)), m.group(2).upper()
    return val*1024 if unit == "GB" else (val/1024 if unit == "KB" else val)

def _res_score(t):
    if re.search(r"2160p|4k|uhd", t or "", re.I): return 4
    if re.search(r"1080p", t or "", re.I): return 3
    if re.search(r"720p", t or "", re.I): return 2
    return 1

def _quality_label(t):
    m = re.search(r"2160p|4k|uhd|1080p|720p|480p", t or "", re.I)
    return m.group(0).upper() if m else ""

def _sort(items, pref):
    def key(s):
        size = s.get("size_mb", 0)
        is_pack = ("pack" in (s.get("title","").lower())) or size > 40000
        return (1 if is_pack else 0,
                -_res_score(s.get("title","")) if pref == "quality" else 0,
                -size if pref in ("quality","size") else (size if size else 1e9))
    return sorted(items, key=key)


def _path(media_type, imdb, season, episode):
    if media_type == "tv":
        return "stream/series/%s:%s:%s.json" % (imdb, season or 1, episode or 1)
    return "stream/movie/%s.json" % imdb


def _scrape_one(base, media_type, imdb, season, episode):
    """Return (streams, ok). ok=False means the scraper itself failed."""
    url = "%s/%s" % (base, _path(media_type, imdb, season, episode))
    try:
        r = requests.get(url, timeout=12, headers=_HEADERS)
        if r.status_code >= 400:
            return [], False
        return (r.json().get("streams", []) or []), True
    except Exception:
        return [], False


def fetch_streams(imdb_id, media_type, season=None, episode=None):
    if not debrid.available_services():
        raise StreamError("No debrid service set. Add a Premiumize, Real-Debrid "
                          "or AllDebrid key in settings.")
    if not imdb_id:
        raise StreamError("No IMDb id for this title, so streams can't be found.")

    # 1) query all scrapers IN PARALLEL (was sequential: up to 60s of waiting
    #    behind a frozen progress dialog), merge + dedupe by infoHash.
    results = {}
    def worker(name, base):
        results[name] = _scrape_one(base, media_type, imdb_id, season, episode)
    threads = [threading.Thread(target=worker, args=(n, b)) for n, b in SCRAPERS.items()]
    for t in threads:
        t.daemon = True
        t.start()
    for t in threads:
        t.join(15)

    failed = [n for n in SCRAPERS if not results.get(n, ([], False))[1]]
    if len(failed) == len(SCRAPERS):
        raise StreamError("No source sites answered (%s). They may be down or "
                          "blocked on your network." % ", ".join(failed))

    seen = {}
    for name in SCRAPERS:
        for s in results.get(name, ([], False))[0]:
            h = (s.get("infoHash") or "").lower()
            if not h or h in seen:
                continue
            title = s.get("title", "") or s.get("description", "") or ""
            hints = s.get("behaviorHints") or {}
            seen[h] = {
                "name": (s.get("name", "") or "Stream").replace("\n", " "),
                "title": title,
                "infoHash": h,
                "filename": hints.get("filename", "") or "",
                "size_mb": _size_mb(title),
                "quality": _quality_label(title + " " + (s.get("name") or "")),
                "source": name,
            }
    items = list(seen.values())
    if not items:
        return []

    items = _sort(items, ADDON.getSetting("sort_pref") or "quality")

    # 2) cache state per service. "_maybe" = provider can't say in advance,
    #    so the link is still offered and resolve() finds out on click.
    cached = debrid.cache_check([i["infoHash"] for i in items])
    cached_only = ADDON.getSetting("cached_only") == "true"

    confirmed, maybe = [], []
    for i in items:
        svc = cached.get(i["infoHash"]) or ""
        i["service"] = svc
        i["cached"] = bool(svc) and not svc.endswith("_maybe")
        if i["cached"]:
            confirmed.append(i)
        elif svc and not cached_only:
            maybe.append(i)
        elif not svc and not cached_only:
            # Premiumize-only user and not cached: offer as a cloud download.
            i["service"] = (debrid.available_services() or [""])[0]
            maybe.append(i)
    # Known-cached first, then everything else in the user's sort order.
    return confirmed + maybe


def best_cached(streams):
    """Return the top confirmed-cached stream, or None."""
    for s in streams:
        if s.get("cached"):
            return s
    return None


def resolve_playable(stream, season=None, episode=None):
    try:
        return debrid.resolve(stream["infoHash"], stream.get("service") or "",
                              stream.get("name", "download"),
                              filename=stream.get("filename") or None,
                              season=season, episode=episode)
    except debrid.NotCached as e:
        raise NotCachedError(str(e))
    except debrid.DebridError as e:
        raise StreamError(str(e))


def send_to_debrid(stream):
    """Queue a not-cached source as a cloud download on the user's service."""
    try:
        return debrid.queue_download(stream["infoHash"], stream.get("service") or "")
    except debrid.DebridError as e:
        raise StreamError(str(e))


# Backwards-compatible name used by older code paths.
send_to_premiumize = send_to_debrid


# ── Premiumize transfers (kept for the Transfers screen) ─────────────────────

PREMIUMIZE = "https://www.premiumize.me/api"
def _pm_key(): return ADDON.getSetting("premiumize_key") or ""

def list_transfers():
    if not _pm_key():
        raise StreamError("Transfers need a Premiumize key.")
    try:
        r = requests.get(PREMIUMIZE + "/transfer/list",
                         params={"apikey": _pm_key()}, headers=_HEADERS, timeout=20)
        j = r.json()
    except Exception:
        raise StreamError("Couldn't reach Premiumize.")
    if j.get("status") != "success":
        raise StreamError(j.get("message") or "Couldn't load transfers.")
    return j.get("transfers", []) or []

def delete_transfer(transfer_id):
    if not _pm_key():
        raise StreamError("No Premiumize key set.")
    try:
        r = requests.post(PREMIUMIZE + "/transfer/delete",
                          data={"apikey": _pm_key(), "id": transfer_id},
                          headers=_HEADERS, timeout=20)
        j = r.json()
    except Exception:
        raise StreamError("Couldn't reach Premiumize.")
    if j.get("status") != "success":
        raise StreamError(j.get("message") or "Couldn't delete that transfer.")
    return True
