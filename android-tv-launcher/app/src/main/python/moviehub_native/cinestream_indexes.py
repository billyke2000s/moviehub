"""CineStream index/AJAX paths, CSX@13903aa (GPL-3.0-or-later).

Explicit episode identities replace upstream's first-episode/combined fallback.
M4U's established movie-only adapters remain unchanged; the additional .lat
route supplies its missing TV protocol and refuses redirects into existing roots.
"""
import json
import re
from urllib.parse import quote, urlencode, urljoin, urlparse

from moviehub_compat import client
from .cinestream_api import Base, request, get
from .cinestream_dom import tree
from .cinestream_downloads import DownloadCatalogue, download_anchor, links, matches_number, title_matches
from .cinestream_links import Links, absolute


class Mlsbd(DownloadCatalogue):
    name = 'Mlsbd'
    base_link = 'https://mlsbd.co'

    def search(self, q):
        yield from super().search(dict(q, imdb=''))

    def episode_entries(self, content, q, page_title=''):
        if not matches_number(page_title, 'season', q['season']):
            return
        for heading in content.all('div', cls='download'):
            label = heading.text()
            if not matches_number(label, 'episode', q['episode']):
                continue
            for block in heading.siblings():
                if 'download' in (block.attrs.get('class') or '').split():
                    break
                for anchor in links(block):
                    if download_anchor(anchor):
                        yield anchor.attrs['href'], label + ' · ' + anchor.text()

    def movie_entries(self, content):
        heading = next((node for node in content.all('h2,h3,div,p')
                        if 'download' in (node.attrs.get('class') or '').split() and
                        'download now' in node.text().casefold()), None)
        if heading is None:
            return
        for paragraph in heading.siblings():
            if 'download' in (paragraph.attrs.get('class') or '').split():
                break
            for anchor in paragraph.all('a'):
                href = anchor.attrs.get('href') or ''
                if href and download_anchor(anchor) and not re.search(
                        r'How To|Tutorial|telegram|facebook|youtube|imdb\.com',
                        anchor.text() + ' ' + href, re.I):
                    yield href, paragraph.text()


class SkyMovies(DownloadCatalogue):
    name = 'SkyMovies'
    base_link = 'https://new4.skymovieshd.clinic'

    def extract(self, q):
        args = {'search': q['title'] + ' (' + q['year'] + ')', 'cat': 'All'}
        doc, actual, _ = self.page(self.base_link + '/search.php?' + urlencode(args))
        resolver = Links(self.name)
        for block in doc.all('div', cls='L'):
            for anchor in block.all('a'):
                label = anchor.text()
                if not title_matches(label, q['title']) or q['year'] not in label:
                    continue
                exact = False
                if 'season' in q:
                    code = re.search(r'\bS0*(\d+)E0*(\d+)\b', label, re.I)
                    if code:
                        if (int(code[1]), int(code[2])) != (int(q['season']), int(q['episode'])):
                            continue
                        exact = True
                    elif not matches_number(label, 'season', q['season']):
                        continue
                target = absolute(actual, anchor.attrs.get('href') or '')
                detail, final, _ = self.page(target, actual)
                for group in detail.all('div'):
                    if not set((group.attrs.get('class') or '').split()) & {'Bolly', 'L', 'Let'}:
                        continue
                    for source in group.all('a'):
                        if 'season' in q and not exact and not matches_number(source.text(), 'episode', q['episode']):
                            continue
                        if source.attrs.get('href') and download_anchor(source):
                            yield from resolver.follow(absolute(final, source.attrs['href']), final,
                                                       label + ' · ' + source.text())


class Fibwatch(DownloadCatalogue):
    name = 'Fibwatch'
    base_link = 'https://fibwatch.art'

    def extract(self, q):
        query = self.base_link + '/search?' + urlencode({'keyword': q['title'], 'page_id': 1})
        doc, actual, _ = self.page(query)
        resolver = Links(self.name, {'Referer': 'https://urlshortlink.top/',
                                     'Origin': 'https://urlshortlink.top'})
        for card in doc.all('div', cls='video-thumb'):
            anchor = card.first('a')
            label = card.first('p', cls='hptag')
            image = card.first('img')
            title = label.text() if label else (image.attrs.get('alt') or '') if image else ''
            if anchor is None or not title_matches(title, q['title']):
                continue
            detail, final, _ = self.page(absolute(actual, anchor.attrs.get('href') or ''), actual)
            if not self.verified(detail, q):
                continue
            identity = detail.first('input', id='video-id')
            if identity is None:
                continue
            video_id = identity.attrs.get('value') or ''
            if 'season' in q:
                data = get(self.base_link + '/ajax/episodes.php?' + urlencode({'video_id': video_id}), self.headers())
                selected = None
                for row in data.get('episodes') or []:
                    code = re.search(r'\bs0*(\d{1,2})e0*(\d{1,3})\b', str(row.get('title') or ''), re.I)
                    if code and (int(code[1]), int(code[2])) == (int(q['season']), int(q['episode'])):
                        selected = row.get('url')
                        break
                if not selected:
                    client.host_result(self.name, 'no_exact_episode')
                    continue
                detail, final, _ = self.page(absolute(final, selected), final)
                identity = detail.first('input', id='video-id')
                if identity is None:
                    continue
                video_id = identity.attrs.get('value') or ''
            data = get(self.base_link + '/ajax/resolution_switcher.php?' + urlencode({'video_id': video_id}), self.headers())
            for row in ((data.get('current') or []) + (data.get('popup') or []))[:48]:
                if row.get('url'):
                    yield from resolver.follow(absolute(final, row['url']), final, title + ' · ' + str(row.get('res') or ''))


class M4ufreeLat(DownloadCatalogue):
    name = 'M4UFree'
    base_link = 'https://ww5.m4ufree.lat'
    existing = {'m4ufree.se', 'm4uhd.tv', 'm4uhdpro.net', 'm4uhd.to'}

    def page(self, url, referer=''):
        doc, actual, body = super().page(url, referer)
        if (urlparse(actual).hostname or '').removeprefix('www.') in self.existing:
            raise ValueError('Duplicate established M4U catalogue')
        return doc, actual, body

    def extract(self, q):
        slug = re.sub(r'[^a-z0-9]+', '-', q['title'].lower()).strip('-')
        path = '/search/' + slug + '-' + q['year'] + '?type=' + ('tvs' if 'season' in q else 'movie')
        doc, actual, _ = self.page(self.base_link + path)
        resolver = Links(self.name)
        for card in doc.all('div', cls='item'):
            anchor = card.first('a')
            if anchor is None:
                continue
            label = anchor.attrs.get('title') or anchor.text()
            if not title_matches(label, q['title']) or q['year'] not in label:
                continue
            detail, final, _ = self.page(absolute(actual, anchor.attrs.get('href') or ''), actual)
            token = detail.first('meta', name='csrf-token')
            if token is None or not token.attrs.get('content'):
                continue
            csrf = token.attrs['content']
            origin = urlparse(final).scheme + '://' + urlparse(final).netloc
            headers = dict(self.headers(), Referer=final, **{'X-Requested-With': 'XMLHttpRequest'})
            if 'season' in q:
                code = 'S%02d-E%02d' % (int(q['season']), int(q['episode']))
                button = next((b for b in detail.all('button', cls='episode') if b.text().upper() == code), None)
                if button is None or not button.attrs.get('idepisode'):
                    continue
                response = request(origin + '/ajaxtv', headers=headers,
                                   post={'idepisode': button.attrs['idepisode'], '_token': csrf}, redirect=False)
                detail = tree(response)
            for span in detail.all('span'):
                if not span.attrs.get('data'):
                    continue
                response = request(origin + '/ajax', headers=headers,
                                   post={'m4u': span.attrs['data'], '_token': csrf}, redirect=False)
                for frame in tree(response).all('iframe'):
                    if frame.attrs.get('src'):
                        yield from resolver.follow(absolute(final, frame.attrs['src']), final, label)
