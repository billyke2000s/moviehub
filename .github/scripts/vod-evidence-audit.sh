#!/usr/bin/env bash
set -euo pipefail
mkdir -p vod-evidence
# Only this disposable emulator's dedicated virtual sink is recorded.
python - <<'PY'
import json, pathlib, time
pathlib.Path('vod-audio-clock.json').write_text(json.dumps(dict(startEpochMs=int(time.time() * 1000))))
PY
ffmpeg -nostdin -v error -f pulse -i moviehub_audit.monitor -ac 2 -ar 16000 -t 4400 -c:a flac vod-audio.flac > vod-audio-recorder.log 2>&1 &
vod_audio_pid=$!
stop_audio() {
    if kill -0 "$vod_audio_pid" 2>/dev/null; then
        kill -INT "$vod_audio_pid"
        wait "$vod_audio_pid" || true
    fi
}
trap stop_audio EXIT
VOD_CAPTURE_EVIDENCE=true bash android-tv-launcher/tests/run-live-vod-audit.sh "$@"
stop_audio
trap - EXIT
adb exec-out run-as local.tv.home tar -cf - files/vod-evidence | tar -xf - --strip-components=2 -C vod-evidence
python android-tv-launcher/tests/review-vod-evidence.py vod-live-audit.jsonl vod-audio.flac vod-audio-clock.json vod-evidence
