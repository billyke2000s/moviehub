#!/usr/bin/env bash
set -uo pipefail
gradle -p android-tv-launcher --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.LiveHlsMetadataCheckTest -Pandroid.testInstrumentationRunnerArguments.liveHlsMeta=true
result=$?
adb logcat -d -s MovieHubHlsMeta:I '*:S' || true
exit "$result"
