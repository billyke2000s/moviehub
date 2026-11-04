from ast import literal_eval
# -*- coding: utf-8 -*-

import re
from urllib.parse import urljoin, urlsplit
from moviehub_compat.pressplay_page import Catalogue, Player, bundled_servers, template_link

from six.moves.urllib_parse import parse_qs, urlencode

from moviehub_scrubs import cleantitle
from moviehub_scrubs import client
from moviehub_scrubs import scrape_sources
#from moviehub_scrubs import log_utils

class source:
    def __init__(self):
        self.results = []
        self.domains = ['pressplay.top', 'pressplayz.top']
        self.base_link = 'https://pressplayz.top'
        self.search_link = '/explore?q=%s'
        self.notes = 'Published Astro catalogue/player data; downstream hosts require separate verification.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        if not url:
            return self.results
        data = {key: value[0] for key, value in parse_qs(url).items()}
        series = 'tvshowtitle' in data
        title = data['tvshowtitle'] if series else data['title']
        aliases = literal_eval(data['aliases']) or [{'title': title}]
        catalogue = Catalogue()
        catalogue.feed(client.request(self.base_link + '/explore?' + urlencode({'q': title})))
        prefix = '/watch/tv-' if series else '/watch/movie-'
        checked = 0
        for card in catalogue.cards:
            if not cleantitle.match_alias(card['title'], aliases):
                continue
            if data['year'] not in card['text']:
                continue
            page_url = urljoin(self.base_link, card['url'])
            parsed = urlsplit(page_url)
            if (parsed.scheme != 'https' or parsed.hostname != urlsplit(self.base_link).hostname or
                    parsed.username or parsed.password or parsed.port not in (None, 443) or
                    not parsed.path.startswith(prefix)):
                continue
            checked += 1
            player = Player(data['tmdb'], series, data.get('season', '1'), data.get('episode', '1'))
            player.feed(client.request(page_url))
            links = list(player.links)
            if links:
                # The live selector's failed API falls back to these published
                # literal configs. Read data only; never run the downloaded JS.
                for asset in player.assets[:1]:
                    asset_url = urljoin(page_url, asset)
                    if not self._site_asset(asset_url):
                        continue
                    try:
                        module = client.request(asset_url, referer=page_url)
                        imports = re.findall(r'from["\'](\./app\.[A-Za-z0-9_-]+\.js)["\']', module)
                        if not imports:
                            continue
                        bundle_url = urljoin(asset_url, imports[0])
                        if not self._site_asset(bundle_url):
                            continue
                        configs = bundled_servers(client.request(bundle_url, referer=page_url))
                        field = 'tvUrl' if series else 'movieUrl'
                        for config in configs:
                            link = template_link(config.get(field), data['tmdb'],
                                                 data.get('season', '1'), data.get('episode', '1'))
                            if link:
                                links.append(link)
                    except Exception:
                        # Keep the independently validated page-selected player.
                        continue
            for link in dict.fromkeys(links):
                try:
                    self.results.extend(scrape_sources.process(hostDict, link))
                except Exception:
                    continue
            if player.links or checked >= 4:
                break
        return self.results


    def _site_asset(self, url):
        parsed = urlsplit(url)
        return (parsed.scheme == 'https' and parsed.netloc == urlsplit(self.base_link).netloc and
                re.fullmatch(r'/_astro/[A-Za-z0-9._-]+\.js', parsed.path) is not None and
                not parsed.query and not parsed.fragment)

    def resolve(self, url):
        return url


