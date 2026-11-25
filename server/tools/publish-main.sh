#!/usr/bin/env -S -i HOME=/var/lib/moviehub-release PATH=/usr/local/bin:/usr/bin:/bin /bin/bash
# Installed by root. The Actions runner may select a product and supply an expected commit on stdin.
# All code, tool configuration and signing inputs come from the publisher's account and protected main.
set -euo pipefail
[[ "$(id -un)" == moviehub-release && $# == 1 ]]
case "$1" in providers|scrapers|scrapers-experimental) area=$1 ;; *) exit 2 ;; esac
IFS= read -r expected
[[ "$expected" =~ ^[a-f0-9]{40}$ ]]
umask 077
cd "$HOME"
exec 9>publish.lock
flock -x 9
work=$(mktemp -d "$HOME/build.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
remote=http://127.0.0.1:3000/billy/moviehub.git
git clone --quiet --depth=1 --single-branch --branch main "$remote" "$work"
cd "$work"
[[ "$(git rev-parse HEAD)" == "$expected" ]] || { echo 'Selected commit is no longer main' >&2; exit 1; }
feed=/srv/moviehub/feed
key=/srv/moviehub/keys/signing.pem
if [[ "$area" == providers ]]; then
    npm ci --ignore-scripts --prefix provider-engine
    PROVIDER_VERSION=$(date +%s) npm run build --prefix provider-engine
    npm test --prefix provider-engine
    RHINO_JAR=/srv/moviehub/tools/rhino-1.8.0.jar bash provider-engine/tools/verify.sh
    PROVIDER_SIGNING_KEY_B64=$(base64 -w0 "$key") node provider-engine/tools/sign.mjs
    files=(provider-engine/dist/providers.json provider-engine/dist/providers.sig)
else
    python3 android-tv-launcher/tests/test_free_vod.py
    python3 android-tv-launcher/tests/test_primewire.py
    python3 android-tv-launcher/tests/test_scraper_loader.py
    app=app
    [[ "$area" != scrapers-experimental ]] || app=experimental
    version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionCode"])' "$feed/$app/update.json")
    python3 server/tools/build-scrapers.py --key "$key" --app "$version" --out build/scrapers
    files=(build/scrapers/scrapers.zip build/scrapers/scrapers.sig)
fi
[[ "$(git ls-remote --exit-code origin refs/heads/main | cut -f1)" == "$expected" ]] || exit 1
for artifact in "${files[@]}"; do
    name=${artifact##*/}
    install -m 0644 "$artifact" "$feed/$area/.$name.new"
done
for artifact in "${files[@]}"; do
    name=${artifact##*/}
    mv -f -- "$feed/$area/.$name.new" "$feed/$area/$name"
done
