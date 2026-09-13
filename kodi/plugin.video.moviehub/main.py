"""
main.py — add-on entry point.

Kodi calls this with plugin://plugin.video.moviehub/?action=...
It ensures the user is logged in and a profile is chosen (the Netflix flow),
then dispatches to the router.
"""

import sys
import urllib.parse

import xbmcplugin

from resources.lib import session
from resources.lib import router


def _params():
    if len(sys.argv) > 2 and sys.argv[2]:
        return dict(urllib.parse.parse_qsl(sys.argv[2].lstrip("?")))
    return {}


def run():
    params = _params()
    action = params.get("action", "root")

    # A couple of actions should work without forcing the whole flow.
    if action == "switch_profile":
        session.switch_profile()
        import xbmc
        xbmc.executebuiltin("Container.Refresh")
        return

    if action == "experience_setup":
        if not session.ensure_ready():
            return
        from resources.lib import gui
        gui.experience_setup(force=True)
        import xbmc
        xbmc.executebuiltin("Container.Refresh")
        return

    if action == "connection_setup":
        from resources.lib import gui
        gui.connection_setup(force=True)
        return

    if action == "link_trakt":
        if not session.ensure_ready():
            return
        router.link_trakt()
        return

    # Everything else needs a ready session (login + keys + profile).
    if not session.ensure_ready():
        # user backed out — end quietly
        handle = int(sys.argv[1]) if len(sys.argv) > 1 else -1
        if handle >= 0:
            xbmcplugin.endOfDirectory(handle, succeeded=False)
        return

    if action == "root":
        router.root_menu()
    elif action == "legacy_root":
        router.legacy_root_menu()
    elif action == "hub":
        router.hub_menu(params["mt"])
    elif action == "genres":
        router.genres_menu(params["mt"])
    elif action == "decades":
        router.decades_menu(params["mt"])
    elif action == "languages":
        router.languages_menu(params["mt"])
    elif action == "providers":
        router.providers_menu(params["mt"])
    elif action == "sorts":
        router.sorts_menu(params["mt"])
    elif action == "row":
        router.row(params["mt"], params["cat"], int(params.get("page", 1)),
                   params.get("gid"), params.get("decade"), params.get("lang"),
                   params.get("provider"), params.get("sort"))
    elif action == "watchlist":
        router.watchlist_menu()
    elif action == "history":
        router.history_menu()
    elif action == "wl_add":
        router.wl_add(params)
    elif action == "wl_remove":
        router.wl_remove(params)
    elif action == "details":
        router.details_page(params["mt"], params["id"], params.get("title", ""))
    elif action == "person":
        router.person_page(params["id"], params.get("name", ""))
    elif action == "cast":
        router.cast_menu(params["mt"], params["id"], params.get("title", ""))
    elif action == "trailer":
        router.play_trailer(params["key"])
    elif action == "surprise":
        router.surprise_me(params.get("mt", "movie"))
    elif action == "transfers":
        router.transfers_menu()
    elif action == "link_trakt":
        router.link_trakt()
    elif action == "notifications":
        router.notifications_menu()
    elif action == "notif_dismiss":
        router.notif_dismiss(params)
    elif action == "tr_delete":
        router.tr_delete(params)
    elif action == "tr_play":
        router.tr_play(params)
    elif action == "seasons":
        router.seasons_menu(params["id"], params.get("title", ""))
    elif action == "episodes":
        router.episodes_menu(params["id"], int(params["season"]),
                             params.get("title", ""), params.get("imdb", ""))
    elif action == "streams":
        router.streams_menu(params["mt"], params["id"], params.get("title", ""),
                            params.get("imdb"), _int(params.get("season")),
                            _int(params.get("episode")), params.get("auto") == "1")
    elif action == "play":
        router.play(params.get("infoHash", ""), params.get("sname", ""),
                    params.get("title", ""), params.get("media_id", ""),
                    params.get("service", ""), params.get("mt", ""),
                    params.get("tmdb_id", ""), params.get("season", ""),
                    params.get("episode", ""))
    elif action == "send_pm":
        router.send_pm(params)
    elif action == "continue":
        router.continue_watching()
    elif action == "search":
        router.search()
    else:
        router.root_menu()


def _int(v):
    try:
        return int(v) if v is not None else None
    except (ValueError, TypeError):
        return None


if __name__ == "__main__":
    run()
