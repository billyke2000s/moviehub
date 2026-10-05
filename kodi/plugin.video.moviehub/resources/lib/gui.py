"""
gui.py — setup, login and profile screens using Kodi's own dialogs only.

No custom WindowXML skins are used anywhere in Movie Hub. Every screen here is
a stock xbmcgui.Dialog (ok / input / select / yesno), so it always follows the
installed Kodi skin and cannot break when the skin changes.
"""

import xbmcgui
import xbmcaddon
import requests

from . import serverapi
from . import tmdb

ADDON = xbmcaddon.Addon()


def _notify(msg, err=False):
    xbmcgui.Dialog().notification("Movie Hub", msg,
                                  xbmcgui.NOTIFICATION_ERROR if err else xbmcgui.NOTIFICATION_INFO,
                                  4000)


# ─────────────────────────────────────────────────────────────────────────────
# PRIVATE SERVER CONNECTION
# ─────────────────────────────────────────────────────────────────────────────

def connection_setup(force=False):
    """Collect and verify the private Worker connection on first use."""
    current_url = (ADDON.getSetting("server_url") or "").rstrip("/")
    current_secret = ADDON.getSetting("app_secret") or ""
    needs_setup = (not current_url or "yourname.workers.dev" in current_url or
                   not current_secret)
    if not force and not needs_setup:
        return True
    dialog = xbmcgui.Dialog()
    dialog.ok(
        "Connect your private Movie Hub",
        "Enter your private server URL and secret code shown when your server was "
        "deployed. You can paste using the official Kodi remote app if typing on a "
        "television is awkward.")
    if "yourname.workers.dev" in current_url:
        current_url = ""
    url = dialog.input("Private server URL", defaultt=current_url).strip().rstrip("/")
    if not url:
        return False
    if not url.startswith("https://") and not url.startswith("http://localhost"):
        dialog.ok("Secure connection required", "Use the full HTTPS address supplied by the deployer.")
        return False
    secret = dialog.input(
        "Secret code", defaultt=current_secret,
        type=xbmcgui.INPUT_ALPHANUM, option=xbmcgui.ALPHANUM_HIDE_INPUT).strip()
    if not secret:
        return False
    try:
        health = requests.get(url + "/health", timeout=10)
        auth_probe = requests.get(url + "/me", headers={"X-App-Key": secret}, timeout=10)
        # /me with a correct secret but no login token must answer 401.
        if health.status_code != 200 or auth_probe.status_code != 401:
            raise ValueError("Those connection details were not accepted.")
    except Exception as exc:
        dialog.ok("Connection unsuccessful", str(exc) + "\n\nCheck both values and try again.")
        return False
    ADDON.setSetting("server_url", url)
    ADDON.setSetting("app_secret", secret)
    _notify("Private server connected")
    return True


# ─────────────────────────────────────────────────────────────────────────────
# LOGIN (native dialogs)
# ─────────────────────────────────────────────────────────────────────────────

def show_login():
    """Returns the login result dict, or None if cancelled."""
    dialog = xbmcgui.Dialog()
    while True:
        choice = dialog.select("Movie Hub — account", ["Sign in", "Create account"])
        if choice < 0:
            return None
        user = dialog.input("Username").strip()
        if not user:
            continue
        pw = dialog.input("Password", type=xbmcgui.INPUT_ALPHANUM,
                          option=xbmcgui.ALPHANUM_HIDE_INPUT)
        if not pw:
            continue
        try:
            if choice == 0:
                return serverapi.login(user, pw)
            return serverapi.register(user, pw)
        except serverapi.ServerError as e:
            dialog.ok("Movie Hub", str(e))


# ─────────────────────────────────────────────────────────────────────────────
# PROFILE PICKER (native select dialog)
# ─────────────────────────────────────────────────────────────────────────────

def show_profiles(account):
    """Returns ('profile', (id, name)) | ('logout', None) | (None, None)."""
    dialog = xbmcgui.Dialog()
    while True:
        profiles = account.get("profiles", [])
        items = []
        for p in profiles:
            li = xbmcgui.ListItem(p["name"])
            icon = serverapi.avatar_url(p.get("avatar", "")) or "DefaultUser.png"
            li.setArt({"icon": icon, "thumb": icon})
            items.append(li)
        extras = ["+ New profile", "Manage profiles", "Sign out"]
        for label in extras:
            items.append(xbmcgui.ListItem(label))
        idx = dialog.select("Who's watching?", items, useDetails=True)
        if idx < 0:
            return (None, None)
        if idx < len(profiles):
            p = profiles[idx]
            return ("profile", (str(p["id"]), p["name"]))
        extra = extras[idx - len(profiles)]
        if extra == "Sign out":
            return ("logout", None)
        if extra == "+ New profile":
            account = _new_profile(account)
        elif extra == "Manage profiles":
            account = _manage(account)


def _new_profile(account):
    name = xbmcgui.Dialog().input("New profile name").strip()
    if not name:
        return account
    try:
        return serverapi.add_profile(name)["account"]
    except serverapi.ServerError as e:
        _notify(str(e), err=True)
        return account


def _manage(account):
    profs = account.get("profiles", [])
    if not profs:
        _notify("No profiles yet.")
        return account
    dialog = xbmcgui.Dialog()
    idx = dialog.select("Manage — pick a profile", [p["name"] for p in profs])
    if idx < 0:
        return account
    p = profs[idx]
    choice = dialog.select("Profile: %s" % p["name"], ["Set picture", "Delete profile"])
    try:
        if choice == 0:
            path = dialog.browse(2, "Choose a picture", "files", ".png|.jpg|.jpeg|.gif|.webp")
            if path:
                account = serverapi.set_avatar(p["id"], path)["account"]
                _notify("Picture updated.")
        elif choice == 1:
            if dialog.yesno("Delete profile", "Delete '%s' and everything it saved?" % p["name"]):
                account = serverapi.delete_profile(p["id"])["account"]
    except serverapi.ServerError as e:
        _notify(str(e), err=True)
    return account


# ─────────────────────────────────────────────────────────────────────────────
# KEYS
# ─────────────────────────────────────────────────────────────────────────────

def first_login_keys():
    """Collect the discovery key; debrid is selected after profile creation."""
    dlg = xbmcgui.Dialog()
    dlg.ok("Movie Hub — one-time setup",
           "Enter your TMDb API key for film and television discovery. It is "
           "encrypted on your private server and follows your account.")
    tmdb_key = dlg.input(
        "TMDb API key", type=xbmcgui.INPUT_ALPHANUM,
        option=xbmcgui.ALPHANUM_HIDE_INPUT).strip()
    if not tmdb_key:
        return None
    if not tmdb.validate_key(tmdb_key):
        if not dlg.yesno("TMDb key", "That key didn't validate. Use it anyway?"):
            return None
    try:
        res = serverapi.save_keys(tmdb_key, "")
    except serverapi.ServerError as e:
        _notify(str(e), err=True)
        return None
    return res["account"]


def debrid_setup(profile_id):
    """Let a profile use any supported debrid provider; none is privileged."""
    dlg = xbmcgui.Dialog()
    choices = ["Premiumize", "Real-Debrid", "AllDebrid"]
    selected = dlg.select("Choose your playback service", choices)
    if selected < 0:
        return False
    setting_keys = ["premiumize_key", "realdebrid_key", "alldebrid_key"]
    vault_keys = ["premiumize", "realdebrid", "alldebrid"]
    key = dlg.input(
        "%s API key" % choices[selected],
        type=xbmcgui.INPUT_ALPHANUM,
        option=xbmcgui.ALPHANUM_HIDE_INPUT).strip()
    if not key:
        return False
    try:
        serverapi.set_vault(profile_id, {vault_keys[selected]: key})
    except serverapi.ServerError as exc:
        _notify(str(exc), err=True)
        return False
    ADDON.setSetting(setting_keys[selected], key)
    _notify("%s saved." % choices[selected])
    return True
