"""
router.py — the browsing/playback brain.

Builds Kodi's native list menus (reliable on a TV remote), fetches metadata from
TMDb locally, resolves streams via Torrentio+Premiumize locally, plays through
Kodi's own player, and syncs progress to the server.

Only native Kodi lists and dialogs are used (no custom skin windows), so the
add-on always follows the installed skin and navigates cleanly on a remote.
"""

import os
import sys
import urllib.parse

import xbmc
import xbmcgui
import xbmcplugin
import xbmcaddon

from . import tmdb
from . import streams
from . import serverapi
from . import session
from . import trakt
from . import debrid
from . import playback

ADDON = xbmcaddon.Addon()
HANDLE = int(sys.argv[1]) if len(sys.argv) > 1 else -1
BASE = sys.argv[0] if sys.argv else "plugin://plugin.video.moviehub/"


def _url(**kwargs):
    return BASE + "?" + urllib.parse.urlencode(kwargs)

def _notify(msg, err=False):
    xbmcgui.Dialog().notification("Movie Hub", msg,
                                  xbmcgui.NOTIFICATION_ERROR if err else xbmcgui.NOTIFICATION_INFO, 4000)


# ─────────────────────────────────────────────────────────────────────────────
# MENUS
# ─────────────────────────────────────────────────────────────────────────────

def root_menu():
    """Main menu — a standard Kodi list that follows the installed skin."""
    fanart = os.path.join(ADDON.getAddonInfo("path"), "resources", "media", "fanart.jpg")
    items = [
        ("Movies", _url(action="hub", mt="movie"), "DefaultMovies.png", True),
        ("TV Shows", _url(action="hub", mt="tv"), "DefaultTVShows.png", True),
        ("Search", _url(action="search"), "DefaultAddonsSearch.png", True),
        ("My List", _url(action="watchlist"), "DefaultPlaylist.png", True),
        ("Continue Watching", _url(action="continue"), "DefaultInProgressShows.png", True),
        ("Viewing History", _url(action="history"), "DefaultAddonsRecentlyUpdated.png", True),
        ("New Episodes", _url(action="notifications"), "DefaultAddonsUpdates.png", True),
        ("Surprise Me", _url(action="surprise", mt="movie"), "DefaultAddonsRecentlyAdded.png", True),
        ("Debrid Transfers", _url(action="transfers"), "DefaultNetwork.png", True),
        ("Connect Trakt", _url(action="link_trakt"), "DefaultAddonService.png", False),
        ("Change Playback Service", _url(action="debrid_setup"), "DefaultNetwork.png", False),
        ("Switch Profile", _url(action="switch_profile"), "DefaultUser.png", False),
        ("Reconnect Private Server", _url(action="connection_setup"), "DefaultNetwork.png", False),
    ]
    for label, url, icon, folder in items:
        li = xbmcgui.ListItem(label)
        li.setArt({"icon": icon, "thumb": icon, "fanart": fanart})
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=folder)
    xbmcplugin.endOfDirectory(HANDLE)


# Old favourites/shortcuts may still point at this action.
legacy_root_menu = root_menu


def hub_menu(mt):
    """Top-level category sections for movies or tv, organised into groups."""
    if mt == "movie":
        rows = [
            ("Trending Today", _url(action="row", mt=mt, cat="trending_day")),
            ("Trending This Week", _url(action="row", mt=mt, cat="trending")),
            ("Popular", _url(action="row", mt=mt, cat="popular")),
            ("Top Rated", _url(action="row", mt=mt, cat="top_rated")),
            ("Now Playing", _url(action="row", mt=mt, cat="now_playing")),
            ("Upcoming", _url(action="row", mt=mt, cat="upcoming")),
        ]
    else:
        rows = [
            ("Trending Today", _url(action="row", mt=mt, cat="trending_day")),
            ("Trending This Week", _url(action="row", mt=mt, cat="trending")),
            ("Popular", _url(action="row", mt=mt, cat="popular")),
            ("Top Rated", _url(action="row", mt=mt, cat="top_rated")),
            ("On The Air", _url(action="row", mt=mt, cat="on_the_air")),
            ("Airing Today", _url(action="row", mt=mt, cat="airing_today")),
        ]
    # Browse-by sub-menus
    rows += [
        ("Browse by Genre", _url(action="genres", mt=mt)),
        ("Browse by Decade", _url(action="decades", mt=mt)),
        ("Browse by Language", _url(action="languages", mt=mt)),
        ("Browse by Streaming Service", _url(action="providers", mt=mt)),
        ("Browse by Sort Order", _url(action="sorts", mt=mt)),
    ]
    for label, url in rows:
        li = xbmcgui.ListItem(label)
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)
    xbmcplugin.setContent(HANDLE, "files")
    xbmcplugin.endOfDirectory(HANDLE)


_LOCAL_MEDIA = os.path.join(ADDON.getAddonInfo("path"), "resources", "media", "genres")

# The Xzener flat genre-icon resource addon (installed from Kodi's repo).
# Its images are addressed as resource://<addon-id>/<GenreName>.png
_XZENER = "resource://resource.images.moviegenreicons.xzener-flat/"

def _genre_icon(name):
    # Prefer the installed Xzener resource pack (nice pro icons); its filenames
    # are the genre names as-is (e.g. "Action.png", "Science Fiction.png").
    xz = _XZENER + name + ".png"
    # We can't easily test resource:// existence here, so try it first; Kodi
    # falls back to our bundled set via the fallback art if the pack's absent.
    local = _GENRE_ICON.get(name)
    if local:
        lp = os.path.join(_LOCAL_MEDIA, local + ".png")
        if os.path.exists(lp):
            return xz, lp   # (primary, fallback)
    return xz, "DefaultGenre.png"

# Map for our bundled fallback icons.
_GENRE_ICON = {
    "Action": "action", "Adventure": "adventure", "Animation": "animation",
    "Comedy": "comedy", "Crime": "crime", "Documentary": "documentary",
    "Drama": "drama", "Family": "family", "Fantasy": "fantasy",
    "History": "history", "Horror": "horror", "Music": "music",
    "Mystery": "mystery", "Romance": "romance", "Science Fiction": "scifi",
    "TV Movie": "tvmovie", "Thriller": "thriller", "War": "war",
    "Western": "western", "Action & Adventure": "action", "Kids": "kids",
    "News": "news", "Reality": "reality", "Sci-Fi & Fantasy": "scifi",
    "Soap": "soap", "Talk": "talk", "War & Politics": "war",
}


def decades_menu(mt):
    for value, label in tmdb.DECADES:
        li = xbmcgui.ListItem(label)
        li.setArt({"icon": "DefaultYear.png"})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat="decade", decade=value), li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)

def languages_menu(mt):
    for code, label, flag in tmdb.LANGUAGES:
        li = xbmcgui.ListItem(label)
        li.setArt({"icon": tmdb.flag_url(flag), "thumb": tmdb.flag_url(flag)})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat="language", lang=code), li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)

def providers_menu(mt):
    for pid, label, logo in tmdb.PROVIDERS:
        li = xbmcgui.ListItem(label)
        url_logo = tmdb.provider_logo_url(logo)
        li.setArt({"icon": url_logo, "thumb": url_logo})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat="provider", provider=str(pid)), li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)

def sorts_menu(mt):
    for value, label in tmdb.SORT_OPTIONS:
        li = xbmcgui.ListItem(label)
        li.setArt({"icon": "DefaultMovieTitle.png"})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat="sort", sort=value), li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)


def genres_menu(mt):
    glist = tmdb.MOVIE_GENRES if mt == "movie" else tmdb.TV_GENRES
    for gid, name in glist:
        li = xbmcgui.ListItem(name)
        primary, fallback = _genre_icon(name)
        # icon = the nice Xzener pack; thumb = our bundled fallback so something
        # always shows even if the pack isn't installed.
        li.setArt({"icon": primary, "thumb": fallback})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat="genre", gid=gid), li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)


def row(mt, cat, page=1, gid=None, decade=None, lang=None, provider=None, sort=None):
    try:
        if cat == "trending":
            data = tmdb.trending(mt, page)
        elif cat == "trending_day":
            data = tmdb.trending_day(mt, page)
        elif cat == "popular":
            data = tmdb.popular(mt, page)
        elif cat == "top_rated":
            data = tmdb.top_rated(mt, page)
        elif cat == "now_playing":
            data = tmdb.now_playing(page)
        elif cat == "upcoming":
            data = tmdb.upcoming(page)
        elif cat == "on_the_air":
            data = tmdb.on_the_air(page)
        elif cat == "airing_today":
            data = tmdb.airing_today(page)
        elif cat == "genre":
            data = tmdb.by_genre(mt, gid, page)
        elif cat == "decade":
            data = tmdb.by_decade(mt, decade, page)
        elif cat == "language":
            data = tmdb.by_language(mt, lang, page)
        elif cat == "provider":
            data = tmdb.by_provider(mt, provider, page)
        elif cat == "sort":
            data = tmdb.by_sort(mt, sort, page)
        else:
            data = tmdb.popular(mt, page)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return

    _list_results(data.get("results", []), mt)

    # pagination — carry all filter params forward
    if page < data.get("total_pages", 1):
        nxt = xbmcgui.ListItem("Next »")
        extra = {}
        for k, v in (("gid", gid), ("decade", decade), ("lang", lang),
                     ("provider", provider), ("sort", sort)):
            if v is not None:
                extra[k] = v
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="row", mt=mt, cat=cat, page=page + 1, **extra), nxt, isFolder=True)
    xbmcplugin.setContent(HANDLE, "movies" if mt == "movie" else "tvshows")
    xbmcplugin.endOfDirectory(HANDLE)


def _apply_meta(li, r, mt, full=None):
    """Set rich metadata + art the proper Kodi way (InfoTagVideo) so skins
    render it nicely (poster walls, cast photo walls, etc.)."""
    title = r.get("title") or r.get("name") or "Untitled"
    poster = (tmdb.POSTER + r["poster_path"]) if r.get("poster_path") else ""
    backdrop = (tmdb.BACKDROP + r["backdrop_path"]) if r.get("backdrop_path") else ""
    li.setArt({"poster": poster, "thumb": poster, "fanart": backdrop, "landscape": backdrop})
    d = full or r
    try:
        vt = li.getVideoInfoTag()
        vt.setTitle(title)
        vt.setPlot(d.get("overview", ""))
        y = _year(d)
        if y:
            vt.setYear(y)
        prem = d.get("release_date") or d.get("first_air_date") or ""
        if prem:
            vt.setPremiered(prem)
        vt.setRating(float(d.get("vote_average", 0) or 0))
        vt.setVotes(int(d.get("vote_count", 0) or 0))
        vt.setMediaType("movie" if mt == "movie" else "tvshow")
        if d.get("genres"):
            vt.setGenres([g["name"] for g in d["genres"]])
        if d.get("tagline"):
            vt.setTagLine(d["tagline"])
        rt = d.get("runtime") or 0
        if not rt and isinstance(d.get("episode_run_time"), list) and d["episode_run_time"]:
            rt = d["episode_run_time"][0]
        if rt:
            vt.setDuration(int(rt) * 60)
        if d.get("production_companies"):
            vt.setStudios([c["name"] for c in d["production_companies"] if c.get("name")])
        cast = d.get("credits", {}).get("cast", [])
        if cast:
            actors = []
            for c in cast[:30]:
                actors.append(xbmc.Actor(
                    c.get("name", ""), c.get("character", ""), order=c.get("order", 0),
                    thumbnail=(tmdb.POSTER + c["profile_path"]) if c.get("profile_path") else ""))
            vt.setCast(actors)
    except Exception:
        li.setInfo("video", {"title": title, "plot": d.get("overview", ""),
                             "year": _year(d), "rating": d.get("vote_average", 0),
                             "mediatype": "movie" if mt == "movie" else "tvshow"})
    return poster, backdrop


def _list_results(results, default_mt):
    for r in results:
        mt = r.get("media_type") or default_mt
        if mt not in ("movie", "tv"):
            continue
        title = r.get("title") or r.get("name") or "Untitled"
        tmdb_id = r.get("id")
        li = xbmcgui.ListItem(title)
        poster, backdrop = _apply_meta(li, r, mt)

        wl_media_id = _media_id(mt, tmdb_id)
        li.addContextMenuItems([(
            "Add to Watchlist",
            "RunPlugin(%s)" % _url(action="wl_add", media_id=wl_media_id, mt=mt,
                                   id=tmdb_id, title=title, poster=poster))])

        url = _url(action="details", mt=mt, id=tmdb_id, title=title)
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)


def seasons_menu(tmdb_id, title):
    try:
        det = tmdb.details("tv", tmdb_id)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    except Exception as e:
        _notify("Show failed to load: %s" % e, err=True)
        xbmcplugin.endOfDirectory(HANDLE); return
    imdb = det.get("external_ids", {}).get("imdb_id", "")
    seasons = [s for s in det.get("seasons", []) if s.get("season_number", 0) != 0]
    if not seasons:
        # some shows only have specials, or season data under a different shape
        seasons = det.get("seasons", [])
    if not seasons:
        _notify("No seasons found for this show.")
        xbmcplugin.endOfDirectory(HANDLE); return
    for s in seasons:
        num = s.get("season_number", 0)
        li = xbmcgui.ListItem(s.get("name") or ("Season %s" % num))
        if s.get("poster_path"):
            li.setArt({"poster": tmdb.POSTER + s["poster_path"]})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="episodes", id=tmdb_id, season=num, title=title, imdb=imdb),
            li, isFolder=True)
    xbmcplugin.setContent(HANDLE, "seasons")
    xbmcplugin.endOfDirectory(HANDLE)


def episodes_menu(tmdb_id, season, title, imdb):
    try:
        data = tmdb.season(tmdb_id, season)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    for ep in data.get("episodes", []):
        en = ep.get("episode_number")
        label = "%dx%02d  %s" % (int(season), en, ep.get("name", ""))
        li = xbmcgui.ListItem(label)
        still = (tmdb.BACKDROP + ep["still_path"]) if ep.get("still_path") else ""
        li.setArt({"thumb": still})
        try:
            vt = li.getVideoInfoTag()
            vt.setPlot(ep.get("overview", "")); vt.setMediaType("episode")
        except Exception:
            li.setInfo("video", {"plot": ep.get("overview", ""), "mediatype": "episode"})
        url = _url(action="streams", mt="tv", id=tmdb_id, title="%s %s" % (title, label),
                   imdb=imdb, season=season, episode=en)
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)
    xbmcplugin.setContent(HANDLE, "episodes")
    xbmcplugin.endOfDirectory(HANDLE)


# ─────────────────────────────────────────────────────────────────────────────
# STREAMS + PLAYBACK
# ─────────────────────────────────────────────────────────────────────────────

def _stream_label(s):
    svc = debrid.base_service(s.get("service"))
    badge = debrid.LABELS.get(svc, "")
    if s.get("cached"):
        tag = "[COLOR lime]%s CACHED[/COLOR]" % badge
    else:
        tag = "[COLOR grey]%s ?[/COLOR]" % badge
    parts = [tag]
    if s.get("quality"):
        parts.append(s["quality"])
    if s.get("size_mb"):
        parts.append("%.1f GB" % (s["size_mb"] / 1024) if s["size_mb"] > 1024
                     else "%d MB" % int(s["size_mb"]))
    first_line = (s.get("title") or "").split("\n")[0].strip()
    return "%s  %s" % ("  ".join(parts), first_line or s.get("name", "Stream"))


def _play_url(s, title, media_id, mt, tmdb_id, season, episode):
    return _url(action="play", infoHash=s["infoHash"], sname=s.get("name", "")[:60],
                service=s.get("service", ""), fname=s.get("filename", ""),
                title=title, media_id=media_id, mt=mt, tmdb_id=tmdb_id,
                season=season or "", episode=episode or "")


def streams_menu(mt, tmdb_id, title, imdb=None, season=None, episode=None, auto=False):
    """List every link as a native, playable Kodi item.

    Previously, links that weren't confirmed cached (all of them for
    Real-Debrid/AllDebrid, whose cache-check APIs no longer exist) were added
    as non-playable "send to debrid" items, so there was never a link to play.
    Now every link is playable; resolve() decides on click.
    """
    if not imdb:
        try:
            det = tmdb.details(mt, tmdb_id)
            imdb = det.get("external_ids", {}).get("imdb_id", "")
        except tmdb.TmdbError as e:
            _notify(str(e), err=True); _end(False); return

    dlg = xbmcgui.DialogProgress()
    dlg.create("Movie Hub", "Searching sources…")
    try:
        found = streams.fetch_streams(imdb, mt, season, episode)
    except streams.StreamError as e:
        dlg.close(); _notify(str(e), err=True); _end(False); return
    except Exception as e:
        dlg.close(); _notify("Source search failed: %s" % e, err=True); _end(False); return
    dlg.close()

    if not found:
        _notify("No links found for this title.")
        _end(False); return

    media_id = _media_id(mt, tmdb_id, season, episode)

    # Up Next auto mode (called from the background service via RunPlugin,
    # so there is no directory handle): play the best cached link directly.
    if auto:
        for s in ([streams.best_cached(found)] if streams.best_cached(found) else []) + found[:5]:
            try:
                surl = streams.resolve_playable(s, season, episode)
            except streams.StreamError:
                continue
            li = xbmcgui.ListItem(title, path=surl)
            _tag_episode(li, title, season, episode)
            playback.set_now_playing(media_id, title, mt, tmdb_id, season, episode,
                                     _saved_position(media_id))
            xbmc.Player().play(surl, li)
            return
        _notify("Couldn't find a cached link for the next episode.", err=True)
        return

    best = streams.best_cached(found)
    if best:
        li = xbmcgui.ListItem("[B]Play best cached link (%s)[/B]" % (best.get("quality") or "auto"))
        li.setProperty("IsPlayable", "true")
        _tag_episode(li, title, season, episode)
        xbmcplugin.addDirectoryItem(
            HANDLE, _play_url(best, title, media_id, mt, tmdb_id, season, episode),
            li, isFolder=False)

    for s in found:
        li = xbmcgui.ListItem(_stream_label(s))
        li.setLabel2(s.get("name", ""))
        li.setProperty("IsPlayable", "true")
        _tag_episode(li, title, season, episode)
        li.addContextMenuItems([(
            "Send to debrid cloud (download)",
            "RunPlugin(%s)" % _url(action="send_pm", infoHash=s["infoHash"],
                                   service=s.get("service", ""), sname=s.get("name", "")[:60]))])
        xbmcplugin.addDirectoryItem(
            HANDLE, _play_url(s, title, media_id, mt, tmdb_id, season, episode),
            li, isFolder=False)
    xbmcplugin.setContent(HANDLE, "videos")
    xbmcplugin.setPluginCategory(HANDLE, title)
    xbmcplugin.endOfDirectory(HANDLE, cacheToDisc=False)


def _tag_episode(li, title, season, episode):
    try:
        vt = li.getVideoInfoTag()
        vt.setTitle(title)
        if season and episode:
            vt.setMediaType("episode")
            vt.setSeason(int(season)); vt.setEpisode(int(episode))
        else:
            vt.setMediaType("movie")
    except Exception:
        pass


def _end(ok=True):
    if HANDLE >= 0:
        xbmcplugin.endOfDirectory(HANDLE, succeeded=ok)


def send_pm(params):
    stream = {"infoHash": params.get("infoHash", ""), "service": params.get("service", ""),
              "name": params.get("sname", "download")}
    try:
        streams.send_to_debrid(stream)
        _notify("Sent to your debrid cloud — it'll be playable once downloaded.")
    except streams.StreamError as e:
        _notify(str(e), err=True)


def play(info_hash, sname, title, media_id, service="", mt="", tmdb_id="", season="",
         episode="", fname=""):
    """Resolve one link and hand it to Kodi's player — and return immediately.

    The old version stayed inside this plugin call for the whole film
    (progress polling, subtitles, Up Next loops). Long-running code in a
    resolver invocation is fragile in Kodi; that work now lives in the
    background service (resources/lib/playback.py).
    """
    stream = {"infoHash": info_hash, "name": sname, "service": service, "filename": fname}
    try:
        surl = streams.resolve_playable(stream, season or None, episode or None)
    except streams.NotCachedError as e:
        xbmcplugin.setResolvedUrl(HANDLE, False, xbmcgui.ListItem())
        if xbmcgui.Dialog().yesno("Not cached yet", "%s\n\nSend it to your debrid cloud to "
                                  "download so it's playable later?" % e,
                                  nolabel="No", yeslabel="Download"):
            send_pm({"infoHash": info_hash, "service": service, "sname": sname})
        return
    except Exception as e:
        _notify(str(e) or "That link couldn't be opened.", err=True)
        xbmcplugin.setResolvedUrl(HANDLE, False, xbmcgui.ListItem())
        return

    li = xbmcgui.ListItem(title, path=surl)
    _tag_episode(li, title, season, episode)
    playback.set_now_playing(media_id, title, mt, tmdb_id, season, episode,
                             _saved_position(media_id))
    xbmcplugin.setResolvedUrl(HANDLE, True, li)


# ─────────────────────────────────────────────────────────────────────────────
# CONTINUE WATCHING / SEARCH
# ─────────────────────────────────────────────────────────────────────────────

def continue_watching():
    prog = _profile_progress()
    unfinished = [(mid, p) for mid, p in prog.items() if not p.get("completed")]
    unfinished.sort(key=lambda x: x[1].get("updated_at", 0), reverse=True)
    if not unfinished:
        _notify("Nothing in progress yet.")
        xbmcplugin.endOfDirectory(HANDLE); return
    for mid, p in unfinished:
        pct = int((p["position"] / p["duration"]) * 100) if p.get("duration") else 0
        li = xbmcgui.ListItem("%s  (%d%%)" % (p.get("title", "Untitled"), pct))
        # re-resolve streams for this item when picked
        mt, tmdb_id, season, episode = _parse_media_id(mid)
        li.setProperty("IsPlayable", "false")
        url = _url(action="streams", mt=mt, id=tmdb_id, title=p.get("title", ""),
                   **({"season": season, "episode": episode} if mt == "tv" else {}))
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)


def search():
    q = xbmcgui.Dialog().input("Search movies & TV")
    if not q:
        xbmcplugin.endOfDirectory(HANDLE); return
    try:
        data = tmdb.search_multi(q)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    _list_results(data.get("results", []), "movie")
    xbmcplugin.endOfDirectory(HANDLE)


# ─────────────────────────────────────────────────────────────────────────────
# helpers
# ─────────────────────────────────────────────────────────────────────────────

def _year(r):
    d = r.get("release_date") or r.get("first_air_date") or ""
    try:
        return int(d[:4]) if d else 0
    except ValueError:
        return 0

def _media_id(mt, tmdb_id, season=None, episode=None):
    if mt == "tv" and season and episode:
        return "ep-%s_%s_%s" % (tmdb_id, season, episode)
    if mt == "tv":
        return "tv-%s" % tmdb_id
    return "movie-%s" % tmdb_id

def _parse_media_id(mid):
    if mid.startswith("ep-"):
        rest = mid[3:].split("_")
        return "tv", rest[0], rest[1], rest[2]
    if mid.startswith("tv-"):
        return "tv", mid[3:], None, None
    return "movie", mid[6:], None, None

_progress_cache = None
def _profile_progress():
    global _progress_cache
    if _progress_cache is not None:
        return _progress_cache
    pid = session.active_profile_id()
    if not pid:
        _progress_cache = {}
        return _progress_cache
    try:
        _progress_cache = serverapi.get_progress(pid)
    except serverapi.ServerError:
        _progress_cache = {}
    return _progress_cache

def _saved_position(media_id):
    p = _profile_progress().get(media_id)
    if p and not p.get("completed"):
        return p.get("position", 0)
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# WATCHLIST & HISTORY
# ─────────────────────────────────────────────────────────────────────────────

def watchlist_menu():
    pid = session.active_profile_id()
    try:
        items = serverapi.get_watchlist(pid)
    except serverapi.ServerError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    if not items:
        _notify("Your watchlist is empty. Add titles with the context menu (long-press).")
        xbmcplugin.endOfDirectory(HANDLE); return
    for it in items:
        mt = it.get("media_type", "movie")
        tmdb_id = it.get("tmdb_id", "")
        title = it.get("title", "Untitled")
        li = xbmcgui.ListItem(title)
        # Fetch full details so watchlist shows the SAME rich info as everywhere.
        try:
            det = tmdb.details(mt, tmdb_id)
            _apply_meta(li, det, mt, full=det)
        except Exception:
            if it.get("poster"):
                li.setArt({"poster": it["poster"], "thumb": it["poster"]})
        li.addContextMenuItems([(
            "Remove from Watchlist",
            "RunPlugin(%s)" % _url(action="wl_remove", media_id=it.get("media_id", "")))])
        url = _url(action="details", mt=mt, id=tmdb_id, title=title)
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)
    xbmcplugin.setContent(HANDLE, "movies")
    xbmcplugin.endOfDirectory(HANDLE)


def history_menu():
    prog = _profile_progress()
    watched = [(mid, p) for mid, p in prog.items() if p.get("completed")]
    watched.sort(key=lambda x: x[1].get("updated_at", 0), reverse=True)
    if not watched:
        _notify("No watch history yet.")
        xbmcplugin.endOfDirectory(HANDLE); return
    for mid, p in watched:
        li = xbmcgui.ListItem(p.get("title", "Untitled"))
        mt, tmdb_id, season, episode = _parse_media_id(mid)
        url = _url(action="streams", mt=mt, id=tmdb_id, title=p.get("title", ""),
                   **({"season": season, "episode": episode} if mt == "tv" else {}))
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)
    xbmcplugin.endOfDirectory(HANDLE)


def wl_add(params):
    pid = session.active_profile_id()
    try:
        serverapi.add_watchlist(pid, params.get("media_id", ""), params.get("mt", "movie"),
                                params.get("id", ""), params.get("title", ""),
                                params.get("poster", ""))
        _notify("Added to Watchlist.")
    except serverapi.ServerError as e:
        _notify(str(e), err=True)
    # optional Trakt mirror
    try:
        trakt.add_to_watchlist(params.get("id", ""), params.get("mt", "movie"))
    except Exception:
        pass


def link_trakt():
    """Run Trakt device-code login and save the token to the server + settings."""
    tok = trakt.link_device()
    if not tok:
        _notify("Trakt not linked.")
        return
    ADDON.setSetting("trakt_token", tok)
    ADDON.setSetting("trakt_enabled", "true")
    pid = session.active_profile_id()
    try:
        serverapi.save_trakt_token(pid, tok)
    except serverapi.ServerError:
        pass  # settings copy still works even if server save fails
    _notify("Trakt linked! Your watches will now mirror to Trakt.")


def wl_remove(params):
    pid = session.active_profile_id()
    try:
        serverapi.remove_watchlist(pid, params.get("media_id", ""))
        _notify("Removed from Watchlist.")
        xbmc.executebuiltin("Container.Refresh")
    except serverapi.ServerError as e:
        _notify(str(e), err=True)


# ─────────────────────────────────────────────────────────────────────────────
# DETAILS PAGE (native Kodi info dialog — any skin styles it)
# ─────────────────────────────────────────────────────────────────────────────

def details_page(mt, tmdb_id, title=""):
    """Rich details: Play/Browse + Watchlist + Trailer, full metadata + native
    cast (skins render a cast photo wall), then Similar as a poster row."""
    try:
        det = tmdb.details(mt, tmdb_id)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return

    name = det.get("title") or det.get("name") or title
    backdrop = (tmdb.BACKDROP + det["backdrop_path"]) if det.get("backdrop_path") else ""
    poster = (tmdb.POSTER + det["poster_path"]) if det.get("poster_path") else ""
    imdb = det.get("external_ids", {}).get("imdb_id", "")
    media_id = _media_id(mt, tmdb_id)

    head = "Play" if mt == "movie" else "Browse Episodes"
    li = xbmcgui.ListItem(head)
    _apply_meta(li, det, mt, full=det)
    if mt == "movie":
        url = _url(action="streams", mt="movie", id=tmdb_id, title=name, imdb=imdb)
    else:
        url = _url(action="seasons", id=tmdb_id, title=name)
    xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)

    li = xbmcgui.ListItem("Add to Watchlist")
    li.setArt({"fanart": backdrop, "poster": poster})
    xbmcplugin.addDirectoryItem(
        HANDLE, _url(action="wl_add", media_id=media_id, mt=mt, id=tmdb_id,
                     title=name, poster=poster), li, isFolder=False)

    trailer = _find_trailer(det)
    if trailer:
        li = xbmcgui.ListItem("Watch Trailer")
        li.setArt({"fanart": backdrop, "poster": poster})
        li.setProperty("IsPlayable", "true")
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="trailer", key=trailer), li, isFolder=False)

    # View Cast — browsable row of actors (tap an actor -> their filmography)
    if det.get("credits", {}).get("cast"):
        li = xbmcgui.ListItem("View Cast")
        li.setArt({"fanart": backdrop, "poster": poster, "icon": "DefaultActor.png"})
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="cast", mt=mt, id=tmdb_id, title=name), li, isFolder=True)

    similar = (det.get("similar", {}).get("results", [])
               or det.get("recommendations", {}).get("results", []))
    for sres in similar[:20]:
        stitle = sres.get("title") or sres.get("name") or "Untitled"
        sli = xbmcgui.ListItem(stitle)
        _apply_meta(sli, sres, mt)
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="details", mt=mt, id=sres.get("id"), title=stitle),
            sli, isFolder=True)

    xbmcplugin.setContent(HANDLE, "movies" if mt == "movie" else "tvshows")
    xbmcplugin.setPluginCategory(HANDLE, name)
    xbmcplugin.endOfDirectory(HANDLE)

def _find_trailer(det):
    vids = det.get("videos", {}).get("results", [])
    for v in vids:
        if v.get("site") == "YouTube" and v.get("type") == "Trailer":
            return v.get("key", "")
    for v in vids:
        if v.get("site") == "YouTube":
            return v.get("key", "")
    return ""


def play_trailer(key):
    # Use the YouTube plugin if installed; otherwise notify.
    yt = "plugin://plugin.video.youtube/play/?video_id=%s" % key
    li = xbmcgui.ListItem(path=yt)
    xbmcplugin.setResolvedUrl(HANDLE, True, li)


def cast_menu(mt, tmdb_id, title):
    """Browsable cast: each actor shown with their photo; tap -> filmography."""
    try:
        det = tmdb.details(mt, tmdb_id)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    cast = det.get("credits", {}).get("cast", [])
    if not cast:
        _notify("No cast information."); xbmcplugin.endOfDirectory(HANDLE); return
    for c in cast[:40]:
        name = c.get("name", "")
        role = c.get("character", "")
        li = xbmcgui.ListItem(name)
        photo = (tmdb.POSTER + c["profile_path"]) if c.get("profile_path") else "DefaultActor.png"
        li.setArt({"poster": photo, "thumb": photo, "icon": photo})
        try:
            vt = li.getVideoInfoTag()
            vt.setTitle(name)
            vt.setPlot("as %s" % role if role else "")
        except Exception:
            pass
        xbmcplugin.addDirectoryItem(
            HANDLE, _url(action="person", id=c.get("id"), name=name), li, isFolder=True)
    xbmcplugin.setContent(HANDLE, "actors")
    xbmcplugin.setPluginCategory(HANDLE, "Cast — %s" % title)
    xbmcplugin.endOfDirectory(HANDLE)


def person_page(person_id, name):
    """Show a person's known-for titles."""
    try:
        data = tmdb._get("/person/%s/combined_credits" % person_id)
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    cast = data.get("cast", [])
    cast.sort(key=lambda c: c.get("popularity", 0), reverse=True)
    _list_results(cast[:40], "movie")
    xbmcplugin.setContent(HANDLE, "movies")
    xbmcplugin.endOfDirectory(HANDLE)


def surprise_me(mt="movie"):
    """Pick a random popular title and open its details."""
    import random
    try:
        page = random.randint(1, 20)
        data = tmdb.popular(mt, page)
        results = data.get("results", [])
        if not results:
            _notify("Couldn't find a surprise, try again."); xbmcplugin.endOfDirectory(HANDLE); return
        pick = random.choice(results)
        details_page(mt, pick.get("id"), pick.get("title") or pick.get("name", ""))
    except tmdb.TmdbError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE)


# ─────────────────────────────────────────────────────────────────────────────
# TRANSFERS (Premiumize downloads for uncached picks)
# ─────────────────────────────────────────────────────────────────────────────

def transfers_menu():
    try:
        transfers = streams.list_transfers()
    except streams.StreamError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    if not transfers:
        _notify("No transfers. Uncached picks you download appear here.")
        xbmcplugin.endOfDirectory(HANDLE); return

    for t in transfers:
        name = t.get("name", "Download")
        status = t.get("status", "")
        progress = t.get("progress", 0) or 0
        tid = str(t.get("id", ""))
        pct = int(float(progress) * 100) if progress and float(progress) <= 1 else int(progress)

        if status == "finished" or status == "seeding":
            label = "%s  (ready)" % name
            # finished → play it
            li = xbmcgui.ListItem(label)
            # tr_play calls setResolvedUrl, so this MUST be a playable,
            # non-folder item (as a folder it left Kodi waiting forever).
            li.setProperty("IsPlayable", "true")
            li.addContextMenuItems([(
                "Delete transfer",
                "RunPlugin(%s)" % _url(action="tr_delete", id=tid))])
            # folder/list needs the transfer's FOLDER id, not the transfer id.
            url = _url(action="tr_play", id=t.get("folder_id") or tid, name=name)
            xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=False)
        else:
            label = "⬇  %s  —  %s %d%%" % (name, status, pct)
            li = xbmcgui.ListItem(label)
            li.addContextMenuItems([
                ("Cancel / Delete", "RunPlugin(%s)" % _url(action="tr_delete", id=tid)),
                ("Refresh", "Container.Refresh")])
            xbmcplugin.addDirectoryItem(HANDLE, _url(action="transfers"), li, isFolder=True)

    xbmcplugin.endOfDirectory(HANDLE)


def tr_delete(params):
    try:
        streams.delete_transfer(params.get("id", ""))
        _notify("Transfer removed.")
        xbmc.executebuiltin("Container.Refresh")
    except streams.StreamError as e:
        _notify(str(e), err=True)


def tr_play(params):
    """Play a finished transfer by resolving its folder to a stream link."""
    # A finished transfer's content is available via folder/list; grab the
    # biggest video file and play it.
    try:
        import requests as _rq
        r = _rq.get(streams.PREMIUMIZE + "/folder/list",
                    params={"apikey": streams._pm_key(), "id": params.get("id", "")},
                    headers=streams._HEADERS, timeout=20)
        j = r.json()
        content = j.get("content", []) if j.get("status") == "success" else []
        video_exts = (".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm")
        vids = [c for c in content if str(c.get("name", "")).lower().endswith(video_exts)]
        if not vids:
            vids = [c for c in content if c.get("link") or c.get("stream_link")]
        if not vids:
            _notify("That transfer has no playable file yet.")
            xbmcplugin.setResolvedUrl(HANDLE, False, xbmcgui.ListItem()); return
        best = max(vids, key=lambda c: c.get("size", 0))
        link = best.get("stream_link") or best.get("link")
        li = xbmcgui.ListItem(params.get("name", "Download"), path=link)
        xbmcplugin.setResolvedUrl(HANDLE, True, li)
    except Exception as e:
        _notify("Couldn't open transfer: %s" % e, err=True)
        xbmcplugin.setResolvedUrl(HANDLE, False, xbmcgui.ListItem())


# ─────────────────────────────────────────────────────────────────────────────
# NOTIFICATIONS — new episodes for watchlisted / in-progress shows.
# Check runs on the device; the "last seen" state lives on the server so every
# box shows the same thing.
# ─────────────────────────────────────────────────────────────────────────────

import time as _time

def notifications_menu():
    pid = session.active_profile_id()
    try:
        state = serverapi.get_notif_state(pid)
    except serverapi.ServerError as e:
        _notify(str(e), err=True); xbmcplugin.endOfDirectory(HANDLE); return
    last_check = state.get("last_check", 0) or 0
    dismissed = set(state.get("dismissed", []))

    # Which shows to watch for new episodes: watchlisted TV + in-progress TV.
    shows = {}  # tmdb_id -> title
    try:
        for it in serverapi.get_watchlist(pid):
            if it.get("media_type") == "tv" and it.get("tmdb_id"):
                shows[str(it["tmdb_id"])] = it.get("title", "")
    except serverapi.ServerError:
        pass
    for mid, p in _profile_progress().items():
        if mid.startswith("ep-") or mid.startswith("tv-"):
            _, tid, _, _ = _parse_media_id(mid)
            shows.setdefault(str(tid), p.get("title", ""))

    if not shows:
        _notify("No shows to watch for new episodes. Add TV shows to your watchlist.")
        xbmcplugin.endOfDirectory(HANDLE); return

    # For each show, find episodes aired since last_check.
    new_items = []
    now = int(_time.time())
    for tid, title in shows.items():
        try:
            det = tmdb.details("tv", tid)
        except tmdb.TmdbError:
            continue
        last_ep = det.get("last_episode_to_air") or {}
        next_ep = det.get("next_episode_to_air") or {}
        for ep in (last_ep, next_ep):
            if not ep:
                continue
            air = ep.get("air_date", "")
            if not air:
                continue
            air_ts = _airdate_ts(air)
            notif_id = "%s-s%se%s" % (tid, ep.get("season_number"), ep.get("episode_number"))
            if notif_id in dismissed:
                continue
            # "new" = aired after last check (and not in the future beyond today)
            if air_ts and last_check and air_ts > last_check and air_ts <= now + 86400:
                new_items.append((notif_id, tid, title, ep))

    if not new_items:
        _notify("No new episodes since you last checked.")
        # still update the check time
        try: serverapi.set_notif_check(pid, now)
        except serverapi.ServerError: pass
        xbmcplugin.endOfDirectory(HANDLE); return

    for notif_id, tid, title, ep in new_items:
        sn = ep.get("season_number"); en = ep.get("episode_number")
        label = "%s — S%dE%d: %s" % (title, sn, en, ep.get("name", ""))
        li = xbmcgui.ListItem(label)
        li.addContextMenuItems([(
            "Dismiss", "RunPlugin(%s)" % _url(action="notif_dismiss", nid=notif_id))])
        imdb = ""
        url = _url(action="streams", mt="tv", id=tid, title=title,
                   season=sn, episode=en)
        xbmcplugin.addDirectoryItem(HANDLE, url, li, isFolder=True)

    # mark checked now (so next open only shows newer)
    try: serverapi.set_notif_check(pid, now)
    except serverapi.ServerError: pass
    xbmcplugin.endOfDirectory(HANDLE)


def _airdate_ts(datestr):
    try:
        import calendar, datetime
        d = datetime.datetime.strptime(datestr, "%Y-%m-%d")
        return int(calendar.timegm(d.timetuple()))
    except Exception:
        return 0


def notif_dismiss(params):
    pid = session.active_profile_id()
    try:
        serverapi.dismiss_notif(pid, params.get("nid", ""))
        xbmc.executebuiltin("Container.Refresh")
    except serverapi.ServerError as e:
        _notify(str(e), err=True)
