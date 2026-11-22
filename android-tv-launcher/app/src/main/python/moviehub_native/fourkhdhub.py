"""Observed 4KHDHub catalogue -> public redirect -> HubCloud -> individual media hosts.

No remote scripts execute. Literal landing payloads are decoded as data, and every request uses
MovieHub's bounded public-HTTPS transport. See the dated source audit for observations.
"""
import base64
import codecs
import html
import json
import re
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlparse, urlsplit, urlunsplit
from moviehub_compat import client
from moviehub_compat.current_hosts import direct
from moviehub_scrubs import cleantitle, dom_parser


def text(value):
    return html.unescape(re.sub('<[^>]+>', ' ', value)).strip()


def anchors(page):
    return dom_parser.parse_dom(page, 'a', req='href')


def public_link(base, value):
    url = urljoin(base, html.unescape(value))
    # The observed worker paths contain spaces and literal plus signs in filenames.
    parts = urlsplit(url)
    return client.safe(urlunsplit(parts._replace(path=quote(parts.path, safe='/%:@'))))


def landing_target(page):
    """The public landing page puts this same data into localStorage for its next page."""
    match = re.search(r"\bs\('o','([A-Za-z0-9+/=]{1,16000})'", page)
    if not match:
        return None
    first = base64.b64decode(match[1], validate=True)
    second = base64.b64decode(first, validate=True).decode('ascii')
    data = json.loads(base64.b64decode(codecs.decode(second, 'rot13'), validate=True))
    return base64.b64decode(data['o'], validate=True).decode('utf-8')


def follow_changed_mirror(entry, referer, filename, quality, info):
    """Fallback for public redirect/file-host layouts no longer handled by the original parser.

    Use the existing bounded, cookie-preserving CineStream traversal; never execute page scripts,
    treat a landing page as media, or promote a non-media/hosted link to direct playback.
    """
    from .cinestream_api import _HTTP, http_session
    from .cinestream_links import Links

    previous = http_session()
    session = client.requests.Session()
    session.trust_env = False
    _HTTP.session = session
    try:
        for row in Links('4KHDHub').follow(entry, referer, filename):
            if not row or not row.get('direct'):
                continue
            media_url = row['url'].split('|', 1)[0]
            if re.search(r'\.(?:zip|rar|7z)(?:$|\?)', media_url, re.I):
                continue
            row['filename'] = filename
            row['quality'] = quality
            row['info'] = info
            row['boundedRanges'] = not re.search(r'\.(?:m3u8|mpd)(?:$|\?)', media_url, re.I)
            # A file host may offer several mirrors. Publish each immediately,
            # then continue traversing the remaining host buttons for backups.
            yield row
    finally:
        _HTTP.session = previous
        session.close()


class source:
    base_link = 'https://4khdhub.one'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return json.dumps(dict(title=title, year=str(year), aliases=aliases))

    def sources(self, key, hosts):
        # Preserve the list-based provider contract for direct callers and existing tests.
        return list(self.iter_sources(key, hosts))

    def iter_sources(self, key, hosts):
        """Find mirrors on the live catalogue, skipping slow file-host chains."""
        info = json.loads(key)
        page = client.request(self.base_link + '/?' + urlencode({'s': info['title']}))
        found = None
        aliases = [{'title': info['title']}] + list(info.get('aliases') or [])
        for card in dom_parser.parse_dom(page, 'a', {'class': 'movie-card'}, req='href'):
            titles = dom_parser.parse_dom(card.content, 'h3', {'class': 'movie-card-title'})
            if not titles:
                titles = dom_parser.parse_dom(card.content, 'div', {'class': 'movie-card-title'})
            title = text(titles[0].content) if titles else card.attrs.get('aria-label', '').removesuffix(' details')
            years = dom_parser.parse_dom(card.content, 'div', {'class': 'movie-card-meta'})
            year_text = text(years[0].content) if years else text(card.content)
            if cleantitle.match_alias(title, aliases) and re.search(r'\b' + re.escape(info['year']) + r'\b', year_text):
                found = public_link(self.base_link, card.attrs['href'])
                break
        if found is None:
            return

        detail = client.request(found, referer=self.base_link + '/')
        for card in dom_parser.parse_dom(detail, 'div', {'class': 'download-item'}):
            client.check()
            labels = dom_parser.parse_dom(card.content, 'div', {'class': 'file-title'})
            filename = text(labels[0].content) if labels else text(card.content)
            quality = re.search(r'\b(2160|1080|720|480)p\b', filename, re.I)
            quality = quality[1] + 'p' if quality else 'SD'
            size = re.search(r'\b[0-9.]+\s*[GM]B\b', text(card.content))
            extra_info = (filename + (' · ' + size[0] if size else ''))[:500]
            for link in anchors(card.content):
                mirror_name = text(link.content)
                if not re.search(r'\bHub(?:Cloud|Drive)\b', mirror_name, re.I):
                    continue

                entry = None
                mirror_results = []
                try:
                    entry = public_link(found, link.attrs['href'])
                    parsed = urlparse(entry)
                    # The live 4KHDHub page currently advertises greenmotors.club
                    # ?id= links, which redirect to greenmotors.cc. These are
                    # mediators, not legacy HubCloud pages; use the shared redirect
                    # traversal directly instead of fetching each page twice.
                    migrated = (parsed.hostname in ('greenmotors.club', 'greenmotors.cc')
                                and 'id' in parse_qs(parsed.query))
                    # The live film can list 7 releases x 2 host buttons. Do not
                    # let one dead chain spend the full 400-second search budget.
                    # Collect results within this scope, then yield them after it
                    # ends so playback checks cannot inherit the mirror deadline.
                    with client.time_budget(10):
                        if migrated:
                            mirror_results.extend(
                                follow_changed_mirror(entry, found, filename, quality, extra_info))
                        else:
                            try:
                                landing = client.request(entry, referer=found)
                                target = landing_target(landing)
                                if target:
                                    entry = public_link(entry, target)
                                    landing = client.request(entry, referer=found)
                                host = urlparse(entry).hostname or ''
                                if not host.startswith('hubcloud.'):
                                    client.host_result(host, 'unsupported_host')
                                else:
                                    match = re.search(
                                        r'''\bvar\s+url\s*=\s*['"]([^'"]+)['"]''', landing)
                                    if not match:
                                        client.host_result(host, 'no_matches')
                                    else:
                                        files_url = public_link(entry, match[1])
                                        files = client.request(files_url, referer=entry)
                                        for media in anchors(files):
                                            label = text(media.content)
                                            if not any(value in label for value in
                                                       ('Download File', 'PixelServer', 'ZipDisk', 'FSL')):
                                                continue
                                            address = public_link(files_url, media.attrs['href'])
                                            if not re.search(r'\.(?:mkv|mp4|m3u8)(?:$|\?)', address, re.I):
                                                client.host_result(
                                                    urlparse(address).hostname or 'HubCloud mirror',
                                                    'unsupported_host')
                                                continue
                                            provider = 'HubCloud · ' + (urlparse(address).hostname or 'Media')
                                            item = direct(
                                                address, urlparse(entry).scheme + '://' + host + '/',
                                                provider, quality)
                                            item['info'] = extra_info
                                            item['boundedRanges'] = True
                                            mirror_results.append(item)
                            except client.Cancelled:
                                raise
                            except Exception as error:
                                client.host_result('HubCloud', type(error).__name__)
                            if not mirror_results and entry:
                                mirror_results.extend(
                                    follow_changed_mirror(entry, found, filename, quality, extra_info))
                except client.Cancelled:
                    # The temporary mirror deadline has been restored. If the
                    # user cancelled or the site budget expired, propagate it;
                    # otherwise record this slow mirror and try the next one.
                    client.check()
                    client.host_result('4KHDHub · ' + mirror_name, 'mirror_timeout')
                except Exception as error:
                    client.host_result('4KHDHub · ' + mirror_name, type(error).__name__)

                # Publish real candidates as soon as a mirror finishes, while
                # continuing to discover the remaining 4K and HD backups.
                yield from mirror_results

    def resolve(self, url):
        return url
