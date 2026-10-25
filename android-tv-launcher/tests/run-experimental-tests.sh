#!/usr/bin/env bash
set -uo pipefail
build=android-tv-launcher/app/build
mkdir -p "$build/reports/experimental"
gradle -p android-tv-launcher --no-daemon -Pexperimental connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.ExoLiveTest,local.tv.home.DlhdStartupTest lintDebug lintRelease 2>&1 | tee "$build/experimental-tests.log"
result=${PIPESTATUS[0]}
# Collect while the emulator is still alive; emulator-runner stops it after this script returns.
timeout 15s adb logcat -d -b crash > "$build/reports/experimental/crash.log" 2>/dev/null || true
timeout 15s adb pull /sdcard/Download/moviehub-test-screenshots/ "$build/reports/experimental/" || true
exit "$result"
