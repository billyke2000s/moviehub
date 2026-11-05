"""Read additional files from the selected film's explicitly advertised Archive directory."""
from html.parser import HTMLParser
from pathlib import PurePosixPath
import re
import xml.etree.ElementTree as ET
from urllib.parse import quote, unquote, urljoin, urlsplit


def rss_movie_link(xml, origin, aliases, match_alias):
    # The public search feed advertises actual post URLs, not guessed film slugs.
    if '<!DOCTYPE' in xml.upper() or len(xml) > 2 * 1024 * 1024:
        return None
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for item in root.findall('./channel/item')[:50]:
        title = item.findtext('title') or ''
        link = item.findtext('link') or ''
        try:
            target = urlsplit(link)
        except ValueError:
            continue
        if (target.scheme == 'https' and target.netloc == urlsplit(origin).netloc and
                not target.query and not target.fragment and target.path.endswith('.html') and
                match_alias(title, aliases)):
            return link
    return None


class Links(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.sources = []
        self.anchors = []
        self.feed(html[:2 * 1024 * 1024])

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'source' and attrs.get('src'):
            self.sources.append(attrs['src'])
        if tag == 'a' and attrs.get('href'):
            self.anchors.append(attrs['href'])


def archive_path(url):
    try:
        parts = urlsplit(url)
    except ValueError:
        return None
    if (parts.scheme != 'https' or parts.netloc != 'archive.org' or
            parts.query or parts.fragment or len(url) > 4096):
        return None
    path = unquote(parts.path)
    fields = path.split('/')
    if (len(fields) != 4 or fields[:2] != ['', 'download'] or
            not re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9_.-]*', fields[2])):
        return None
    return fields[2], fields[3]


def additional_files(page_html, fetch):
    page = Links(page_html)
    known = {}
    for source in page.sources:
        path = archive_path(source)
        if path and path[1]:
            known.setdefault(path[0], set()).add(PurePosixPath(path[1]).stem)
    directories = []
    for link in page.anchors:
        path = archive_path(link)
        if path and not path[1] and path[0] in known and link not in directories:
            directories.append(link)
    found = []
    for directory in directories[:2]:
        identity = archive_path(directory)[0]
        try:
            listing = Links(fetch(directory))
        except Exception:
            # The optional download route must not discard the embedded sources.
            continue
        for link in listing.anchors:
            try:
                url = urljoin(directory, link)
            except ValueError:
                continue
            path = archive_path(url)
            if not path or path[0] != identity or not path[1]:
                continue
            name = PurePosixPath(path[1])
            if name.stem not in known[identity] or name.suffix.lower() not in (
                    '.mp4', '.mpeg', '.mpg', '.m4v', '.ogv', '.webm', '.mkv', '.avi'):
                continue
            url = directory + quote(path[1], safe='')
            if url not in page.sources and url not in found:
                found.append(url)
            if len(found) >= 8:
                break
        if len(found) >= 8:
            break
    # A full MP4 is normally more seekable than the legacy Ogg embed. Preserve all choices.
    return sorted(found, key=lambda url: 0 if urlsplit(url).path.lower().endswith('.mp4') else 1)
