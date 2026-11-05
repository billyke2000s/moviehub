"""AnyMovies' current series cards, shared by its existing two catalogue loaders."""
from html.parser import HTMLParser
from moviehub_identity import matches
import re
from urllib.parse import parse_qs, urlencode, urlparse, urljoin


class _SeriesCards(HTMLParser):
    def __init__(self, imdb):
        super().__init__(convert_charrefs=True)
        self.imdb = imdb
        self.depth = 0
        self.matched = False
        self.links = []
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'script' and attrs.get('src'):
            self.scripts.append(attrs['src'])
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


def advertised_players(script, imdb, season, episode):
    """Read only literal, exact-episode routes in the site's player switch."""
    found = []
    marker = chr(96)
    pattern = r'case\s+[0-9]+:\s*return\s+' + marker + r'(https://[^' + marker + r'\r\n]+)' + marker
    suffix = '/{}/{}/{}'.format(imdb, season, episode)
    for template in re.findall(pattern, script[:2 * 1024 * 1024]):
        url = template.replace('${imdbId}', imdb).replace('${season}', str(season)).replace(
            '${episode}', str(episode))
        if '${' in url or len(url) > 2048:
            continue
        try:
            parts = urlparse(url)
        except ValueError:
            continue
        if parts.scheme != 'https' or parts.fragment:
            continue
        query = parse_qs(parts.query, keep_blank_values=True)
        label = None
        if (parts.netloc == 'vidsrc.sh' and parts.path == '/embed/tv' and
                query == dict(imdb=[imdb], season=[str(season)], episode=[str(episode)])):
            label = 'VidSrc'
        elif parts.netloc == 'moviesapi.to' and parts.path == '/tv' + suffix and not query:
            label = 'MoviesAPI'
        elif (parts.netloc == 'vidfast.vc' and parts.path == '/tv' + suffix and
              query == dict(nextButton=['true'], autoNext=['true'])):
            label = 'VidFast'
        elif (parts.netloc == 'peachify.top' and parts.path == '/embed/tv' + suffix and
              query == dict(showNextBtn=['true'], autoNext=['true'])):
            label = 'Peachify'
        if label and not any(row['url'] == url for row in found):
            found.append(dict(url=url, source=label, direct=False, quality='SD'))
    return found

def resolve_player(player, data, resolve):
    """Reuse an existing protocol only after this site advertises that player."""
    tmdb = str(data.get('tmdb', ''))
    if player['source'] in ('VidFast', 'Peachify') and re.fullmatch(r'[1-9][0-9]{0,9}', tmdb):
        if player['source'] == 'VidFast':
            from moviehub_native.cinestream_api import Vidfast as Player
        else:
            from moviehub_native.cinestream_crypto import Peachify as Player
        provider = Player()
        key = provider.movie(data['imdb'], tmdb, data['title'], data['title'], [], data.get('year', ''))
        if 'season' in data:
            key = provider.episode(key, data['imdb'], tmdb, '', '', '', data['season'], data['episode'])
        return provider.sources(key, [])
    return resolve(player['url']) if resolve else []


def movie_player_sources(url, data):
    """Resolve a published movie button only when its identity matches this film."""
    try:
        parsed = urlparse(url)
    except ValueError:
        return None
    if parsed.scheme != 'https' or parsed.query or parsed.fragment:
        return None
    imdb, tmdb = data.get('imdb', ''), str(data.get('tmdb', ''))
    if not re.fullmatch(r'tt[0-9]{5,10}', imdb) or not re.fullmatch(r'[1-9][0-9]{0,9}', tmdb):
        return None
    label = None
    if parsed.netloc == 'vidfast.vc' and parsed.path == '/movie/' + imdb:
        label = 'VidFast'
    elif parsed.netloc == 'peachify.top' and parsed.path == '/embed/movie/' + tmdb:
        label = 'Peachify'
    if label is None:
        return None
    wanted = dict(imdb=imdb, tmdb=tmdb, season=-1)
    rows = resolve_player(dict(url=url, source=label), data, None) or []
    return [dict(row, imdb=imdb, tmdb=tmdb) for row in rows if matches(row, wanted)]


class SeriesLookup:
    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return urlencode(dict(imdb=imdb, tmdb=tmdb, title=title, year=year))

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        data = {key: values[0] for key, values in parse_qs(url).items()}
        data.update(season=season, episode=episode)
        return urlencode(data)

    def series_sources(self, data, scraper, resolve=None):
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
                identity = dict(imdb=imdb, season=season, episode=episode)
                results = [dict(url='https://vidsrc.sh/embed/tv/{}/{}/{}'.format(imdb, season, episode),
                                source='VidSrc', quality='SD', direct=False, **identity)]
                # The card anchor is only the default tab. Read the other tabs
                # from the first-party script explicitly advertised by this page.
                for script in cards.scripts[:20]:
                    try:
                        script_url = urljoin(self.base_link + '/', script)
                        parsed_script = urlparse(script_url)
                    except ValueError:
                        continue
                    if (parsed_script.scheme != 'https' or
                            parsed_script.netloc != urlparse(self.base_link).netloc or
                            parsed_script.path != '/js/series_info_box_for_movie.js' or
                            parsed_script.fragment):
                        continue
                    try:
                        players = advertised_players(scraper.scrapePage(script_url).text,
                                                     imdb, season, episode)
                    except Exception:
                        continue
                    # Try the small public API before protocols with several backends.
                    players.sort(key=lambda row: {'MoviesAPI': 0, 'Peachify': 1, 'VidFast': 2}.get(row['source'], 3))
                    for player in players:
                        if player['source'] == 'VidSrc':
                            continue
                        try:
                            resolved = resolve_player(player, data, resolve)
                        except Exception:
                            resolved = []
                        for item in resolved or [player]:
                            if not matches(item, identity):
                                continue
                            item = dict(item, **identity)
                            if not any(row['url'] == item['url'] for row in results):
                                results.append(item)
                    break
                return results
        return []
