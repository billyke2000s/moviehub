"""AnyMovies' current series cards, shared by its existing two catalogue loaders."""
from html.parser import HTMLParser
import re
from urllib.parse import parse_qs, urlencode, urlparse


class _SeriesCards(HTMLParser):
    def __init__(self, imdb):
        super().__init__(convert_charrefs=True)
        self.imdb = imdb
        self.depth = 0
        self.matched = False
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'div':
            if self.depth:
                self.depth += 1
            elif (attrs.get('data-kind') == 'series' and
                  attrs.get('data-imdb') == self.imdb and
                  'result_block' in attrs.get('class', '').split()):
                self.depth = 1
                self.matched = True
        if tag == 'a' and self.matched and self.depth:
            self.links.append(attrs.get('href', ''))

    def handle_endtag(self, tag):
        if tag == 'div' and self.depth:
            self.depth -= 1
            if not self.depth:
                self.matched = False


class SeriesLookup:
    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return urlencode(dict(imdb=imdb, title=title, year=year))

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        data = {key: values[0] for key, values in parse_qs(url).items()}
        data.update(season=season, episode=episode)
        return urlencode(data)

    def series_sources(self, data, scraper):
        imdb = data.get('imdb', '')
        if not re.fullmatch(r'tt[0-9]{5,10}', imdb):
            return []
        season, episode = int(data['season']), int(data['episode'])
        if not 0 <= season <= 999 or not 1 <= episode <= 9999:
            return []
        search = self.base_link + '/search/search.php?' + urlencode({'q': data['title']})
        cards = _SeriesCards(imdb)
        cards.feed(scraper.scrapePage(search).text)
        for link in cards.links:
            parsed = urlparse(link)
            if (parsed.scheme == 'https' and parsed.netloc == 'vidsrc.sh' and
                    parsed.path == '/embed/tv' and
                    parse_qs(parsed.query).get('imdb') == [imdb]):
                # The player documents this exact-episode route. It is a lookup
                # locator, not a filename or proof of the eventual media identity.
                return [dict(url='https://vidsrc.sh/embed/tv/{}/{}/{}'.format(imdb, season, episode),
                             source='VidSrc', quality='SD', direct=False, imdb=imdb,
                             season=season, episode=episode)]
        return []
