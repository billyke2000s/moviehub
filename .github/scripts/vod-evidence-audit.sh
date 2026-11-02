#!/usr/bin/env bash
set -euo pipefail
mkdir -p vod-evidence-raw vod-evidence
VOD_CAPTURE_EVIDENCE=true bash android-tv-launcher/tests/run-live-vod-audit.sh "$@"
adb exec-out run-as local.tv.home tar -cf - files/vod-evidence | tar -xf - --strip-components=2 -C vod-evidence-raw
python android-tv-launcher/tests/review-vod-evidence.py vod-live-audit.jsonl vod-evidence-raw vod-evidence
