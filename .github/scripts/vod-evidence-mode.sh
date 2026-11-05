#!/usr/bin/env bash
set -euo pipefail
capture=${1:?Evidence mode required}
shift
case "$capture" in
    true) exec bash .github/scripts/vod-evidence-audit.sh "$@" ;;
    false) exec bash android-tv-launcher/tests/run-live-vod-audit.sh "$@" ;;
    *) echo 'Evidence mode must be true or false' >&2; exit 2 ;;
esac
