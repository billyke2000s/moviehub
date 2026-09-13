"""
debrid.py — multi-debrid resolver (Premiumize, Real-Debrid, AllDebrid).

Given a list of torrent infoHashes, each service can:
  • cache_check(hashes) -> {hash: bool}   which are instantly playable
  • resolve(hash)       -> playable URL    turn a cached hash into a video link

The add-on uses whichever services the user has keys for, checks them all, and
prefers cached results. All device-side; keys come from settings.
"""

import requests
import xbmcaddon

ADDON = xbmcaddon.Addon()

_HEADERS = {"User-Agent": "MovieHub/1.0", "Accept": "application/json"}

PM = "https://www.premiumize.me/api"
RD = "https://api.real-debrid.com/rest/1.0"
AD = "https://api.alldebrid.com/v4"


def _pm_key(): return ADDON.getSetting("premiumize_key") or ""
def _rd_key(): return ADDON.getSetting("realdebrid_key") or ""
def _ad_key(): return ADDON.getSetting("alldebrid_key") or ""


class DebridError(Exception):
    pass


def available_services():
    s = []
    if _pm_key(): s.append("premiumize")
    if _rd_key(): s.append("realdebrid")
    if _ad_key(): s.append("alldebrid")
    return s


# ── cache check across all services ──────────────────────────────────────────

def cache_check(hashes):
    """Return {hash: service_name} for hashes cached on ANY service (first hit)."""
    cached = {}
    if _pm_key():
        for h, ok in _pm_cache(hashes).items():
            if ok and h not in cached:
                cached[h] = "premiumize"
    if _rd_key():
        # Real-Debrid deprecated instant-availability; treat all as resolvable
        # on demand. We mark them 'realdebrid' only if not already cached.
        for h in hashes:
            cached.setdefault(h, "realdebrid_maybe")
    if _ad_key():
        for h, ok in _ad_cache(hashes).items():
            if ok and h not in cached:
                cached[h] = "alldebrid"
    return cached


def _pm_cache(hashes):
    out = {}
    for i in range(0, len(hashes), 100):
        chunk = hashes[i:i+100]
        try:
            r = requests.post(PM + "/cache/check",
                              data=[("apikey", _pm_key())] + [("items[]", h) for h in chunk],
                              headers=_HEADERS, timeout=20)
            j = r.json()
            resp = j.get("response", []) if j.get("status") == "success" else []
            for idx, h in enumerate(chunk):
                out[h] = bool(resp[idx]) if idx < len(resp) else False
        except Exception:
            for h in chunk: out.setdefault(h, False)
    return out


def _ad_cache(hashes):
    out = {}
    try:
        r = requests.get(AD + "/magnet/instant",
                         params=[("agent", "moviehub"), ("apikey", _ad_key())] +
                                [("magnets[]", h) for h in hashes],
                         headers=_HEADERS, timeout=20)
        j = r.json()
        if j.get("status") == "success":
            for m in j.get("data", {}).get("magnets", []):
                out[m.get("hash", "").lower()] = bool(m.get("instant"))
    except Exception:
        pass
    for h in hashes: out.setdefault(h, False)
    return out


# ── resolve a hash to a playable URL ─────────────────────────────────────────

def resolve(info_hash, service, name="download"):
    if service == "premiumize":
        return _pm_resolve(info_hash)
    if service in ("realdebrid", "realdebrid_maybe"):
        return _rd_resolve(info_hash)
    if service == "alldebrid":
        return _ad_resolve(info_hash)
    # try any available
    for s in available_services():
        try:
            return resolve(info_hash, s, name)
        except DebridError:
            continue
    raise DebridError("No debrid service could open that source.")


def _magnet(h): return "magnet:?xt=urn:btih:%s" % h
_VID = (".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm")


def _pm_resolve(h):
    try:
        r = requests.post(PM + "/transfer/directdl",
                          data={"apikey": _pm_key(), "src": _magnet(h)},
                          headers=_HEADERS, timeout=30)
        j = r.json()
    except Exception:
        raise DebridError("Couldn't reach Premiumize.")
    if j.get("status") != "success":
        raise DebridError(j.get("message") or "Premiumize couldn't open that.")
    files = j.get("content", []) or []
    vids = [f for f in files if str(f.get("path", "")).lower().endswith(_VID)] or files
    if not vids: raise DebridError("No playable file.")
    best = max(vids, key=lambda f: f.get("size", 0))
    link = best.get("stream_link") or best.get("link")
    if not link: raise DebridError("No playable link.")
    return link


def _rd_resolve(h):
    hdr = dict(_HEADERS); hdr["Authorization"] = "Bearer " + _rd_key()
    try:
        # add magnet
        r = requests.post(RD + "/torrents/addMagnet",
                          data={"magnet": _magnet(h)}, headers=hdr, timeout=30)
        j = r.json()
        tid = j.get("id")
        if not tid: raise DebridError("Real-Debrid rejected that source.")
        # select all files
        requests.post(RD + "/torrents/selectFiles/%s" % tid,
                      data={"files": "all"}, headers=hdr, timeout=30)
        info = requests.get(RD + "/torrents/info/%s" % tid, headers=hdr, timeout=30).json()
        links = info.get("links", [])
        if not links: raise DebridError("Real-Debrid is still caching this — try another source.")
        # unrestrict the first link
        u = requests.post(RD + "/unrestrict/link",
                          data={"link": links[0]}, headers=hdr, timeout=30).json()
        link = u.get("download")
        if not link: raise DebridError("Real-Debrid couldn't produce a link.")
        return link
    except DebridError:
        raise
    except Exception:
        raise DebridError("Couldn't reach Real-Debrid.")


def _ad_resolve(h):
    try:
        up = requests.get(AD + "/magnet/upload",
                          params={"agent": "moviehub", "apikey": _ad_key(), "magnets[]": h},
                          headers=_HEADERS, timeout=30).json()
        magnets = up.get("data", {}).get("magnets", [])
        if not magnets: raise DebridError("AllDebrid rejected that source.")
        mid = magnets[0].get("id")
        status = requests.get(AD + "/magnet/status",
                              params={"agent": "moviehub", "apikey": _ad_key(), "id": mid},
                              headers=_HEADERS, timeout=30).json()
        links = status.get("data", {}).get("magnets", {}).get("links", [])
        vids = [l for l in links if str(l.get("filename", "")).lower().endswith(_VID)] or links
        if not vids: raise DebridError("AllDebrid is still caching this — try another source.")
        best = max(vids, key=lambda l: l.get("size", 0))
        unlocked = requests.get(AD + "/link/unlock",
                                params={"agent": "moviehub", "apikey": _ad_key(),
                                        "link": best.get("link")},
                                headers=_HEADERS, timeout=30).json()
        link = unlocked.get("data", {}).get("link")
        if not link: raise DebridError("AllDebrid couldn't produce a link.")
        return link
    except DebridError:
        raise
    except Exception:
        raise DebridError("Couldn't reach AllDebrid.")
