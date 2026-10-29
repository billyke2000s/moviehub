"""CineStream 487 public catalogue protocols (CSX@13903aa, GPL-3.0-or-later).

Retain exact catalogue identity and explicit episode evidence; never copy upstream fallbacks
which choose the first unrelated search result or first episode.
"""
import json
import re
from urllib.parse import quote, urlencode, urljoin, urlparse
from moviehub_compat import client
from moviehub_scrubs import dom_parser, cleantitle
from .fourkhdhub import anchors, text
from .cinestream_api import Base, get, item, request


def nodes(page, tag, cls=None):
    return dom_parser.parse_dom(page, tag, {'class': cls} if cls else {})


def external(url, referer, label):
    client.safe(url)
    if re.search(r'\.(?:m3u8|mp4|mkv|mpd)(?:$|\?)', url, re.I):
        return item(url, {'Referer': referer}, label)
    return dict(url=url, direct=False, source=label, originProvider=label, quality='Auto', info='')


class ProjectFreeTv(Base):
    base_link = 'https://projectfreetv.sx'

    def extract(self, q):
        title = q['title'] + (' - season ' + q['season'] if 'season' in q else '')
        args = dict(lang=3, keyword=title, year=q['year'], networks='', rating='', votes='', genre='',
                    country='', cast='', directors='', type='', order_by='', page=1, limit=20)
        rows = get(self.base_link + '/data/browse/?' + urlencode(args), self.headers()).get('movies') or []
        for row in rows[:20]:
            # Upstream takes the first result. Require actual name/year or explicit IMDb instead.
            imdb = str(row.get('imdb') or row.get('imdb_id') or '')
            name = row.get('title') or row.get('name') or ''
            if imdb != q['imdb'] and not (cleantitle.get(name) == cleantitle.get(title) and str(row.get('year')) == q['year']):
                continue
            detail = get(self.base_link + '/data/watch/?' + urlencode({'_id': row['_id']}), self.headers())
            for stream in detail.get('streams', [])[:64]:
                if 'season' in q and str(stream.get('e')) != q['episode']:
                    continue
                if stream.get('stream'):
                    yield external(stream['stream'], self.base_link, 'ProjectFreeTV')


class Akwam(Base):
    requires_imdb = True
    base_link = 'https://akwam.it'

    def extract(self, q):
        # Upstream never verifies the requested season on its series page. Keep films only.
        if 'season' in q:
            return
        args = dict(q=q['title'], section='movie', year=q['year'], rating=0, formats=0, quality=0)
        search = request(self.base_link + '/search?' + urlencode(args))
        for link in dom_parser.parse_dom(search, 'a', {'class': 'box'}, req='href')[:20]:
            url = urljoin(self.base_link, link.attrs['href'])
            page = request(url, referer=self.base_link + '/')
            if not any(re.search(r'/title/' + re.escape(q['imdb']) + r'(?:/|$)', a.attrs['href']) for a in anchors(page)):
                continue
            for cls in ('link-download', 'download-link', 'link'):
                candidates = dom_parser.parse_dom(page, 'a', {'class': cls}, req='href')
                if not candidates:
                    break
                url = client.safe(urljoin(url, candidates[0].attrs['href']))
                if cls == 'link':
                    yield item(url, self.headers(), 'Akwam')
                else:
                    page = request(url, referer=self.base_link + '/')


class Asiaflix(Base):
    base_link = 'https://asiaflix.net'

    def extract(self, q):
        if q.get('season', '1') != '1':
            return
        headers = {'Referer': self.base_link, 'X-Access-Control': 'web'}
        rows = get('https://api.asiaflix.net/v1/drama/search?' + urlencode(dict(q=q['title'])), headers).get('body') or []
        match = next((r for r in rows if cleantitle.get(r.get('name', '')) == cleantitle.get(q['title'])), None)
        if not match:
            return
        slug = quote(match['name'].replace(' ', '-'), safe='')
        page = request(self.base_link + '/play/' + slug + '-1/' + quote(str(match['_id']), safe='') + '/1')
        scripts = dom_parser.parse_dom(page, 'script', {'id': 'ng-state'})
        if not scripts:
            return
        def walk(value):
            if isinstance(value, dict):
                if str(value.get('number')) == q.get('episode', '1') and isinstance(value.get('streamUrls'), list):
                    for stream in value['streamUrls'][:32]:
                        url = stream.get('url')
                        if isinstance(url, str):
                            yield external('https:' + url if url.startswith('//') else url, self.base_link, 'Asiaflix')
                else:
                    for child in value.values():
                        yield from walk(child)
            elif isinstance(value, list):
                for child in value:
                    yield from walk(child)
        yield from walk(json.loads(scripts[0].content))
