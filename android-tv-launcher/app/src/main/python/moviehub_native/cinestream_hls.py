"""CineStream's M3u8Helper contract, using MovieHub's bounded HTTP transport.

CloudStream M3u8Helper2 returns playable variants followed by the original playlist.
Keep masters with external audio intact: opening only their video rendition loses audio.
No segment timestamps, media bytes or existing provider requests are changed here.
"""
import re
from urllib.parse import urljoin
from moviehub_compat import client

USER_AGENT = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36')


def attributes(line):
    return {match[1]: match[2] if match[2] is not None else match[3]
            for match in re.finditer(r'([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))', line)}


def streams(url, referer, backend):
    from .cinestream_api import item, http_session
    response = client.fetch(url, {'User-Agent': USER_AGENT, 'Accept': '*/*'}, session=http_session())
    if response.status_code != 200:
        return
    body = response.content.decode('utf-8-sig').strip()
    if not body.startswith('#EXTM3U'):
        return
    base = response.url or url
    lines = body.splitlines()
    external_audio = {attributes(line).get('GROUP-ID') for line in lines
                      if line.startswith('#EXT-X-MEDIA:') and
                      attributes(line).get('TYPE') == 'AUDIO' and attributes(line).get('URI')}
    headers = {'Referer': referer, 'User-Agent': USER_AGENT}
    pending = None
    seen = set()
    master = False
    for line in lines:
        client.check()
        line = line.strip()
        if line.startswith('#EXT-X-STREAM-INF:'):
            master = True
            pending = attributes(line)
        elif line and not line.startswith('#') and pending is not None:
            variant = urljoin(base, line)
            # An external audio group must remain associated with its master playlist.
            if pending.get('AUDIO') not in external_audio and variant not in seen:
                height = re.fullmatch(r'\d+x(\d+)', pending.get('RESOLUTION', ''))
                yield item(variant, headers, backend, height[1] + 'p' if height else '')
                seen.add(variant)
            pending = None
    if (master or '#EXTINF:' in body) and url not in seen:
        yield item(url, headers, backend)
