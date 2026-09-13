"""
serverapi.py — the ONLY thing that talks to your VPS.

It handles accounts, profiles, keys storage, and watch progress. It deliberately
never touches TMDb, Torrentio, Premiumize, or any stream — all of that is done
locally on the device (see tmdb.py and streams.py) so no bandwidth-heavy traffic
ever routes through your server.

Reads the server URL and app-secret from the add-on's settings (the one place),
so nothing here is hardcoded per-install.
"""

import json
import requests

import xbmcaddon

ADDON = xbmcaddon.Addon()


def _base():
    return (ADDON.getSetting("server_url") or "").rstrip("/")

def _headers(with_auth=True):
    h = {"X-App-Key": (ADDON.getSetting("app_secret") or "").strip(),
         "Content-Type": "application/json"}
    if with_auth:
        tok = ADDON.getSetting("auth_token") or ""
        if tok:
            h["Authorization"] = "Bearer " + tok
    return h


class ServerError(Exception):
    """Carries a human-readable message already suitable for a dialog."""
    pass


def _request(method, path, body=None, with_auth=True, raw_body=None,
             content_type=None, timeout=20):
    url = _base() + path
    if not _base():
        raise ServerError("No server address set. Open the add-on settings and "
                          "enter your Server URL.")
    try:
        if raw_body is not None:
            # raw binary body (image upload) — the Worker reads arrayBuffer()
            h = {"X-App-Key": (ADDON.getSetting("app_secret") or "").strip(),
                 "Content-Type": content_type or "application/octet-stream"}
            tok = ADDON.getSetting("auth_token") or ""
            if with_auth and tok:
                h["Authorization"] = "Bearer " + tok
            r = requests.request(method, url, headers=h, data=raw_body, timeout=timeout)
        else:
            data = json.dumps(body) if body is not None else None
            r = requests.request(method, url, headers=_headers(with_auth),
                                 data=data, timeout=timeout)
    except requests.exceptions.SSLError:
        raise ServerError("Couldn't establish a secure connection to the server. "
                          "Check the Server URL uses https:// and the certificate "
                          "is set up.")
    except requests.exceptions.ConnectionError:
        raise ServerError("Couldn't reach the server. Check it's online and the "
                          "Server URL is correct.")
    except requests.exceptions.Timeout:
        raise ServerError("The server took too long to respond. Try again shortly.")

    # Parse the friendly error envelope the server sends.
    try:
        payload = r.json()
    except Exception:
        raise ServerError("The server sent a response the add-on couldn't read.")

    if r.status_code >= 400:
        msg = payload.get("error") if isinstance(payload, dict) else None
        if r.status_code == 401:
            raise ServerError(msg or "Your session expired — please log in again.")
        if r.status_code == 403:
            raise ServerError(msg or "Those connection details were not accepted.")
        if r.status_code == 429:
            raise ServerError(msg or "Too many attempts — wait a minute and try again.")
        raise ServerError(msg or ("Server error (%s)." % r.status_code))

    return payload


# ── auth ─────────────────────────────────────────────────────────────────────

def register(username, password):
    return _request("POST", "/register", {"username": username, "password": password},
                    with_auth=False)

def login(username, password):
    return _request("POST", "/login", {"username": username, "password": password},
                    with_auth=False)

def logout():
    try:
        _request("POST", "/logout")
    except ServerError:
        pass

def me():
    return _request("GET", "/me")


# ── keys ─────────────────────────────────────────────────────────────────────

def save_keys(tmdb, premiumize):
    return _request("POST", "/keys", {"tmdb": tmdb, "premiumize": premiumize})


# ── profiles ─────────────────────────────────────────────────────────────────

def add_profile(name):
    return _request("POST", "/profiles", {"name": name})

def delete_profile(profile_id):
    return _request("DELETE", "/profiles/%s" % profile_id)

def set_avatar(profile_id, image_path):
    """
    Re-encode the chosen image ON THE DEVICE before upload. This strips ALL
    metadata (GPS/device/timestamps) and any payload hidden inside the file —
    only the decoded pixels survive — then sends the clean bytes as the raw
    request body. The Worker double-checks it's a real image and stores it.
    Kodi ships Python + Pillow, so this runs everywhere the add-on does.
    """
    raw = _reencode_image(image_path)
    return _request("POST", "/profiles/%s/avatar" % profile_id,
                    raw_body=raw, content_type="application/octet-stream")


def _reencode_image(image_path):
    try:
        from PIL import Image
    except Exception:
        # Pillow missing (rare) — fall back to sending the original bytes; the
        # Worker still verifies it's a real image by magic bytes.
        with open(image_path, "rb") as fh:
            return fh.read()
    import io
    img = Image.open(image_path)
    img = img.convert("RGB")
    img.thumbnail((512, 512))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88, optimize=True)  # fresh file, no metadata
    return buf.getvalue()

def avatar_url(fname):
    if not fname:
        return ""
    return _base() + "/avatar/" + fname


# ── progress ─────────────────────────────────────────────────────────────────

def get_progress(profile_id):
    return _request("GET", "/progress/%s" % profile_id).get("progress", {})

def save_progress(profile_id, media_id, title, position, duration, completed):
    return _request("POST", "/progress", {
        "profile_id": int(profile_id),
        "media_id": media_id,
        "title": title,
        "position": int(position),
        "duration": int(duration),
        "completed": bool(completed),
    })


# ── watchlist ────────────────────────────────────────────────────────────────

def get_watchlist(profile_id):
    return _request("GET", "/watchlist/%s" % profile_id).get("watchlist", [])

def add_watchlist(profile_id, media_id, media_type, tmdb_id, title, poster):
    return _request("POST", "/watchlist", {
        "profile_id": int(profile_id), "media_id": media_id,
        "media_type": media_type, "tmdb_id": str(tmdb_id),
        "title": title, "poster": poster,
    })

def remove_watchlist(profile_id, media_id):
    return _request("DELETE", "/watchlist", {
        "profile_id": int(profile_id), "media_id": media_id,
    })


# ── trakt token (stored per profile on the server, syncs across devices) ─────

def save_trakt_token(profile_id, token):
    return _request("POST", "/trakt-token", {
        "profile_id": int(profile_id), "token": token,
    })

def get_trakt_token(profile_id):
    return _request("GET", "/trakt-token/%s" % profile_id).get("token", "")


# ── encrypted credential vault + synced prefs ────────────────────────────────

def get_vault(profile_id):
    return _request("GET", "/vault/%s" % profile_id).get("vault", {})

def set_vault(profile_id, items):
    return _request("POST", "/vault", {"profile_id": int(profile_id), "items": items})

def get_prefs(profile_id):
    return _request("GET", "/prefs/%s" % profile_id).get("prefs", {})

def set_prefs(profile_id, items):
    return _request("POST", "/prefs", {"profile_id": int(profile_id), "items": items})


# ── notifications state (per profile, server-held so all boxes agree) ────────

def get_notif_state(profile_id):
    return _request("GET", "/notif-state/%s" % profile_id)

def set_notif_check(profile_id, ts):
    return _request("POST", "/notif-check", {"profile_id": int(profile_id), "ts": int(ts)})

def dismiss_notif(profile_id, notif_id):
    return _request("POST", "/notif-dismiss", {"profile_id": int(profile_id), "notif_id": notif_id})
