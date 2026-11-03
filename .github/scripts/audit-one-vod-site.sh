#!/usr/bin/env bash
set -euo pipefail
site=${1:?One enabled website ID required}
if [[ ! "$site" =~ ^(free99|scrubs|native):[a-z0-9_]+$ ]]; then
    echo 'Invalid website ID' >&2
    exit 2
fi
failed=0
for media in movies series; do
    mkdir -p "site-audit/$media"
    # Each invocation clears the app's previous private evidence. Move the
    # collected report first, so a failure in the next type cannot overwrite it.
    rm -rf vod-evidence vod-evidence-raw
    rm -f vod-live-audit.jsonl
    if VOD_SOURCE_LIMIT=200 VOD_SAMPLE_SECONDS=20 VOD_STOP_AFTER_VERIFIED=false \
        bash .github/scripts/vod-evidence-audit.sh 0 1 "$site" "$media"; then
        printf '0\n' > "site-audit/$media/exit-code.txt"
    else
        code=$?
        printf '%s\n' "$code" > "site-audit/$media/exit-code.txt"
        failed=1
    fi
    if [ -f vod-live-audit.jsonl ]; then
        mv vod-live-audit.jsonl "site-audit/$media/"
    fi
    if [ -d vod-evidence ]; then
        mv vod-evidence "site-audit/$media/"
    fi
done
exit "$failed"
