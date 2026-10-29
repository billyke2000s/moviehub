"""CineStream 487's movie download catalogues, CSX@13903aa (GPL-3.0-or-later).

These adapters retain the catalogue-to-redirect-to-file chain. They do not change
the established source implementations or treat an intermediate page as media.
"""
import json
import re
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlparse

from moviehub_compat import client
from .cinestream_api import Base, request
from .cinestream_dom import tree
from .cinestream_links import CHALLENGE, Links, absolute, clean


def matches_number(label, kind, number):
    compact = re.search(r'\bS0*(\d+)E0*(\d+)(?![\d.])\b', label, re.I)
    if compact:
        return int(compact[1 if kind == 'season' else 2]) == int(number)
    names = r'(?:Season|S)' if kind == 'season' else r'(?:Episode|Epi|Ep|E)'
    return bool(re.search(r'\b' + names + r'\s*[-:]?\s*0*' + re.escape(str(number)) + r'(?![\d.])\b', label, re.I))


def sections(node):
    """Heading plus following siblings, stopping at the next peer/higher heading."""
    yield node
    level = int(node.tag[1]) if re.fullmatch(r'h[1-6]', node.tag) else 7
    for sibling in node.siblings():
        if re.fullmatch(r'h[1-6]', sibling.tag) and int(sibling.tag[1]) <= level:
            break
        yield sibling


def links(node):
    if node.tag == 'a' and node.attrs.get('href'):
        yield node
    yield from (a for a in node.all('a') if a.attrs.get('href'))


def download_anchor(anchor):
    parsed = urlparse(anchor.attrs.get('href') or '')
    if '/category/' in parsed.path or set(parse_qs(parsed.query)) & {'s', 'search', 'keyword'}:
        return False
    return bool(re.search(r'\b(?:480p|720p|1080p|2160p|4K|Download|Server|G-Drive|V-Cloud|Watch)\b',
                          anchor.text(), re.I))


def title_matches(label, title):
    words = lambda value: ' '.join(re.findall(r'\w+', value.casefold()))
    return (' ' + words(title) + ' ') in (' ' + words(label) + ' ')


class DownloadCatalogue(Base):
    name = ''
    search_path = '/?s={query}'
    json_search = False
    heading_tags = 'h3,h4,h5'

    def page(self, url, referer=''):
        body, _, _, _, final = request(url, headers=self.headers(), referer=referer or self.base_link + '/',
                                      output='extended')
        if CHALLENGE.search(body):
            client.host_result(self.name, 'interaction_required')
            raise ValueError('Catalogue requires browser interaction')
        return tree(body), final or url, body

    def search(self, q):
        query = q['imdb'] or (q['title'] + ' ' + q['year'])
        path = self.search_path.format(query=quote(query, safe=''))
        doc, base, body = self.page(self.base_link + path)
        if self.json_search:
            try:
                data = json.loads(body)
            except ValueError:
                data = {}
            for hit in data.get('hits') or []:
                row = hit.get('document') or {}
                if row.get('imdb_id') and str(row['imdb_id']) != q['imdb']:
                    continue
                if row.get('permalink'):
                    # Current catalogue mirrors advertise an old host in permalinks.
                    yield absolute(self.base_link, urlparse(row['permalink']).path)
            if data.get('hits'):
                return
            doc, base, _ = self.page(self.base_link + '/?' + urlencode({'s': query}))
        seen = set()
        for anchor in doc.all('a'):
            href = anchor.attrs.get('href') or ''
            label = anchor.text() or anchor.attrs.get('title') or ''
            if not href or href.startswith('#') or '/category/' in href:
                continue
            # IMDb searches are verified again against the returned detail page.
            card = anchor.parent
            is_card = card.tag in ('article', 'h2', 'h3') or any(
                value in (anchor.attrs.get('class') or '') for value in ('thumbnail', 'post-title'))
            if not is_card and not title_matches(label, q['title']):
                continue
            target = absolute(base, href)
            if urlparse(target).netloc != urlparse(base).netloc or target in seen:
                continue
            seen.add(target)
            yield target
            if len(seen) >= 12:
                return

    def verified(self, doc, q):
        imdb = {match for a in doc.all('a') for match in
                re.findall(r'imdb\.com/title/(tt\d+)', a.attrs.get('href') or '')}
        if imdb:
            return q['imdb'] in imdb
        titles = [n.text() for n in doc.all('h1,title')]
        return any(title_matches(label, q['title']) and re.search(r'\b' + re.escape(q['year']) + r'\b', label)
                   for label in titles)

    def content(self, doc):
        body = doc.first('main', cls='page-body') or doc.first('article', cls='post-content')
        if body is not None:
            return body
        for cls in ('thecontent', 'entry-content', 'wp-content', 'single_post', 'mod', 'post-content'):
            found = doc.first('div', cls=cls)
            if found is not None:
                return found
        return doc

    def movie_entries(self, content):
        found = set()
        for heading in content.all(self.heading_tags):
            label = heading.text()
            if not re.search(r'\b(?:480p|720p|1080p|2160p|4K)\b', label, re.I):
                continue
            if re.search(r'Batch|Zip|Trailer|Series Info', label, re.I):
                continue
            for block in sections(heading):
                for anchor in links(block):
                    href = anchor.attrs.get('href')
                    if download_anchor(anchor) and href not in found:
                        found.add(href)
                        yield href, label
        if not found:
            for anchor in content.all('a'):
                cls = anchor.attrs.get('class') or ''
                if re.search(r'maxbutton|movie-simple-button|dwd-button', cls) and anchor.attrs.get('href'):
                    yield anchor.attrs['href'], anchor.text()

    def episode_entries(self, content, q, page_title=''):
        season_ok = matches_number(page_title, 'season', q['season'])
        for heading in content.all('h2,h3,h4,h5,p'):
            label = heading.text()
            if re.search(r'\b(?:Season\s*\d+|S\d{1,2}(?:E\d+|\b))', label, re.I):
                season_ok = matches_number(label, 'season', q['season'])
            if not season_ok or not matches_number(label, 'episode', q['episode']):
                continue
            for block in sections(heading):
                if block is not heading and re.search(r'\b(?:Episode|Ep)\s*[-:]?\s*\d+', block.text(), re.I):
                    break
                for anchor in links(block):
                    if anchor.attrs.get('href'):
                        yield anchor.attrs['href'], label

    def series_entries(self, content, q, detail_url, page_title):
        direct = list(self.episode_entries(content, q, page_title))
        if direct:
            yield from direct
            return
        for heading in content.all('h2,h3,h4,h5'):
            label = heading.text()
            if not matches_number(label, 'season', q['season']) or re.search(r'Batch|Zip|Combined', label, re.I):
                continue
            for block in sections(heading):
                for anchor in links(block):
                    if not anchor.attrs.get('href'):
                        continue
                    if not (download_anchor(anchor) or re.search(r'Episodes?|Single', anchor.text(), re.I)):
                        continue
                    folder_url = absolute(detail_url, anchor.attrs['href'])
                    yield from self.each(((folder_url, label),),
                                         lambda row: self.folder_entries(row, q, detail_url))

    def folder_entries(self, row, q, detail_url):
        folder_url, label = row
        folder, final, _ = self.page(folder_url, detail_url)
        # The selected season section establishes this folder's season. Each
        # returned file must still carry an explicit matching episode label.
        for href, info in self.episode_entries(folder, q, 'Season ' + q['season']):
            yield absolute(final, href), label + ' · ' + info

    def extract(self, q):
        resolver = Links(self.name)
        for target in self.search(q):
            client.check()
            yield from self.each((target,), lambda value: self.detail(value, q, resolver))

    def detail(self, target, q, resolver):
        doc, final, _ = self.page(target)
        if not self.verified(doc, q):
            return
        content = self.content(doc)
        heading = doc.first('h1') or doc.first('title')
        page_title = heading.text() if heading else ''
        entries = (self.series_entries(content, q, final, page_title) if 'season' in q
                   else self.movie_entries(content))
        for href, info in entries:
            yield from resolver.follow(absolute(final, href), final, info)


class Bollyflix(DownloadCatalogue):
    requires_imdb = True
    name = 'Bollyflix'
    base_link = 'https://bollyflix.co.za'
    search_path = '/search/{query}'


class TopMovies(DownloadCatalogue):
    requires_imdb = True
    name = 'TopMovies'
    base_link = 'https://moviesleech.club'
    search_path = '/search/{query}'


class MoviesMod(DownloadCatalogue):
    requires_imdb = True
    name = 'MoviesMod'
    base_link = 'https://moviesmod.meme'
    search_path = '/search/{query}'


class MoviesDrive(DownloadCatalogue):
    requires_imdb = True
    name = 'MoviesDrive'
    base_link = 'https://new1.moviesdrive.cyou'
    search_path = '/search.php?q={query}'
    json_search = True


class VegaMovies(MoviesDrive):
    name = 'VegaMovies'
    base_link = 'https://1vegamovies.shop'

    def search(self, q):
        doc, actual, body = self.page(self.base_link)
        if 'View Full Site' in doc.text():
            match = re.search(r"window\.location\.href\s*=\s*atob\(['\"]([^'\"]+)", body)
            if match:
                from .cinestream_links import decode64
                doc, actual, _ = self.page(absolute(actual, decode64(match[1])), actual)
            if 'View Full Site' in doc.text():
                client.host_result(self.name, 'catalogue_gateway_unresolved')
                return
            self.base_link = urlparse(actual).scheme + '://' + urlparse(actual).netloc
        yield from super().search(q)


class RogMovies(MoviesDrive):
    name = 'RogMovies'
    base_link = 'https://rogmovies.im'


class UHDMovies(DownloadCatalogue):
    name = 'UHDMovies'
    base_link = 'https://uhdmovies.beauty'
    search_path = '/search/{query}'

    def search(self, q):
        yield from super().search(dict(q, imdb=''))


class DudeFilms(DownloadCatalogue):
    requires_imdb = True
    name = 'DudeFilms'
    base_link = 'https://dudefilms.red'


class HDHub4u(MoviesDrive):
    name = 'HDHub4u'
    base_link = 'https://new10.hdhub4u.cl'

    def content(self, doc):
        return doc.first('article') or doc.first('main') or doc

    def search(self, q):
        args = {'q': q['imdb'], 'query_by': 'post_title,category,stars,director,imdb_id',
                'query_by_weights': '4,2,2,2,4', 'sort_by': 'sort_by_date:desc',
                'limit': 15, 'highlight_fields': 'none', 'use_cache': 'true', 'page': 1}
        data = request('https://search.pingora.fyi/collections/post/documents/search?' + urlencode(args),
                       headers=self.headers(), output='json')
        for hit in data.get('hits') or []:
            row = hit.get('document') or {}
            if row.get('imdb_id') and row['imdb_id'] != q['imdb']:
                continue
            if row.get('permalink'):
                yield absolute(self.base_link, urlparse(row['permalink']).path)

    def movie_entries(self, content):
        for anchor in content.all('a'):
            href = anchor.attrs.get('href') or ''
            is_file = re.search(r'\b(?:480p|720p|1080p|2160p)\b|hubdrive|hubcloud|hubcdn|greenmountmotors|hdstream4u|hubstream',
                                anchor.text() + ' ' + href, re.I)
            if is_file and anchor.attrs.get('data-wpel-link') == 'external' and not re.search(
                    r'imdb\.com|themoviedb|telegram|whatsapp|catimages|youtube|4khdhub\.one', href, re.I):
                yield href, anchor.text()


class ZinkMovies(DownloadCatalogue):
    name = 'ZinkMovies'
    base_link = 'https://new6.zinkmovies.mobi'

    def search(self, q):
        yield from super().search(dict(q, imdb=''))


class HindMoviez(DownloadCatalogue):
    requires_imdb = True
    name = 'HindMoviez'
    base_link = 'https://hindmovie.tax'

    # The shared link traversal follows the current same-origin signing AJAX.
