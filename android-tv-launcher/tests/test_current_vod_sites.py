"""Regressions for current catalogue and separate player-provider contracts."""
import base64
import hashlib
import importlib
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import requests
import moviehub_vod as vod
from moviehub_compat import client, current_hosts
from moviehub_scrubs import client as scraper_client


class CurrentSiteTests(unittest.TestCase):
    def test_bnwmovies_does_not_retry_a_refused_search_through_rss(self):
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.bnwmovies_com')
            for status in (401, 403):
                provider = module.source()
                key = provider.movie('tt0038991', '20246', 'The Stranger', 'The Stranger',
                                     [{'title': 'The Stranger'}], '1946')
                with self.subTest(family=family, status=status), patch.object(
                        module.client, 'scrapePage', return_value=SimpleNamespace(status_code=status, text='')) as fetch:
                    self.assertEqual(provider.sources(key, []), [])
                    self.assertEqual(fetch.call_count, 1)

    def test_bnwmovies_uses_current_search_feed_after_primary_server_error(self):
        feed = ('<rss><channel><item><title>Hands Of A Stranger</title><link>'
                'https://bnwmovies.com/hands-of-a-stranger.html</link></item>'
                '<item><title>The Stranger</title><link>https://bnwmovies.com/the-stranger.html'
                '</link></item></channel></rss>')
        page = ('<span itemprop="name">The Stranger (1946)</span>'
                '<source src="https://archive.org/download/film/Movie.mp4">')
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.bnwmovies_com')
            provider = module.source()
            key = provider.movie('tt0038991', '20246', 'The Stranger', 'The Stranger',
                                 [{'title': 'The Stranger'}], '1946')
            with self.subTest(family=family), patch.object(module.client, 'scrapePage', side_effect=[
                    SimpleNamespace(status_code=502, text='Bad gateway'),
                    SimpleNamespace(status_code=200, text=feed), SimpleNamespace(text=page)]) as fetch, \
                    patch.object(module.scrape_sources, 'make_direct_item',
                        side_effect=lambda hosts, link, **kwargs: dict(source='archive.org', url=link)):
                self.assertEqual(len(provider.sources(key, [])), 1)
                self.assertIn('/search/', fetch.call_args_list[1].args[0])
                self.assertTrue(fetch.call_args_list[1].args[0].endswith('/feed/rss2/'))
                self.assertEqual(fetch.call_args.args[0], 'https://bnwmovies.com/the-stranger.html')

    def test_bnwmovies_feed_rejects_external_or_credentialled_film_links(self):
        from moviehub_compat.bnwmovies_archive import rss_movie_link
        for link in ['https://evil.example/the-stranger.html',
                     'https://user:pass@bnwmovies.com/the-stranger.html',
                     'https://bnwmovies.com/the-stranger.html?token=bad']:
            feed = '<rss><channel><item><title>The Stranger</title><link>' + link + '</link></item></channel></rss>'
            self.assertIsNone(rss_movie_link(feed, 'https://bnwmovies.com', [], lambda title, aliases: True))
        self.assertIsNone(rss_movie_link('<!DOCTYPE rss><rss/>', 'https://bnwmovies.com', [],
                                        lambda title, aliases: True))

    def test_bnwmovies_discovers_advertised_full_files_in_both_existing_loaders(self):
        search = '<div class="post"><a href="https://bnwmovies.com/the-stranger.html">The Stranger</a></div>'
        directory = 'https://archive.org/download/TheStranger_0/'
        page = ('<span itemprop="name">The Stranger (1946)</span>'
                '<source src="' + directory + 'The_Stranger.ogv">'
                '<source src="' + directory + 'The_Stranger_512kb.mp4">'
                '<a href="' + directory + '">Download</a>')
        listing = ('<a href="The_Stranger.MPEG">Original</a>'
                   '<a href="The_Stranger.mp4">Full MP4</a>'
                   '<a href="The_Stranger.ogv">Ogg</a>'
                   '<a href="The_Stranger_512kb.mp4">Small MP4</a>')
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.bnwmovies_com')
            provider = module.source()
            key = provider.movie('tt0038991', '20246', 'The Stranger', 'The Stranger',
                                 [{'title': 'The Stranger'}], '1946')
            with self.subTest(family=family), \
                    patch.object(module.client, 'scrapePage', side_effect=[
                        SimpleNamespace(text=search), SimpleNamespace(text=page), SimpleNamespace(text=listing)]) as fetch, \
                    patch.object(module.scrape_sources, 'make_direct_item',
                        side_effect=lambda hosts, link, **kwargs: dict(source='archive.org', url=link)), \
                    patch.object(module.scrape_sources, 'check_host_limit',
                        side_effect=lambda host, rows: len(rows) >= 3):
                links = [row['url'] for row in provider.sources(key, [])]
                self.assertEqual(links, [directory + name for name in [
                    'The_Stranger.mp4', 'The_Stranger.MPEG', 'The_Stranger.ogv', 'The_Stranger_512kb.mp4']])
                self.assertEqual(fetch.call_args.args[0], directory)

    def test_bnwmovies_download_route_is_scoped_to_the_embedded_film(self):
        from moviehub_compat.bnwmovies_archive import additional_files
        directory = 'https://archive.org/download/film/'
        page = ('<source src="' + directory + 'Movie.ogv">'
                '<a href="' + directory + '">Download</a>'
                '<a href="https://archive.org/download/another/">Other</a>'
                '<a href="https://user:pass@archive.org/download/film/">Bad</a>')
        listing = ''.join('<a href="' + link + '">File</a>' for link in [
            'Movie.mp4', 'Movie.mp4', 'Movie.MPEG', 'Movie_trailer.mp4', 'Another.mp4',
            '../another/Movie.mp4', 'https://example.com/Movie.mp4',
            'https://archive.org/download/film/Movie.mp4?token=bad', '%2e%2e%2fMovie.mp4',
            'Movie.mp3', 'https://[bad'])
        calls = []
        result = additional_files(page, lambda url: calls.append(url) or listing)
        self.assertEqual(calls, [directory])
        self.assertEqual(result, [directory + 'Movie.mp4', directory + 'Movie.MPEG'])

    def test_bnwmovies_does_not_guess_or_require_a_download_directory(self):
        from moviehub_compat.bnwmovies_archive import additional_files
        page = '<source src="https://archive.org/download/film/Movie.ogv">'
        self.assertEqual(additional_files(page, lambda url: self.fail('Guessed directory')), [])
        def failed(url):
            raise OSError('Optional listing unavailable')
        self.assertEqual(additional_files(page + '<a href="https://archive.org/download/film/">Files</a>',
                                          failed), [])

    def test_levidia_loaders_select_exact_advertised_episode_on_requested_season(self):
        for family in ('free99', 'scrubs'):
            for present in (True, False):
                with self.subTest(family=family, present=present):
                    module = importlib.import_module('moviehub_' + family + '.providers.levidia_ch')
                    provider = module.source()
                    calls = []
                    def request(session, method, url, **kwargs):
                        calls.append(url)
                        response = requests.Response()
                        response.status_code = 200
                        response.url = url
                        response.headers['Content-Type'] = 'text/html'
                        if 'search.php' in url:
                            html = '<div class="mainlink"><a href="tv-show.php?watch=breaking-bad">Breaking Bad (2008)</a></div>'
                        elif 'tv-show.php' in url:
                            self.assertIn('s=2', url)
                            html = ('<li class="mlist links"><a href="tv-episode.php?watch=breaking-bad-s2e10-over">Ten</a></li>'
                                    '<li class="mlist links"><a href="tv-episode.php?watch=breaking-bad-s12e1-other">Other season</a></li>')
                            if present:
                                html += '<li class="mlist links"><a href="tv-episode.php?watch=breaking-bad-S02E01-seven-thirtyseven">One</a></li>'
                        elif 'tv-episode.php' in url:
                            self.assertIn('S02E01-', url)
                            html = ('<li class="xxx0"><span class="kiri xxx1 xx12">Fixture host</span>'
                                    '<a class="xxx xflv" target="_blank" href="https://host.example/embed">Play</a></li>')
                        else:
                            html = ''
                        response._content = html.encode()
                        response._content_consumed = True
                        return response
                    key = provider.tvshow('tt0903747', '1396', '', 'Breaking Bad', '', [{'title': 'Breaking Bad'}], '2008')
                    key = provider.episode(key, 'tt0903747', '1396', '', 'Seven Thirty-Seven', '2009-03-08', 2, 1)
                    item = dict(url='https://media.example/episode.mp4', source='fixture', direct=True)
                    client.scope(lambda: False)
                    with patch.object(requests.Session, 'request', new=request), \
                            patch.object(client, 'safe', side_effect=lambda url: url), \
                            patch.object(module.scrape_sources, 'process', return_value=[item]) as process:
                        result = provider.sources(key, [])
                    self.assertEqual(len(result or []), 1 if present else 0)
                    self.assertEqual(process.call_count, 1 if present else 0)
                    self.assertFalse(any('s2e10-' in call or 's12e1-' in call for call in calls))

    def test_levidia_season_url_replaces_existing_selector_without_losing_watch(self):
        from moviehub_compat.levidia_episode import episode_link, season_page
        self.assertEqual(season_page('https://www.levidia.ch/tv-show.php?watch=fixture&s=1', 2),
                         'https://www.levidia.ch/tv-show.php?watch=fixture&s=2')
        self.assertIsNone(episode_link(['tv-episode.php?watch=fixture-s2e10',
                                       'movie.php?watch=fixture-s2e1',
                                       'tv-episode.php?watch=fixture-s2e1&watch=other'], 2, 1))

    def test_fzmovies_uses_exact_imdb_and_resolves_root_relative_download_hops(self):
        from moviehub_free99.providers import fzmovies_live
        search = ('<div class="mainbox"><a id="wrong" href="/movie/wrong">Film</a>'
                  '<span class="imdbRatingPlugin" data-title="tt13756660"></span></div>'
                  '<div class="mainbox"><a id="correct" href="/movie/correct">Film</a>'
                  '<span class="imdbRatingPlugin" data-title="tt1375666"></span></div>')
        calls = []
        def page(url, **kwargs):
            calls.append(url)
            if 'advancedsearch.php' in url:
                return SimpleNamespace(text=search)
            self.assertEqual(url, 'https://www.fzmovies.live/movie/correct')
            return SimpleNamespace(text='<a id="dlink" href="/hop/one">WebM</a>')
        def request(url, **kwargs):
            calls.append(url)
            if kwargs.get('output') == 'cookie':
                return 'fixture-cookie'
            if url.endswith('/hop/one'):
                return '<a id="dlink" href="/hop/two">MP4</a>'
            if url.endswith('/hop/two'):
                return '<a id="dlink" href="//www.fzmovies.live/hop/three">MP4</a>'
            self.assertEqual(url, 'https://www.fzmovies.live/hop/three')
            return '<input name="download" value="https://media.example/movie.mp4">'
        provider = fzmovies_live.source()
        key = provider.movie('tt1375666', '27205', 'Inception', '', [], '2010')
        with patch.object(fzmovies_live.client, 'scrapePage', side_effect=page), \
                patch.object(fzmovies_live.client, 'request', side_effect=request), \
                patch.object(fzmovies_live.scrape_sources, 'make_direct_item',
                    side_effect=lambda hosts, link, **kwargs: dict(source='Direct', url=link)) as direct:
            result = provider.sources(key, [])
        self.assertEqual([item['url'] for item in result], ['https://media.example/movie.mp4'])
        self.assertFalse(any('/movie/wrong' in call for call in calls))
        self.assertEqual(direct.call_args.kwargs['info'], 'WebM')

    def test_fzmovies_stays_in_shared_inventory_with_explicit_unsupported_tv_result(self):
        rows = json.loads(vod.search_providers())
        self.assertEqual(len(rows), 74)
        row = next(row for row in rows if row['id'] == 'free99:fzmovies_live')
        self.assertTrue(row['movies'])
        self.assertFalse(row['series'])
        query = dict(title='Breaking Bad', year='2008', imdb='tt0903747', tmdb=1396,
                     season=1, episode=1)
        result = json.loads(vod.query_site(row['id'], json.dumps(query), lambda: False))
        self.assertEqual(result['status'], 'unsupported_type')
        self.assertEqual(result['sources'], [])

    def test_bnwmovies_rejects_another_year_without_losing_both_advertised_formats(self):
        search = '<div class="post"><h3><a href="https://bnwmovies.com/the-stranger.html">The Stranger</a></h3></div>'
        page = ('<span itemprop="name">The Stranger (1946)</span>'
                '<source src="https://archive.org/download/film/movie.ogv">'
                '<source src="https://archive.org/download/film/movie.mp4">'
                '<div class="tombstone">Another film (1955)</div>')
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.bnwmovies_com')
            for year, count in [('1946', 2), ('1955', 0)]:
                provider = module.source()
                key = provider.movie('tt0038991', '20246', 'The Stranger', 'The Stranger',
                                     [{'title': 'The Stranger'}], year)
                responses = [SimpleNamespace(text=search), SimpleNamespace(text=page)]
                with self.subTest(family=family, year=year), \
                        patch.object(module.client, 'scrapePage', side_effect=responses), \
                        patch.object(module.scrape_sources, 'make_direct_item',
                            side_effect=lambda hosts, link, **kwargs: dict(source='archive.org', url=link)), \
                        patch.object(module.scrape_sources, 'check_host_limit', return_value=False):
                    self.assertEqual(len(provider.sources(key, [])), count)

    def test_distinct_hosts_sharing_a_media_url_remain_separate_in_website_results(self):
        module = importlib.import_module('moviehub_free99.providers.levidia_ch')
        class Provider:
            base_link = 'https://www.current-catalogue.example'
            def movie(self, *args): return 'lookup'
            def sources(self, key, hosts):
                return [dict(source=host, url='https://cdn.example/video.m3u8', direct=True,
                             segmentContainers=True, boundedRanges=True) for host in ['Host One', 'Host Two']]
            def resolve(self, url): return url
        raw = json.dumps(dict(title='Film', year='2020', imdb='tt123', tmdb=1, season=-1, episode=-1))
        def playable(url, quality, expected_seconds=0): return dict(url=url, headers={}, verifiedQuality=None)
        with patch.object(module, 'source', return_value=Provider()), patch.object(vod, 'playable', side_effect=playable):
            result = json.loads(vod.query_site('free99:levidia_ch', raw, lambda: False))
        self.assertEqual({x['providerName'] for x in result['sources']},
                         {'current-catalogue.example · Host One', 'current-catalogue.example · Host Two'})
        self.assertTrue(all(x['website'] == 'current-catalogue.example' and x['catalogue'] == 'levidia.ch' and x['segmentContainers'] and x['boundedRanges'] for x in result['sources']))

    def test_watch_tvseries_and_tvids_follow_absolute_canonical_links(self):
        from moviehub_scrubs import scrape_sources
        for name in ['watchtvseries_net', 'tvids_net']:
            provider = importlib.import_module('moviehub_scrubs.providers.' + name).source()
            search = ('<div class="relative text-center"><a href="https://canonical.example/movies/film-1">'
                      '<img alt="Film"></a><a href="/list/movies/years/2020">2020</a></div>')
            calls = []
            def request(url, **kwargs):
                calls.append(url)
                return search if '/search?' in url else '<a data-url="https://host.example/embed/1">Server</a>'
            with patch.object(scraper_client, 'request', side_effect=request), patch.object(scraper_client, 'scrapePage', side_effect=lambda url, **kwargs: SimpleNamespace(status_code=200, text=request(url))), patch.object(scrape_sources, 'process', return_value=[]):
                key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
                provider.sources(key, [])
            self.assertEqual(calls[-1], 'https://canonical.example/movies/film-1')
            if name == 'watchtvseries_net':
                self.assertTrue(calls[0].startswith('https://www.watch-tvseries.tv/search?'))

    def test_tvids_uses_public_entry_domain_when_its_advertised_mirror_is_challenged(self):
        from moviehub_scrubs.providers import tvids_net
        from moviehub_scrubs import scrape_sources
        search = ('<div class="relative text-center"><a href="https://www.tvids.to/movies/film-1">'
                  '<img alt="Film"></a><a href="/list/movies/years/2020">2020</a></div>')
        responses = [SimpleNamespace(status_code=403, text='Challenge'),
                     SimpleNamespace(status_code=200, text='<a data-url="https://host.example/embed/1">Play</a>')]
        provider = tvids_net.source()
        key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
        with patch.object(scraper_client, 'request', return_value=search), patch.object(scraper_client, 'scrapePage', side_effect=responses) as pages, patch.object(scrape_sources, 'process', return_value=[]) as process:
            provider.sources(key, [])
        self.assertEqual([call.args[0] for call in pages.call_args_list],
                         ['https://www.tvids.to/movies/film-1', 'https://www.tvids.net/movies/film-1'])
        self.assertEqual(process.call_args.args[1], 'https://host.example/embed/1')

    def test_m4u_current_movie_cards_and_each_ajax_server(self):
        from moviehub_scrubs.providers import m4uhd_tv
        from moviehub_scrubs import scrape_sources
        calls = []
        def request(url, **kwargs):
            if kwargs.get('output') == 'cookie': return 'session=fixture'
            if '/search/' in url:
                return '<div class="movie-item"><a href="watch-film.html" title="Film (2020)">Film</a></div>'
            if url.endswith('/ajax'):
                calls.append(kwargs['post']['m4u'])
                self.assertEqual(kwargs['referer'], 'https://m4uhdpro.net/watch-film.html')
                return '<iframe src="https://host.example/' + kwargs['post']['m4u'] + '"></iframe>'
            return '<meta name="csrf-token" content="fixture"><span data="one"></span><span data="two"></span>'
        with patch.object(scraper_client, 'request', side_effect=request), patch.object(scrape_sources, 'process', return_value=[]):
            provider = m4uhd_tv.source()
            key = provider.movie('tt1', '1', 'Film', 'Film', [], '2020')
            provider.sources(key, [])
        self.assertEqual(calls, ['one', 'two'])

    def test_pressplay_reads_matching_published_player_without_obsolete_api(self):
        from html import escape
        from moviehub_scrubs.providers import pressplay_top
        def encode(value):
            if isinstance(value, dict):
                return [0, {key: encode(item) for key, item in value.items()}]
            if isinstance(value, list):
                return [1, [encode(item) for item in value]]
            return [0, value]
        for series in (False, True):
            kind = 'tv' if series else 'movie'
            catalogue = ''.join('<div class="group relative w-full"><div><a href="%s">Poster</a></div><span>2020</span><h3>Fixture</h3></div>' % link
                                for link in ('https://other.example/watch/' + kind + '-external',
                                             '/watch/' + kind + '-wrong', '/watch/' + kind + '-correct'))
            calls = []
            def request(url, **kwargs):
                calls.append(url)
                if '/explore?' in url:
                    return catalogue
                identity = 999 if url.endswith('-wrong') else 42
                props = (dict(result=dict(id=identity), activeServerConfig=dict(active=True,
                              tvUrl='https://host.example/tv/{id}/{season}/{episode}?t={time}')) if series else
                         dict(mediaId=identity, mediaType='movie', iframeUrl='https://host.example/movie/42'))
                component = 'TVShowPlayer.hash.js' if series else 'BackdropPlayer.hash.js'
                payload = {key: encode(value) for key, value in props.items()}
                return '<astro-island component-url="/_astro/%s" props="%s"></astro-island>' % (component, escape(json.dumps(payload), quote=True))
            provider = pressplay_top.source()
            if series:
                key = provider.tvshow('tt1', '42', '', 'Fixture', '', [], '2020')
                key = provider.episode(key, 'tt1', '42', '', 'Episode', '2021-01-01', '2', '3')
            else:
                key = provider.movie('tt1', '42', 'Fixture', '', [], '2020')
            with patch.object(scraper_client, 'request', side_effect=request), \
                    patch.object(pressplay_top.scrape_sources, 'process', return_value=[]) as process:
                provider.sources(key, [])
            expected = 'https://host.example/tv/42/2/3?t=0' if series else 'https://host.example/movie/42'
            self.assertEqual([call.args[1] for call in process.call_args_list], [expected])
            self.assertEqual(len(calls), 4 if series else 3)
            self.assertFalse(any('/api/player-servers' in call or 'other.example' in call for call in calls))

    def test_pressplay_reads_only_inert_published_server_arrays(self):
        from moviehub_compat.pressplay_page import bundled_servers
        script = ('const executable=[{id:"bad",movieUrl:steal(),tvUrl:"https://bad.example/{id}"}];'
                  'const sources=[{id:"one",name:"One",movieUrl:"https://one.example/movie/{id}",'
                  'tvUrl:"https://one.example/tv/{id}/{season}/{episode}",rank:1.5},'
                  '{id:"off",movieUrl:"https://off.example/{id}",tvUrl:"https://off.example/{id}",active:false},'
                  '{id:"two",movieUrl:"https://two.example/{id}",tvUrl:"https://two.example/{id}",active:true}];')
        self.assertEqual([row['id'] for row in bundled_servers(script)], ['one', 'two'])
        duplicate = 'const x=[{id:"one",id:"two",movieUrl:"https://one.example/{id}",tvUrl:"https://one.example/{id}"}];'
        self.assertEqual(bundled_servers(duplicate), [])

    def test_pressplay_tries_published_fallbacks_after_default_player_failure(self):
        from html import escape
        from moviehub_scrubs.providers import pressplay_top
        catalogue = '<div class="group relative w-full"><a href="/watch/movie-fixture">Poster</a><span>2020</span><h3>Fixture</h3></div>'
        props = dict(mediaId=[0, 42], mediaType=[0, 'movie'], iframeUrl=[0, 'https://default.example/42'])
        page = ('<astro-island component-url="/_astro/CompactServerSelector.fixture.js"></astro-island>'
                '<astro-island component-url="/_astro/BackdropPlayer.fixture.js" props="%s"></astro-island>' % escape(json.dumps(props), quote=True))
        bundle = ('const choices=[{id:"one",movieUrl:"https://one.example/movie/{id}",tvUrl:"https://one.example/tv/{id}"},'
                  '{id:"two",movieUrl:"https://two.example/movie/{id}",tvUrl:"https://two.example/tv/{id}"}];')
        responses = [catalogue, page, 'import{x}from"./app.fixture.js";', bundle]
        provider = pressplay_top.source()
        key = provider.movie('tt1', '42', 'Fixture', '', [], '2020')
        def process(hosts, link):
            if 'default.example' in link:
                raise ValueError('Unavailable default player')
            return [dict(url=link)]
        with patch.object(scraper_client, 'request', side_effect=responses), \
                patch.object(pressplay_top.scrape_sources, 'process', side_effect=process) as resolved:
            result = provider.sources(key, [])
        self.assertEqual([call.args[1] for call in resolved.call_args_list],
                         ['https://default.example/42', 'https://one.example/movie/42', 'https://two.example/movie/42'])
        self.assertEqual(len(result), 2)
        self.assertFalse(provider._site_asset('https://other.example/_astro/app.fixture.js'))
        self.assertFalse(provider._site_asset('https://pressplayz.top/_astro/../../api/private.js'))

    def test_pressplay_unavailable_selector_cannot_remove_validated_default(self):
        from html import escape
        from moviehub_scrubs.providers import pressplay_top
        catalogue = '<div class="group relative w-full"><a href="/watch/movie-fixture">Poster</a><span>2020</span><h3>Fixture</h3></div>'
        props = dict(mediaId=[0, 42], mediaType=[0, 'movie'], iframeUrl=[0, 'https://default.example/42'])
        page = ('<astro-island component-url="/_astro/CompactServerSelector.fixture.js"></astro-island>'
                '<astro-island component-url="/_astro/BackdropPlayer.fixture.js" props="%s"></astro-island>' % escape(json.dumps(props), quote=True))
        provider = pressplay_top.source()
        key = provider.movie('tt1', '42', 'Fixture', '', [], '2020')
        with patch.object(scraper_client, 'request', side_effect=[catalogue, page, ValueError('Unavailable bundle')]), \
                patch.object(pressplay_top.scrape_sources, 'process', return_value=[dict(url='https://default.example/42')]):
            self.assertEqual(len(provider.sources(key, [])), 1)

    def test_pressplay_template_links_preserve_episode_and_reject_unsafe_urls(self):
        from moviehub_compat.pressplay_page import template_link
        self.assertEqual(template_link('https://one.example/{id}/{season}/{episode}?t={time}', 42, 2, 3),
                         'https://one.example/42/2/3?t=0')
        for value in ('http://one.example/{id}', 'https://user:secret@one.example/{id}',
                      'https://one.example:invalid/{id}', 'https://one.example/{unknown}', None):
            self.assertIsNone(template_link(value, 42, 2, 3))

    def test_pressplay_rejects_inactive_malformed_or_incomplete_player_data(self):
        from html import escape
        from moviehub_compat.pressplay_page import Player
        for props in (
                {'result': [0, {'id': [0, 42]}], 'activeServerConfig': [0, {'active': [0, False], 'tvUrl': [0, 'https://host.example/{id}']}]},
                {'result': [0, {'id': [0, 42]}], 'activeServerConfig': [0, {'active': [0, True], 'tvUrl': [0, 'https://host.example/{unknown}']}]},
                {'result': [0, {'id': [0, 999]}], 'activeServerConfig': [0, {'active': [0, True], 'tvUrl': [0, 'https://host.example/{id}']}]},
                {'result': [0], 'activeServerConfig': [0]},
                {'result': [0, {'id': [0, 42]}], 'activeServerConfig': [0, {'active': [0, True], 'tvUrl': [0, 'javascript:alert(1)']}]}):
            parser = Player(42, True, 2, 3)
            parser.feed('<astro-island component-url="/_astro/TVShowPlayer.hash.js" props="%s">' % escape(json.dumps(props), quote=True))
            self.assertEqual(parser.links, [])
        parser.feed('<astro-island component-url="/_astro/TVShowPlayer.hash.js" props="not json">')
        self.assertEqual(parser.links, [])

    def test_pressplay_movie_requires_matching_media_type_and_player_component(self):
        from html import escape
        from moviehub_compat.pressplay_page import Player
        for component, kind in [('RecommendationList.hash.js', 'movie'), ('BackdropPlayer.hash.js', 'tv')]:
            props = dict(mediaId=[0, 42], mediaType=[0, kind], iframeUrl=[0, 'https://host.example/movie/42'])
            parser = Player(42, False, 1, 1)
            parser.feed('<astro-island component-url="/_astro/%s" props="%s">' % (component, escape(json.dumps(props), quote=True)))
            self.assertEqual(parser.links, [])

    def test_project_player_enumerates_each_source_and_continues_after_a_failure(self):
        def request(url, **kwargs):
            if '/vembed/' in url:
                return '<option value="">Choose</option><option value="one1">One</option><option value="two1">Two</option>'
            if '/one1/' in url: raise ValueError('Unavailable first player')
            return '<iframe src="https://two.example/embed/1"></iframe>'
        with patch.object(client, 'request', side_effect=request):
            result = current_hosts.process('https://watchseries.cyou/vembed/pid1/', lambda child: [child])
        self.assertEqual(result, ['https://two.example/embed/1'])

    def test_iwaatch_uses_current_search_signed_watch_page_and_public_work_challenge(self):
        from moviehub_scrubs.providers import iwaatch_com
        salt = 'fixture-salt'
        challenge = dict(algorithm='SHA-256', salt=salt, maxnumber=10, signature='issued-fixture',
                         challenge=hashlib.sha256((salt + '7').encode()).hexdigest())
        page_chunk = json.dumps('1:{"watchToken":"issued-page-token"}')
        page = 'self.__next_f.push([1,' + page_chunk + '])'
        def request(url, **kwargs):
            if '/movie/search?' in url:
                return dict(data=[dict(id='wrong', slug='wrong', title_en='Film', release_year=2019),
                                  dict(id='correct', slug='film', title_en='Film', release_year=2020)])
            if '/pow/challenge' in url: return dict(data=challenge)
            if '/files?' in url:
                self.assertIn('/correct/', url)
                headers = kwargs['headers']
                proof = json.loads(base64.b64decode(headers['X-Pow-Token']))
                self.assertEqual(proof['number'], 7)
                self.assertEqual(proof['signature'], 'issued-fixture')
                self.assertEqual(headers['X-Watch-Token'], 'issued-page-token')
                return dict(data=dict(urls=dict(stream={'720p': 'https://media.example/film.mp4'})))
            return page
        provider = iwaatch_com.source()
        key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
        with patch.object(client, 'request', side_effect=request): result = provider.sources(key, [])
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['quality'], '720p')
        self.assertEqual(result[0]['source'], 'media.example')

    def test_xpass_authenticates_current_response_and_keeps_independent_backends(self):
        # AES-GCM fixture produced independently with cryptography's AESGCM implementation.
        encrypted = 'AAECAwQFBgcICQoLzDNsw3w-xSxCbdxTu2W43ATesxQkVvNCDpt9Yf0pzQbhT--RikD06i7hBGqjHyGzevCUiKOxd3RFZMh42awk1KPrAKppkeBO2Jnkau-r1mworwHfwZSXtao48OsP1-smFqw9B_jbL9vsDUs5xQkrCT6JfeymCQ80YecfO5DUYexwCv4XtjkoIu1Q'
        table = str.maketrans('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',
                             'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/')
        encoded = base64.b64encode(b'/static/current-player.js').decode().translate(table)
        def request(url, **kwargs):
            if '/e/movie/' in url:
                return '<script src="/static/mainmini.js"></script><script>var dataUrl="/data/movie/1?token=issued-fixture.signature";</script>'
            if '/static/main' in url: return 'const table=["' + encoded + '"];'
            if '/static/current-player.js' in url: return 'const build="spv3-build-fixture";'
            if '/data/movie/' in url: return encrypted
            if url.endswith('/mdata/bad'): raise ValueError('Unavailable first backend')
            return dict(playlist=[dict(sources=[dict(file='https://cdn.example/film.m3u8', label='1080p')])])
        with patch.object(client, 'request', side_effect=request):
            result = current_hosts.xpass('https://play.xpass.top/e/movie/1')
        self.assertEqual([x['source'] for x in result], ['cdn.example · One', 'cdn.example · Two'])
        self.assertTrue(all(x['segmentContainers'] and x['direct'] for x in result))
        tampered = encrypted[:-2] + ('AA' if encrypted[-2:] != 'AA' else 'BB')
        def corrupt(url, **kwargs):
            return tampered if '/data/movie/' in url else request(url, **kwargs)
        with patch.object(client, 'request', side_effect=corrupt):
            with self.assertRaises(ValueError): current_hosts.xpass('https://play.xpass.top/e/movie/1')

    def test_bstsrs_reads_the_pages_current_dbneg_offset(self):
        from moviehub_scrubs.providers import bstsrs_one
        from moviehub_scrubs import scrape_sources
        offset = 0x19e0c889e
        target = 'https://host.example/e/episode'
        encoded = '-'.join(format(offset + ord(character), 'x') for character in target)
        html = ('<a href="https://imdb.com/title/tt123/">IMDb</a>'
                '<script>function dbneg(X){var Y={};Y.subtract=function(a,b){return a-b;};'
                'var currentOffset=0x19e0c889e;return X;}</script>'
                "<a onclick=\"window.open(dbneg('" + encoded + "'), '_blank')\">Play</a>")
        provider = bstsrs_one.source()
        key = provider.tvshow('tt123', '1', '', 'Show', 'Show', [], '2020')
        key = provider.episode(key, 'tt123', '1', '', 'Episode', '2020-01-01', '1', '1')
        with patch.object(scraper_client, 'scrapePage', return_value=SimpleNamespace(text=html)), patch.object(scrape_sources, 'process', return_value=[]) as process:
            provider.sources(key, [])
        self.assertEqual(process.call_args.args[1], target)

    def test_rarefilmm_matches_primary_title_without_subtitle_markup(self):
        from moviehub_scrubs.providers import rarefilmm_com
        html = ('<h2 class="excerpt-title"><a href="https://rarefilmm.com/film/">'
                '<span class="entry-title-primary">Film (2020)</span>'
                '<span class="entry-subtitle">A different alternate name</span></a></h2>')
        with patch.object(scraper_client, 'scrapePage', return_value=SimpleNamespace(text=html)):
            result = rarefilmm_com.source().movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
        self.assertEqual(result, 'https://rarefilmm.com/film/')

    def test_tubi_guest_handshake_keeps_padded_challenge_and_uses_issued_signing_key(self):
        from moviehub_compat import tubi
        replies = [dict(id='issued-id', key=base64.b64encode(b'issued-secret').decode()),
                   dict(access_token='issued-guest-token')]
        with patch.object(tubi.secrets, 'token_hex', return_value='fixture-verifier'), patch.object(tubi.uuid, 'uuid4', return_value='fixture-device'), patch.object(client, 'request', side_effect=replies) as request:
            self.assertEqual(tubi.anonymous_token(), 'issued-guest-token')
        first = json.loads(request.call_args_list[0].kwargs['post'])
        self.assertEqual(first['challenge'], base64.urlsafe_b64encode(hashlib.sha256(b'fixture-verifier').digest()).decode())
        self.assertTrue(first['challenge'].endswith('='))
        second = request.call_args_list[1]
        self.assertEqual(json.loads(second.kwargs['post'])['id'], 'issued-id')
        self.assertIn('X-Tubi-Signature=', second.args[0])
        self.assertNotIn('issued-secret', second.args[0])

    def test_tubi_matches_year_skips_account_and_drm_resources_and_keeps_each_cdn(self):
        from moviehub_compat import tubi
        catalogue = dict(contents={
            'one': dict(id='1', title='Film', year=2020, type='v'),
            'wrong-year': dict(id='2', title='Film', year=2019, type='v'),
            'login': dict(id='3', title='Film', year=2020, type='v', needs_login=True)})
        resources = [dict(type='hlsv6', codec='H264', resolution='720P', manifest=dict(url='https://one.example/master.m3u8')),
                     dict(type='hlsv6', codec='H265', resolution='1080P', manifest=dict(url='https://two.example/master.m3u8')),
                     dict(type='widevine', manifest=dict(url='https://drm.example/master.mpd'))]
        def request(url, **kwargs):
            if '/search?' in url:
                self.assertEqual(kwargs['headers']['Authorization'], 'Bearer issued-guest-token')
                return catalogue
            self.assertEqual(url, 'https://tubitv.com/embed/1')
            return '<script>var data={"video_resources":' + json.dumps(resources) + '};</script>'
        with patch.object(tubi, 'anonymous_token', return_value='issued-guest-token'), patch.object(client, 'request', side_effect=request):
            result = tubi.movies('Film', '2020', [{'title': 'Film'}])
        self.assertEqual([x['source'] for x in result], ['one.example', 'two.example'])
        self.assertNotIn('issued-guest-token', json.dumps(result))

    def test_waffi_exposes_each_mirror_and_keeps_working_one_after_an_error(self):
        page = ('<main data-download-page><a class="mirror-download" href="/a/id/mirrors/one">One</a>'
                '<a class="mirror-download" href="/a/id/mirrors/two">Two</a>'
                '<a class="watch watch-online watch-enabled" href="/a/id/watch">Watch</a></main>')
        def fetch(url, **kwargs):
            response = requests.Response(); response.status_code = 200; response.url = url
            response.headers['Content-Type'] = 'text/html'
            if '/mirrors/one' in url:
                response.url = 'https://one.example/file'; response.status_code = 403
            elif '/mirrors/two' in url: response.url = 'https://two.example/file'
            else: response._content = page.encode()
            return response
        pending = SimpleNamespace(status_code=202, json=lambda: dict(state='queued'))
        with patch.object(client, 'fetch', side_effect=fetch), patch.object(client, 'request', return_value='<movi-player data-source="/a/id/playback"></movi-player>'), patch.object(client, 'scrapePage', return_value=pending):
            result = current_hosts.waffi('https://new.waffi.cloud/a/id/film', lambda target: [target])
        self.assertEqual(result, ['https://two.example/file'])
        self.assertIn(dict(provider='one.example', status='http_403'), client.diagnostics()['hostAttempts'])
        self.assertIn(dict(provider='new.waffi.cloud · Watch', status='preparing'), client.diagnostics()['hostAttempts'])

    def test_trace_contains_only_hosts_methods_statuses_and_survives_resolution_scope(self):
        response = requests.Response(); response.status_code = 403
        response.url = 'https://site.example/token?secret=private'; response._content = b'blocked'
        response._content_consumed = True
        with patch.object(client, 'safe', side_effect=lambda url: url), patch.object(requests.Session, 'request', return_value=response):
            client.fetch(response.url, headers={'Cookie': 'private=secret'})
        client.host_result('https://host.example/e/private?token=secret', 'unsupported_host')
        client.scope(lambda: False, preserve=True)
        serialized = json.dumps(client.diagnostics())
        self.assertNotIn('private', serialized)
        self.assertNotIn('secret', serialized)
        self.assertEqual(client.diagnostics()['requests'], [dict(host='site.example', method='GET', status=403)])
        self.assertEqual(client.diagnostics()['hostAttempts'], [dict(provider='host.example', status='unsupported_host')])
