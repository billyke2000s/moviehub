"""Current AnyMovies advertised-player and exact-identity regressions."""
import importlib
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import requests
import moviehub_vod as vod
from moviehub_compat import client, current_hosts
from moviehub_scrubs import client as scraper_client


class AnyMoviesPlayerTests(unittest.TestCase):

    def test_movie_buttons_require_the_requested_film_identity(self):
        from moviehub_compat.anymovies_series import movie_player_sources
        data = dict(imdb='tt1375666', tmdb='27205', title='Inception', year='2010')
        returned = [dict(url='https://media.example/correct.m3u8', source='vidfast', direct=True),
                    dict(url='https://media.example/wrong.m3u8', source='vidfast', direct=True, tmdb='693134')]
        for url, label in [('https://vidfast.vc/movie/tt1375666', 'VidFast'),
                           ('https://peachify.top/embed/movie/27205', 'Peachify')]:
            with self.subTest(player=label), patch('moviehub_compat.anymovies_series.resolve_player',
                    return_value=returned) as resolve:
                rows = movie_player_sources(url, data)
            self.assertEqual(len(rows), 1)
            self.assertEqual((rows[0]['imdb'], rows[0]['tmdb']), ('tt1375666', '27205'))
            self.assertEqual(resolve.call_args.args[0]['source'], label)
        for url in ['https://vidfast.vc.evil.example/movie/tt1375666',
                    'https://user:pass@vidfast.vc/movie/tt1375666',
                    'https://vidfast.vc/movie/tt9999999',
                    'https://peachify.top/embed/movie/693134']:
            with self.subTest(url=url), patch('moviehub_compat.anymovies_series.resolve_player') as resolve:
                self.assertIsNone(movie_player_sources(url, data))
                resolve.assert_not_called()

    def test_both_movie_loaders_publish_advertised_alternative_player(self):
        search = '<div class="result_title"><a href="/movie/Inception_2010">Watch Inception (2010)</a></div>'
        page = '<button onclick="_loadP(this,\'https://vidfast.vc/movie/tt1375666\')">VidFast</button>'
        item = dict(url='https://media.example/feature.m3u8', source='vidfast vRapid', direct=True)
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.anymovies_co')
            provider = module.source()
            key = provider.movie('tt1375666', '27205', 'Inception', 'Inception', [{'title': 'Inception'}], '2010')
            with self.subTest(family=family), patch.object(module.client, 'scrapePage', side_effect=[
                    SimpleNamespace(text=search), SimpleNamespace(text=page)]), \
                    patch.object(module, 'movie_player_sources', return_value=[item]) as alternative, \
                    patch.object(module.scrape_sources, 'process') as legacy:
                rows = provider.sources(key, vod._HOSTS)
            self.assertEqual(rows, [item])
            self.assertEqual(alternative.call_args.args[0], 'https://vidfast.vc/movie/tt1375666')
            self.assertEqual(alternative.call_args.args[1]['tmdb'], '27205')
            legacy.assert_not_called()

    def test_current_mxdrop_click_movie_link_reaches_existing_resolver(self):
        from resolveurl.plugins.mixdrop import MixDropResolver
        self.assertIn('mxdrop.click', vod._HOSTS)
        resolver = MixDropResolver()
        self.assertTrue(resolver.valid_url('https://mxdrop.click/f/fixture123', None))
        self.assertFalse(resolver.valid_url('https://mxdrop.click.evil.example/f/fixture123', None))
        with patch.object(resolver.net, 'http_GET', return_value=SimpleNamespace(
                content='wurl="//media.example/feature.mp4";')) as request:
            url = resolver.get_media_url('mxdrop.click', 'fixture123')
        self.assertEqual(request.call_args.args[0], 'https://mxdrop.click/e/fixture123')
        media, _, headers = url.partition('|')
        self.assertEqual(media, 'https://media.example/feature.mp4')
        from urllib.parse import parse_qs
        self.assertEqual(parse_qs(headers)['Referer'], ['https://mxdrop.click/e/fixture123'])

    def test_anymovies_advertised_players_reuse_existing_exact_episode_protocols(self):
        from moviehub_compat.anymovies_series import resolve_player
        from moviehub_native.cinestream_api import Vidfast
        from moviehub_native.cinestream_crypto import Peachify
        data = dict(imdb='tt0903747', tmdb='1396', title='Breaking Bad', year='2008', season='2', episode='3')
        for label, provider in [('VidFast', Vidfast), ('Peachify', Peachify)]:
            with self.subTest(player=label), patch.object(provider, 'sources', return_value=[]) as sources:
                resolve_player(dict(source=label, url='https://player.example/unused'), data, None)
            key = json.loads(sources.call_args.args[0])
            self.assertEqual((key['imdb'], key['tmdb'], key['season'], key['episode']),
                             ('tt0903747', '1396', '2', '3'))
        for label, provider in [('VidFast', Vidfast), ('Peachify', Peachify)]:
            movie = dict(imdb='tt1375666', tmdb='27205', title='Inception', year='2010')
            with self.subTest(movie_player=label), patch.object(provider, 'sources', return_value=[]) as sources:
                resolve_player(dict(source=label, url='https://player.example/unused'), movie, None)
            key = json.loads(sources.call_args.args[0])
            self.assertEqual((key['imdb'], key['tmdb']), ('tt1375666', '27205'))
            self.assertNotIn('season', key)
            self.assertNotIn('episode', key)
        with patch.object(Vidfast, 'sources') as sources:
            resolve_player(dict(source='VidFast', url='https://player.example/unused'),
                           dict(data, tmdb='not-an-id'), None)
            sources.assert_not_called()

    def test_anymovies_reads_only_published_exact_episode_player_templates(self):
        from moviehub_compat.anymovies_series import advertised_players
        marker = chr(96)
        templates = [
            'https://vidsrc.sh/embed/tv?imdb=${imdbId}&season=${season}&episode=${episode}',
            'https://vidfast.vc/tv/${imdbId}/${season}/${episode}?nextButton=true&autoNext=true',
            'https://peachify.top/embed/tv/${imdbId}/${season}/${episode}?showNextBtn=true&autoNext=true',
            'https://moviesapi.to/tv/${imdbId}/${season}/${episode}',
            'https://moviesapi.to.evil.example/tv/${imdbId}/${season}/${episode}',
            'https://user:pass@moviesapi.to/tv/${imdbId}/${season}/${episode}',
            'https://moviesapi.to/tv/${imdbId}/${season}/999',
            'https://moviesapi.to/tv/${otherId}/${season}/${episode}',
            'https://moviesapi.to/tv/${imdbId}/${season}/${episode}?token=wrong',
        ]
        script = '\n'.join('case {}: return {}{}{};'.format(i, marker, t, marker)
                           for i, t in enumerate(templates, 1))
        rows = advertised_players(script, 'tt0903747', 2, 3)
        self.assertEqual([row['source'] for row in rows],
                         ['VidSrc', 'VidFast', 'Peachify', 'MoviesAPI'])
        self.assertEqual(rows[-1]['url'], 'https://moviesapi.to/tv/tt0903747/2/3')

    def test_anymovies_both_loaders_resolve_other_advertised_series_tabs(self):
        marker = chr(96)
        script = 'case 4: return ' + marker + 'https://moviesapi.to/tv/${imdbId}/${season}/${episode}' + marker + ';'
        page = ('<div class="result_block" data-kind="series" data-imdb="tt0903747">'
                '<a href="https://vidsrc.sh/embed/tv?imdb=tt0903747">Breaking Bad</a></div>'
                '<script src="/js/series_info_box_for_movie.js"></script>')
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.anymovies_co')
            provider = module.source()
            key = provider.tvshow('tt0903747', '1396', '', 'Breaking Bad', '', [], '2008')
            key = provider.episode(key, '', '', '', '', '', '2', '3')
            with self.subTest(family=family), patch.object(module.client, 'scrapePage',
                    side_effect=[SimpleNamespace(text=page), SimpleNamespace(text=script)]) as fetch, \
                    patch.object(module.scrape_sources, 'process', return_value=[
                        dict(url='https://media.example/episode.m3u8', source='moviesapi.to', direct=True),
                        dict(url='https://media.example/wrong.m3u8', source='moviesapi.to', direct=True,
                             imdb='tt9999999', season=2, episode=3),
                        dict(url='https://media.example/wrong-episode.m3u8', source='moviesapi.to', direct=True,
                             filename='Breaking.Bad.S02E04.mp4')]) as process:
                result = provider.sources(key, [])
            self.assertEqual(len(result), 2)
            self.assertEqual(fetch.call_args.args[0],
                             'https://www.downloads-anymovies.co/js/series_info_box_for_movie.js')
            self.assertEqual(process.call_args.args[1], 'https://moviesapi.to/tv/tt0903747/2/3')
            self.assertEqual((result[1]['imdb'], result[1]['season'], result[1]['episode']),
                             ('tt0903747', 2, 3))
            self.assertTrue(result[1]['direct'])

    def test_anymovies_optional_series_script_failure_retains_primary(self):
        from moviehub_compat.anymovies_series import SeriesLookup
        provider = SeriesLookup()
        provider.base_link = 'https://www.downloads-anymovies.co'
        page = ('<div class="result_block" data-kind="series" data-imdb="tt0903747">'
                '<a href="https://vidsrc.sh/embed/tv?imdb=tt0903747">Show</a></div>'
                '<script src="https://evil.example/js/series_info_box_for_movie.js"></script>'
                '<script src="/js/series_info_box_for_movie.js"></script>')
        scraper = SimpleNamespace(scrapePage=unittest.mock.Mock(
            side_effect=[SimpleNamespace(text=page), OSError('Unavailable')]))
        rows = provider.series_sources(dict(imdb='tt0903747', title='Breaking Bad', season='1', episode='1'), scraper)
        self.assertEqual(len(rows), 1)
        self.assertEqual(scraper.scrapePage.call_count, 2)
        self.assertNotIn('evil.example', scraper.scrapePage.call_args.args[0])

    def test_moviesapi_accepts_current_slash_episode_route_and_legacy_route(self):
        key = 'a' * 64
        script = 'const k=' + chr(39) + key + chr(39) + ',p=' + chr(39) + '/api/vidora/subs/' + chr(39) + ';'
        for route in ['tv/tt0903747/2/3', 'tv/1396/2/3', 'tv/1396-2-3']:
            identity = 'tt0903747' if 'tt0903747' in route else '1396'
            with self.subTest(route=route), patch.object(current_hosts.client, 'request', side_effect=[
                    '<script src="/assets/index-current.js"></script>', script,
                    {'result': True, 'sources': [{'url': '/stream/episode.m3u8'}]}]) as request:
                rows = current_hosts.moviesapi('https://moviesapi.to/' + route)
            self.assertEqual(len(rows), 1)
            self.assertEqual(request.call_args.args[0], 'https://moviesapi.to/api/vidora/v1/tv/' + identity + '/2/3')
        for route in ['tv/1396/2', 'tv/1396/2/0', 'tv/1396/2/3/4', 'tv/wrong/2/3']:
            with self.subTest(route=route), patch.object(current_hosts.client, 'request') as request:
                self.assertEqual(current_hosts.moviesapi('https://moviesapi.to/' + route), [])
                request.assert_not_called()

    def test_anymovies_series_uses_matching_imdb_card_and_exact_requested_episode(self):
        page = ('<div class="result_block" data-kind="movie" data-imdb="tt0903747">'
                '<a href="https://vidsrc.sh/embed/tv?imdb=tt0903747">Wrong type</a></div>'
                '<div class="result_block" data-kind="series" data-imdb="tt9999999">'
                '<a href="https://vidsrc.sh/embed/tv?imdb=tt9999999">Wrong show</a></div>'
                '<div class="result_block" data-kind="series" data-imdb="tt0903747">'
                '<div class="result_image"><a href="https://vidsrc.sh/embed/tv?imdb=tt0903747">'
                '<img alt="Breaking Bad"></a></div><div class="series_title">Breaking Bad</div></div>')
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.anymovies_co')
            provider = module.source()
            key = provider.tvshow('tt0903747', '1396', '', 'Breaking Bad', 'Breaking Bad', [], '2008')
            key = provider.episode(key, 'tt0903747', '1396', '', '', '', '2', '3')
            with patch.object(module.client, 'scrapePage', return_value=SimpleNamespace(text=page)):
                result = provider.sources(key, [])
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]['url'], 'https://vidsrc.sh/embed/tv/tt0903747/2/3')
            self.assertEqual((result[0]['season'], result[0]['episode']), (2, 3))
            self.assertFalse(result[0]['direct'])
            self.assertNotIn('filename', result[0])

    def test_anymovies_rejects_conflicting_player_identity_and_lookalike_hosts(self):
        from moviehub_free99.providers.anymovies_co import source
        provider = source()
        key = provider.tvshow('tt0903747', '1396', '', 'Breaking Bad', '', [], '2008')
        key = provider.episode(key, '', '', '', '', '', '1', '1')
        for player in ('https://vidsrc.sh.evil.example/embed/tv?imdb=tt0903747',
                       'https://vidsrc.sh/embed/tv?imdb=tt9999999',
                       'https://user:pass@vidsrc.sh/embed/tv?imdb=tt0903747',
                       'https://vidsrc.sh/embed/movie?imdb=tt0903747'):
            page = ('<div class="result_block" data-kind="series" data-imdb="tt0903747">'
                    '<a href="' + player + '">Show</a></div>')
            with self.subTest(player=player), patch.object(scraper_client, 'scrapePage',
                    return_value=SimpleNamespace(text=page)):
                # Free99 and Scrubs expose separate compatibility module aliases.
                from moviehub_free99 import client as free_client
                with patch.object(free_client, 'scrapePage', return_value=SimpleNamespace(text=page)):
                    self.assertEqual(provider.sources(key, []), [])

    def test_anymovies_current_movie_search_and_player_buttons_are_preserved(self):
        for family in ('free99', 'scrubs'):
            module = importlib.import_module('moviehub_' + family + '.providers.anymovies_co')
            page = ('<div class="result_title"><a href="/movie/Inception_2010">'
                    '<span>Inception</span><span>(2010)</span></a></div>')
            responses = [SimpleNamespace(text=page), SimpleNamespace(text=
                '<button onclick="_loadP(this,\'https://playmogo.com/e/example\')">Play</button>')]
            provider = module.source()
            key = provider.movie('tt1375666', '27205', 'Inception', 'Inception',
                                 [{'title': 'Inception'}], '2010')
            with patch.object(module.client, 'scrapePage', side_effect=responses) as pages, \
                    patch.object(module.scrape_sources, 'process', return_value=[]) as process:
                provider.sources(key, [])
            self.assertEqual(pages.call_args.args[0], provider.base_link + '/movie/Inception_2010')
            self.assertEqual(process.call_args.args[1], 'https://playmogo.com/e/example')

    def test_anymovies_series_is_scheduled_without_adding_a_duplicate_website(self):
        rows = json.loads(vod.search_providers())
        row = next(row for row in rows if row['id'] == 'free99:anymovies_co')
        self.assertTrue(row['series'])
        self.assertEqual(row['adapters'], ['free99:anymovies_co', 'scrubs:anymovies_co'])

    def setUp(self):
        transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        transport.start()
        self.addCleanup(transport.stop)
        client.scope(lambda: False, timeout=30)

