"""
debrid.py — multi-debrid resolver (Premiumize, Real-Debrid, AllDebrid).

  • cache_check(hashes) -> {hash: service}  which hashes are known-cached
  • resolve(hash, ...)  -> playable URL      turn a hash into a direct video link

Notes on provider APIs (checked October 2026):
  • Real-Debrid removed /torrents/instantAvailability in Nov 2024, so cache
    state is unknown until we try. Those results are tagged "realdebrid_maybe"
    and are still selectable — resolve() finds out and tells the user.
  • AllDebrid no longer documents /magnet/instant; /v4/magnet/status links
    were moved to /v4/magnet/files. Auth is an "Authorization: Bearer" header.
    Results are tagged "alldebrid_maybe" for the same reason.
  • Premiumize still exposes /cache/check (not independently verified here).
"""

import re
import time

import requests
import xbmcaddon

ADDON = xbmcaddon.Addon()

_HEADERS = {"User-Agent": "MovieHub/3.2", "Accept": "application/json"}

PM = "https://www.premiumize.me/api"
RD = "https://api.real-debrid.com/rest/1.0"
AD = "https://api.alldebrid.com/v4"

_VID = (".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm", ".wmv", ".mpg", ".mpeg")
_JUNK = re.compile(r"\bsample\b|\btrailer\b|\bextras?\b|featurette", re.I)

LABELS = {"premiumize": "PM", "realdebrid": "RD", "alldebrid": "AD"}


def _pm_key(): return ADDON.getSetting("premiumize_key") or ""
def _rd_key(): return ADDON.getSetting("realdebrid_key") or ""
def _ad_key(): return ADDON.getSetting("alldebrid_key") or ""


class DebridError(Exception):
    pass


class NotCached(DebridError):
    """The torrent exists but the provider has not finished downloading it."""
    pass


def available_services():
    s = []
    if _pm_key(): s.append("premiumize")
    if _rd_key(): s.append("realdebrid")
    if _ad_key(): s.append("alldebrid")
    return s


def base_service(svc):
    return (svc or "").replace("_maybe", "")


# ── file picking ─────────────────────────────────────────────────────────────

def _pick(files, name_of, size_of, filename=None, season=None, episode=None):
    """Choose the right video file from a torrent's file list.

    Priority: exact filename hint from the scraper > SxxEyy match for an
    episode > largest non-sample video. Picking simply "the largest file"
    plays the wrong episode for season packs.
    """
    vids = [f for f in files if str(name_of(f)).lower().endswith(_VID)]
    clean = [f for f in vids if not _JUNK.search(str(name_of(f)))] or vids
    if not clean:
        return None
    if filename:
        want = filename.lower().rsplit("/", 1)[-1]
        for f in clean:
            if str(name_of(f)).lower().rsplit("/", 1)[-1] == want:
                return f
    if season and episode:
        try:
            s, e = int(season), int(episode)
            pat = re.compile(r"s0*%d[ ._-]*e0*%d(?!\d)|\b0*%dx0*%d(?!\d)" % (s, e, s, e), re.I)
            hits = [f for f in clean if pat.search(str(name_of(f)))]
            if hits:
                return max(hits, key=lambda f: size_of(f) or 0)
        except (TypeError, ValueError):
            pass
    return max(clean, key=lambda f: size_of(f) or 0)


# ── cache check across all services ──────────────────────────────────────────

def cache_check(hashes):
    """Return {hash: service} for every hash we can offer.

    "premiumize" = confirmed cached. "realdebrid_maybe"/"alldebrid_maybe" =
    provider cannot tell us in advance; still playable if it is cached.
    """
    cached = {}
    if _pm_key():
        for h, ok in _pm_cache(hashes).items():
            if ok:
                cached[h] = "premiumize"
    for svc, key in (("realdebrid", _rd_key()), ("alldebrid", _ad_key())):
        if key:
            for h in hashes:
                cached.setdefault(h, svc + "_maybe")
    return cached


def _pm_cache(hashes):
    out = {}
    for i in range(0, len(hashes), 100):
        chunk = hashes[i:i + 100]
        try:
            r = requests.post(PM + "/cache/check",
                              data=[("apikey", _pm_key())] + [("items[]", h) for h in chunk],
                              headers=_HEADERS, timeout=15)
            j = r.json()
            resp = j.get("response", []) if j.get("status") == "success" else []
            for idx, h in enumerate(chunk):
                out[h] = bool(resp[idx]) if idx < len(resp) else False
        except Exception:
            for h in chunk:
                out.setdefault(h, False)
    return out


# ── resolve a hash to a playable URL ─────────────────────────────────────────

def resolve(info_hash, service, name="download", filename=None, season=None, episode=None):
    order = []
    svc = base_service(service)
    if svc in available_services():
        order.append(svc)
    order += [s for s in available_services() if s not in order]
    if not order:
        raise DebridError("No debrid service set.")
    last = None
    for s in order:
        try:
            if s == "premiumize":
                return _pm_resolve(info_hash, filename, season, episode)
            if s == "realdebrid":
                return _rd_resolve(info_hash, filename, season, episode)
            if s == "alldebrid":
                return _ad_resolve(info_hash, filename, season, episode)
        except DebridError as e:
            last = e
    raise last or DebridError("No debrid service could open that source.")


def _magnet(h): return "magnet:?xt=urn:btih:%s" % h


def _pm_resolve(h, filename, season, episode):
    try:
        r = requests.post(PM + "/transfer/directdl",
                          data={"apikey": _pm_key(), "src": _magnet(h)},
                          headers=_HEADERS, timeout=30)
        j = r.json()
    except Exception:
        raise DebridError("Couldn't reach Premiumize.")
    if j.get("status") != "success":
        raise NotCached(j.get("message") or "Premiumize hasn't cached that source.")
    files = j.get("content", []) or []
    best = _pick(files, lambda f: f.get("path", ""), lambda f: f.get("size", 0),
                 filename, season, episode)
    if not best:
        raise DebridError("Premiumize found no video file in that source.")
    link = best.get("stream_link") or best.get("link")
    if not link:
        raise DebridError("Premiumize returned no playable link.")
    return link


def _rd_resolve(h, filename, season, episode):
    hdr = dict(_HEADERS)
    hdr["Authorization"] = "Bearer " + _rd_key()
    tid = None
    try:
        r = requests.post(RD + "/torrents/addMagnet", data={"magnet": _magnet(h)},
                          headers=hdr, timeout=20)
        if r.status_code == 401:
            raise DebridError("Real-Debrid rejected the API key.")
        tid = (r.json() or {}).get("id")
        if not tid:
            raise DebridError("Real-Debrid rejected that source.")

        # Wait (briefly) for magnet conversion before files can be selected.
        info = {}
        for _ in range(10):
            info = requests.get(RD + "/torrents/info/%s" % tid, headers=hdr, timeout=15).json()
            if info.get("status") != "magnet_conversion":
                break
            time.sleep(1)
        files = info.get("files", []) or []
        if info.get("status") == "waiting_files_selection" or not any(f.get("selected") for f in files):
            target = _pick(files, lambda f: f.get("path", ""), lambda f: f.get("bytes", 0),
                           filename, season, episode)
            if not target:
                raise DebridError("Real-Debrid found no video file in that source.")
            requests.post(RD + "/torrents/selectFiles/%s" % tid,
                          data={"files": str(target["id"])}, headers=hdr, timeout=20)
            for _ in range(3):
                info = requests.get(RD + "/torrents/info/%s" % tid, headers=hdr, timeout=15).json()
                if info.get("status") == "downloaded":
                    break
                time.sleep(1)

        if info.get("status") != "downloaded" or not info.get("links"):
            raise NotCached("Real-Debrid hasn't cached that one — pick another link.")

        # links[] lines up with the selected files, in order.
        selected = [f for f in info.get("files", []) if f.get("selected")]
        link_idx = 0
        if len(selected) > 1:
            chosen = _pick(selected, lambda f: f.get("path", ""), lambda f: f.get("bytes", 0),
                           filename, season, episode)
            link_idx = selected.index(chosen) if chosen in selected else 0
        links = info["links"]
        u = requests.post(RD + "/unrestrict/link",
                          data={"link": links[min(link_idx, len(links) - 1)]},
                          headers=hdr, timeout=20).json()
        link = u.get("download")
        if not link:
            raise DebridError("Real-Debrid couldn't produce a link.")
        tid = None  # keep successful torrents in the account
        return link
    except DebridError:
        raise
    except Exception:
        raise DebridError("Couldn't reach Real-Debrid.")
    finally:
        # Don't litter the user's account with uncached/failed torrents.
        if tid:
            try:
                requests.delete(RD + "/torrents/delete/%s" % tid, headers=hdr, timeout=10)
            except Exception:
                pass


def _ad_flatten(nodes, prefix=""):
    out = []
    for n in nodes or []:
        name = prefix + str(n.get("n", ""))
        if "e" in n:
            out += _ad_flatten(n.get("e"), name + "/")
        elif n.get("l"):
            out.append({"name": name, "size": n.get("s", 0), "link": n.get("l")})
    return out


def _ad_resolve(h, filename, season, episode):
    hdr = dict(_HEADERS)
    hdr["Authorization"] = "Bearer " + _ad_key()
    mid = None
    try:
        up = requests.post(AD + "/magnet/upload", data={"magnets[]": h},
                           headers=hdr, timeout=20).json()
        if up.get("status") != "success":
            msg = (up.get("error") or {}).get("message")
            raise DebridError(msg or "AllDebrid rejected that source.")
        magnets = up.get("data", {}).get("magnets", [])
        if not magnets or magnets[0].get("error"):
            raise DebridError("AllDebrid rejected that source.")
        mid = magnets[0].get("id")
        if not magnets[0].get("ready"):
            raise NotCached("AllDebrid hasn't cached that one — pick another link.")
        fl = requests.post(AD + "/magnet/files", data={"id[]": mid},
                           headers=hdr, timeout=20).json()
        mags = fl.get("data", {}).get("magnets", [])
        files = _ad_flatten(mags[0].get("files", [])) if mags else []
        best = _pick(files, lambda f: f["name"], lambda f: f["size"], filename, season, episode)
        if not best:
            raise DebridError("AllDebrid found no video file in that source.")
        un = requests.post(AD + "/link/unlock", data={"link": best["link"]},
                           headers=hdr, timeout=20).json()
        link = un.get("data", {}).get("link")
        if not link:
            raise DebridError("AllDebrid couldn't produce a link right now.")
        mid = None
        return link
    except DebridError:
        raise
    except Exception:
        raise DebridError("Couldn't reach AllDebrid.")
    finally:
        if mid:
            try:
                requests.post(AD + "/magnet/delete", data={"id": mid}, headers=hdr, timeout=10)
            except Exception:
                pass


def queue_download(info_hash, service):
    """Add a not-yet-cached source to the provider so it downloads in the cloud."""
    svc = base_service(service) or (available_services() or [""])[0]
    m = _magnet(info_hash)
    try:
        if svc == "premiumize":
            j = requests.post(PM + "/transfer/create", data={"apikey": _pm_key(), "src": m},
                              headers=_HEADERS, timeout=20).json()
            if j.get("status") != "success":
                raise DebridError(j.get("message") or "Premiumize refused the download.")
        elif svc == "realdebrid":
            hdr = dict(_HEADERS); hdr["Authorization"] = "Bearer " + _rd_key()
            tid = requests.post(RD + "/torrents/addMagnet", data={"magnet": m},
                                headers=hdr, timeout=20).json().get("id")
            if not tid:
                raise DebridError("Real-Debrid refused the download.")
            time.sleep(2)
            requests.post(RD + "/torrents/selectFiles/%s" % tid, data={"files": "all"},
                          headers=hdr, timeout=20)
        elif svc == "alldebrid":
            hdr = dict(_HEADERS); hdr["Authorization"] = "Bearer " + _ad_key()
            j = requests.post(AD + "/magnet/upload", data={"magnets[]": info_hash},
                              headers=hdr, timeout=20).json()
            if j.get("status") != "success":
                raise DebridError("AllDebrid refused the download.")
        else:
            raise DebridError("No debrid service set.")
    except DebridError:
        raise
    except Exception:
        raise DebridError("Couldn't reach the debrid service.")
    return True
