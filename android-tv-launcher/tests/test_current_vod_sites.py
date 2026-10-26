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
    def setUp(self):
        transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        transport.start()
        self.addCleanup(transport.stop)
        client.scope(lambda: False, timeout=30)

    def test_distinct_hosts_sharing_a_media_url_remain_separate_in_website_results(self):
        module = importlib.import_module('moviehub_free99.providers.levidia_ch')
        class Provider:
            base_link = 'https://levidia.ch'
            def movie(self, *args): return 'lookup'
            def sources(self, key, hosts):
                return [dict(source=host, url='https://cdn.example/video.m3u8', direct=True,
                             segmentContainers=True) for host in ['Host One', 'Host Two']]
            def resolve(self, url): return url
        raw = json.dumps(dict(title='Film', year='2020', imdb='tt123', tmdb=1, season=-1, episode=-1))
        def playable(url, quality): return dict(url=url, headers={}, verifiedQuality=None)
        with patch.object(module, 'source', return_value=Provider()), patch.object(vod, 'playable', side_effect=playable):
            result = json.loads(vod.query_site('free99:levidia_ch', raw, lambda: False))
        self.assertEqual({x['providerName'] for x in result['sources']},
                         {'levidia.ch · Host One', 'levidia.ch · Host Two'})
        self.assertTrue(all(x['website'] == 'levidia.ch' and x['segmentContainers'] for x in result['sources']))

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
            with patch.object(scraper_client, 'request', side_effect=request), patch.object(scrape_sources, 'process', return_value=[]):
                key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
                provider.sources(key, [])
            self.assertEqual(calls[-1], 'https://canonical.example/movies/film-1')

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

    def test_pressplay_reads_current_active_servers_and_expands_episode_identity(self):
        from moviehub_scrubs.providers import pressplay_top
        from moviehub_scrubs import scrape_sources
        servers = [dict(active=True, tvUrl='https://one.example/tv/{id}/{season}/{episode}'),
                   dict(active=False, tvUrl='https://disabled.example/tv/{id}'),
                   dict(active=True, tvUrl='https://two.example/tv/{id}-{season}-{episode}?t={time}')]
        provider = pressplay_top.source()
        key = provider.tvshow('tt1', '42', '', 'Show', 'Show', [], '2020')
        key = provider.episode(key, 'tt1', '42', '', 'Episode', '2020-01-01', '2', '3')
        with patch.object(scraper_client, 'request', return_value=servers), patch.object(scrape_sources, 'process', return_value=[]) as process:
            provider.sources(key, [])
        self.assertEqual([call.args[1] for call in process.call_args_list],
                         ['https://one.example/tv/42/2/3', 'https://two.example/tv/42-2-3?t=0'])

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
