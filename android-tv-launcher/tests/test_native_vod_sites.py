"""Current public native catalogue and independent mirror regressions."""
import base64
import codecs
import json
import time
import unittest
from unittest.mock import patch
import requests
from moviehub_compat import client
from moviehub_native import fourkhdhub, vixsrc


class NativeSiteTests(unittest.TestCase):
    def setUp(self):
        transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        transport.start()
        self.addCleanup(transport.stop)
        client.scope(lambda: False, timeout=30)

    def payload(self, url):
        data = json.dumps({'o': base64.b64encode(url.encode()).decode()}).encode()
        encoded = codecs.encode(base64.b64encode(data).decode(), 'rot13').encode()
        return base64.b64encode(base64.b64encode(encoded)).decode()

    def test_literal_redirect_data_is_decoded_without_executing_its_scripts(self):
        url = 'https://hubcloud.ist/video/one'
        self.assertEqual(fourkhdhub.landing_target("s('o','" + self.payload(url) + "',180000);"), url)
        self.assertIsNone(fourkhdhub.landing_target("fetch('https://unexpected.example')"))
        with self.assertRaises(Exception):
            fourkhdhub.landing_target("s('o','AAAA',180000)")

    def test_exact_catalogue_identity_and_independent_mirrors_survive_one_broken_file(self):
        provider = fourkhdhub.source()
        search = '''<a class="movie-card" href="/wrong"><h3 class="movie-card-title">Film</h3>
                    <div class="movie-card-meta">2021</div></a>
                    <a class="movie-card" href="/right"><h3 class="movie-card-title">Film</h3>
                    <div class="movie-card-meta">2020</div></a>'''
        detail = '''<div class="download-item"><div class="file-title">Film 2020 2160p.mkv</div>
                    <a href="/broken">Download HubCloud</a><a href="/landing">Download HubCloud</a></div>'''
        calls = []
        def request(url, **kwargs):
            calls.append(url)
            if '?s=' in url: return search
            if url.endswith('/right'): return detail
            if url.endswith('/broken'): raise ValueError('Unavailable mirror')
            if url.endswith('/landing'): return "s('o','" + self.payload('https://hubcloud.ist/video/one') + "',180000)"
            if url.endswith('/video/one'): return "var url = 'https://hubcloud.ist/files/one';"
            if url.endswith('/files/one'): return '''<a href="https://worker-a.example/Film + Cut.mkv">Download File</a>
                        <a href="https://worker-b.example/Film.mkv">Download File</a>
                        <a href="https://ad.example">Advert</a>'''
            raise AssertionError('Unexpected path')
        with patch.object(client, 'request', side_effect=request), patch.object(client, 'safe', side_effect=lambda x: x):
            key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
            rows = provider.sources(key, [])
        self.assertEqual(len(rows), 2)
        self.assertEqual({x['source'] for x in rows}, {'HubCloud · worker-a.example', 'HubCloud · worker-b.example'})
        self.assertTrue(all(x['quality'] == '2160p' and x['direct'] and x['boundedRanges'] for x in rows))
        self.assertIn('Film%20%2B%20Cut.mkv', rows[0]['url'])
        self.assertFalse(any(x.endswith('/wrong') for x in calls))

    def test_changed_4k_mediator_and_hubcloud_domain_keep_video_but_not_archives(self):
        provider = fourkhdhub.source()
        search = '<a class="movie-card" href="/film"><h3 class="movie-card-title">Film</h3><div class="movie-card-meta">2020</div></a>'
        detail = ('<div class="download-item"><div class="file-title">Film 2160p.mkv</div>'
                  '<a href="https://greenmotors.club/start">Download HubCloud</a></div>')
        hubcloud = 'https://hubcloud.pw/file/one'

        def legacy_request(url, **kwargs):
            if '?s=' in url:
                return search
            if url.endswith('/film'):
                return detail
            if url == 'https://greenmotors.club/start':
                return '<p>Landing format changed</p>'
            raise AssertionError('Unexpected legacy URL ' + url)

        calls = []

        def fetch(url, headers, **kwargs):
            calls.append(url)
            response = requests.Response()
            response.url = url
            response.status_code = 200
            if url == 'https://greenmotors.club/start':
                response.status_code = 302
                response.headers['Location'] = 'https://greenmotors.cc/next'
                body = ''
            elif url == 'https://greenmotors.cc/next':
                body = "s('o','" + self.payload(hubcloud) + "',180000)"
            elif url == hubcloud:
                body = ('<div class="card-header">Film 2160p.mkv</div>'
                        '<a href="https://cdn.example/Film.mkv">Download File</a>'
                        '<a href="https://cdn.example/Film.zip">Download File</a>')
            else:
                raise AssertionError('Unexpected fallback URL ' + url)
            response._content = body.encode()
            return response

        with patch.object(client, 'request', side_effect=legacy_request), \
             patch.object(client, 'fetch', side_effect=fetch), \
             patch.object(client, 'safe', side_effect=lambda value: value):
            key = provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020')
            rows = provider.sources(key, [])

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['quality'], '2160p')
        self.assertTrue(rows[0]['direct'] and rows[0]['boundedRanges'])
        self.assertIn('cdn.example/Film.mkv', rows[0]['url'])
        self.assertIn('4KHDHub', rows[0]['source'])
        self.assertEqual(calls, ['https://greenmotors.club/start',
                                 'https://greenmotors.cc/next', hubcloud])

    def test_live_greenmotors_id_mirror_timeout_does_not_block_the_next(self):
        provider = fourkhdhub.source()
        search = ('<a class="movie-card" href="/film">'
                  '<h3 class="movie-card-title">Film</h3>'
                  '<div class="movie-card-meta">2020</div></a>')
        detail = ('<div class="download-item"><div class="file-title">Film 1080p.mkv</div>'
                  '<a href="https://greenmotors.club/?id=slow">Download HubCloud</a>'
                  '<a href="https://greenmotors.club/?id=working">Download HubDrive</a></div>')
        visited = []

        def legacy_request(url, **kwargs):
            if '?s=' in url:
                return search
            if url.endswith('/film'):
                return detail
            raise AssertionError('Migrated links should not use the legacy page parser')

        def resolve(entry, referer, filename, quality, info):
            visited.append(entry)
            if 'id=slow' in entry:
                raise client.Cancelled()
            return iter([dict(url='https://cdn.example/Film.2020.mkv',
                              direct=True, quality=quality, source='HubDrive')])

        with patch.object(client, 'request', side_effect=legacy_request), \
             patch.object(client, 'safe', side_effect=lambda url: url), \
             patch.object(fourkhdhub, 'follow_changed_mirror', side_effect=resolve):
            key = provider.movie('tt1', '1', 'Film', 'Film', [], '2020')
            results = provider.sources(key, [])

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]['quality'], '1080p')
        self.assertEqual(len(visited), 2)
        self.assertEqual([row['status'] for row in client.diagnostics()['hostAttempts']
                          if row['status'] == 'mirror_timeout'], ['mirror_timeout'])

    def test_mirror_deadline_restores_parent_search_deadline(self):
        client.scope(lambda: False, timeout=50)
        original = client._STATE.deadline
        with client.time_budget(10):
            self.assertLess(client._STATE.deadline, original)
        self.assertEqual(client._STATE.deadline, original)
        client.check()

    def test_hubdrive_download_button_after_double_quoted_redirect_reaches_video(self):
        provider = fourkhdhub.source()
        search = ('<a class="movie-card" href="/film">'
                  '<h3 class="movie-card-title">Film</h3>'
                  '<div class="movie-card-meta">2020</div></a>')
        detail = ('<div class="download-item"><div class="file-title">Film 1080p.mkv</div>'
                  '<a href="https://greenmotors.club/hubdrive">Download HubDrive</a></div>')
        hubdrive = 'https://hubdrive.pw/file/one'
        hubcloud = 'https://hubcloud.pw/drive/two'
        seen = []

        def legacy_request(url, **kwargs):
            if '?s=' in url:
                return search
            if url.endswith('/film'):
                return detail
            if url == 'https://greenmotors.club/hubdrive':
                return '<p>Changed landing</p>'
            raise AssertionError('Unexpected legacy URL ' + url)

        def fetch(url, headers, **kwargs):
            seen.append(url)
            response = requests.Response()
            response.url = url
            response.status_code = 200
            if url == 'https://greenmotors.club/hubdrive':
                response.status_code = 302
                response.headers['Location'] = 'https://greenmotors.cc/next'
                body = ''
            elif url == 'https://greenmotors.cc/next':
                body = 's("o","' + self.payload(hubdrive) + '",180000)'
            elif url == hubdrive:
                body = '<a id="download" href="' + hubcloud + '">Download</a>'
            elif url == hubcloud:
                body = '<a href="https://cdn.example/Film.mp4">Download File</a>'
            else:
                raise AssertionError('Unexpected URL ' + url)
            response._content = body.encode()
            return response

        with patch.object(client, 'request', side_effect=legacy_request), \
             patch.object(client, 'fetch', side_effect=fetch), \
             patch.object(client, 'safe', side_effect=lambda value: value):
            rows = provider.sources(provider.movie('tt1', '1', 'Film', 'Film', [], '2020'), [])

        self.assertEqual(len(rows), 1)
        self.assertIn('cdn.example/Film.mp4', rows[0]['url'])
        self.assertEqual(rows[0]['quality'], '1080p')
        self.assertTrue(rows[0]['direct'] and rows[0]['boundedRanges'])
        self.assertEqual(seen, ['https://greenmotors.club/hubdrive',
                                'https://greenmotors.cc/next', hubdrive, hubcloud])

    def test_changed_host_traversal_emits_before_the_second_media_button(self):
        from moviehub_native.cinestream_links import Links
        delivered = []

        def follow(linker, entry, referer, filename):
            yield dict(url='https://cdn.example/Film.2020.one.mkv', direct=True,
                       source='Mirror One')
            self.assertEqual(delivered, ['first'])
            yield dict(url='https://cdn.example/Film.2020.two.mkv', direct=True,
                       source='Mirror Two')

        with patch.object(Links, 'follow', follow):
            mirror = fourkhdhub.follow_changed_mirror(
                'https://greenmotors.cc/page', 'https://4khdhub.one/film',
                'Film 1080p.mkv', '1080p', 'Film 2020')
            first = next(mirror)
            self.assertEqual(first['quality'], '1080p')
            delivered.append('first')
            second = next(mirror)
            self.assertIn('two.mkv', second['url'])
            with self.assertRaises(StopIteration):
                next(mirror)
        self.assertEqual(delivered, ['first'])

    def test_moviehub_emits_4khdhub_results_before_searching_remaining_mirrors(self):
        import moviehub_vod as vod

        emitted = []
        testcase = self
        class Fixture:
            base_link = 'https://4khdhub.one'

            def movie(self, *args):
                return 'film'

            def iter_sources(self, key, hosts):
                yield dict(url='https://cdn.example/Film.2020.one.mkv', direct=True,
                           filename='Film.2020.one.mkv', quality='1080p', source='Mirror One')
                # This generator cannot resume until the first candidate has been
                # validated and reported to MovieHub's source-display callback.
                testcase.assertEqual(len(emitted), 1)
                yield dict(url='https://cdn.example/Film.2020.two.mkv', direct=True,
                           filename='Film.2020.two.mkv', quality='2160p', source='Mirror Two')

            def resolve(self, url):
                return url

        class Report:
            def ready(self, value):
                emitted.append(json.loads(value))

        raw = json.dumps(dict(title='Film', year='2020', imdb='tt1', tmdb=1,
                              season=-1, episode=-1, http=False))
        with patch.object(fourkhdhub, 'source', return_value=Fixture()), \
             patch('moviehub_identity.matches', return_value=True), \
             patch.object(vod, 'playable', side_effect=lambda url, quality, expected_seconds=0:
                          dict(url=url, headers={}, verifiedQuality=None)):
            report = json.loads(vod.query_site('native:fourkhdhub', raw,
                                               lambda: False, Report()))

        self.assertEqual(report['status'], 'complete')
        self.assertEqual(len(report['sources']), 2)
        self.assertEqual(len(emitted), 2)
        self.assertEqual({item['quality'] for item in emitted}, {'1080p', '2160p'})

    def test_uhdmovies_search_uses_the_redirect_destination_as_its_origin(self):
        from moviehub_native.cinestream_downloads import UHDMovies
        from moviehub_native.cinestream_dom import tree

        provider = UHDMovies()
        self.assertEqual(provider.base_link, 'https://uhdmovies.ceo')
        doc = tree('<article><a href="/film-2020">Film</a></article>')
        query = {'title': 'Film', 'year': '2020', 'imdb': 'tt1'}
        calls = []

        def page(url, referer=''):
            calls.append(url)
            if '/search/' in url:
                return tree(''), url, ''
            return doc, url, ''

        with patch.object(provider, 'page', side_effect=page), \
             patch.object(client, 'safe', side_effect=lambda value: value):
            self.assertEqual(list(provider.search(query)),
                             ['https://uhdmovies.ceo/film-2020'])
        self.assertEqual(len(calls), 2)
        self.assertIn('/search/Film%202020', calls[0])
        self.assertIn('/?s=Film%202020', calls[1])
        self.assertEqual(provider.search_path, '/search/{query}')

    def test_missing_matching_year_does_not_guess_a_detail_url(self):
        provider = fourkhdhub.source()
        with patch.object(client, 'request', return_value='<a class="movie-card" href="/wrong">Film 2021</a>') as call:
            self.assertEqual(provider.sources(provider.movie('tt1', '1', 'Film', 'Film', [{'title': 'Film'}], '2020'), []), [])
        self.assertEqual(call.call_count, 1)

    def test_vixsrc_fetches_fresh_embed_preserves_token_query_and_supports_exact_episodes(self):
        provider = vixsrc.source()
        future = str(int(time.time()) + 300)
        calls = []
        def request(url, **kwargs):
            calls.append(url)
            if '/api/' in url: return {'src': '/embed/one'}
            return "var masterPlaylist = { params: {'token': 'issued', 'expires': '" + future + "'}, url: 'https://cdn.example/master.m3u8?existing=1'}"
        with patch.object(client, 'request', side_effect=request), patch.object(client, 'safe', side_effect=lambda x: x):
            series = provider.tvshow('tt1', '12', '', 'Show', 'Show', [], '2020')
            key = provider.episode(series, 'tt1', '12', '', 'Episode', '', '2', '3')
            rows = provider.sources(key, [])
        self.assertTrue(calls[0].startswith('https://vixsrc.to/api/tv/12/2/3?_='))
        self.assertEqual(len(rows), 1)
        self.assertIn('?existing=1&token=issued&expires=' + future + '&h=1', rows[0]['url'])
        self.assertEqual(rows[0]['source'], 'VixCloud')

    def test_expired_player_token_is_not_offered_as_playable(self):
        provider = vixsrc.source()
        with patch.object(client, 'request', side_effect=[{'src': '/embed/one'},
            "var p={params:{'token':'old','expires':'1'},url:'https://cdn.example/master.m3u8'}"]), \
            patch.object(client, 'safe', side_effect=lambda x: x):
            self.assertEqual(provider.sources('/api/movie/1', []), [])
        self.assertIsNone(provider.movie('tt1', 'bad', 'Film', 'Film', [], '2020'))
