#!/usr/bin/env bash
set -euo pipefail
gradle -p android-tv-launcher --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r android-tv-launcher/app/build/outputs/apk/debug/app-debug.apk
adb install -r android-tv-launcher/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as local.tv.home rm -f files/vod-runner-calibration.json
adb shell am instrument -w -r -e class local.tv.home.VodRunnerCalibrationTest \
    -e vodRunnerCalibration true local.tv.home.test/local.tv.home.MovieHubTestRunner
adb exec-out run-as local.tv.home cat files/vod-runner-calibration.json > vod-runner-calibration.json
python - <<'PY'
import json
import os
from pathlib import Path
report = json.loads(Path('vod-runner-calibration.json').read_text())
if report.get('kind') != 'runner_calibration' or (report['width'], report['height']) != (1280, 720):
    raise SystemExit('Invalid calibration fixture/report')
if len(report['windows']) != 3 or any(row['audioBuffers'] <= 0 for row in report['windows']):
    raise SystemExit('Incomplete calibration windows')
report['emulatorCanvas'] = os.environ.get('VOD_CALIBRATION_CANVAS', 'unknown')
for window in report['windows']:
    if not 29.9 <= window['nominalFps'] <= 30.1:
        raise SystemExit('Calibration must measure the real 30 fps input')
    window['meetsExistingPlaybackCriteria'] = (
        window['displayedFps'] >= .8 * window['nominalFps'] and
        window['videoGapMs'] <= 4000 and window['audioGapMs'] <= 4000 and
        window['progressMs'] >= .8 * window['elapsedMs'] and window['audioBuffers'] > 0)
Path('vod-runner-calibration.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
print('Synthetic throughput measurement only; not provider playback certification')
PY
