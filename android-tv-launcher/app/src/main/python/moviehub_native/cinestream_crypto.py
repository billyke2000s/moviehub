"""Public player data decoders from CineStream 487/CSX@13903aa (GPL-3.0-or-later).

Constants below are published player-format keys, never account credentials. Authentication
failures remain failures; no access checks, paid locks or transport restrictions are removed.
"""
import base64
import json
from urllib.parse import parse_qs, urlencode, urlparse
from moviehub_compat import client
from resolveurl.lib.aesgcm import python_aesgcm
from resolveurl.lib import pyaes
from .cinestream_api import Base, get, item, USER_AGENT

VIDROCK_FORMAT_KEY = bytes.fromhex('7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f')
PEACHIFY_FORMAT_KEY = bytes.fromhex('a8f2a1b5e9c470814f6b2c3a5d8e7f9c1a2b3c4d5e3f7a8b8cad1e2d0a4d5c5d')
VIDZEE_FORMAT_KEY = base64.b64decode('QTdrUDl4TTJRdjhMcjROejFIdDZZYzNCdzVKZjBEc1U=')


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
                request_headers = row.get('headers') or {}
                # Upstream's proxy URLs explicitly carry the actual media URL and headers.
                if urlparse(url).path in ('/m3u8-proxy', '/mp4-proxy'):
                    args = parse_qs(urlparse(url).query)
                    url = args.get('url', [url])[0]
                    request_headers = json.loads(args.get('headers', ['{}'])[0])
                media_headers = {'Referer': self.base_link + '/', 'Origin': self.base_link, 'User-Agent': USER_AGENT}
                for key, value in request_headers.items():
                    media_headers = {k: v for k, v in media_headers.items() if k.lower() != key.lower()}
                    media_headers[key] = value
                yield item(url, media_headers, 'Peachify ' + name + ' · ' + str(response.get('providerName') or name),
                           str(row.get('quality') or ''), str(row.get('dub') or ''))
        yield from self.each(('multi', 'hr', 'holly', 'air', 'moviebox'), backend)


class Vidzee(Base):
    base_link = 'https://player.vidzee.wtf'

    def extract(self, q):
        def backend(number):
            args = dict(id=q['tmdb'], sr=number)
            if 'season' in q:
                args.update(ss=q['season'], ep=q['episode'])
            response = get(self.base_link + '/api/server?' + urlencode(args))
            headers = {'User-Agent': USER_AGENT, 'referer': self.base_link + '/'}
            for key, value in (response.get('headers') or {}).items():
                headers = {k: v for k, v in headers.items() if k.lower() != key.lower()}
                headers[key] = value
            for row in response.get('url', [])[:32]:
                encoded = row.get('link')
                if not encoded:
                    continue
                iv, encrypted = b64(encoded).decode('ascii').split(':', 1)
                iv, encrypted = b64(iv), b64(encrypted)
                if len(iv) != 16 or len(encrypted) % 16 or len(encrypted) > client.MAX_BODY:
                    continue
                decoder = pyaes.Decrypter(pyaes.AESModeOfOperationCBC(VIDZEE_FORMAT_KEY, iv=iv))
                url = (decoder.feed(encrypted) + decoder.feed()).decode('utf-8')
                label = 'Vidzee ' + str(number) + ' · ' + str(row.get('name') or number)
                yield item(url, headers, label, info=str(row.get('lang') or ''))
        yield from self.each((0, 1, 2, 4, 5, 6, 7), backend)
