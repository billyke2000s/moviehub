"""Read published catalogue cards and player props without executing site code."""
from html.parser import HTMLParser
import json
import re
from urllib.parse import urlsplit


class Catalogue(HTMLParser):
    def __init__(self):
        super().__init__()
        self.cards = []
        self.depth = 0
        self.heading = False
        self.card = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'div':
            if self.depth:
                self.depth += 1
            elif {'group', 'relative', 'w-full'} <= set(attrs.get('class', '').split()):
                self.depth = 1
                self.card = dict(url='', title='', text=[])
        if not self.depth:
            return
        if tag == 'a' and not self.card['url']:
            self.card['url'] = attrs.get('href', '')
        if tag == 'h3':
            self.heading = True

    def handle_data(self, text):
        if self.depth:
            self.card['text'].append(text.strip())
            if self.heading:
                self.card['title'] += text

    def handle_endtag(self, tag):
        if tag == 'h3':
            self.heading = False
        if tag == 'div' and self.depth:
            self.depth -= 1
            if not self.depth:
                self.cards.append(self.card)


def scalar(props, key):
    value = props.get(key, [])
    return value[1] if isinstance(value, list) and len(value) == 2 and value[0] == 0 else None


class Player(HTMLParser):
    def __init__(self, tmdb, series, season, episode):
        super().__init__()
        self.tmdb = str(tmdb)
        self.series = series
        self.season = str(int(season))
        self.episode = str(int(episode))
        self.links = []
        self.assets = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        component = attrs.get('component-url', '').rsplit('/', 1)[-1]
        expected = 'TVShowPlayer.' if self.series else 'BackdropPlayer.'
        if tag == 'astro-island' and component.startswith(('CompactServerSelector.', 'TVShowPlayer.')):
            self.assets.append(attrs['component-url'])
        if tag != 'astro-island' or not component.startswith(expected):
            return
        try:
            props = json.loads(attrs.get('props', '{}'))
            if self.series:
                result = scalar(props, 'result')
                config = scalar(props, 'activeServerConfig')
                if not isinstance(result, dict) or str(scalar(result, 'id')) != self.tmdb:
                    return
                if not isinstance(config, dict) or scalar(config, 'active') is not True:
                    return
                link = scalar(config, 'tvUrl')
                replacements = dict(id=self.tmdb, season=self.season, episode=self.episode, time='0')
                if isinstance(link, str):
                    for key, value in replacements.items():
                        link = link.replace('{' + key + '}', value)
            else:
                if str(scalar(props, 'mediaId')) != self.tmdb or scalar(props, 'mediaType') != 'movie':
                    return
                link = scalar(props, 'iframeUrl')
            if isinstance(link, str) and link.startswith('https://') and not re.search(r'[{}]', link):
                self.links.append(link)
        except (ValueError, TypeError, AttributeError):
            return


def template_link(template, tmdb, season, episode):
    if not isinstance(template, str):
        return None
    for key, value in dict(id=tmdb, season=season, episode=episode, time=0).items():
        template = template.replace('{' + key + '}', str(value))
    try:
        parsed = urlsplit(template)
        if (parsed.scheme == 'https' and parsed.hostname and not parsed.username and
                not parsed.password and parsed.port in (None, 443) and
                not re.search(r'[{}]', template)):
            return template
    except ValueError:
        pass
    return None


def bundled_servers(script):
    # Only flat literal arrays are data. Calls, expressions and executable values
    # cannot match this grammar and are never evaluated. Limit each candidate span.
    string = r'"(?:\\.|[^"\\])*"'
    value = r'(?:' + string + r'|-?\d+(?:\.\d+)?|true|false|null)'
    field = r'[A-Za-z_$][\w$]*\s*:\s*' + value
    obj = r'\{\s*' + field + r'(?:\s*,\s*' + field + r')*\s*\}'
    array = re.compile(r'\[\s*' + obj + r'(?:\s*,\s*' + obj + r')*\s*\]')
    fields = re.compile(r'([A-Za-z_$][\w$]*)\s*:\s*(' + value + r')')
    servers = []
    for start in re.finditer(r'\b[A-Za-z_$][\w$]*\s*=\s*(\[\s*\{\s*id\s*:)', script):
        match = array.match(script[start.start(1):start.start(1) + 20000])
        if not match:
            continue
        candidates = []
        for item in re.finditer(obj, match[0]):
            pairs = fields.findall(item[0])
            if len({key for key, _ in pairs}) != len(pairs):
                candidates = []
                break
            row = {key: json.loads(value) for key, value in pairs}
            if (not {'id', 'movieUrl', 'tvUrl'} <= row.keys() or
                    not row.keys() <= {'id', 'name', 'movieUrl', 'tvUrl', 'rank', 'active'}):
                candidates = []
                break
            candidates.append(row)
        servers.extend(row for row in candidates if row.get('active', True) is True)
    return servers
