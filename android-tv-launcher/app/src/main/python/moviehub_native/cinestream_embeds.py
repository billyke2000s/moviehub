"""Additional catalogue/embed paths from CineStream 487, CSX@13903aa (GPL-3.0-or-later).

Only explicitly labelled episodes are selected. Hosted embeds remain subject to the existing
ResolveURL support and media validation; an extracted iframe is not proof of playback.
"""
import base64
import html
import json
import re
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlparse
from moviehub_compat import client
from moviehub_scrubs import dom_parser, cleantitle
from .fourkhdhub import anchors, text
from .cinestream_api import Base, DECODER, get, item, request
from .cinestream_catalogues import external


def slug(title):
    return re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')


def episode_path(q, segment='episode'):
    return ('/' + segment + '/' + slug(q['title']) + '-' + q['season'] + 'x' + q['episode'] + '/'
            if 'season' in q else '/movies/' + slug(q['title']) + '/')


class Multimovies(Base):
    base_link = 'https://m1.multimovies.courses'

    @staticmethod
    def server_url(value):
        # watchConfig sometimes escapes its URL twice. Decode data, never execute page scripts.
        return html.unescape(html.unescape(value))

    def server(self, row, referer):
        if row.get('type') != 'iframe' or not row.get('url'):
            return
        url = client.safe(self.server_url(row['url']))
        label = 'Multimovies ' + str(row.get('name') or 'server')
        if urlparse(url).hostname == 'rozgarlelo.modiplay.xyz':
            page = request(url, referer=referer)
            # The wrapper's explicit server controls carry hosted media. Other page URLs
            # include ads, recommendations and a proxy player; do not scan them for streams.
            seen = set()
            for server in dom_parser.parse_dom(page, 'div', {'class': 'srv-item'}, req='data-url')[:32]:
                link = client.safe(urljoin(url, self.server_url(server.attrs['data-url'])))
                if urlparse(link).hostname == urlparse(url).hostname or link in seen:
                    continue
                seen.add(link)
                yield external(link, url, label + ' ' + server.attrs.get('data-pname', 'host'))
        else:
            yield external(url, referer, label)

    def config_identity(self, config, q):
        identities = []
        for row in (config.get('initialServers') or [])[:32]:
            parsed = urlparse(self.server_url(str(row.get('url') or '')))
            args = parse_qs(parsed.query)
            if parsed.hostname == 'rozgarlelo.modiplay.xyz' and parsed.path.startswith('/embed/tmdb/'):
                identities.append((args.get('id', [''])[0], args.get('s', [''])[0], args.get('e', [''])[0],
                                   parsed.path.rsplit('/', 1)[-1]))
        wanted = (q['tmdb'], q.get('season', ''), q.get('episode', ''), 'tv' if 'season' in q else 'movie')
        return bool(identities) and all(value == wanted for value in identities)

    def extract(self, q):
        # The current site replaced WordPress/Doo with a searchable catalogue and
        # literal watchConfig. Keep the old path as a fallback for older mirrors.
        data = get(self.base_link + '/api/search?' + urlencode(dict(q=q['title'], limit=20)))
        rows = data.get('tv_shows' if 'season' in q else 'movies') or []
        def title_match(row):
            name = row.get('title', '')
            if 'season' in q:
                name = re.sub(r'\s*\(Now in Hindi\)\s*$', '', name, flags=re.I)
            return slug(name) == slug(q['title'])
        matches = [row for row in rows if title_match(row) and
                   ('season' in q or str(row.get('year')) == q['year'])]
        if len(matches) == 1:
            url = urljoin(self.base_link, matches[0]['url'])
            if urlparse(url).netloc != urlparse(self.base_link).netloc:
                raise ValueError('Catalogue origin changed')
            if 'season' in q:
                url += '/season/' + q['season'] + '/episode/' + q['episode']
            page = request(url)
            match = re.search(r'\bconst\s+watchConfig\s*=\s*', page)
            if match:
                config, _ = json.JSONDecoder().raw_decode(page[match.end():])
                if 'season' in q and (str(config.get('season')) != q['season'] or
                                      str(config.get('episode')) != q['episode']):
                    return
                if slug(matches[0].get('title', '')) != slug(q['title']) and not self.config_identity(config, q):
                    return
                yield from self.each((config.get('initialServers') or [])[:32], lambda row: self.server(row, url))
                return
        url = self.base_link + episode_path(q, 'episodes')
        page = request(url)
        lists = dom_parser.parse_dom(page, 'ul', {'id': 'playeroptionsul'})
        for group in lists:
            for row in dom_parser.parse_dom(group.content, 'li', req=['data-post', 'data-nume', 'data-type'])[:32]:
                if 'trailer' in row.attrs['data-nume'].lower():
                    continue
                data = dict(action='doo_player_ajax', post=row.attrs['data-post'],
                            nume=row.attrs['data-nume'], type=row.attrs['data-type'])
                result = request(self.base_link + '/wp-admin/admin-ajax.php', post=data,
                                        referer=url, XHR=True, output='json')
                embed = result.get('embed_url', '')
                frames = dom_parser.parse_dom(embed, 'iframe', req='src')
                link = frames[0].attrs['src'] if frames else embed if embed.startswith('https://') else None
                if link and 'youtube.com' not in link:
                    yield external(urljoin(url, link), url, 'Multimovies ' + row.attrs['data-nume'])


class Toonstream(Base):
    base_link = 'https://toonstream.us'

    def extract(self, q):
        url = self.base_link + episode_path(q)
        page = request(url, referer=self.base_link)
        for group in dom_parser.parse_dom(page, 'div', {'class': 'video'}):
            for frame in dom_parser.parse_dom(group.content, 'iframe', req='data-src')[:32]:
                wrapper = client.safe(urljoin(url, frame.attrs['data-src']))
                inner = request(wrapper)
                for container in dom_parser.parse_dom(inner, 'div', {'class': 'Video'}):
                    for child in dom_parser.parse_dom(container.content, 'iframe', req='src')[:32]:
                        yield external(urljoin(wrapper, child.attrs['src']), self.base_link + '/', 'Toonstream')


class AnimeSalt(Base):
    base_link = 'https://animesalt.cx'

    def extract(self, q):
        page = request(self.base_link + episode_path(q), headers=self.headers())
        match = re.search(r'src="(https://as-cdn\d+\.top)/video/([a-f0-9]+)"', page)
        if not match:
            for group in dom_parser.parse_dom(page, 'div', {'class': 'video'}):
                for frame in dom_parser.parse_dom(group.content, 'iframe')[:32]:
                    link = frame.attrs.get('data-src') or frame.attrs.get('src')
                    if not link:
                        continue
                    url = urljoin(self.base_link, link)
                    parsed = urlparse(url)
                    if parsed.netloc == urlparse(self.base_link).netloc and parsed.path.endswith('/plyr/player.php'):
                        encoded = parse_qs(parsed.query).get('data', [''])[0]
                        rows = json.loads(base64.b64decode(encoded + '=' * (-len(encoded) % 4), validate=True))
                        for row in rows[:32]:
                            if row.get('link'):
                                yield external(row['link'], self.base_link + '/',
                                               'AnimeSalt ' + str(row.get('language') or 'server'))
                    else:
                        yield external(url, self.base_link + '/', 'AnimeSalt')
            return
        host, identity = match.groups()
        response = request(host + '/player/index.php?' + urlencode(dict(data=identity, do='getVideo')),
            post=dict(hash=identity, r=self.base_link + '/'),
            headers={'Referer': self.base_link + '/', 'Origin': host, 'X-Requested-With': 'XMLHttpRequest'}, output='json')
        yield item(response.get('videoSource') or response.get('securedLink'), self.headers(), 'AnimeSalt')


# Kept as a re-export for existing registry/import callers.
from .cinestream_rtally import Rtally


class KissKH(Base):
    base_link = 'https://kisskh.do'

    def extract(self, q):
        args = dict(q=q['title'], type='1' if 'season' in q else '2')
        rows = get(self.base_link + '/api/DramaList/Search?' + urlencode(args), self.headers())
        wanted = slug(q['title'])
        def matches(row):
            title = row.get('title') or ''
            if q.get('season', '1') == '1':
                return slug(title) == wanted or slug(title) == wanted + '-season-1'
            return slug(title) == wanted + '-season-' + q['season']
        match = next((r for r in rows if matches(r)), None)
        if not match:
            return
        detail_headers = dict(self.headers(), Referer=self.base_link + '/Drama/' +
                              re.sub(r'[^a-zA-Z\d]', '-', match['title']) + '?id=' + str(match['id']))
        data = get(self.base_link + '/api/DramaList/Drama/' + str(match['id']) + '?isq=false', detail_headers)
        episodes = data.get('episodes') or []
        if 'season' in q:
            number = re.escape(q['episode']) + r'(?:\.0+)?'
            episode = next((r for r in episodes if re.fullmatch(number, str(r.get('number')))), None)
        else:
            episode = episodes[0] if len(episodes) == 1 else None
        if not episode:
            return
        identity = str(episode['id'])
        key = get(DECODER + '/enc-kisskh?' + urlencode(dict(text=identity, type='vid')),
                  {'Referer': self.base_link}).get('result')
        if not isinstance(key, str):
            return
        response = get(self.base_link + '/api/DramaList/Episode/' + identity + '.png?' +
                       urlencode(dict(err='false', ts='', time='', kkey=key)), {'Referer': self.base_link})
        for field in ('Video', 'ThirdParty'):
            value = response.get(field)
            if not isinstance(value, str) or not value:
                continue
            url = client.safe(urljoin(self.base_link, value))
            if re.search(r'\.(?:m3u8|mp4)(?:$|\?)', url, re.I):
                yield item(url, dict(self.headers(), Referer=self.base_link, Origin=self.base_link), 'KissKH ' + field)
            else:
                yield external(url.partition('?')[0], self.base_link + '/', 'KissKH ' + field)
