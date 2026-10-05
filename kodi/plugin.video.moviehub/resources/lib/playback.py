"""
playback.py — everything that happens *while* a video plays.

Runs inside the add-on's background service (service.py), never inside a
plugin call. The plugin's play() resolves a link, stores a small "now playing"
record in a home-window property, then returns straight away. This service
picks that record up when Kodi reports the video has started and handles:

  • resuming from the saved position
  • syncing progress to the private server every 30 seconds and on stop
  • marking titles watched on Trakt (optional)
  • subtitles nudge (optional)
  • Up Next for episodes
"""

import json

import xbmc
import xbmcaddon
import xbmcgui

PROP = "moviehub.now_playing"
_PLUGIN = "plugin://plugin.video.moviehub/"


def _home():
    return xbmcgui.Window(10000)


def set_now_playing(media_id, title, mt, tmdb_id, season, episode, resume=0):
    data = {"media_id": media_id, "title": title, "mt": mt, "tmdb_id": str(tmdb_id or ""),
            "season": str(season or ""), "episode": str(episode or ""),
            "resume": int(resume or 0)}
    _home().setProperty(PROP, json.dumps(data))


def _take_now_playing():
    raw = _home().getProperty(PROP)
    _home().clearProperty(PROP)
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def _push(info, pos, dur, completed):
    if not info or pos <= 0:
        return
    from . import serverapi, session
    pid = session.active_profile_id()
    if not pid:
        return
    try:
        serverapi.save_progress(pid, info["media_id"], info["title"], int(pos), int(dur), completed)
    except Exception:
        pass  # never interrupt playback for a sync hiccup
    if completed:
        try:
            from . import trakt
            mt = "tv" if info.get("season") else info.get("mt", "movie")
            trakt.mark_watched(info["tmdb_id"], mt, info.get("season") or None,
                               info.get("episode") or None)
        except Exception:
            pass


def _next_episode(tmdb_id, season, episode):
    """Return (season, episode, name) of the next episode, or None."""
    from . import tmdb
    try:
        season, episode = int(season), int(episode)
        eps = tmdb.season(tmdb_id, season).get("episodes", [])
        for e in eps:
            if e.get("episode_number") == episode + 1:
                return season, episode + 1, e.get("name", "")
        neps = tmdb.season(tmdb_id, season + 1).get("episodes", [])
        if neps:
            return season + 1, neps[0].get("episode_number", 1), neps[0].get("name", "")
    except Exception:
        return None
    return None


class _Player(xbmc.Player):
    def __init__(self):
        super().__init__()
        self.info = None
        self.pos = 0
        self.dur = 0
        self.upnext_done = False

    def onAVStarted(self):
        info = _take_now_playing()
        if not info:
            self.info = None  # not a Movie Hub video
            return
        self.info, self.pos, self.dur, self.upnext_done = info, 0, 0, False
        try:
            if info.get("resume", 0) > 5:
                self.seekTime(float(info["resume"]))
        except Exception:
            pass
        addon = xbmcaddon.Addon()
        if addon.getSetting("subtitles_on") == "true":
            try:
                self.showSubtitles(True)
            except Exception:
                pass

    def _finish(self):
        if self.info:
            completed = bool(self.dur and self.pos >= self.dur * 0.9)
            _push(self.info, self.pos, self.dur, completed)
        self.info = None

    def onPlayBackStopped(self):
        self._finish()

    def onPlayBackEnded(self):
        self._finish()

    def onPlayBackError(self):
        self.info = None

    def tick(self):
        """Called by the service loop once a second."""
        if not self.info or not self.isPlayingVideo():
            return
        try:
            self.pos = self.getTime()
            self.dur = self.getTotalTime()
        except Exception:
            return
        if self.info.get("season") and not self.upnext_done and self.dur and \
                self.dur - self.pos <= 40:
            self.upnext_done = True
            self._offer_up_next()

    def _offer_up_next(self):
        if xbmcaddon.Addon().getSetting("autoplay_next") != "true":
            return
        info = self.info
        nxt = _next_episode(info["tmdb_id"], info["season"], info["episode"])
        if not nxt:
            return
        ns, ne, nname = nxt
        go = xbmcgui.Dialog().yesno("Up Next", "Play S%dE%d — %s?" % (ns, ne, nname),
                                    nolabel="Stop", yeslabel="Play", autoclose=25000)
        if not go:
            return
        show = info["title"].split(" %dx" % int(info["season"]))[0]
        import urllib.parse
        url = _PLUGIN + "?" + urllib.parse.urlencode({
            "action": "streams", "mt": "tv", "id": info["tmdb_id"],
            "title": "%s %dx%02d  %s" % (show, ns, ne, nname),
            "season": ns, "episode": ne, "auto": "1"})
        xbmc.executebuiltin("RunPlugin(%s)" % url)


def run_service():
    monitor = xbmc.Monitor()
    player = _Player()
    sync_every = 30
    n = 0
    while not monitor.abortRequested():
        if monitor.waitForAbort(1):
            break
        player.tick()
        n += 1
        if n >= sync_every:
            n = 0
            if player.info and player.isPlayingVideo():
                _push(player.info, player.pos, player.dur, False)
