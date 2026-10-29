#!/usr/bin/env bash
set -uo pipefail
ORG_GRADLE_PROJECT_moviehubAbi=x86_64 bash android-tv-launcher/tests/run-live-vod-audit.sh 0 1 "$1" "$2" "$3"
result=$?
python - <<'PY'
import pathlib, re, subprocess
response = subprocess.run(['adb', 'logcat', '-d', '-b', 'crash'], capture_output=True, text=True)
data = re.sub(r'https?://[^\s<>\"]+', '[redacted-url]', response.stdout)
data = re.sub(r'(?i)Bearer\s+[^\s]+', 'Bearer [redacted]', data)
pathlib.Path('cinestream-crash.log').write_text(data)
print(data)
PY
exit "$result"
