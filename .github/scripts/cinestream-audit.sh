#!/usr/bin/env bash
set -uo pipefail
# The matrix groups protocols, but some genuinely support films or TV only.
# Reject unknown IDs; omit only modes the provider registry explicitly excludes.
providers=$(PYTHONPATH=android-tv-launcher/app/src/main/python python - "$1" "$2" <<'PYFILTER'
import sys
from moviehub_native import PROVIDERS
records = {row['id']: row for row in PROVIDERS}
requested = sys.argv[1].split(',')
assert all(name in records for name in requested), 'Unknown audit provider'
mode = 'series' if sys.argv[2] == 'series' else 'movies'
selected = [name for name in requested if records[name][mode]]
assert selected, 'No providers support this audit media type'
print(','.join(selected))
PYFILTER
)
VOD_CINESTREAM_BACKENDS=vegas,phoenix ORG_GRADLE_PROJECT_moviehubAbi=x86_64 bash android-tv-launcher/tests/run-live-vod-audit.sh 0 1 "$providers" "$2" "$3"
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
