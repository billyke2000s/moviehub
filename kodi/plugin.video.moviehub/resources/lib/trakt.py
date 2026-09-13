"""
trakt.py — OPTIONAL Trakt mirror.

Design: your server is the source of truth. Trakt is a best-effort mirror so
watch state shows up in other addons and vice-versa. EVERY function here is
wrapped so that if Trakt is down, unconfigured, or errors, it fails silently and
the rest of Movie Hub carries on using the server.

Device-code login: user enters a code on trakt.tv/activate once; the resulting
token is stored on our server against the profile (so it syncs across devices).
"""

import time
import requests
import xbmc
import xbmcgui
import xbmcaddon

ADDON = xbmcaddon.Addon()
API = "https://api.trakt.tv"


def _enabled():
    return ADDON.getSetting("trakt_enabled") == "true"

def _cid():
    return ADDON.getSetting("trakt_client_id") or ""

def _secret():
    return ADDON.getSetting("trakt_client_secret") or ""

def _token():
    return ADDON.getSetting("trakt_token") or ""


def active():
    """True only if Trakt is fully set up and turned on."""
    return bool(_enabled() and _cid() and _token())


def _headers(auth=True):
    h = {"Content-Type": "application/json",
         "trakt-api-version": "2",
         "trakt-api-key": _cid()}
    if auth and _token():
        h["Authorization"] = "Bearer " + _token()
    return h


# ── device-code login ────────────────────────────────────────────────────────

def link_device():
    """Run the device-code flow. Returns the token string on success, else None."""
    if not _cid():
        xbmcgui.Dialog().ok("Trakt", "Enter your Trakt Client ID in settings first "
                            "(from trakt.tv/oauth/applications).")
        return None
    try:
        r = requests.post(API + "/oauth/device/code",
                          json={"client_id": _cid()},
                          headers={"Content-Type": "application/json"}, timeout=15)
        d = r.json()
    except Exception:
        xbmcgui.Dialog().ok("Trakt", "Couldn't reach Trakt. Try again later.")
        return None

    code = d.get("user_code", "")
    url = d.get("verification_url", "trakt.tv/activate")
    device_code = d.get("device_code", "")
    interval = d.get("interval", 5)
    expires = d.get("expires_in", 600)

    pd = xbmcgui.DialogProgress()
    pd.create("Link Trakt",
              "On your phone/PC go to:\n[B]%s[/B]\n\nEnter code:  [B]%s[/B]" % (url, code))

    waited = 0
    while waited < expires:
        if pd.iscanceled():
            pd.close(); return None
        xbmc.sleep(interval * 1000)
        waited += interval
        try:
            t = requests.post(API + "/oauth/device/token",
                              json={"code": device_code, "client_id": _cid(),
                                    "client_secret": _secret()},
                              headers={"Content-Type": "application/json"}, timeout=15)
            if t.status_code == 200:
                tok = t.json().get("access_token", "")
                pd.close()
                return tok
            # 400 = still pending, keep polling
        except Exception:
            pass
    pd.close()
    return None


# ── best-effort id lookup (tmdb id -> trakt scrobble payload) ────────────────

def _lookup(tmdb_id, media_type):
    try:
        r = requests.get(API + "/search/tmdb/%s?type=%s" % (tmdb_id,
                         "movie" if media_type == "movie" else "show"),
                         headers=_headers(), timeout=10)
        arr = r.json()
        if arr:
            key = "movie" if media_type == "movie" else "show"
            return arr[0].get(key, {}).get("ids", {})
    except Exception:
        pass
    return None


# ── mirror actions (all silent on failure) ──────────────────────────────────

def mark_watched(tmdb_id, media_type, season=None, episode=None):
    if not active():
        return
    try:
        ids = _lookup(tmdb_id, media_type)
        if not ids:
            return
        if media_type == "movie":
            body = {"movies": [{"ids": ids}]}
        elif season and episode:
            body = {"shows": [{"ids": ids, "seasons": [
                {"number": int(season), "episodes": [{"number": int(episode)}]}]}]}
        else:
            return
        requests.post(API + "/sync/history", json=body, headers=_headers(), timeout=10)
    except Exception:
        pass


def add_to_watchlist(tmdb_id, media_type):
    if not active():
        return
    try:
        ids = _lookup(tmdb_id, media_type)
        if not ids:
            return
        key = "movies" if media_type == "movie" else "shows"
        requests.post(API + "/sync/watchlist", json={key: [{"ids": ids}]},
                      headers=_headers(), timeout=10)
    except Exception:
        pass


def get_watchlist():
    """Pull Trakt watchlist (best-effort) -> list of {media_type, tmdb_id, title}."""
    if not active():
        return []
    out = []
    try:
        r = requests.get(API + "/sync/watchlist", headers=_headers(), timeout=15)
        for it in r.json():
            if "movie" in it:
                m = it["movie"]
                out.append({"media_type": "movie",
                            "tmdb_id": m.get("ids", {}).get("tmdb", ""),
                            "title": m.get("title", "")})
            elif "show" in it:
                sh = it["show"]
                out.append({"media_type": "tv",
                            "tmdb_id": sh.get("ids", {}).get("tmdb", ""),
                            "title": sh.get("title", "")})
    except Exception:
        pass
    return out
