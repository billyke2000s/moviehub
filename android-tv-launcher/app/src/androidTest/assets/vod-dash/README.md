Synthetic DASH fixture, derived from the existing generated playback.mp4 (no external content).

Regenerate from repository root using FFmpeg:

    ffmpeg -hide_banner -loglevel error -y -i android-tv-launcher/app/src/androidTest/assets/playback.mp4 -t 6 -c copy -f dash -seg_duration 2 android-tv-launcher/app/src/androidTest/assets/vod-dash/manifest.mpd

The test server requires a fixture cookie for the MPD and every media/init segment. Public literal
8.8.8.8 is intercepted by the existing test connection factory; it is never contacted by the fixture.
