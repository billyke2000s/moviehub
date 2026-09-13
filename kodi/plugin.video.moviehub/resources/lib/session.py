"""
session.py — holds the current login/profile state.

The auth token, active profile, and cached keys live in the add-on's settings
so they persist between launches. This module is the single place that reads and
writes them, and drives the login -> profile-pick -> keys flow.
"""

import xbmcaddon

from . import serverapi
from . import gui

ADDON = xbmcaddon.Addon()


def _set(key, val):
    ADDON.setSetting(key, val or "")

def _get(key):
    return ADDON.getSetting(key) or ""


def is_logged_in():
    return bool(_get("auth_token"))

def active_profile_id():
    return _get("active_profile_id")


def _apply_keys(account):
    keys = account.get("keys", {})
    _set("tmdb_key", keys.get("tmdb", ""))
    _set("premiumize_key", keys.get("premiumize", ""))


def logout():
    serverapi.logout()
    for k in ("auth_token", "active_profile_id", "tmdb_key", "premiumize_key",
              "realdebrid_key", "alldebrid_key", "trakt_token",
              "opensubtitles_login"):
        _set(k, "")



def _load_trakt_for_profile(pid):
    """Pull this profile's Trakt token from the server into settings (best-effort)."""
    try:
        tok = serverapi.get_trakt_token(pid)
    except Exception:
        tok = ""
    ADDON.setSetting("trakt_token", tok or "")
    ADDON.setSetting("trakt_enabled", "true" if tok else ADDON.getSetting("trakt_enabled"))
    _load_vault_and_prefs(pid)


def _load_vault_and_prefs(pid):
    """Pull all encrypted credentials + synced prefs from the server into local
    settings, so this device has everything for the active profile."""
    # vault -> credential settings
    vault_map = {
        "premiumize": "premiumize_key",
        "realdebrid": "realdebrid_key",
        "alldebrid": "alldebrid_key",
        "opensubtitles": "opensubtitles_login",
    }
    # Never allow one profile's credentials to remain active after switching
    # to another profile that has no value stored for that provider.
    for setting in vault_map.values():
        ADDON.setSetting(setting, "")
    try:
        vault = serverapi.get_vault(pid)
        for vkey, setting in vault_map.items():
            if vkey in vault:
                ADDON.setSetting(setting, vault.get(vkey, ""))
    except Exception:
        pass
    # prefs -> pref settings
    pref_keys = ["autoplay_next", "subtitles_on", "subtitle_lang", "sort_pref", "cached_only",
                 "experience_mode", "preview_mode", "reduced_motion"]
    try:
        prefs = serverapi.get_prefs(pid)
        for pk in pref_keys:
            if pk in prefs:
                ADDON.setSetting(pk, prefs.get(pk, ""))
    except Exception:
        pass


def push_vault(pid, items):
    """Save credentials to the server (encrypted) and mirror to local settings."""
    try:
        serverapi.set_vault(pid, items)
    except Exception:
        pass

def push_prefs(pid, items):
    try:
        serverapi.set_prefs(pid, items)
    except Exception:
        pass


def ensure_ready():
    """
    Guarantee we have: a valid token, keys, and a chosen profile.
    Returns True if ready to browse, False if the user backed out.
    Called at the top of the add-on before showing content.
    """
    # 0) server connected? New installations get a guided connection check
    # before seeing a login form that could never succeed.
    if not gui.connection_setup():
        return False

    # 1) logged in?
    if not is_logged_in():
        res = gui.show_login()
        if not res:
            return False
        _set("auth_token", res["token"])
        account = res["account"]
    else:
        # verify the token still works; refresh account
        try:
            account = serverapi.me()["account"]
        except serverapi.ServerError:
            # token dead — clear and re-login
            _set("auth_token", "")
            return ensure_ready()

    # 2) discovery key?
    if not account.get("has_keys"):
        updated = gui.first_login_keys()
        if not updated:
            return False
        account = updated
    _apply_keys(account)

    # 3) profile chosen?
    profiles = account.get("profiles", [])
    if not profiles:
        # force-create at least one
        updated = _force_first_profile(account)
        if not updated:
            return False
        account = updated
        profiles = account.get("profiles", [])

    if not active_profile_id() or active_profile_id() not in [str(p["id"]) for p in profiles]:
        kind, payload = gui.show_profiles(account)
        if kind == "logout":
            logout()
            return ensure_ready()
        if kind != "profile":
            return False
        _set("active_profile_id", payload[0])
        _load_trakt_for_profile(payload[0])

    # Always refresh profile-scoped credentials, then require exactly one of
    # the supported playback services.
    pid = active_profile_id()
    _load_vault_and_prefs(pid)
    from . import debrid
    if not debrid.available_services() and not gui.debrid_setup(pid):
        return False

    return True


def _force_first_profile(account):
    import xbmcgui
    name = xbmcgui.Dialog().input("Create your first profile — name")
    if not name:
        return None
    try:
        return serverapi.add_profile(name)["account"]
    except serverapi.ServerError as e:
        xbmcgui.Dialog().notification("Movie Hub", str(e), xbmcgui.NOTIFICATION_ERROR)
        return None


def switch_profile():
    """Re-show the profile picker (menu action)."""
    try:
        account = serverapi.me()["account"]
    except serverapi.ServerError:
        return
    kind, payload = gui.show_profiles(account)
    if kind == "profile":
        _set("active_profile_id", payload[0])
        _load_trakt_for_profile(payload[0])
    elif kind == "logout":
        logout()
