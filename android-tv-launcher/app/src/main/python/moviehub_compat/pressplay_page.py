"""Read published catalogue cards and player props without executing site code."""
from html.parser import HTMLParser
import json
import re


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

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        component = attrs.get('component-url', '').rsplit('/', 1)[-1]
        expected = 'TVShowPlayer.' if self.series else 'BackdropPlayer.'
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
