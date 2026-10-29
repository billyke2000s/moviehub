"""Additional catalogue/embed paths from CineStream 487, CSX@13903aa (GPL-3.0-or-later).

Only explicitly labelled episodes are selected. Hosted embeds remain subject to the existing
ResolveURL support and media validation; an extracted iframe is not proof of playback.
"""
import json
import re
from urllib.parse import quote, urlencode, urljoin
from moviehub_compat import client
from moviehub_scrubs import dom_parser, cleantitle
from .fourkhdhub import anchors, text
from .cinestream_api import Base, DECODER, get, item
from .cinestream_catalogues import external


def slug(title):
    return re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')


def episode_path(q, segment='episode'):
    return ('/' + segment + '/' + slug(q['title']) + '-' + q['season'] + 'x' + q['episode'] + '/'
            if 'season' in q else '/movies/' + slug(q['title']) + '/')


class Multimovies(Base):
    base_link = 'https://m1.multimovies.courses'

    def extract(self, q):
        url = self.base_link + episode_path(q, 'episodes')
        page = client.request(url)
        lists = dom_parser.parse_dom(page, 'ul', {'id': 'playeroptionsul'})
        for group in lists:
            for row in dom_parser.parse_dom(group.content, 'li', req=['data-post', 'data-nume', 'data-type'])[:32]:
                if 'trailer' in row.attrs['data-nume'].lower():
                    continue
                data = dict(action='doo_player_ajax', post=row.attrs['data-post'],
                            nume=row.attrs['data-nume'], type=row.attrs['data-type'])
                result = client.request(self.base_link + '/wp-admin/admin-ajax.php', post=data,
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
        page = client.request(url, referer=self.base_link)
        for group in dom_parser.parse_dom(page, 'div', {'class': 'video'}):
            for frame in dom_parser.parse_dom(group.content, 'iframe', req='data-src')[:32]:
                wrapper = client.safe(urljoin(url, frame.attrs['data-src']))
                inner = client.request(wrapper)
                for container in dom_parser.parse_dom(inner, 'div', {'class': 'Video'}):
                    for child in dom_parser.parse_dom(container.content, 'iframe', req='src')[:32]:
                        yield external(urljoin(wrapper, child.attrs['src']), self.base_link + '/', 'Toonstream')


class AnimeSalt(Base):
    base_link = 'https://animesalt.ac'

    def extract(self, q):
        page = client.request(self.base_link + episode_path(q), headers=self.headers())
        match = re.search(r'src="(https://as-cdn\d+\.top)/video/([a-f0-9]+)"', page)
        if not match:
            return
        host, identity = match.groups()
        response = client.request(host + '/player/index.php?' + urlencode(dict(data=identity, do='getVideo')),
            post=dict(hash=identity, r=self.base_link + '/'),
            headers={'Referer': self.base_link + '/', 'Origin': host, 'X-Requested-With': 'XMLHttpRequest'}, output='json')
        yield item(response.get('videoSource') or response.get('securedLink'), self.headers(), 'AnimeSalt')


class Rtally(Base):
    base_link = 'https://rtally.lol'

    def extract(self, q):
        if 'season' in q:
            return
        page = client.request(self.base_link + '/post/' + slug(q['title']))
        # Next/React stores this literal JSON in escaped strings; no script evaluation.
        page = page.replace('\\"', '"')
        for match in re.finditer(r'"(?:small|medium|large|extraLarge)":"(https?://[^"\\]+)"', page):
            yield external(match[1], self.base_link, 'Rtally direct')
        hosts = {'vidhide': 'https://vidhideplus.com/v/', 'lulustream': 'https://lulustream.com/e/',
                 'filemoon': 'https://filemoon.sx/e/', 'streamwish': 'https://playerwish.com/e/',
                 'strmup': 'https://strmup.cc/'}
        for match in re.finditer(r'"(lulustream|strmup|filemoon|vidhide|streamwish)Url":"([^"\\]+)"', page):
            if match[2] != 'null':
                yield external(hosts[match[1]] + quote(match[2], safe=''), self.base_link, 'Rtally ' + match[1])


class KissKH(Base):
    base_link = 'https://kisskh.nl'

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
        data = get(self.base_link + '/api/DramaList/Drama/' + str(match['id']) + '?isq=false', self.headers())
        episodes = data.get('episodes') or []
        if 'season' in q:
            episode = next((r for r in episodes if str(r.get('number')) == q['episode']), None)
        else:
            episode = episodes[0] if len(episodes) == 1 else None
        if not episode:
            return
        identity = str(episode['id'])
        key = get(DECODER + '/enc-kisskh?' + urlencode(dict(text=identity, type='vid'))).get('result')
        if not isinstance(key, str):
            return
        response = get(self.base_link + '/api/DramaList/Episode/' + identity + '.png?' +
                       urlencode(dict(err='false', ts='', time='', kkey=key)), self.headers())
        for field in ('Video', 'ThirdParty'):
            value = response.get(field)
            if not isinstance(value, str) or not value:
                continue
            url = client.safe(urljoin(self.base_link, value))
            if re.search(r'\.(?:m3u8|mp4)(?:$|\?)', url, re.I):
                yield item(url, dict(self.headers(), Origin=self.base_link), 'KissKH ' + field)
            else:
                yield external(url, self.base_link + '/', 'KissKH ' + field)
