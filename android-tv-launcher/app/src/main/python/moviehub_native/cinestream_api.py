"""Additional CineStream 487 protocols, adapted from CSX (GPL-3.0-or-later).

Upstream: SaurabhKaperwan/CSX@13903aa, CineStreamExtractors.kt. No downloaded code is
executed. The upstream enc-dec service is a data API; URLs and headers still pass MovieHub's
existing public-address, cancellation, response-size and media validation gates.
"""
import base64
import json
import re
from urllib.parse import quote, urlencode, urljoin, urlparse
from moviehub_compat import client
from moviehub_scrubs import dom_parser
from .rive import positive
from .cinestream_hls import streams as hls_streams, USER_AGENT

DECODER = 'https://enc-dec.app/api'
DESKTOP_138 = ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
               '(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36')


def get(url, headers=None):
    return client.request(url, headers=headers, output='json')


def post_json(url, data, headers=None):
    headers = dict(headers or {}, **{'Content-Type': 'application/json'})
    return client.request(url, headers=headers, post=json.dumps(data), output='json')


def item(url, headers, backend, quality='', info=''):
    if not isinstance(url, str) or not url:
        return None
    client.safe(url)
    allowed = {'referer', 'origin', 'user-agent', 'cookie', 'accept', 'authorization',
               'x-csrf-token', 'x-requested-with'}
    clean = {k: v for k, v in headers.items() if k.lower() in allowed and isinstance(v, str)}
    return dict(url=url + '|' + urlencode(clean), direct=True, source=backend,
                originProvider=backend, quality=quality or 'Auto', info=info)


class Base:
    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        if not positive(tmdb) or not re.fullmatch(r'tt\d+', str(imdb)):
            return None
        return json.dumps(dict(imdb=imdb, tmdb=str(tmdb), title=title, year=str(year)))

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
        return [row for row in self.extract(json.loads(key)) if row is not None]

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
        args = dict(imdb=q['imdb'], type='tv' if 'season' in q else 'movie')
        if 'season' in q:
            args.update(season=q['season'], episode=q['episode'])
        data = client.request(self.base_link + '/api/v1/s?' + urlencode(args),
                              headers=self.headers(), output='json', timeout=30)
        def backend(server):
            link = get(self.base_link + '/api/v1/l?' + urlencode({'key': server['key']}), self.headers()).get('link')
            if link:
                # Hosted pages still go through the existing headless ResolveURL runtime.
                filename = server.get('file_name') if isinstance(server.get('file_name'), str) else ''
                yield dict(url=client.safe(link), direct=False, source=str(server.get('name') or 'PrimeWire'),
                           quality=str(server.get('quality') or 'Auto'), filename=filename,
                           info=' '.join(str(v) for v in (filename, server.get('file_size')) if v))
        yield from self.each(data.get('servers', [])[:32], backend)


class Fshare(Base):
    base_link = 'https://fsharetv.cc'

    def extract(self, q):
        if 'season' in q:
            return
        slug = re.sub(r'[^a-z0-9]+', '-', (q['title'] + ' episode 1 ' + q['imdb']).lower()).strip('-')
        page_url = self.base_link + '/w/' + slug
        page = client.request(page_url)
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
    base_link = 'https://player.videasy.to'
    api = 'https://api.speedracelight.com'
    backends = ('myflixerzupcloud', 'downloader2', 'm4uhd', 'hdmovie', 'cdn', 'superflix',
                'lamovie', 'jett', 'tejo', 'neon2', 'ym')

    def extract(self, q):
        headers = dict(self.headers(), Origin=self.base_link, Accept='*/*')
        seed = get(self.api + '/seed?' + urlencode(dict(mediaId=q['tmdb'])), headers)['seed']
        def backend(name):
            args = dict(title=quote(q['title'], safe=''), mediaType='tv' if 'season' in q else 'movie',
                        year=q['year'], tmdbId=q['tmdb'], imdbId=q['imdb'], enc=2, seed=seed)
            if 'season' in q:
                args.update(episodeId=q['episode'], seasonId=q['season'])
            encrypted = client.request(self.api + '/' + name + '/sources-with-title?' + urlencode(args), headers=headers)
            result = post_json(DECODER + '/dec-videasy', dict(text=encrypted, id=int(q['tmdb']), seed=seed))['result']
            for row in result.get('sources', [])[:32]:
                yield item(row.get('url'), headers, 'Videasy ' + name, str(row.get('quality') or ''))
        yield from self.each(self.backends, backend)


class Meowtv(Base):
    base_link = 'https://meowtv.ru'
    api = 'https://api.meowtv.ru'

    def extract(self, q):
        headers = dict(self.headers(), Origin=self.base_link)
        def backend(name):
            data = get(self.api + '/streams/' + self.route(q) + '?' + urlencode(dict(s=name)), headers)
            result = post_json(DECODER + '/dec-meowtv', dict(data=data)).get('result') or {}
            yield item(result.get('url'), headers, 'Meowtv ' + name, info=str(result.get('language') or ''))
        yield from self.each(('ipcloud', 'dcloud', 'tik', 'turkce', 'hindiv3'), backend)


class TokenServers(Base):
    """Vidup, Vidcore and Vidfast use the same published two-stage server protocol."""
    decoder = ''

    def request_headers(self):
        headers = self.headers()
        if self.decoder == 'vidfast':
            headers['User-Agent'] = DESKTOP_138
        else:
            headers['X-Requested-With'] = 'XMLHttpRequest'
        return headers

    def extract(self, q):
        path = self.base_link + '/' + self.route(q)
        if self.decoder == 'vidfast':
            path += '/'
        page = client.request(path, headers=self.request_headers() if self.decoder == 'vidfast' else None)
        token = re.search(r'\\"(?:en|token)\\":\\"(.*?)\\"', page)
        if not token:
            return
        initial = get(DECODER + '/enc-' + self.decoder + '?' + urlencode(dict(text=token[1])),
                      self.request_headers() if self.decoder == 'vidup' else None)
        if self.decoder == 'vidup' and initial.get('status') != 200:
            return
        result = initial.get('result')
        if not isinstance(result, dict):
            return
        headers = dict(self.request_headers(), **{'X-CSRF-Token': result['token']})
        encrypted = client.request(client.safe(result['servers']), headers=headers, post='', redirect=False)
        decoded = post_json(DECODER + '/dec-' + self.decoder, dict(text=encrypted),
                            headers if self.decoder == 'vidcore' else None)
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
            encrypted = client.request(client.safe(stream_url), headers=headers, post='', redirect=False)
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
        encrypted = client.request(self.base_link + '/vod/search?' + urlencode(dict(page=1, keyword=title)))
        rows = post_json(DECODER + '/dec-onetouchtv', dict(text=encrypted)).get('result') or []
        match = next((r for r in rows if r.get('title', '').casefold() == title.casefold()), None)
        if not match:
            return
        path = '/web/vod/' + quote(str(match['id']), safe='') + '/episode/' + q.get('episode', '0')
        encrypted = client.request(self.base_link + path)
        data = post_json(DECODER + '/dec-onetouchtv', dict(text=encrypted)).get('result') or {}
        for row in data.get('sources', [])[:32]:
            yield item(row.get('url'), row.get('headers') or {}, 'OneTouchTv', str(row.get('quality') or ''))


class Cinejoy(Base):
    base_link = 'https://cinejoy.to'
    api = 'https://api.shegu.st'

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
            encrypted = client.request(self.api + '/g', headers=dict(headers, **{'Content-Type': 'application/octet-stream'}),
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
        encrypted = client.request(self.api + path, headers=headers)
        result = post_json(DECODER + '/dec-hexa', dict(text=encrypted, key=key)).get('result') or {}
        def backend(row):
            yield from hls_streams(row['url'], self.base_link + '/', 'Hexa ' + str(row.get('server') or 'server'))
        yield from self.each(result.get('sources') or [], backend)


class AllMovieLand(Base):
    base_link = 'https://allmovieland.one'

    def extract(self, q):
        script = client.request('https://allmovieland.link/player.js?v=60%20128')
        host_match = re.search(r"const AwsIndStreamDomain[^\n]*?'(https://[^']+)'", script)
        if not host_match:
            return
        host = client.safe(host_match[1])
        page = client.request(host.rstrip('/') + '/play/' + q['imdb'], referer=self.base_link + '/')
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
        raw = client.request(catalogue, headers=headers)
        rows = json.loads(re.sub(r',\s*\[\]', '', raw))
        if 'season' in q:
            season = next((r for r in rows if str(r.get('id')) == q['season']), {})
            episode = next((r for r in season.get('folder', []) if str(r.get('episode')) == q['episode']), {})
            rows = episode.get('folder', [])
        def backend(row):
            path = host.rstrip('/') + '/playlist/' + quote(str(row['file']), safe='') + '.txt'
            url = client.request(path, headers=headers, post='', redirect=False).strip()
            yield item(url, self.headers(), 'AllMovieLand ' + str(row.get('title') or 'server'))
        yield from self.each(rows[:32], backend)
