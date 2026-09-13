"""Movie Hub's custom, remote-first cinema interface.

The window deliberately uses a small number of controls and two shelves. That
keeps navigation predictable on televisions and memory use reasonable on a
Fire Stick, while the backdrop-led composition still feels bespoke.
"""

import os
import sys
import threading
import urllib.parse

import xbmc
import xbmcaddon
import xbmcgui

from . import serverapi, session, tmdb


ADDON = xbmcaddon.Addon()
ADDON_PATH = ADDON.getAddonInfo("path")
BASE = sys.argv[0] if sys.argv else "plugin://plugin.video.moviehub/"


def _url(**parts):
    return BASE + "?" + urllib.parse.urlencode(parts)


def _notice(message, error=False):
    xbmcgui.Dialog().notification(
        "Movie Hub", message,
        xbmcgui.NOTIFICATION_ERROR if error else xbmcgui.NOTIFICATION_INFO,
        3500)


def _title(item):
    return item.get("title") or item.get("name") or "Untitled"


def _year(item):
    date = item.get("release_date") or item.get("first_air_date") or ""
    return date[:4]


def _kind(item, fallback="movie"):
    value = item.get("media_type") or fallback
    return value if value in ("movie", "tv") else fallback


def _poster(item):
    path = item.get("poster_path") or ""
    if path.startswith("http"):
        return path
    return tmdb.POSTER + path if path else ""


def _backdrop(item):
    path = item.get("backdrop_path") or ""
    if path.startswith("http"):
        return path
    return tmdb.BACKDROP + path if path else ""


def _as_list_item(item, fallback="movie"):
    li = xbmcgui.ListItem(_title(item))
    media_type = _kind(item, fallback)
    li.setArt({"poster": _poster(item), "thumb": _poster(item),
               "fanart": _backdrop(item)})
    li.setProperty("moviehub.id", str(item.get("id") or item.get("tmdb_id") or ""))
    li.setProperty("moviehub.type", media_type)
    li.setProperty("moviehub.title", _title(item))
    li.setProperty("moviehub.plot", item.get("overview") or "")
    li.setProperty("moviehub.year", _year(item))
    rating = float(item.get("vote_average") or 0)
    li.setProperty("moviehub.rating", ("%.1f" % rating) if rating else "")
    li.setProperty("moviehub.meta", "  •  ".join(
        p for p in (_year(item), ("%.1f / 10" % rating) if rating else "",
                    "FILM" if media_type == "movie" else "SERIES") if p))
    return li


class HomeDialog(xbmcgui.WindowXMLDialog):
    NAV = {100: "home", 101: "movies", 102: "tv", 103: "search",
           104: "watchlist", 105: "account"}
    SHELVES = (300, 301)

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.section = "home"
        self.rows = {300: [], 301: []}
        self._last_item = None
        self._current_item = None

    def onInit(self):
        self._load("home")
        self.setFocusId(300)

    def _fetch(self, section):
        if section == "home":
            return ("Trending now", tmdb.trending("movie").get("results", []), "movie",
                    "Acclaimed television", tmdb.top_rated("tv").get("results", []), "tv")
        if section == "movies":
            return ("Now playing", tmdb.now_playing().get("results", []), "movie",
                    "Great films, chosen by viewers", tmdb.top_rated("movie").get("results", []), "movie")
        if section == "tv":
            return ("On television", tmdb.on_the_air().get("results", []), "tv",
                    "Most watched series", tmdb.popular("tv").get("results", []), "tv")
        if section == "search":
            query = xbmcgui.Dialog().input("Search films and television").strip()
            if not query:
                return None
            found = tmdb.search_multi(query).get("results", [])
            return ("Results for “%s”" % query, found, "movie", "", [], "movie")
        if section == "watchlist":
            pid = session.active_profile_id()
            saved = serverapi.get_watchlist(pid) if pid else []
            normalised = []
            for entry in saved:
                normalised.append({
                    "id": entry.get("tmdb_id"), "title": entry.get("title"),
                    "media_type": entry.get("media_type", "movie"),
                    "poster_path": entry.get("poster", "")})
            return ("My List", normalised, "movie", "", [], "movie")
        return None

    def _load(self, section):
        if section == "account":
            self.close()
            xbmc.executebuiltin("Container.Update(%s)" % _url(action="legacy_root"))
            return
        try:
            payload = self._fetch(section)
        except Exception as exc:
            _notice("This section could not be loaded: %s" % exc, True)
            return
        if payload is None:
            return
        self.section = section
        first_title, first, first_type, second_title, second, second_type = payload
        self.getControl(30).setLabel(first_title.upper())
        self.getControl(31).setLabel(second_title.upper())
        self.getControl(31).setVisible(bool(second))
        self.getControl(301).setVisible(bool(second))
        self._fill(300, first, first_type)
        self._fill(301, second, second_type)
        if first:
            self._show_item(self.getControl(300).getListItem(0))
        else:
            self._empty(section)
        self.setFocusId(300)

    def _fill(self, control_id, items, fallback):
        control = self.getControl(control_id)
        control.reset()
        self.rows[control_id] = items
        for item in items[:20]:
            control.addItem(_as_list_item(item, fallback))

    def _empty(self, section):
        self.getControl(20).setLabel("Nothing here yet")
        message = ("Titles you save will appear here." if section == "watchlist"
                   else "Try another search.")
        self.getControl(21).setLabel(message)
        self.getControl(22).setLabel("")
        self.getControl(10).setImage("")

    def _show_item(self, item):
        if not item:
            return
        key = (item.getProperty("moviehub.type"), item.getProperty("moviehub.id"))
        if key == self._last_item:
            return
        self._last_item = key
        self._current_item = item
        self.getControl(10).setImage(item.getArt("fanart"))
        self.getControl(20).setLabel(item.getProperty("moviehub.title"))
        self.getControl(21).setLabel(item.getProperty("moviehub.plot"))
        self.getControl(22).setLabel(item.getProperty("moviehub.meta"))

    def onFocus(self, control_id):
        if control_id in self.SHELVES:
            self._show_item(self.getControl(control_id).getSelectedItem())

    def onClick(self, control_id):
        if control_id in self.NAV:
            self._load(self.NAV[control_id])
            return
        if control_id in self.SHELVES:
            item = self.getControl(control_id).getSelectedItem()
            if item and item.getProperty("moviehub.id"):
                show_details(item.getProperty("moviehub.type"),
                             item.getProperty("moviehub.id"),
                             item.getProperty("moviehub.title"))
                self._show_item(item)
        elif control_id == 200 and self._current_item:
            show_details(self._current_item.getProperty("moviehub.type"),
                         self._current_item.getProperty("moviehub.id"),
                         self._current_item.getProperty("moviehub.title"))

    def onAction(self, action):
        if action.getId() in (xbmcgui.ACTION_NAV_BACK, xbmcgui.ACTION_PREVIOUS_MENU):
            self.close()


class DetailsDialog(xbmcgui.WindowXMLDialog):
    def __init__(self, *args, **kwargs):
        self.media_type = kwargs.pop("media_type")
        self.tmdb_id = kwargs.pop("tmdb_id")
        self.fallback_title = kwargs.pop("title", "")
        super().__init__(*args)
        self.details = {}
        self.saved = False
        self._preview_timer = None
        self._preview_started = False

    def onInit(self):
        try:
            self.details = tmdb.details(self.media_type, self.tmdb_id)
        except Exception as exc:
            _notice("Title details could not be loaded: %s" % exc, True)
            self.close()
            return
        d = self.details
        self.getControl(10).setImage(_backdrop(d))
        self.getControl(11).setImage(_poster(d))
        self.getControl(20).setLabel(_title(d) or self.fallback_title)
        self.getControl(21).setLabel(self._metadata(d))
        self.getControl(22).setLabel(d.get("tagline") or "")
        self.getControl(23).setText(d.get("overview") or "No synopsis is available.")
        self.getControl(100).setLabel("PLAY" if self.media_type == "movie" else "EPISODES")
        self.saved = self._is_saved()
        self._set_list_label()
        trailer = self._trailer_key()
        preview_mode = ADDON.getSetting("preview_mode") or "manual"
        self.getControl(102).setVisible(bool(trailer) and preview_mode != "off")
        if trailer and preview_mode == "automatic":
            self._preview_timer = threading.Timer(5.0, self._start_preview)
            self._preview_timer.daemon = True
            self._preview_timer.start()
        similar = (d.get("similar", {}).get("results", []) or [])[:12]
        lst = self.getControl(300)
        for item in similar:
            lst.addItem(_as_list_item(item, self.media_type))
        self.getControl(30).setVisible(bool(similar))
        lst.setVisible(bool(similar))
        self.setFocusId(100)

    def _metadata(self, d):
        rating = float(d.get("vote_average") or 0)
        runtime = int(d.get("runtime") or 0)
        genres = ", ".join(g.get("name", "") for g in d.get("genres", [])[:3])
        values = [_year(d), "%.1f / 10" % rating if rating else "",
                  "%dh %02dm" % (runtime // 60, runtime % 60) if runtime else "", genres]
        return "  •  ".join(value for value in values if value)

    def _media_id(self):
        return "%s:%s" % (self.media_type, self.tmdb_id)

    def _is_saved(self):
        try:
            pid = session.active_profile_id()
            entries = serverapi.get_watchlist(pid) if pid else []
            return any(str(x.get("media_id")) == self._media_id() for x in entries)
        except Exception:
            return False

    def _set_list_label(self):
        self.getControl(101).setLabel("REMOVE FROM MY LIST" if self.saved else "ADD TO MY LIST")

    def _trailer_key(self):
        videos = self.details.get("videos", {}).get("results", [])
        preferred = [v for v in videos if v.get("site") == "YouTube" and v.get("type") == "Trailer"]
        any_youtube = [v for v in videos if v.get("site") == "YouTube"]
        choice = (preferred or any_youtube)
        return choice[0].get("key", "") if choice else ""

    def _play(self):
        self._cancel_preview(stop=True)
        d = self.details
        title = _title(d)
        if self.media_type == "movie":
            imdb = d.get("external_ids", {}).get("imdb_id", "")
            target = _url(action="streams", mt="movie", id=self.tmdb_id,
                          title=title, imdb=imdb)
        else:
            target = _url(action="seasons", id=self.tmdb_id, title=title)
        self.close()
        xbmc.executebuiltin("Container.Update(%s)" % target)

    def _toggle_list(self):
        pid = session.active_profile_id()
        if not pid:
            return
        try:
            if self.saved:
                serverapi.remove_watchlist(pid, self._media_id())
                self.saved = False
            else:
                serverapi.add_watchlist(pid, self._media_id(), self.media_type,
                                        self.tmdb_id, _title(self.details),
                                        _poster(self.details))
                self.saved = True
            self._set_list_label()
        except Exception as exc:
            _notice("My List could not be updated: %s" % exc, True)

    def _start_preview(self):
        key = self._trailer_key()
        if not key:
            return
        self._preview_started = True
        xbmc.executebuiltin(
            "PlayMedia(plugin://plugin.video.youtube/play/?video_id=%s,1)" % key)

    def _cancel_preview(self, stop=False):
        if self._preview_timer:
            self._preview_timer.cancel()
            self._preview_timer = None
        if stop and self._preview_started:
            xbmc.Player().stop()
            self._preview_started = False

    def onClick(self, control_id):
        if control_id == 100:
            self._play()
        elif control_id == 101:
            self._toggle_list()
        elif control_id == 102:
            self._cancel_preview(stop=True)
            key = self._trailer_key()
            if key:
                xbmc.Player().play("plugin://plugin.video.youtube/play/?video_id=%s" % key)
        elif control_id == 103:
            self._cancel_preview(stop=True)
            self.close()
            xbmc.executebuiltin("Container.Update(%s)" % _url(
                action="cast", mt=self.media_type, id=self.tmdb_id,
                title=_title(self.details)))
        elif control_id == 300:
            self._cancel_preview(stop=True)
            item = self.getControl(300).getSelectedItem()
            if item:
                show_details(item.getProperty("moviehub.type"),
                             item.getProperty("moviehub.id"),
                             item.getProperty("moviehub.title"))

    def onAction(self, action):
        if action.getId() in (xbmcgui.ACTION_NAV_BACK, xbmcgui.ACTION_PREVIOUS_MENU):
            self._cancel_preview(stop=True)
            self.close()


def show_home():
    dialog = HomeDialog("home.xml", ADDON_PATH, "default")
    dialog.doModal()
    del dialog


def show_details(media_type, tmdb_id, title=""):
    dialog = DetailsDialog("details.xml", ADDON_PATH, "default",
                           media_type=media_type, tmdb_id=tmdb_id, title=title)
    dialog.doModal()
    del dialog
