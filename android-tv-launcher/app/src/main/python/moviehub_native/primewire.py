"""PrimeWire catalogue discovery: read linked players, never guess a media URL.

The user-supplied /watch/<title> pattern is a catalogue route, not a stream. Only
literal HTTPS player links present in the returned page become candidates. If the
site switches to an undocumented AJAX/JavaScript player API, fail closed until a
real response can be examined; a server button alone is not proof of playback.
"""
import html
import re
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse

from moviehub_compat import client
from .cinestream_api import Base, request
from .cinestream_catalogues import external


def slug(title):
    return re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')


def matching_title(page, title, year):
    # Refuse a similarly named film or an unrelated search result. A title and
    # year must both be present; movie-only until episode identity is verified.
    visible = re.sub(r'<[^>]+>', ' ', re.sub(r'<script\b[^>]*>.*?</script>', ' ', page,
                                               flags=re.I | re.S), flags=re.S)
    visible = re.sub(r'[^a-z0-9]+', ' ', html.unescape(visible).lower())
    wanted = re.sub(r'[^a-z0-9]+', ' ', title.lower()).strip()
    return bool(wanted and re.search(r'\b' + re.escape(wanted) + r'\b', visible)
                and re.search(r'\b' + re.escape(str(year)) + r'\b', visible))


class PlayerLinks(HTMLParser):
    """Discover HTML server controls without executing a website's scripts."""

    def __init__(self, page_url):
        super().__init__(convert_charrefs=True)
        self.page_url = page_url
        self.links = []
        self.seen = set()

    def add(self, value, label):
        if not isinstance(value, str) or len(value) > 4096:
            return
        value = html.unescape(value.strip())
        if not value or value.startswith(('javascript:', 'data:', '#')):
            return
        if value.startswith('//'):
            value = 'https:' + value
        elif value.startswith('/'):
            # Same-site player API paths must be fetched by an audited adapter,
            # not presented to ResolveURL as videos.
            return
        if not value.startswith('https://'):
            return
        target = urlparse(value)
        if target.hostname == urlparse(self.page_url).hostname:
            return
        try:
            value = client.safe(value)
        except Exception:
            return
        if value not in self.seen:
            self.seen.add(value)
            self.links.append((value, label or target.hostname or 'server'))

    def handle_starttag(self, tag, attributes):
        if tag not in ('a', 'button', 'iframe', 'div', 'li', 'source'):
            return
        attrs = dict(attributes)
        classes = attrs.get('class', '')
        candidate = (tag in ('iframe', 'source') or
                     bool(re.search(r'\b(server|player|source|embed)\b', classes, re.I)) or
                     any(key in attrs for key in ('data-server', 'data-player', 'data-embed')))
        if not candidate:
            return
        name = (attrs.get('data-server') or attrs.get('data-name') or
                attrs.get('title') or attrs.get('aria-label') or '')
        for field in ('data-url', 'data-src', 'data-embed', 'data-link',
                      'data-player', 'href', 'src'):
            if field in attrs:
                self.add(attrs[field], name)
        # Some player buttons contain an absolute URL in a literal onclick
        # argument. Extract only the URL, never evaluate JavaScript.
        for link in re.findall(r'https://[^\s\x22\x27<>]+', attrs.get('onclick', '')):
            self.add(link, name)


class PrimeWire(Base):
    base_link = 'https://primewire1.buzz'

    def extract(self, q):
        if 'season' in q:
            return  # No verified episode path or season/episode identity.
        page_url = self.base_link + '/watch/' + slug(q['title'])
        page = request(page_url, headers=self.headers())
        if not isinstance(page, str) or not matching_title(page, q['title'], q['year']):
            return
        parser = PlayerLinks(page_url)
        parser.feed(page[:2 * 1024 * 1024])
        for url, name in parser.links[:32]:
            client.check()
            yield external(url, page_url, 'PrimeWire ' + name)
