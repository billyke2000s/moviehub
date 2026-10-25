#!/usr/bin/env bash
# Diagnostic only: resolve one channel as exp-14 does, then report segment sizes and the stream's
# real resolution/bitrate with ffprobe. Prints no tokens or full URLs.
set -u
loc=$(node provider-engine/tools/zlive-diagnose.mjs "${1:-boomerang-uk}" --print-location 2>/dev/null | grep '^LOCATION ' | cut -d' ' -f2-)
[ -z "$loc" ] && { echo "::error title=media::no location"; exit 0; }
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
pl=$(curl -s -A "$UA" -H 'Referer: https://zlive.st/' -H 'Origin: https://zlive.st' "$loc")
echo "::notice title=playlist::lines=$(echo "$pl" | wc -l) entries=$(echo "$pl" | grep -vc '^#') tags: $(echo "$pl" | grep '^#EXT' | cut -d: -f1 | sort | uniq -c | tr '\n' ' ')"
sizes=""
for u in $(echo "$pl" | grep -v '^#' | grep . | head -4); do
  case "$u" in http*) ;; *) u="$(dirname "$loc")/$u";; esac
  curl -s -A "$UA" -H 'Referer: https://zlive.st/' -H 'Origin: https://zlive.st' -o seg.bin "$u"
  sizes="$sizes $(stat -c %s seg.bin)B"
done
echo "::notice title=segment sizes::$sizes"
probe=$(ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,bit_rate,r_frame_rate:format=duration,bit_rate -of compact seg.bin 2>&1 | tr '\n' ' ')
echo "::notice title=ffprobe last segment::$probe"
ffmpeg -v error -i seg.bin -vf "select=eq(n\,0),scale=64:-1,format=gray" -frames:v 1 -f rawvideo - 2>/dev/null | od -An -tu1 -v | tr -s ' ' '\n' | awk 'NF{n++;s+=$1;if($1>200)w++} END{printf "::notice title=frame brightness::pixels=%d mean=%.0f bright(>200)=%.0f%%\n", n, s/n, 100*w/n}'
ffmpeg -v error -i seg.bin -vf "freezedetect=n=0.001:d=2" -map 0:v -f null - 2>&1 | grep -c freeze_start | xargs -I{} echo "::notice title=frozen picture (2s+ still)::{} freeze periods"
