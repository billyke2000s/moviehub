#!/usr/bin/env bash
set -uo pipefail
build=android-tv-launcher/app/build
mkdir -p "$build"
# The Gradle output is kept in a file so a failure that writes no test results (an app crash, an
# install or emulator problem) can still be reported by report-test-failures.py.
gradle -p android-tv-launcher --no-daemon testDebugUnitTest connectedDebugAndroidTest 2>&1 | tee "$build/device-tests.log"
result=${PIPESTATUS[0]}
if [ "$result" -ne 0 ]; then
  adb logcat -d -b crash > "$build/device-crash.log" 2>/dev/null || true
fi
# Preserve screenshots even when an assertion fails, before the emulator is shut down.
adb pull /sdcard/Download/moviehub-test-screenshots/ "$build/reports/tv-screenshots/" || true
exit "$result"
