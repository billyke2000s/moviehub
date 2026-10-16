#!/usr/bin/env bash
set -uo pipefail
result=0
gradle -p android-tv-launcher --no-daemon testDebugUnitTest connectedDebugAndroidTest || result=$?
# Preserve screenshots even when an assertion fails, before the emulator is shut down.
adb pull /sdcard/Download/moviehub-test-screenshots/ android-tv-launcher/app/build/reports/tv-screenshots/ || true
exit "$result"
