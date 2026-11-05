"""Rtally's observed search, scoped React post data and numbered episode players.

Only JSON literals are parsed. Recommendation cards cannot contribute media to the chosen post.
"""
import json
import re
from urllib.parse import quote, urlencode
from .cinestream_api import Base, get, request
from .cinestream_catalogues import external
from .cinestream_links import Links


def slug(value):
    return re.sub(r'[^a-z0-9]+', '-', str(value).casefold()).strip('-')


def posts(page):
    values = []
    for match in re.finditer(r'self\.__next_f\.push\(', page):
        try:
            entry, _ = json.JSONDecoder().raw_decode(page[match.end():])
        except ValueError:
            continue
        if not isinstance(entry, list) or len(entry) < 2 or not isinstance(entry[1], str):
            continue
        for line in entry[1].splitlines():
            try:
                values.append(json.loads(line.partition(':')[2]))
            except ValueError:
                continue
    # Also accept an explicit JSON response/fixture, never an unscoped scan of media fields.
    if page.lstrip().startswith('{'):
        try:
            values.append(json.loads(page))
        except ValueError:
            pass
    nodes = 0
    while values:
        value = values.pop()
        nodes += 1
        if nodes > 100000:
            raise ValueError('Rtally page data limit')
        if isinstance(value, dict):
            if isinstance(value.get('post'), dict):
                yield value['post']
            values.extend(value.values())
        elif isinstance(value, list):
            values.extend(value)


class Rtally(Base):
    base_link = 'https://rtally.lol'
    movie_hosts = {
        'filemoonUrl': 'https://bysewihe.com/e/',
        'vidhideUrl': 'https://vidhideplus.com/v/',
        'streamwishUrl': 'https://hglink.to/e/',
        'lulustreamUrl': 'https://lulustream.com/e/',
        'vidaraUrl': 'https://vidara.to/e/',
        'vidmolyUrl': 'https://vidmoly.org/embed-',
        'turboUrl': 'https://turbonewvid.com/t/',
        'streamoriginUrl': 'https://embedorigin.cc/e/',
    }
    series_hosts = {
        'multiLinksDl': 'https://bysewihe.com/e/',
        'multiLinksSl': 'https://vidhideplus.com/v/',
        'streamwishMultiUrl': 'https://hglink.to/e/',
        'lulustreamMultiUrl': 'https://lulustream.com/e/',
        'vidaraMultiUrl': 'https://vidara.to/e/',
        'vidmolyMultiUrl': 'https://vidmoly.org/embed-',
        'turboMultiUrl': 'https://turbonewvid.com/t/',
        'streamoriginMultiUrl': 'https://embedorigin.cc/e/',
    }

    def extract(self, q):
        rows = get(self.base_link + '/api/search?' + urlencode(dict(q=q['title']))).get('results') or []
        series = 'season' in q
        wanted = slug(q['title'] + (' Season ' + q['season'] if series else ''))
        # Later seasons have their own release year. Verify the show's original identity first.
        first_season = slug(q['title'] + ' Season 1')
        if series and not any(slug(row.get('title')) == first_season and str(row.get('year')) == q['year']
                              for row in rows):
            return
        for row in rows[:50]:
            if slug(row.get('title')) != wanted or (not series and str(row.get('year')) != q['year']):
                continue
            identity = row.get('slug')
            if not isinstance(identity, str) or not re.fullmatch(r'[a-z0-9-]{1,200}', identity):
                continue
            url = self.base_link + '/post/' + identity
            for post in posts(request(url, referer=self.base_link + '/')):
                if slug(post.get('title')) != wanted:
                    continue
                years = [str(value.get('title')) for value in post.get('year', []) if isinstance(value, dict)]
                if not series and q['year'] not in years:
                    continue
                if series:
                    index = int(q['episode']) - 1
                    for field, root in self.series_hosts.items():
                        value = post.get(field)
                        # This is the site's own Episode N -> links[N-1] mapping.
                        entries = [v.strip() for v in value.split(',') if v.strip()] if isinstance(value, str) else []
                        if index >= len(entries) or len(entries) > 1000:
                            continue
                        key = entries[index]
                        if not re.fullmatch(r'[A-Za-z0-9_-]{1,200}', key):
                            continue
                        suffix = '.html' if field == 'vidmolyMultiUrl' else ''
                        yield external(root + key + suffix, url, 'Rtally ' + field)
                else:
                    resolver = Links('Rtally')
                    for field in ('small', 'medium', 'large', 'extraLarge'):
                        address = post.get(field)
                        if isinstance(address, str) and address.startswith('https://'):
                            yield from self.each(((address, field),),
                                lambda entry: resolver.follow(entry[0], url, 'Rtally ' + entry[1]))
                    for field, root in self.movie_hosts.items():
                        value = post.get(field)
                        if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,200}', value):
                            suffix = '.html' if field == 'vidmolyUrl' else ''
                            yield external(root + quote(value, safe='') + suffix, url, 'Rtally ' + field)
