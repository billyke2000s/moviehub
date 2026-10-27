"""Atlantic's public Moscow route, reusing Android's built-in authenticated cipher."""
import json
from urllib.parse import urlencode

from moviehub_compat import client
from moviehub_compat.current_hosts import direct
from .rive import positive


def decode(value):
    if not value.startswith('ms_'):
        return value
    # This is the public player's response-envelope key, not an account credential.
    key = bytes.fromhex('55060a042823f51a94c296894a58a0db15f3baef807155140983083fa799ef47')
    data = bytes.fromhex(value[3:])
    if len(data) < 28 or len(data) > 16384:
        raise ValueError('Invalid Moscow payload')
    from java import jclass
    cipher = jclass('javax.crypto.Cipher').getInstance('AES/GCM/NoPadding')
    secret = jclass('javax.crypto.spec.SecretKeySpec')(key, 'AES')
    parameters = jclass('javax.crypto.spec.GCMParameterSpec')(128, data[:12])
    cipher.init(2, secret, parameters)
    return bytes(cipher.doFinal(data[12:])).decode('utf-8')


class source:
    base_link = 'https://atlantic.st'
    api = 'https://stream.hls.lol/moscow'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return json.dumps(dict(tmdbId=str(tmdb), type='movie')) if positive(tmdb) else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return json.dumps(dict(tmdbId=str(tmdb), type='tv')) if positive(tmdb) else None

    def episode(self, key, imdb, tmdb, tvdb, title, premiered, season, episode):
        if key is None or not str(season).isdigit() or not positive(episode):
            return None
        query = json.loads(key)
        query.update(seasonId=str(season), episodeId=str(episode))
        return json.dumps(query)

    def sources(self, key, hosts):
        if key is None:
            return []
        response = client.request(self.api + '?' + urlencode(json.loads(key)),
                                  referer=self.base_link + '/', output='json')
        rows = response.get('sources', {}) if isinstance(response, dict) else {}
        row = rows.get('Moscow', {}) if isinstance(rows, dict) else {}
        value = row.get('url') if isinstance(row, dict) else None
        if not isinstance(value, str) or row.get('drm'):
            return []
        url = client.safe(decode(value))
        # The audit found a 1080-class stream; read each returned playlist's actual
        # resolution through the existing media probe rather than assigning 4K.
        item = direct(url, self.base_link + '/', 'Moscow', 'Auto')
        # Moscow's TS segments are served as text/html, even during zero-range
        # reads. Use the existing opt-in MIME/container handling, as Citadel does.
        item['segmentContainers'] = True
        return [item]

    def resolve(self, url):
        return url
