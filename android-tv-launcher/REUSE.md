# Existing Movie Hub code reused

The Android module is an addition. Existing Kodi add-on, private server, repository feed and release files remain intact.

Sources reviewed at commit e567abb53abf2d61451ac45a5b0570ae43d979a1:

- resources/lib/tmdb.py and router.py: browse menus and discovery filters.
- resources/lib/streams.py: Torrentio/Comet/MediaFusion source aggregation and info-hash deduplication.
- resources/lib/debrid.py: Premiumize source resolution and episode/file-selection rules; VideoFiles.java ports the filename/episode checks.
- resources/lib/playback.py: resume-position and episode-selection semantics.

Kodi Python modules are not directly executable in an Android APK. The native module ports these behaviors to Java. It keeps the new explicit Cached / Not cached / Unknown distinction; the earlier Kodi cache error handling is not copied.

The private-server profile, Trakt and cross-device sync code remains available in the existing repository but is not wired into this Android module. No server deployment or private credentials were assumed.

The repository MIT license and original copyright apply to reused code. LibVLC is an external dependency; its notices and source obligations must be retained in a distributed release.
