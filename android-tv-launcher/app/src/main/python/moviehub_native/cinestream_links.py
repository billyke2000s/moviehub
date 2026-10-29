"""CineStream 487 redirect/file-host protocols, CSX@13903aa (GPL-3.0-or-later).

Used only by the additional catalogue adapters. Ordinary redirects are followed;
forms, cookie tokens and literal player data are interpreted without executing JS.
Existing scrapers, ResolveURL and 4KHDHub's own resolver remain unchanged.
"""
import base64
import codecs
import html
import json
import re
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlparse

from moviehub_compat import client
from .cinestream_api import http_session, item, USER_AGENT
from .cinestream_catalogues import external
from .cinestream_dom import tree

MEDIA = re.compile(r'\.(?:mp4|mkv|webm|m3u8|mpd|m4v)(?:$|[?])', re.I)
HOSTS = ('hubcloud.', 'vcloud.', 'hubdrive.', 'gdflix.', 'gdlink.', 'driveleech.',
         'driveseed.', 'linksmod.', 'fastdlserver.', 'fastdl.', 'hblinks.', 'howblogs.',
         'pixeldrain.', 'pixeldra.', 'hshare.', 'hubcdn.')
CHALLENGE = re.compile(r'cf-chl-|<title>\s*Just a moment|Verify you are human', re.I)


def host_matches(host, prefixes):
    return any(re.search(r'(?:^|\.)' + re.escape(prefix), host) for prefix in prefixes)


def clean(value):
    return re.sub(r'[\U0001f000-\U0001ffff\u2600-\u27ff]', '', str(value)).strip()[:700]


def absolute(base, value):
    return client.safe(urljoin(base, html.unescape(value).strip()))


def same_origin(base, value):
    target = absolute(base, value)
    if urlparse(base).netloc != urlparse(target).netloc:
        raise ValueError('Form token origin changed')
    return target


def decode64(value):
    if len(value) > 32000:
        raise ValueError('Redirect data limit')
    return base64.b64decode(value + '=' * (-len(value) % 4)).decode('utf-8')


def landing(page):
    chunks = re.findall(r"s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)'", page)
    if not chunks:
        return None
    value = ''.join(a or b for a, b in chunks)
    data = json.loads(decode64(codecs.decode(decode64(decode64(value)), 'rot13')))
    return decode64(data['o']).strip() if data.get('o') else None


def landing_form(doc):
    return next((form for form in doc.all('form') if form.attrs.get('id') in
                 ('landing', 'lp-land', 'lp-s1-form', 'lp-s2-form', 'lp-s3-form')), None)


def quality(filename):
    match = re.search(r'\b(2160|1080|720|480|360)p\b', filename, re.I)
    return match[1] + 'p' if match else ''


class Links:
    """One bounded, same-session traversal for a catalogue lookup."""
    def __init__(self, name):
        self.name = name
        self.seen = set()
        self.blocked = set()
        self.pages = 0

    def read(self, url, referer, post=None, json_body=False):
        client.check()
        host = urlparse(url).netloc
        if host in self.blocked:
            raise ValueError('Host requires browser interaction')
        if self.pages >= 40:
            raise ValueError('Redirect page budget')
        self.pages += 1
        headers = {'Referer': referer or url, 'User-Agent': USER_AGENT}
        if post is not None:
            headers['Origin'] = urlparse(url).scheme + '://' + host
            headers['X-Requested-With'] = 'XMLHttpRequest'
        if json_body:
            headers['Content-Type'] = 'application/json'
            post = json.dumps(post)
        response = client.fetch(url, headers, post=post, redirect=False,
                                max_bytes=1024 * 1024, session=http_session())
        body = response.content.decode('utf-8', errors='replace')
        if CHALLENGE.search(body):
            self.blocked.add(host)
            client.host_result(self.name + ' · ' + host, 'interaction_required')
            raise ValueError('Host requires browser interaction')
        if response.status_code >= 400:
            client.host_result(self.name + ' · ' + host, 'http_' + str(response.status_code))
            raise ValueError('Host request refused')
        return response, body

    def media(self, url, referer, info='', backend=''):
        label = self.name + ' · ' + (backend or urlparse(url).hostname or 'file')
        row = item(url, {'Referer': referer or url, 'User-Agent': USER_AGENT},
                   label, quality(info), clean(info))
        if row:
            row['filename'] = clean(info)
        return row

    def follow(self, url, referer='', info='', depth=0):
        client.check()
        url = absolute(referer or url, url)
        identity = (url, referer, info)
        if identity in self.seen or depth > 9:
            return
        self.seen.add(identity)
        try:
            yield from self.resolve(url, referer, info, depth)
        except client.Cancelled:
            raise
        except Exception as error:
            client.host_result(self.name + ' · ' + (urlparse(url).hostname or 'redirect'),
                               type(error).__name__)

    def resolve(self, url, referer, info, depth):
        if MEDIA.search(urlparse(url).path):
            yield self.media(url, referer, info)
            return
        host = (urlparse(url).hostname or '').removeprefix('www.')
        if host_matches(host, ('pixeldrain.', 'pixeldra.')):
            media = url if '/api/file/' in url else urlparse(url)._replace(
                path='/api/file/' + urlparse(url).path.rstrip('/').rsplit('/', 1)[-1],
                query='download').geturl()
            yield self.media(media, referer, info, 'Pixeldrain')
            return
        if not host_matches(host, HOSTS):
            import resolveurl
            terminal = resolveurl.HostedMediaFile(url=url, include_universal=False, include_popups=False)
            if terminal.valid_url():
                row = external(url, referer or url, self.name + ' · ' + host)
                row.update(info=clean(info), filename=clean(info))
                yield row
                return
        response, page = self.read(url, referer)
        if response.status_code in (301, 302, 303, 307, 308):
            target = response.headers.get('Location')
            if target:
                yield from self.follow(target, url, info, depth + 1)
            return
        kind = response.headers.get('Content-Type', '').lower()
        if page.lstrip('\ufeff\r\n ').startswith('#EXTM3U') or kind.startswith(('video/', 'audio/')):
            yield self.media(url, referer, info)
            return
        doc = tree(page)
        metadata = [node.text() for node in doc.all('li,p')
                    if re.match(r'^(?:Name|Size)\s*:', node.text(), re.I)]
        header = doc.first('div', cls='card-header') or doc.first('h1', cls='file-title')
        if metadata or header:
            info = clean(' · '.join(metadata) if metadata else header.text())
        targets = []

        def add(value, direct=False, label=''):
            if value and not value.startswith(('#', 'javascript:', 'mailto:')):
                targets.append((value, direct, label))

        decoded = landing(page)
        if decoded:
            add(decoded)
        # Some public link wrappers expose the destination as a literal JSON value.
        encoded_link = re.search(r'"link"\s*:\s*"([A-Za-z0-9+/=_-]+)"', page)
        if encoded_link:
            value = decode64(encoded_link[1])
            if value.startswith('https://'):
                add(value)
        for pattern in (r"\bvar\s+url\s*=\s*atob\(\s*atob\(\s*['\"]([^'\"]+)['\"]",
                        r"\bvar\s+(?:url|reurl)\s*=\s*['\"]([^'\"]+)['\"]",
                        r"(?:window\.)?location\.(?:replace|assign)\(\s*['\"]([^'\"]+)['\"]"):
            match = re.search(pattern, page)
            if match:
                add(decode64(decode64(match[1])) if 'atob' in pattern else match[1])
        for node in doc.all('meta'):
            if (node.attrs.get('http-equiv') or '').lower() == 'refresh':
                match = re.search(r'url\s*=\s*[\'\"]?(.+?)[\'\"]?$', node.attrs.get('content') or '', re.I)
                if match:
                    add(match[1])

        form = landing_form(doc)
        if form is not None:
            # Hrefli's two ordinary POST forms retain their issued cookie session.
            current = url
            fields = {}
            for _ in range(6):
                if form is None:
                    break
                fields = {n.attrs['name']: n.attrs.get('value') or ''
                          for n in form.all('input') if n.attrs.get('name')}
                action = same_origin(current, form.attrs.get('action') or current)
                result, body = self.read(action, current, fields)
                if result.status_code in (301, 302, 303):
                    add(absolute(action, result.headers.get('Location', '')))
                    break
                current = action
                form = landing_form(tree(body))
                page = body
            go = re.search(r'\?go=([^\s"\']+)', page)
            if go and http_session() is not None:
                # Cookie is scoped to the issuing host, never copied to media hosts.
                http_session().cookies.set(go[1], fields.get('_wp_http2', ''),
                                           domain=urlparse(current).hostname, path='/')
                add(absolute(current, '?' + urlencode({'go': go[1]})))
            else:
                for node in tree(page).all('meta'):
                    if (node.attrs.get('http-equiv') or '').lower() == 'refresh':
                        match = re.search(r'url\s*=\s*(.+)$', node.attrs.get('content') or '', re.I)
                        if match:
                            add(absolute(current, match[1].strip('\'"')))

        if host_matches(host, ('gdflix.', 'gdlink.', 'driveleech.', 'driveseed.', 'hubcloud.', 'vcloud.')):
            for anchor in doc.all('a'):
                address = anchor.attrs.get('href') or ''
                label = anchor.text()
                if re.search(r'FSL|DIRECT DL|DIRECT SERVER|Cloud Download|Mega Server|Download File|PixelServer', label, re.I):
                    add(address, True, label)
                elif re.search(r'GD Index|Direct Links', label, re.I):
                    for value in (1, 2):
                        target = absolute(url, address)
                        add(target + ('&' if '?' in target else '?') + 'type=' + str(value))
                elif re.search(r'FAST CLOUD|ZipDisk|Generate Direct Download Link|Resume Cloud|Resume Worker Bot|Buzz Server|Gofile', label, re.I):
                    add(address)
            token = re.search(r"formData\.append\('token',\s*'([^']+)'\)", page)
            endpoint = re.search(r"fetch\('(/download\?id=[^']+)'", page)
            if token and endpoint:
                target = same_origin(url, endpoint[1])
                _, body = self.read(target, url, {'token': token[1]})
                add(json.loads(body).get('url'))

        # Zink's archive pages advertise a public random-id exchange and worker API.
        random_id = re.search(r"generateDownloadLink\(['\"]([^'\"]+)", page)
        ajax = re.search(r'https://[^\s"\'<>]+ajax_generate_token\.php', page)
        download = re.search(r'https://[^\s"\'<>]+/dl/', page)
        if random_id and ajax and download:
            endpoint = same_origin(url, ajax[0])
            _, body = self.read(endpoint + '?' + urlencode({'random_id': random_id[1]}),
                                url, {'random_id': random_id[1]})
            token = json.loads(body).get('token')
            if isinstance(token, str) and token:
                add(download[0] + quote(token, safe=''))
        worker = doc.first('a', id='worker-btn') or doc.first('button', id='worker-btn')
        if worker is not None:
            identity = re.search(r"handleServerRequest\(['\"]worker['\"]\s*,\s*['\"]([^'\"]+)",
                                 worker.attrs.get('onclick') or '')
            handler = re.search(r"SERVER_HANDLER_URL\s*=\s*['\"]([^'\"]+)", page)
            if identity and handler:
                target = same_origin(url, handler[1])
                _, body = self.read(target, url, {'server': 'worker', 'random_id': identity[1]}, True)
                result = json.loads(body)
                value = result.get('url') or result.get('download')
                add(value, True, 'Worker')

        signer = re.search(r"AJAX_URL\s*=\s*['\"]([^'\"]+)", page)
        signed_links = set()
        if signer and 'hindshare_sign' in page:
            endpoint = same_origin(url, signer[1])
            for anchor in doc.all('a', cls='get-link-btn'):
                address = anchor.attrs.get('href') or ''
                parsed = urlparse(address)
                raw = parse_qs(parsed.query).get('id', [''])[0]
                if parsed.hostname not in ('hshare.lol', 'hshare.ink') or not raw:
                    continue
                encoded = base64.urlsafe_b64encode(raw.encode()).decode().rstrip('=')
                _, body = self.read(endpoint, url, {'action': 'hindshare_sign', 'd': encoded})
                data = json.loads(body)
                if data.get('success') and isinstance(data.get('data'), dict):
                    add(data['data'].get('url'))
                    signed_links.add(address)

        for anchor in doc.all('a'):
            address = anchor.attrs.get('href') or ''
            if address in signed_links:
                continue
            cls = anchor.attrs.get('class') or ''
            label = anchor.text()
            is_host = host_matches(urlparse(urljoin(url, address)).hostname or '', HOSTS)
            if MEDIA.search(urlparse(address).path):
                add(address, True)
            elif is_host or re.search(r'btn-success|btn-info|buttonDownloadnew|get-link-btn', cls):
                add(address)
            elif re.search(r'view-well|center_it|mirror-buttons', anchor.parent.attrs.get('class', '') or ''):
                add(address)
            elif re.search(r'maxbutton|\bbutton\b|\bbtn\b|\bDbtn\b', cls) and re.search(
                    r'Server|Download|G-Drive|V-Cloud|Get Link', label, re.I):
                add(address)
            elif 'watch2=1' in address:
                add(parse_qs(urlparse(address).query).get('url', [address])[0])
        # Fibwatch's player/iframe data. Literal packed strings are not executed.
        for frame in doc.all('iframe'):
            address = frame.attrs.get('src') or ''
            if address and not re.search(r'youtube|googletag|doubleclick', address, re.I):
                add(address)
        for match in re.finditer(r'https?://[a-zA-Z0-9.-]+/[^\s"\'`<>]+\.(?:m3u8|mp4|mkv)(?:\?[^\s"\'`<>]*)?', page):
            if MEDIA.search(urlparse(match[0]).path):
                add(match[0], True)
        seen = set()
        for value, direct, label in targets[:64]:
            target = absolute(url, value)
            identity = target
            if identity in seen:
                continue
            seen.add(identity)
            if direct:
                yield self.media(target, url, info, clean(label))
            else:
                yield from self.follow(target, url, info, depth + 1)
        if not targets:
            client.host_result(self.name + ' · ' + host, 'no_advertised_file_or_supported_terminal')
