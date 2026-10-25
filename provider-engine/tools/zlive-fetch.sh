#!/usr/bin/env bash
# Diagnostic only: fetch ZLive's public player code and encrypt it to diag-pub.pem so the
# public branch never carries the plain files.
set -eu
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
mkdir -p z && cd z
curl -s -A "$UA" https://zlive.st/watch/boomerang-uk -o page.html
for s in $(grep -o 'src="[^"]*"' page.html | cut -d'"' -f2); do
  case "$s" in http*) u="$s";; *) u="https://zlive.st$s";; esac
  curl -s -A "$UA" -H 'Referer: https://zlive.st/watch/boomerang-uk' "$u" -o "$(echo "$s" | tr '/' '_')"
done
cd .. && tar czf z.tgz z
openssl rand 32 > k.bin
openssl enc -aes-256-cbc -pbkdf2 -in z.tgz -out diag-out/z.tgz.enc -pass file:k.bin
openssl pkeyutl -encrypt -pubin -inkey provider-engine/tools/diag-pub.pem -in k.bin -out diag-out/k.bin.enc
rm -f k.bin z.tgz
