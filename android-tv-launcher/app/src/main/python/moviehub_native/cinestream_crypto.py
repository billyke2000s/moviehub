"""Public player data decoders from CineStream 487/CSX@13903aa (GPL-3.0-or-later).

Constants below are published player-format keys, never account credentials. Authentication
failures remain failures; no access checks, paid locks or transport restrictions are removed.
"""
import base64
import json
import re
from urllib.parse import quote, parse_qs, urlencode, urlparse
from moviehub_compat import client
from resolveurl.lib.aesgcm import python_aesgcm
from resolveurl.lib import helpers
from .cinestream_api import Base, get, item, USER_AGENT, request

VIDROCK_FORMAT_KEY = bytes.fromhex('7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f')
PEACHIFY_FORMAT_KEY = bytes.fromhex('a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d')
# Published locator-format key in Vidzee's streams-BHpSC3gU.js WebAssembly data.
# This decrypts the public JSON locator, not encrypted video or a DRM licence.
VIDZEE_STREAM_KEY = bytes.fromhex('e4f9b27d8c1a6ef5037db98ac54e21f0b9d6c3a781fe42ad65c0e9b73f148a2d')


def b64(value):
    if not isinstance(value, str) or len(value) > client.MAX_BODY:
        raise ValueError('Invalid encoded player data')
    return base64.urlsafe_b64decode(value + '===')


def gcm(key, iv, encrypted):
    if len(iv) != 12 or len(encrypted) < 16 or len(encrypted) > client.MAX_BODY:
        raise ValueError('Invalid player data length')
    clear = python_aesgcm.new(bytearray(key)).open(bytearray(iv), bytearray(encrypted), bytearray())
    if clear is None:
        raise ValueError('Invalid player authentication tag')
    return bytes(clear).decode('utf-8')


class Vidrock(Base):
    base_link = 'https://vidrock.ru'

    def extract(self, q):
        kind = 'tv' if 'season' in q else 'movie'
        identity = q['tmdb'] + ('_' + q['season'] + '_' + q['episode'] if 'season' in q else '')
        headers = dict(self.headers(), Origin=self.base_link)
        data = get(self.base_link + '/api/' + kind + '/' + identity + '/', headers)
        def backend(pair):
            name, row = pair
            encoded = row.get('url') if isinstance(row, dict) else None
            if encoded and encoded not in ('null', 'error'):
                raw = b64(encoded)
                url = gcm(VIDROCK_FORMAT_KEY, raw[:12], raw[12:])
                yield item(url, headers, 'Vidrock ' + name)
        yield from self.each(list(data.items())[:32], backend)


class Peachify(Base):
    base_link = 'https://peachify.top'
    api = 'https://x.eat-peach.sbs'

    def extract(self, q):
        headers = dict(self.headers(), Origin=self.base_link, **{'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36'})
        def backend(name):
            data = get(self.api + '/' + name + '/' + self.route(q), headers).get('data')
            if not isinstance(data, str):
                return
            parts = data.split('.')
            if len(parts) != 3:
                return
            clear = gcm(PEACHIFY_FORMAT_KEY, b64(parts[0]), b64(parts[1]) + b64(parts[2]))
            response = json.loads(clear)
            for row in response.get('sources', [])[:32]:
                url = row.get('url')
                if not isinstance(url, str):
                    continue
                source_headers = {k.lower(): v for k, v in (row.get('headers') or {}).items()}
                proxy_headers = {}
                # Upstream's proxy URLs explicitly carry the actual media URL and headers.
                if urlparse(url).path in ('/m3u8-proxy', '/mp4-proxy'):
                    args = parse_qs(urlparse(url).query)
                    url = args.get('url', [url])[0]
                    proxy_headers = {k.lower(): v for k, v in json.loads(args.get('headers', ['{}'])[0]).items()}
                defaults = {'referer': self.base_link + '/', 'origin': self.base_link, 'user-agent': USER_AGENT}
                media_headers = {key: proxy_headers.get(key, source_headers.get(key, default))
                                 for key, default in defaults.items()}
                yield item(url, media_headers, 'Peachify ' + name + ' · ' + str(response.get('providerName') or name),
                           str(row.get('quality') or ''), str(row.get('dub') or ''))
        yield from self.each(('multi', 'hr', 'holly', 'air', 'moviebox'), backend)


def vidzee_locator(value):
    if not isinstance(value, str) or not value or len(value) > 262144:
        raise ValueError('Invalid Vidzee locator')
    encoded = base64.b64decode(value + '=' * (-len(value) % 4), validate=True)
    # The current public client uses RC4 and discards the first 2048 keystream bytes.
    # Reuse the bundled data decoder; never download or execute player JavaScript/WASM.
    padded = base64.b64encode(bytes(2048) + encoded)
    clear = helpers.arc4(VIDZEE_STREAM_KEY, padded)[2048:].encode('latin1').decode('utf-8')
    data = json.loads(clear)
    if not isinstance(data, dict):
        raise ValueError('Invalid Vidzee locator object')
    return data


class Vidzee(Base):
    base_link = 'https://player.vidzee.wtf'
    api = 'https://core.vidzee.wtf'

    def extract(self, q):
        route = self.route(q)
        headers = self.headers()
        languages = []
        try:
            data = get(self.api + '/streams/languages/' + route, headers)
            rows = data.get('languages') if isinstance(data, dict) else data
            if isinstance(rows, list):
                languages = list(dict.fromkeys(row for row in rows[:16] if isinstance(row, str)
                    and re.fullmatch(r'[A-Za-z][A-Za-z -]{0,39}', row)))
        except Exception as error:
            client.host_result('Vidzee languages', type(error).__name__)
        servers = ['dcloud'] + ['v4:' + language for language in languages] + ['tik', 'v6:Hindi', 'v6:English']

        def backend(server):
            response = get(self.api + '/streams/' + route + '?s=' + quote(server, safe='') + '&e=1', headers)
            if not isinstance(response, dict):
                return
            if isinstance(response.get('c'), str) and not response.get('url'):
                response = vidzee_locator(response['c'])
            url = response.get('url')
            if not isinstance(url, str) or not url:
                return
            supplied = response.get('headers') or {}
            if not isinstance(supplied, dict):
                raise ValueError('Invalid Vidzee media headers')
            media_headers = dict(headers)
            for key, value in supplied.items():
                media_headers = {k: v for k, v in media_headers.items() if k.lower() != key.lower()}
                media_headers[key] = value
            label = 'Vidzee ' + server.replace('v4:', 'Acme ').replace('v6:', '')
            yield item(url, media_headers, label, info=str(response.get('language') or ''))
        yield from self.each(servers, backend)
