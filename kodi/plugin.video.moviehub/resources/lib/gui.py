"""
gui.py — the custom Movie Hub dialogs (login + profile picker).

These are the two "signature" screens where matching the app's look matters.
Everything else (browsing) uses Kodi's native lists for reliability on a TV
remote. Uses WindowXMLDialog, driven by the skin XML in
resources/skins/default/1080i/.
"""

import os

import xbmc
import xbmcgui
import xbmcaddon
import requests

from . import serverapi
from . import tmdb

ADDON = xbmcaddon.Addon()
ADDON_PATH = ADDON.getAddonInfo("path")


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
        "deployed. Nothing is shared with another Movie Hub owner. You can paste "
        "using the official Kodi remote app if typing on a television is awkward.")
    url = dialog.input("Enter your private server URL", defaultt=current_url).strip().rstrip("/")
    if not url:
        return False
    if not url.startswith("https://") and not url.startswith("http://localhost"):
        dialog.ok("Secure connection required", "Use the full HTTPS address supplied by the deployer.")
        return False
    secret = dialog.input(
        "Enter your secret code", defaultt=current_secret,
        type=xbmcgui.INPUT_ALPHANUM, option=xbmcgui.ALPHANUM_HIDE_INPUT).strip()
    if not secret:
        return False
    try:
        health = requests.get(url + "/health", timeout=10)
        auth_probe = requests.get(url + "/me", headers={"X-App-Key": secret}, timeout=10)
        if health.status_code != 200 or auth_probe.status_code != 401:
            raise ValueError("Those connection details were not accepted.")
    except Exception as exc:
        dialog.ok("Connection unsuccessful", str(exc) + "\n\nCheck both values and try again.")
        return False
    ADDON.setSetting("server_url", url)
    ADDON.setSetting("app_secret", secret)
    dialog.notification("Movie Hub", "Private server connected", xbmcgui.NOTIFICATION_INFO, 3000)
    return True


def _notify(msg, err=False):
    xbmcgui.Dialog().notification("Movie Hub", msg,
                                  xbmcgui.NOTIFICATION_ERROR if err else xbmcgui.NOTIFICATION_INFO,
                                  4000)


# ─────────────────────────────────────────────────────────────────────────────
# LOGIN DIALOG
# ─────────────────────────────────────────────────────────────────────────────

class LoginDialog(xbmcgui.WindowXMLDialog):
    ID_USER = 100
    ID_PASS = 101
    ID_LOGIN = 102
    ID_CREATE = 103

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.result = None   # dict with token+account on success

    def onInit(self):
        self.setFocusId(self.ID_USER)

    def onClick(self, control_id):
        if control_id == self.ID_LOGIN:
            self._do(login=True)
        elif control_id == self.ID_CREATE:
            self._do(login=False)

    def _do(self, login):
        user = self.getControl(self.ID_USER).getText().strip()
        pw = self.getControl(self.ID_PASS).getText()
        if not user or not pw:
            _notify("Enter a username and password.", err=True)
            return
        try:
            if login:
                res = serverapi.login(user, pw)
            else:
                res = serverapi.register(user, pw)
        except serverapi.ServerError as e:
            _notify(str(e), err=True)
            return
        self.result = res
        self.close()

    def onAction(self, action):
        if action.getId() in (xbmcgui.ACTION_NAV_BACK, xbmcgui.ACTION_PREVIOUS_MENU):
            self.result = None
            self.close()


# ─────────────────────────────────────────────────────────────────────────────
# PROFILE PICKER DIALOG
# ─────────────────────────────────────────────────────────────────────────────

class ProfileDialog(xbmcgui.WindowXMLDialog):
    ID_LIST = 50
    ID_MANAGE = 60
    ID_LOGOUT = 61

    def __init__(self, *args, **kwargs):
        self.account = kwargs.pop("account")
        super().__init__(*args)
        self.chosen = None     # (id, name) chosen profile
        self.action = None     # 'logout' | 'manage' | None

    def onInit(self):
        self._populate()
        self.setFocusId(self.ID_LIST)

    def _populate(self):
        lst = self.getControl(self.ID_LIST)
        lst.reset()
        for p in self.account.get("profiles", []):
            li = xbmcgui.ListItem(p["name"])
            icon = serverapi.avatar_url(p.get("avatar", "")) or ""
            if icon:
                li.setArt({"icon": icon, "thumb": icon})
            li.setProperty("profile_id", str(p["id"]))
            lst.addItem(li)
        # add a "+ New profile" tile
        add = xbmcgui.ListItem("+ New profile")
        add.setProperty("profile_id", "__new__")
        lst.addItem(add)

    def onClick(self, control_id):
        if control_id == self.ID_LIST:
            item = self.getControl(self.ID_LIST).getSelectedItem()
            if not item:
                return
            pid = item.getProperty("profile_id")
            if pid == "__new__":
                self._new_profile()
            else:
                self.chosen = (pid, item.getLabel())
                self.close()
        elif control_id == self.ID_LOGOUT:
            self.action = "logout"
            self.close()
        elif control_id == self.ID_MANAGE:
            self._manage()

    def _new_profile(self):
        name = xbmcgui.Dialog().input("New profile name")
        if not name:
            return
        try:
            res = serverapi.add_profile(name)
        except serverapi.ServerError as e:
            _notify(str(e), err=True)
            return
        self.account = res["account"]
        self._populate()

    def _manage(self):
        profs = self.account.get("profiles", [])
        if not profs:
            _notify("No profiles yet.")
            return
        names = [p["name"] for p in profs]
        idx = xbmcgui.Dialog().select("Manage — pick a profile", names)
        if idx < 0:
            return
        p = profs[idx]
        choice = xbmcgui.Dialog().select("Profile: %s" % p["name"],
                                         ["Set picture", "Delete profile", "Cancel"])
        if choice == 0:
            self._set_avatar(p)
        elif choice == 1:
            if xbmcgui.Dialog().yesno("Delete profile", "Delete '%s'?" % p["name"]):
                try:
                    res = serverapi.delete_profile(p["id"])
                    self.account = res["account"]
                    self._populate()
                except serverapi.ServerError as e:
                    _notify(str(e), err=True)

    def _set_avatar(self, p):
        path = xbmcgui.Dialog().browse(2, "Choose a picture", "files",
                                       ".png|.jpg|.jpeg|.gif|.webp")
        if not path:
            return
        try:
            res = serverapi.set_avatar(p["id"], path)
            self.account = res["account"]
            self._populate()
            _notify("Picture updated.")
        except serverapi.ServerError as e:
            _notify(str(e), err=True)

    def onAction(self, action):
        if action.getId() in (xbmcgui.ACTION_NAV_BACK, xbmcgui.ACTION_PREVIOUS_MENU):
            self.close()


# ─────────────────────────────────────────────────────────────────────────────
# FLOW HELPERS
# ─────────────────────────────────────────────────────────────────────────────

def show_login():
    """Returns the login result dict, or None if cancelled."""
    dlg = LoginDialog("login.xml", ADDON_PATH, "default")
    dlg.doModal()
    res = dlg.result
    del dlg
    return res


def show_profiles(account):
    """Returns ('profile', (id,name)) | ('logout', None) | (None, None)."""
    dlg = ProfileDialog("profiles.xml", ADDON_PATH, "default", account=account)
    dlg.doModal()
    chosen, act = dlg.chosen, dlg.action
    del dlg
    if chosen:
        return ("profile", chosen)
    if act == "logout":
        return ("logout", None)
    return (None, None)


def first_login_keys():
    """Collect the discovery key; debrid is selected after profile creation."""
    dlg = xbmcgui.Dialog()
    dlg.ok("Movie Hub — one-time setup",
           "Enter your TMDb API key for film and television discovery. It is "
           "encrypted on your private server and follows your account.")
    tmdb_key = dlg.input(
        "Enter your TMDb API key", type=xbmcgui.INPUT_ALPHANUM,
        option=xbmcgui.ALPHANUM_HIDE_INPUT)
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
    selected = dlg.select(
        "Choose your playback service",
        choices,
        preselect=0,
        useDetails=False)
    if selected < 0:
        return False
    setting_keys = ["premiumize_key", "realdebrid_key", "alldebrid_key"]
    vault_keys = ["premiumize", "realdebrid", "alldebrid"]
    key = dlg.input(
        "Enter your %s API key" % choices[selected],
        type=xbmcgui.INPUT_ALPHANUM,
        option=xbmcgui.ALPHANUM_HIDE_INPUT).strip()
    if not key:
        return False
    ADDON.setSetting(setting_keys[selected], key)
    try:
        serverapi.set_vault(profile_id, {vault_keys[selected]: key})
    except serverapi.ServerError as exc:
        ADDON.setSetting(setting_keys[selected], "")
        _notify(str(exc), err=True)
        return False
    return True


def experience_setup(force=False):
    """Explain and select an interface without treating native Kodi as lesser.

    Returns False only when a first-time user backs out before making a choice.
    Existing users keep their current selection if they cancel a later rerun.
    """
    from . import session
    if not force and ADDON.getSettingBool("setup_complete"):
        return True
    dialog = xbmcgui.Dialog()
    dialog.ok(
        "Choose how Movie Hub feels",
        "Cinematic is Movie Hub's full-screen streaming experience with rich "
        "artwork and custom navigation.\n\nNative Kodi uses Kodi's own media views, "
        "information panels and context menus. It follows your installed skin, "
        "uses fewer resources and is ideal for older hardware.\n\nBoth modes include "
        "the same library, accounts, search and playback features.")
    mode = dialog.select(
        "Interface mode",
        ["Cinematic  —  immersive custom interface",
         "Native Kodi  —  efficient and skin-aware"],
        preselect=0 if ADDON.getSetting("experience_mode") != "native" else 1)
    if mode < 0:
        return bool(ADDON.getSetting("experience_mode"))
    ADDON.setSetting("experience_mode", "cinematic" if mode == 0 else "native")

    dialog.ok(
        "Choose trailer behaviour",
        "Manual loads a trailer only when you select Trailer. Automatic begins "
        "a preview after you pause on a title and needs stronger hardware. "
        "Off prevents Movie Hub from loading trailers at all.")
    preview = dialog.select(
        "Trailer previews",
        ["Manual  —  recommended for most devices",
         "Automatic  —  cinematic, higher resource use",
         "Off  —  never load trailers"], preselect=0)
    if preview < 0:
        preview = 0
    ADDON.setSetting("preview_mode", ("manual", "automatic", "off")[preview])
    reduced = dialog.yesno(
        "Motion and performance",
        "Reduce fades, artwork transitions and other visual effects?\n\nChoose Yes "
        "for older Fire Sticks, Raspberry Pi models or if animation feels sluggish.",
        yeslabel="REDUCE EFFECTS", nolabel="FULL EFFECTS")
    ADDON.setSettingBool("reduced_motion", reduced)
    ADDON.setSettingBool("setup_complete", True)
    try:
        session.push_prefs(session.active_profile_id(), {
            "experience_mode": ADDON.getSetting("experience_mode"),
            "preview_mode": ADDON.getSetting("preview_mode"),
            "reduced_motion": "true" if reduced else "false",
        })
    except Exception:
        pass
    dialog.notification("Movie Hub", "Your experience is ready.", xbmcgui.NOTIFICATION_INFO, 3000)
    return True
