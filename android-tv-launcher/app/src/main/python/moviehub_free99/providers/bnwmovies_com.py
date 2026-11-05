from ast import literal_eval
# -*- coding: utf-8 -*-
import re
from moviehub_compat.bnwmovies_archive import additional_files, rss_movie_link

from six.moves.urllib_parse import parse_qs, urlencode

from moviehub_free99 import cleantitle
from moviehub_free99 import client
from moviehub_free99 import client_utils
from moviehub_free99 import scrape_sources
#from moviehub_free99 import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['bnwmovies.com']
        self.base_link = 'https://bnwmovies.com'
        self.search_link = '/?s=%s'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = literal_eval(data['aliases'])
            title = data['title']
            year = data['year']
            if int(year) > 1970:
                return self.results
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            try:
                response = client.scrapePage(search_url)
            except Exception:
                response = None
            if response is not None and getattr(response, "status_code", 200) in (401, 403):
                return self.results
            search_html = getattr(response, "text", "")
            results = client_utils.parseDOM(search_html, 'div', attrs={'class': 'post'})
            result = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a')) for i in results]
            result = [(i[0][0], i[1][0]) for i in result if len(i[0]) > 0 and len(i[1]) > 0]
            matches = [i[0] for i in result if cleantitle.match_alias(i[1], aliases)]
            page_url = matches[0] if matches else None
            if not page_url:
                feed_url = self.base_link + '/search/%s/feed/rss2/' % cleantitle.get_plus(title)
                feed = client.scrapePage(feed_url)
                if getattr(feed, 'status_code', 200) in (401, 403):
                    return self.results
                page_url = rss_movie_link(feed.text, self.base_link, aliases, cleantitle.match_alias)
            if not page_url:
                return self.results
            page_html = client.scrapePage(page_url).text
            # The current target page supplies its title/year separately from
            # recommendations. Do not substitute another year's same-named film.
            names = client_utils.parseDOM(page_html, 'span', attrs={'itemprop': 'name'})
            identities = [re.fullmatch(r'\s*(.*?)\s*\((\d{4})\)\s*',
                                      client_utils.remove_tags(name)) for name in names]
            identities = [match for match in identities if match]
            if identities and not any(cleantitle.match_alias(match[1], aliases) and
                                      match[2] == year for match in identities):
                return self.results
            embedded = client_utils.parseDOM(page_html, 'source', ret='src')
            links = additional_files(page_html, lambda url: client.scrapePage(url).text) + embedded
            for link in links[:10]:
                try:
                    item = scrape_sources.make_direct_item(hostDict, link, host=None, info=None, referer=page_url, prep=True)
                    if item and not any(row['url'] == item['url'] for row in self.results):
                        self.results.append(item)
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url


