"""Select only an advertised Levidia episode from the requested season page."""
import re
from urllib.parse import parse_qs, parse_qsl, urlencode, urlsplit, urlunsplit


def season_page(url, season):
    parts = urlsplit(url)
    query = [(key, value) for key, value in parse_qsl(parts.query) if key != 's']
    query.append(('s', str(int(season))))
    return urlunsplit(parts._replace(query=urlencode(query)))


def episode_link(links, season, episode):
    pattern = re.compile(r'(?:^|-)s0*%de0*%d(?:-|$)' % (int(season), int(episode)), re.I)
    for link in links:
        parts = urlsplit(link)
        if parts.path.rsplit('/', 1)[-1] != 'tv-episode.php':
            continue
        watch = parse_qs(parts.query).get('watch', [])
        if len(watch) == 1 and pattern.search(watch[0]):
            return link
    return None
