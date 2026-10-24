#!/usr/bin/env bash
set -uo pipefail
mkdir -p android-tv-launcher/app/build
gradle -p android-tv-launcher --no-daemon connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.FreeVodProvidersTest,local.tv.home.PlaybackTest#freeVodRelayShowsIncomingTrafficWhileRealVlcPlays' 2>&1 | tee android-tv-launcher/app/build/vod-diagnostics.log
result=${PIPESTATUS[0]}
adb pull /sdcard/Download/moviehub-test-screenshots/ android-tv-launcher/app/build/reports/tv-screenshots/ || true
exit "$result"
