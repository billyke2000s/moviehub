"""Anonymous mobile MovieBox flow from Phisher StreamPlay 711 (GPL-3.0-or-later).

The public client-format signing parameters are shipped by upstream. No account
token is bundled. A visitor token lives only in this lookup's isolated session.
"""
import base64
import hashlib
import hmac
import json
import re
import time
import uuid
from urllib.parse import urlencode, urlparse

from moviehub_compat import client
from .cinestream_api import Base, http_session, item


AGENT = ('com.community.oneroom/50020132 (Linux; U; Android 14; en_IN; Pixel 8; '
         'Build/UD1A.230803.041; Cronet/145.0.7582.0)')
BUILD = '1790144723381916466.632cf3fe507b339bc757f117f85b4c4a'
# Public request-format key, not a MovieBox user's credential.
FORMAT_KEY = base64.b64decode(base64.b64decode(
    'NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=='))
HOSTS = ('https://apig.inmoviebox.com', 'https://api3.aoneroom.com')
PREFIX = '/wefeed-mobile-bff/'


def canonical(method, url, body, timestamp, content_type='application/json', accept='application/json'):
    parsed = urlparse(url)
    pairs = [part.partition('=')[::2] for part in parsed.query.split('&') if part]
    query = '&'.join(key + '=' + value for key, value in sorted(pairs, key=lambda pair: pair[0]))
    target = (parsed.path or '/') + ('?' + query if query else '')
    raw = body.encode('utf-8') if body is not None else None
    length = str(len(raw)) if raw is not None else ''
    digest = hashlib.md5(raw[:102400]).hexdigest() if raw is not None else ''
    return '\n'.join((method.upper(), accept, content_type, length, str(timestamp), digest, target))


def headers(method, url, body=None, token='', device='', timestamp=None):
    stamp = int(time.time() * 1000) if timestamp is None else timestamp
    content_type = 'application/json; charset=utf-8' if body is not None else 'application/json'
    signed = canonical(method, url, body, stamp, content_type)
    signature = base64.b64encode(hmac.new(FORMAT_KEY, signed.encode(), hashlib.md5).digest()).decode()
    info = dict(package_name='com.community.oneroom', version_name='4.0.03.0930.03',
                version_code=50020132, os='android', os_version='14', device_id=device,
                install_store='official', gaid='1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d',
                brand='Google', model='Pixel 8', system_language='en', net='NETWORK_WIFI',
                region='IN', timezone='Asia/Calcutta', sp_code='')
    result = {'User-Agent': AGENT, 'Accept': 'application/json', 'Content-Type': content_type,
              'x-client-token': str(stamp) + ',' + hashlib.md5(str(stamp)[::-1].encode()).hexdigest(),
              'x-tr-signature': str(stamp) + '|2|' + signature,
              'x-client-info': json.dumps(info, separators=(',', ':')),
              'x-client-status': '0', 'x-client-build': BUILD}
    if token:
        result['Authorization'] = 'Bearer ' + token
    return result


def data(response):
    value = response.get('data', response)
    return value.get('data', value) if isinstance(value, dict) else {}


def title(value):
    value = re.sub(r'\sS\d+(?:-S?\d+)*$', '', value, flags=re.I)
    value = re.sub(r'\s*\[[^\]]*\]\s*$', '', value)
    return ' '.join(re.findall(r'\w+', value.casefold()))


def matches(row, q):
    for field in ('imdbId', 'imdb_id'):
        if row.get(field) and str(row[field]) != q['imdb']:
            return False
    for field in ('tmdbId', 'tmdb_id'):
        if row.get(field) and str(row[field]) != str(q['tmdb']):
            return False
    if title(str(row.get('title') or row.get('name') or '')) != title(q['title']):
        return False
    year = str(row.get('releaseDate') or row.get('year') or '')[:4]
    return not year or year == str(q['year'])


class MobileMovieBox(Base):
    def fetch(self, host, route, body=None, token='', retry=True):
        client.check()
        url = host + PREFIX + route
        payload = json.dumps(body, ensure_ascii=False, separators=(',', ':')) if body is not None else None
        request_headers = headers('POST' if payload is not None else 'GET', url, payload,
                                  token=self.tokens.get(host, token) if token else '', device=self.device)
        response = client.fetch(url, request_headers, post=payload, timeout=12, session=http_session())
        if token and retry and response.status_code in (401, 441, 403, 407):
            self.tokens.pop(host, None)
            fresh = self.visitor(host)
            return self.fetch(host, route, body, fresh, retry=False)
        try:
            fresh = json.loads(response.headers.get('x-user') or '{}').get('token')
        except (ValueError, AttributeError):
            fresh = None
        if isinstance(fresh, str) and 0 < len(fresh) <= 8192:
            self.tokens[host] = fresh
        return response

    def visitor(self, host):
        response = self.fetch(host, 'tab/ranking-list?' + urlencode(dict(
            tabId=0, categoryType='4516404531735022304', page=1, perPage=1)))
        try:
            token = json.loads(response.headers.get('x-user') or '{}').get('token')
        except (ValueError, AttributeError):
            token = None
        if not token:
            token = data(self.fetch(host, 'user-api/visitor-login', {}).json()).get('token')
        if not isinstance(token, str) or not token or len(token) > 8192:
            raise ValueError('MovieBox did not issue a visitor token')
        return token

    def extract(self, q):
        self.device = str(uuid.uuid4())
        self.tokens = {}
        def backend(host):
            token = self.visitor(host)
            result = data(self.fetch(host, 'subject-api/search/v2', dict(
                page=1, perPage=20, keyword=q['title']), token).json())
            subjects = list(result.get('items') or [])
            for group in (result.get('results') or [])[:20]:
                if isinstance(group, dict) and isinstance(group.get('subjects'), list):
                    subjects.extend(group['subjects'][:24])
            candidates = [row for row in subjects[:120]
                          if isinstance(row, dict) and row.get('subjectId') and matches(row, q)]
            # Missing-year results cannot disambiguate multiple different subjects/remakes.
            if len({str(row['subjectId']) for row in candidates}) > 1:
                candidates = [row for row in candidates if str(row.get('releaseDate') or
                              row.get('year') or '')[:4] == str(q['year'])]
            seen = set()
            for row in candidates[:4]:
                identity = str(row['subjectId'])
                if identity in seen:
                    continue
                seen.add(identity)
                args = dict(subjectId=identity, se=q.get('season', '0'), ep=q.get('episode', '0'))
                reply = {}
                for version in ('play-info/v2', 'play-info'):
                    try:
                        route = 'subject-api/' + version + '?' + urlencode(args)
                        reply = data(self.fetch(host, route, token=token).json())
                        if reply.get('streams') or reply.get('downloads'):
                            break
                    except Exception as error:
                        client.host_result('MovieBox ' + version, type(error).__name__)
                for field, expected in (('se', args['se']), ('ep', args['ep']),
                                        ('subjectId', identity)):
                    if field in reply and str(reply[field]) != str(expected):
                        raise ValueError('MovieBox returned another title or episode')
                streams = reply.get('streams') or reply.get('downloads') or []
                for stream in streams[:32]:
                    if not isinstance(stream, dict) or stream.get('vipLocked') or stream.get('drm'):
                        continue
                    url = stream.get('url')
                    if not isinstance(url, str):
                        continue
                    # API authorization is never forwarded to a file or CDN.
                    media_headers = {'User-Agent': AGENT}
                    for key, value in (stream.get('headers') or {}).items():
                        if key.casefold() in ('referer', 'origin', 'user-agent') and isinstance(value, str):
                            media_headers[key] = value
                    yield item(url, media_headers, 'MovieBox MP4 ' + str(stream.get('format') or ''),
                               str(stream.get('resolution') or stream.get('resolutions') or ''),
                               info=str(row.get('lanName') or row.get('language') or 'Original'))
        yield from self.each(HOSTS, backend)
