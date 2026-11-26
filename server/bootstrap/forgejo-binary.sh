#!/bin/bash
# Install a release only after verifying its detached signature against Forgejo's pinned primary key.
set -euo pipefail
[[ $# == 3 ]]
repo=$1 version=$2 destination=$3
case "$repo" in forgejo) name=forgejo ;; runner) name=forgejo-runner ;; *) exit 2 ;; esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
key=EB114F5E6C0DC2BCDD183550A4B61A2DC5923710
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
chmod 700 "$temp"
url="https://code.forgejo.org/forgejo/$repo/releases/download/v$version/$name-$version-linux-amd64"
curl -fsSL --retry 2 -o "$temp/release-key" "https://keys.openpgp.org/vks/v1/by-fingerprint/$key"
# Reject a substituted certificate or an extra primary key before constructing the verification keyring.
fingerprints=$(gpg --batch --no-options --homedir "$temp" --with-colons --show-keys "$temp/release-key" |
    awk -F: '$1=="pub" {primary=1; next} $1=="fpr" && primary {print $10; primary=0}')
[[ "$fingerprints" == "$key" ]]
gpg --batch --no-options --homedir "$temp" --dearmor --output "$temp/releases.gpg" "$temp/release-key"
curl -fsSL --retry 2 -o "$temp/binary" "$url"
curl -fsSL --retry 2 -o "$temp/signature" "$url.asc"
gpg --batch --no-options --homedir "$temp" --no-default-keyring --keyring "$temp/releases.gpg" --verify "$temp/signature" "$temp/binary"
install -m 0755 "$temp/binary" "$destination.new"
mv -f -- "$destination.new" "$destination"
