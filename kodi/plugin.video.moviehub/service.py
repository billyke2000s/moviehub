"""
service.py — Movie Hub background service.

Tracks Movie Hub playback (resume, progress sync, Trakt, Up Next) outside of
plugin calls so the plugin can hand a link to Kodi's player and exit at once.
"""

from resources.lib import playback

if __name__ == "__main__":
    playback.run_service()
