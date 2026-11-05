"""CineStream 487 public catalogue protocols (CSX@13903aa, GPL-3.0-or-later).

Retain exact catalogue identity and explicit episode evidence; never copy upstream fallbacks
which choose the first unrelated search result or first episode.
"""
import json
import re
from html.parser import HTMLParser
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
    # The old .it address now advertises this canonical catalogue and redirects searches to its hub.
    base_link = 'https://akwam.ss'

    @staticmethod
    def season_number(label):
        label = label.translate(str.maketrans('أإآ٠١٢٣٤٥٦٧٨٩', 'ااا0123456789'))
        number = re.search(r'(?:\bSeason\s*|الموسم\s*)(\d+)(?!\d)', label, re.I)
        if number:
            return int(number[1])
        ordinals = ('الاول', 'الثاني', 'الثالث', 'الرابع', 'الخامس', 'السادس',
                    'السابع', 'الثامن', 'التاسع', 'العاشر')
        return next((i for i, word in enumerate(ordinals, 1)
                     if re.search(r'الموسم\s+' + word + r'(?![\w])', label)), None)

    @staticmethod
    def verified(page, imdb):
        return any(re.search(r'/title/' + re.escape(imdb) + r'(?:/|$)', anchor.attrs['href'])
                   for anchor in anchors(page))

    def media(self, url, page=None):
        page = request(url, referer=self.base_link + '/') if page is None else page
        for cls in ('link-download', 'download-link', 'link'):
            class DownloadLinks(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.urls = []

                def handle_starttag(self, tag, attrs):
                    values = dict(attrs)
                    if tag == 'a' and cls in (values.get('class') or '').split() and values.get('href'):
                        self.urls.append(values['href'])

            # Current final anchors have a boolean `download` attribute, which the
            # vendored regex DOM parser cannot parse. Keep this scoped to file navigation.
            links = DownloadLinks()
            links.feed(page)
            candidates = links.urls
            if not candidates:
                # The current canonical catalogue serves the final file on /download/;
                # older mirrors still insert a separate download-link page.
                if cls == 'download-link':
                    continue
                return
            url = client.safe(urljoin(url, candidates[0]))
            if cls == 'link':
                yield item(url, self.headers(), 'Akwam')
            else:
                page = request(url, referer=self.base_link + '/')

    def extract(self, q):
        series = 'season' in q
        args = dict(q=q['title'], section='series' if series else 'movie', year=q['year'],
                    rating=0, formats=0, quality=0)
        search = request(self.base_link + '/search?' + urlencode(args))
        seen = set()
        for anchor in anchors(search):
            url = urljoin(self.base_link, anchor.attrs['href'])
            if not re.search(r'/(?:series|movie)/\d+/', url) or url in seen:
                continue
            seen.add(url)
            if len(seen) > 20:
                break
            page = request(url, referer=self.base_link + '/')
            if not self.verified(page, q['imdb']):
                continue
            if not series:
                yield from self.media(url, page)
                return
            wanted = int(q['season'])
            title = dom_parser.parse_dom(page, 'h1')
            actual = self.season_number(text(title[0].content)) if title else None
            if actual != wanted:
                # Navigate only a labelled season; recheck IMDb and h1 after following it.
                for season in anchors(page):
                    if self.season_number(text(season.content)) != wanted:
                        continue
                    candidate = client.safe(urljoin(url, season.attrs['href']))
                    if not re.search(r'/series/\d+/', candidate):
                        continue
                    selected = request(candidate, referer=url)
                    heading = dom_parser.parse_dom(selected, 'h1')
                    if self.verified(selected, q['imdb']) and heading and self.season_number(text(heading[0].content)) == wanted:
                        page, url, actual = selected, candidate, wanted
                        break
            if actual != wanted:
                continue
            for episode in anchors(page):
                number = re.search(r'(?:حلقة|Episode)\s*(\d+)(?!\d)', text(episode.content), re.I)
                if not number or int(number[1]) != int(q['episode']):
                    continue
                target = client.safe(urljoin(url, episode.attrs['href']))
                if re.search(r'/episode/\d+/', target):
                    yield from self.media(target)
                    return


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
