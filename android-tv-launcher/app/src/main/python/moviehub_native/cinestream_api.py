"""Additional CineStream 487 protocols, adapted from CSX (GPL-3.0-or-later).

Upstream: SaurabhKaperwan/CSX@13903aa, CineStreamExtractors.kt. No downloaded code is
executed. The upstream enc-dec service is a data API; URLs and headers still pass MovieHub's
existing public-address, cancellation, response-size and media validation gates.
"""
import base64
import json
import re
import threading
from urllib.parse import quote, urlencode, urljoin, urlparse
from moviehub_compat import client
from moviehub_scrubs import dom_parser
from .rive import positive
from .cinestream_hls import streams as hls_streams, USER_AGENT

_HTTP = threading.local()


def http_session():
    return getattr(_HTTP, 'session', None)


DECODER = 'https://enc-dec.app/api'
DESKTOP_138 = ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
               '(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36')


def request(url, headers=None, **kwargs):
    # CloudStream's app client supplies its desktop profile even when an extractor
    # omits headers. Keep this local; existing MovieHub scrapers retain their own UA.
    supplied = dict(headers or {})
    if not any(key.lower() == 'user-agent' for key in supplied):
        supplied['User-Agent'] = USER_AGENT
    return client.request(url, headers=supplied, session=http_session(), **kwargs)


def get(url, headers=None):
    return request(url, headers=headers, output='json')


def post_json(url, data, headers=None):
    headers = dict(headers or {}, **{'Content-Type': 'application/json'})
    return request(url, headers=headers, post=json.dumps(data), output='json')


def item(url, headers, backend, quality='', info=''):
    if not isinstance(url, str) or not url:
        return None
    client.safe(url)
    allowed = {'referer', 'origin', 'user-agent', 'cookie', 'accept', 'authorization',
               'x-csrf-token', 'x-requested-with'}
    clean = {k: v for k, v in headers.items() if k.lower() in allowed and isinstance(v, str)}
    session = http_session()
    cookie = (client.requests.cookies.get_cookie_header(session.cookies,
              client.requests.Request('GET', url).prepare()) if session is not None else None)
    if cookie:
        explicit = next((value for key, value in clean.items() if key.lower() == 'cookie'), '')
        clean = {key: value for key, value in clean.items() if key.lower() != 'cookie'}
        clean['Cookie'] = '; '.join(value for value in (explicit, cookie) if value)
    return dict(url=url + '|' + urlencode(clean), direct=True, source=backend,
                originProvider=backend, quality=quality or 'Auto', info=info)


class Base:
    requires_imdb = False

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        if not positive(tmdb) or (self.requires_imdb and not imdb):
            return None
        if imdb and not re.fullmatch(r'tt\d+', str(imdb)):
            return None
        return json.dumps(dict(imdb=imdb or '', tmdb=str(tmdb), title=title, year=str(year)))

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return self.movie(imdb, tmdb, title, localtitle, aliases, year)

    def episode(self, key, imdb, tmdb, tvdb, title, premiered, season, episode):
        if key is None or not positive(season) or not positive(episode):
            return None
        data = json.loads(key)
        data.update(season=str(season), episode=str(episode))
        return json.dumps(data)

    def resolve(self, url):
        return url

    def sources(self, key, hosts):
        if key is None:
            return []
        previous = http_session()
        session = client.requests.Session()
        session.trust_env = False
        _HTTP.session = session
        try:
            return [row for row in self.extract(json.loads(key)) if row is not None]
        finally:
            _HTTP.session = previous
            session.close()

    def headers(self):
        return {'User-Agent': USER_AGENT, 'Referer': self.base_link + '/'}

    @staticmethod
    def route(q):
        return ('tv/' + q['tmdb'] + '/' + q['season'] + '/' + q['episode']
                if 'season' in q else 'movie/' + q['tmdb'])

    def each(self, names, callback):
        # A refused backend must not suppress the other independent backends.
        for name in names:
            client.check()
            try:
                yield from callback(name)
            except Exception as error:
                label = name.get('name', 'server') if isinstance(name, dict) else name[0] if isinstance(name, tuple) else name
                client.host_result(type(self).__name__ + ' ' + str(label), type(error).__name__)


class VaPlayer(Base):
    requires_imdb = True
    base_link = 'https://streamdata.vaplayer.ru'

    def extract(self, q):
        args = dict(imdb=q['imdb'], type='tv' if 'season' in q else 'movie')
        if 'season' in q:
            args.update(season=q['season'], episode=q['episode'])
        headers = {'Referer': 'https://nextgencloudfabric.com/', 'User-Agent': USER_AGENT}
        data = get(self.base_link + '/api.php?' + urlencode(args), headers)
        def backend(url):
            yield from hls_streams(url, headers['Referer'], 'VaPlayer')
        yield from self.each((data.get('data') or {}).get('stream_urls') or [], backend)


class PrimeSrc(Base):
    base_link = 'https://primesrc.me'

    def extract(self, q):
        args = dict(type='tv' if 'season' in q else 'movie')
        args['imdb' if q['imdb'] else 'tmdb'] = q['imdb'] or q['tmdb']
        if 'season' in q:
            args.update(season=q['season'], episode=q['episode'])
        data = request(self.base_link + '/api/v1/s?' + urlencode(args),
                              headers=self.headers(), output='json', timeout=30)
        def backend(server):
            args = {'key': server['key']}
            if server.get('token'):
                args['token'] = server['token']
            link = get(self.base_link + '/api/v1/l?' + urlencode(args), self.headers()).get('link')
            if link:
                # Hosted pages still go through the existing headless ResolveURL runtime.
                filename = server.get('file_name') if isinstance(server.get('file_name'), str) else ''
                yield dict(url=client.safe(link), direct=False, source=str(server.get('name') or 'PrimeWire'),
                           quality=str(server.get('quality') or 'Auto'), filename=filename,
                           info=' '.join(str(v) for v in (filename, server.get('file_size')) if v))
        yield from self.each(data.get('servers', [])[:32], backend)


class Fshare(Base):
    requires_imdb = True
    base_link = 'https://fsharetv.cc'

    def extract(self, q):
        if 'season' in q:
            return
        slug = re.sub(r'[^a-z0-9]+', '-', (q['title'] + ' episode 1 ' + q['imdb']).lower()).strip('-')
        page_url = self.base_link + '/w/' + slug
        page = request(page_url)
        token = re.search(r"Movie\.setSource\('([^']+)'", page)
        trailers = dom_parser.parse_dom(page, 'input', {'id': 'trailer'}, req='value')
        trailer = trailers[0].attrs['value'] if trailers else None
        if not token or trailer is None:
            return
        data = get(self.base_link + '/api/file/' + quote(token[1], safe='') + '/source?' +
                   urlencode(dict(trailer=trailer, type='watch')))
        file = data.get('data', {}).get('file', {})
        rows = file.get('sources', []) + [r for group in file.get('alternatives', []) for r in group]
        for row in rows[:64]:
            yield item(urljoin(self.base_link, row['src']), {'Referer': page_url, 'User-Agent': DESKTOP_138}, 'Fshare', str(row.get('quality') or ''))


class Videasy(Base):
    base_link = 'https://www.vidy.st'
    api = 'https://api.wecollege.net'
    # Boise and Miami are already provided by Movy. Keep its implementation intact.
    backends = ('vegas', 'reno', 'oakland', 'seattle', 'phoenix', 'atlanta', 'portland',
                'austin', 'dallas', 'tampa', 'orlando', 'munich', 'berlin', 'paris',
                'delhi', 'cancun')

    def extract(self, q):
        from .movy import decode
        headers = {'Origin': 'https://vidy.st', 'Referer': 'https://vidy.st/',
                   'User-Agent': USER_AGENT, 'Accept': '*/*'}
        def backend(name):
            seed = get(self.api + '/seed?' + urlencode(dict(mediaId=q['tmdb'])), headers)['seed']
            if not isinstance(seed, str) or not seed.isascii() or len(seed) > 256:
                raise ValueError('Invalid Vidy seed')
            args = dict(title=quote(q['title'], safe=''), mediaType='tv' if 'season' in q else 'movie',
                        year=q['year'], tmdbId=q['tmdb'], imdbId=q['imdb'], enc=2, seed=seed)
            if 'season' in q:
                args.update(episodeId=q['episode'], seasonId=q['season'])
            if name == 'munich':
                args['language'] = 'german'
            encrypted = request(self.api + '/' + name + '/sources?' + urlencode(args), headers=headers)
            result = decode(encrypted, seed, q['tmdb'])
            for row in result.get('sources', [])[:32]:
                if not isinstance(row, dict) or row.get('drm'):
                    continue
                claim = str(row.get('quality') or '')
                if name in ('austin', 'delhi') and claim != {'austin': 'English', 'delhi': 'Hindi'}[name]:
                    continue
                quality = claim if re.fullmatch(r'(?:2160|1080|720|480|360)p', claim) else ''
                yield item(row.get('url'), headers, 'Vidy ' + name.capitalize(), quality,
                           info=claim if claim and not quality else '')
        names = self.backends
        audit = getattr(self, 'cinestream_audit_backends', '')
        if isinstance(audit, str) and audit:
            requested = set(audit.split(','))
            names = tuple(name for name in names if name in requested)
        yield from self.each(names, backend)


class Meowtv(Base):
    base_link = 'https://meowtv.ru'
    api = 'https://api.meowtv.ru'

    def extract(self, q):
        headers = dict(self.headers(), Origin=self.base_link)
        def backend(name):
            # The guest ticket is single use and bound to this session's UA. It is
            # sent only to Meow's API, never to the decoder or media hosts.
            args = dict(s=name)
            if name == 'acme':
                args['list'] = 1
            for attempt in range(2):
                ticket = post_json(self.api + '/streams/ticket', {}, headers)['ticket']
                api_headers = dict(headers, **{'x-stream-ticket': ticket})
                data = request(self.api + '/streams/' + self.route(q) + '?' + urlencode(args),
                               headers=api_headers, output='json', timeout=30, redirect=False)
                error = str(data.get('error') or data.get('message') or '')
                if not attempt and re.search(r'ticket|signature|UA mismatch', error, re.I):
                    continue
                if error:
                    raise ValueError('Meow stream request rejected')
                break
            result = post_json(DECODER + '/dec-meowtv', dict(data=data)).get('result') or {}
            for row in (result.get('streams') or [result])[:32]:
                media_headers = dict(headers)
                supplied = row.get('headers') or {}
                if isinstance(supplied, dict):
                    for key, value in supplied.items():
                        if key.lower() in ('referer', 'origin', 'user-agent', 'accept') and isinstance(value, str):
                            media_headers = {k: v for k, v in media_headers.items() if k.lower() != key.lower()}
                            media_headers[key] = value
                yield item(row.get('url'), media_headers, 'Meowtv ' + name,
                           info=str(row.get('language') or ''))
        names = ('dcloud', 'doppler', 'acme', 'tik', 'ipcloud', 'hindi', 'english')
        if 'season' not in q:
            names += ('turkce',)
        yield from self.each(names, backend)


class TokenServers(Base):
    """Current server protocol: bootstrap, rotated CSRF token, server list, stream."""
    decoder = ''

    def request_headers(self):
        headers = self.headers()
        if self.decoder == 'vidfast':
            headers['User-Agent'] = DESKTOP_138
        else:
            headers['X-Requested-With'] = 'XMLHttpRequest'
        return headers

    def extract(self, q):
        path = self.base_link + '/' + self.route(q) + '/'
        page = request(path, headers=self.request_headers() if self.decoder == 'vidfast' else None)
        token = re.search(r'\\"(?:en|token)\\":\\"(.*?)\\"', page)
        if not token:
            return
        initial = get(DECODER + '/enc-' + self.decoder + '?' + urlencode(dict(text=token[1], stage=1)),
                      self.request_headers() if self.decoder == 'vidup' else None)
        if self.decoder == 'vidup' and initial.get('status') != 200:
            return
        result = initial.get('result')
        if not isinstance(result, dict):
            return
        stage1 = client.safe(result['stage1'])
        if urlparse(stage1).netloc != urlparse(self.base_link).netloc:
            raise ValueError('Bootstrap token origin changed')
        headers = dict(self.request_headers(), **{'X-CSRF-Token': result['token']})
        text = request(stage1, headers=headers, post='', redirect=False)
        result = get(DECODER + '/enc-' + self.decoder + '?' + urlencode(dict(text=text, stage=2))).get('result')
        if not isinstance(result, dict):
            return
        if urlparse(result['servers']).netloc != urlparse(self.base_link).netloc:
            raise ValueError('Server token origin changed')
        headers['X-CSRF-Token'] = result['token']
        encrypted = request(client.safe(result['servers']), headers=headers, post='', redirect=False)
        decoded = post_json(DECODER + '/dec-' + self.decoder, dict(text=encrypted))
        if self.decoder == 'vidup' and decoded.get('status') != 200:
            return
        servers = decoded.get('result')
        if not isinstance(servers, list):
            return
        def backend(server):
            stream_url = result['stream'].rstrip('/') + '/' + quote(str(server['data']), safe='')
            # Do not send a site's CSRF token to a third-party decoder result origin.
            if urlparse(stream_url).netloc != urlparse(result['servers']).netloc:
                raise ValueError('Stream token origin changed')
            encrypted = request(client.safe(stream_url), headers=headers, post='', redirect=False)
            decoded = post_json(DECODER + '/dec-' + self.decoder, dict(text=encrypted))
            if self.decoder == 'vidup' and decoded.get('status') != 200:
                return
            stream = decoded.get('result') or {}
            if not stream.get('url'):
                return
            label = self.decoder + ' ' + str(server.get('name') or 'server')
            if self.decoder == 'vidcore':
                yield from hls_streams(stream['url'], self.base_link + '/', label)
            else:
                media_headers = headers if self.decoder == 'vidfast' else {'Referer': self.base_link + '/', 'User-Agent': USER_AGENT}
                yield item(stream['url'], media_headers, label, info=str(server.get('description') or ''))
        yield from self.each(servers[:32], backend)


class Vidup(TokenServers):
    base_link = 'https://vidup.to'
    decoder = 'vidup'


class Vidcore(TokenServers):
    base_link = 'https://vidcore.io'
    decoder = 'vidcore'


class Vidfast(TokenServers):
    base_link = 'https://vidfast.vc'
    decoder = 'vidfast'


class OneTouchTv(Base):
    base_link = 'https://api3.devcorp.me'

    def extract(self, q):
        title = q['title'] + (' Season ' + q['season'] if q.get('season', '1') != '1' else '') + ' (' + q['year'] + ')'
        encrypted = request(self.base_link + '/vod/search?' + urlencode(dict(page=1, keyword=title)))
        rows = post_json(DECODER + '/dec-onetouchtv', dict(text=encrypted)).get('result') or []
        match = next((r for r in rows if r.get('title', '').casefold() == title.casefold()), None)
        if not match:
            return
        path = '/web/vod/' + quote(str(match['id']), safe='') + '/episode/' + q.get('episode', '0')
        encrypted = request(self.base_link + path)
        data = post_json(DECODER + '/dec-onetouchtv', dict(text=encrypted)).get('result') or {}
        for row in data.get('sources', [])[:32]:
            yield item(row.get('url'), row.get('headers') or {}, 'OneTouchTv', str(row.get('quality') or ''))


class Cinejoy(Base):
    base_link = 'https://cinejoy.pk'
    api = 'https://api.wing.st'

    def extract(self, q):
        headers = dict(self.headers(), Origin=self.base_link, Accept='*/*')
        servers = get(self.api + '/servers', headers).get('servers') or []
        def backend(server):
            args = dict(title=q['title'], type='series' if 'season' in q else 'movie', year=q['year'],
                        imdb=q['imdb'], tmdb=q['tmdb'], server=server['name'])
            if 'season' in q:
                args.update(season=q['season'], episode=q['episode'])
            target = self.api + '/?' + urlencode(args)
            encoded = get(DECODER + '/enc-cinejoy?' + urlencode(dict(url=target)))['result']
            body = base64.urlsafe_b64decode(encoded['data'] + '===')
            encrypted = request(self.api + '/g', headers=dict(headers, **{'Content-Type': 'application/octet-stream'}),
                                       post=body, as_bytes=True)
            clear = post_json(DECODER + '/dec-cinejoy', dict(
                text=base64.urlsafe_b64encode(encrypted).decode().rstrip('='), state=encoded['state']))
            data = clear.get('result', {}).get('data', {})
            for stream in data.get('stream', [])[:32]:
                if stream.get('type') == 'hls':
                    yield item(stream.get('playlist'), {'Referer': self.base_link + '/'}, 'Cinejoy ' + server['name'])
                elif stream.get('type') == 'file':
                    for quality, row in (stream.get('qualities') or {}).items():
                        grade = '2160p' if quality == '4k' else str(quality) + 'p' if str(quality).isdigit() else ''
                        yield item(row.get('url'), {'Referer': self.base_link + '/'}, 'Cinejoy ' + server['name'], grade)
        yield from self.each(servers[:32], backend)


class Hexa(Base):
    base_link = 'https://hexa.su'
    api = 'https://theemoviedb.hexa.su'

    def extract(self, q):
        import secrets
        key = secrets.token_hex(32)
        token = get(DECODER + '/enc-hexa')['result']['token']
        path = ('/api/tmdb/tv/' + q['tmdb'] + '/season/' + q['season'] + '/episode/' + q['episode'] + '/images'
                if 'season' in q else '/api/tmdb/movie/' + q['tmdb'] + '/images')
        headers = dict(self.headers(), **{'Accept': 'text/plain', 'X-Api-Key': key,
                       'X-Fingerprint-Lite': 'e9136c41504646444', 'X-Cap-Token': token})
        encrypted = request(self.api + path, headers=headers)
        result = post_json(DECODER + '/dec-hexa', dict(text=encrypted, key=key)).get('result') or {}
        def backend(row):
            if not getattr(self, 'cinestream_audit', False) and str(row.get('server')).lower() != 'alpha':
                return
            yield from hls_streams(row['url'], self.base_link + '/', 'Hexa ' + str(row.get('server') or 'server'))
        yield from self.each(result.get('sources') or [], backend)


class AllMovieLand(Base):
    requires_imdb = True
    base_link = 'https://allmovieland.one'

    def extract(self, q):
        script = request('https://allmovieland.link/player.js?v=60%20128')
        host_match = re.search(r"const AwsIndStreamDomain[^\n]*?'(https://[^']+)'", script)
        if not host_match:
            return
        host = client.safe(host_match[1])
        page = request(host.rstrip('/') + '/play/' + q['imdb'], referer=self.base_link + '/')
        match = re.search(r'\bplaylist\s*[:=]\s*', page)
        if not match:
            return
        data, _ = json.JSONDecoder().raw_decode(page[match.end():].lstrip().lstrip('(').lstrip())
        if not isinstance(data, dict) or not isinstance(data.get('key'), str):
            return
        headers = dict(self.headers(), **{'X-CSRF-TOKEN': data['key']})
        catalogue = urljoin(host, data['file'])
        if urlparse(catalogue).netloc != urlparse(host).netloc:
            raise ValueError('Catalogue token origin changed')
        raw = request(catalogue, headers=headers)
        rows = json.loads(re.sub(r',\s*\[\]', '', raw))
        if 'season' in q:
            season = next((r for r in rows if str(r.get('id')) == q['season']), {})
            episode = next((r for r in season.get('folder', []) if str(r.get('episode')) == q['episode']), {})
            rows = episode.get('folder', [])
        def backend(row):
            path = host.rstrip('/') + '/playlist/' + quote(str(row['file']), safe='') + '.txt'
            url = request(path, headers=headers, post='', redirect=False).strip()
            yield item(url, self.headers(), 'AllMovieLand ' + str(row.get('title') or 'server'))
        yield from self.each(rows[:32], backend)
