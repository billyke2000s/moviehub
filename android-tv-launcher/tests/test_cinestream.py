"""Offline protocol/identity fixtures, not claims of live provider playback."""
import json
import unittest
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch
import requests
from moviehub_compat import client
from moviehub_native import cinestream_api as api
from moviehub_native import cinestream_catalogues as sites
from moviehub_native import cinestream_hls as hls
from moviehub_compat.source_metadata import describe


class CineStreamTests(unittest.TestCase):
    def setUp(self):
        self.transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        self.transport.start()
        self.addCleanup(self.transport.stop)
        safe = patch.object(client, 'safe', side_effect=lambda url: url)
        safe.start()
        self.addCleanup(safe.stop)
        client.scope(lambda: False, timeout=30)

    def playlist(self, body, url='https://cdn.example/master.m3u8'):
        response = requests.Response()
        response.status_code = 200
        response.url = url
        response._content = body.encode()
        return response

    def key(self, source, series=False):
        key = source.movie('tt1234', '123', 'A Film', 'A Film', [], '2020')
        return source.episode(key, 'tt1234', '123', '', 'Part', '', '2', '3') if series else key

    def test_vaplayer_movie_and_exact_episode_contract_preserves_referer(self):
        provider = api.VaPlayer()
        for series in (False, True):
            with self.subTest(series=series), patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')), patch.object(client, 'request', return_value={
                    'data': {'stream_urls': ['https://cdn.example/master.m3u8']}}) as request:
                rows = provider.sources(self.key(provider, series), [])
            args = parse_qs(urlparse(request.call_args.args[0]).query)
            self.assertEqual(args['type'], ['tv' if series else 'movie'])
            self.assertEqual(args.get('season'), ['2'] if series else None)
            self.assertEqual(args.get('episode'), ['3'] if series else None)
            self.assertIn('Referer=https%3A%2F%2Fnextgencloudfabric.com%2F', rows[0]['url'])
            self.assertEqual(rows[0]['quality'], 'Auto')

    def test_videasy_independent_backends_survive_refusal_without_assumed_quality(self):
        from moviehub_native import movy
        provider = api.Videasy()
        seen = []

        def request(url, **kwargs):
            seen.append(url)
            self.assertNotIn('enc-dec.app', url)
            if '/seed?' in url:
                return {'seed': 'issued'}
            if '/reno/' in url:
                raise ValueError('refused')
            args = parse_qs(urlparse(url).query)
            self.assertEqual(args['seasonId'], ['2'])
            self.assertEqual(args['episodeId'], ['3'])
            self.assertEqual(args['title'], ['A%20Film'])
            self.assertEqual(urlparse(url).path.split('/')[-1], 'sources')
            if '/munich/' in url:
                self.assertEqual(args['language'], ['german'])
            return 'encoded'

        payload = {'sources': [{'url': 'https://cdn.example/master.m3u8', 'quality': 'Streamwish'},
                               {'url': 'https://cdn.example/drm.mpd', 'drm': True}]}
        with patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')), patch.object(client, 'request', side_effect=request), patch.object(movy, 'decode', return_value=payload) as decode:
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 13)  # Reno failed; Austin/Delhi reject other languages.
        self.assertEqual(len({r['source'] for r in rows}), 13)
        self.assertTrue(all(r['quality'] == 'Auto' for r in rows))
        self.assertTrue(all(r['info'] == 'Streamwish' for r in rows))
        self.assertTrue(all('Origin=https%3A%2F%2Fvidy.st' in r['url'] for r in rows))
        self.assertFalse(any('/boise/' in url or '/miami/' in url for url in seen))
        decode.assert_called_with('encoded', 'issued', '123')
        self.assertEqual(sum('/seed?' in url for url in seen), 1)

    def test_videasy_language_filters_and_diagnostic_subset(self):
        from moviehub_native import movy
        provider = api.Videasy()
        provider.cinestream_audit_backends = 'austin,delhi,boise'
        payload = {'sources': [{'url': 'https://cdn.example/' + language + '.m3u8', 'quality': language}
                               for language in ('English', 'Hindi', 'German')]}
        with patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')), patch.object(client, 'request', side_effect=lambda url, **kw: {'seed': 'issued'} if '/seed?' in url else 'encoded'), patch.object(movy, 'decode', return_value=payload):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual([row['info'] for row in rows], ['English', 'Hindi'])
        self.assertEqual([row['source'] for row in rows], ['Vidy Austin', 'Vidy Delhi'])

    def test_token_servers_use_rotated_token_and_exact_episode_route(self):
        for cls in (api.Vidup, api.Vidcore, api.Vidfast):
            with self.subTest(cls=cls.__name__):
                provider = cls()
                calls = []

                def request(url, **kw):
                    calls.append((url, kw))
                    if '/tv/' in url:
                        return r'{\"token\":\"literal\"}'
                    if urlparse(url).path.startswith('/api/enc-'):
                        args = parse_qs(urlparse(url).query)
                        if args['stage'] == ['1']:
                            self.assertEqual(args['text'], ['literal'])
                            return {'status': 200, 'result': dict(token='bootstrap', stage1=provider.base_link + '/bootstrap')}
                        self.assertEqual(args['stage'], ['2'])
                        self.assertEqual(args['text'], ['bootstrap-response'])
                        return {'status': 200, 'result': dict(token='rotated', servers=provider.base_link + '/servers', stream=provider.base_link + '/stream')}
                    if url.endswith('/bootstrap'):
                        self.assertEqual(kw['headers']['X-CSRF-Token'], 'bootstrap')
                        self.assertEqual(kw['post'], '')
                        return 'bootstrap-response'
                    if url.endswith('/servers') or '/stream/' in url:
                        self.assertEqual(kw['post'], '')
                        self.assertEqual(kw['headers']['X-CSRF-Token'], 'rotated')
                        return 'encrypted'
                    if urlparse(url).path.startswith('/api/dec-'):
                        posts = sum(urlparse(u).path.startswith('/api/dec-') for u, k in calls)
                        return {'status': 200, 'result': [dict(name='one', data='hash')] if posts == 1 else dict(url='https://cdn.example/film.m3u8')}
                    raise AssertionError(url)

                with patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')), patch.object(client, 'request', side_effect=request):
                    rows = provider.sources(self.key(provider, True), [])
                self.assertEqual(len(rows), 1)
                self.assertTrue(calls[0][0].endswith('/tv/123/2/3/'))
                self.assertEqual('X-CSRF-Token=rotated' in rows[0]['url'], cls is api.Vidfast)
                self.assertNotIn('X-Requested-With', rows[0]['url'])
                self.assertNotIn('bootstrap', rows[0]['url'])

    def test_token_bootstrap_does_not_send_csrf_to_another_origin(self):
        provider = api.Vidup()
        with patch.object(client, 'request', side_effect=[r'{\"token\":\"literal\"}',
                {'status': 200, 'result': dict(token='secret', stage1='https://other.example/bootstrap')}]) as request:
            with self.assertRaisesRegex(ValueError, 'origin changed'):
                provider.sources(self.key(provider), [])
        self.assertEqual(request.call_count, 2)

    def test_hls_expands_variants_before_master_with_real_heights_and_referer(self):
        body = '#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:URI="trick.m3u8"\n#EXT-X-STREAM-INF:RESOLUTION=1280x720,CODECS="avc1,mp4a"\n720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4\n//other.example/auto.m3u8'
        with patch.object(client, 'fetch', return_value=self.playlist(body, 'https://cdn.example/path/master.m3u8')) as fetch:
            rows = list(hls.streams('https://cdn.example/original.m3u8', 'https://site.example/', 'Site'))
        self.assertEqual([r['url'].partition('|')[0] for r in rows], ['https://cdn.example/path/720/index.m3u8', 'https://other.example/auto.m3u8', 'https://cdn.example/original.m3u8'])
        self.assertEqual([r['quality'] for r in rows], ['720p', 'Auto', 'Auto'])
        self.assertTrue(all(parse_qs(r['url'].partition('|')[2])['Referer'] == ['https://site.example/'] for r in rows))
        self.assertNotIn('Range', fetch.call_args.args[1])

    def test_hls_keeps_external_audio_attached_and_rejects_error_documents(self):
        body = '#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="en",URI="en.m3u8"\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,AUDIO="en"\nvideo.m3u8'
        for content, count in ((body, 1), ('#EXTM3U\n#EXTINF:600,\na.ts', 1), ('<html>refused</html>', 0), ('#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:URI="only-trick.m3u8"', 0)):
            with self.subTest(content=content), patch.object(client, 'fetch', return_value=self.playlist(content)):
                rows = list(hls.streams('https://cdn.example/master.m3u8', 'https://site.example/', 'Site'))
            self.assertEqual(len(rows), count)
            if rows:
                self.assertTrue(rows[0]['url'].startswith('https://cdn.example/master.m3u8|'))

    def test_media_csrf_is_not_sent_across_redirect_authorities(self):
        from moviehub_vod import media_probe
        first = self.playlist('')
        first.status_code = 302
        first.headers['Location'] = 'https://other.example/movie.m3u8'
        last = self.playlist('#EXTM3U', 'https://other.example/movie.m3u8')
        with patch.object(client, 'fetch', side_effect=[first, last]) as fetch:
            self.assertIs(media_probe('https://cdn.example/movie.m3u8', {'X-CSRF-Token': 'issued', 'Referer': 'https://site.example/'}), last)
        self.assertEqual(fetch.call_args_list[0].args[1]['X-CSRF-Token'], 'issued')
        self.assertNotIn('X-CSRF-Token', fetch.call_args_list[1].args[1])
        self.assertEqual(fetch.call_args_list[1].args[1]['Referer'], 'https://site.example/')

    def test_hexa_expands_each_server_independently(self):
        provider = api.Hexa()
        with patch.object(client, 'request', side_effect=[{'result': {'token': 'issued'}}, 'encrypted', {'result': {'sources': [{'server': 'alpha', 'url': 'https://cdn.example/master.m3u8'}]}}]), patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\n1080.m3u8')):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual([r['quality'] for r in rows], ['1080p', 'Auto'])
        self.assertTrue(all(r['source'] == 'Hexa alpha' for r in rows))
        self.assertTrue(all('Referer=https%3A%2F%2Fhexa.su%2F' in r['url'] for r in rows))

    def test_a_refused_hexa_playlist_does_not_discard_other_servers(self):
        provider = api.Hexa()
        provider.cinestream_audit = True
        with patch.object(client, 'request', side_effect=[{'result': {'token': 'issued'}}, 'encrypted', {'result': {'sources': [{'server': 'bad', 'url': 'https://cdn.example/bad.m3u8'}, {'server': 'good', 'url': 'https://cdn.example/good.m3u8'}]}}]), patch.object(client, 'fetch', side_effect=[requests.Timeout(), self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')]):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['source'], 'Hexa good')

    def test_peachify_proxy_keeps_source_header_fallbacks(self):
        import base64
        from urllib.parse import urlencode
        from moviehub_native import cinestream_crypto as crypto
        from resolveurl.lib.aesgcm import python_aesgcm
        iv = bytearray(b'fixture12345')
        url = 'https://proxy.example/m3u8-proxy?' + urlencode(dict(url='https://cdn.example/film.m3u8', headers=json.dumps({'origin': 'https://cdn.example'})))
        clear = json.dumps({'sources': [dict(url=url, headers={'referer': 'https://source.example/', 'user-agent': 'source-player'})]}).encode()
        encoded = bytes(python_aesgcm.new(bytearray(crypto.PEACHIFY_FORMAT_KEY)).seal(iv, bytearray(clear), bytearray()))
        data = '.'.join(base64.urlsafe_b64encode(value).decode().rstrip('=') for value in (bytes(iv), encoded[:-16], encoded[-16:]))
        provider = crypto.Peachify()
        with patch.object(client, 'request', return_value={'data': data}):
            rows = provider.sources(self.key(provider), [])
        headers = parse_qs(rows[0]['url'].partition('|')[2])
        self.assertEqual(headers, {'referer': ['https://source.example/'], 'origin': ['https://cdn.example'], 'user-agent': ['source-player']})

    def test_each_stops_on_cancellation_before_another_backend(self):
        provider = api.Meowtv()
        client.scope(lambda: True, timeout=30)
        with self.assertRaises(client.Cancelled), patch.object(client, 'request') as request:
            provider.sources(self.key(provider), [])
        request.assert_not_called()

    def test_primesrc_retains_host_resolution_and_does_not_call_embed_a_file(self):
        provider = api.PrimeSrc()
        with patch.object(client, 'request', side_effect=[{'servers': [dict(key='one', name='Host A')]},
                                                        {'link': 'https://host.example/e/id'}]):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual(rows[0]['source'], 'Host A')
        self.assertFalse(rows[0]['direct'])

    def test_fshare_is_movie_only_and_keeps_alternatives(self):
        provider = api.Fshare()
        page = "<input value='t' type='hidden' id='trailer'><script>Movie.setSource('token')</script>"
        data = {'data': {'file': {'sources': [dict(src='/a.mp4', quality='720')],
                                 'alternatives': [[dict(src='/b.mp4', quality='1080')]]}}}
        with patch.object(client, 'request', side_effect=[page, data]):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual(len(rows), 2)
        self.assertEqual({r['quality'] for r in rows}, {'720', '1080'})
        with patch.object(client, 'request') as request:
            self.assertEqual(provider.sources(self.key(provider, True), []), [])
        request.assert_not_called()

    def test_projectfreetv_rejects_unrelated_first_hit_and_other_episode(self):
        provider = sites.ProjectFreeTv()
        responses = [{'movies': [dict(_id='wrong', name='Other', year='2020'), dict(_id='right', imdb='tt1234')]},
                     {'streams': [dict(e=2, stream='https://cdn.example/wrong.mp4'), dict(e=3, stream='https://cdn.example/right.mp4')]}]
        with patch.object(client, 'request', side_effect=responses) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 1)
        self.assertIn('right.mp4', rows[0]['url'])
        self.assertIn('_id=right', request.call_args.args[0])

    def test_all_new_adapters_reject_missing_identity_without_requests(self):
        from moviehub_native import PROVIDERS
        import importlib
        new = [r for r in PROVIDERS if 'factory' in r]
        self.assertEqual(len(new), 24)
        self.assertEqual(len({r['site'] for r in PROVIDERS}), len(PROVIDERS))
        import moviehub_vod as vod
        groups = json.loads(vod.search_providers())
        self.assertTrue(all(not any(a in {r['id'] for r in new} for a in g['adapters']) for g in groups[:38]))
        self.assertEqual(len(groups), 59)
        auditable = json.loads(vod.search_providers(include_retired=True))
        self.assertTrue({r['id'] for r in new} <= {a for g in auditable for a in g['adapters']})
        self.assertEqual({r['id'] for r in new if r.get('pending')}, {'native:vaplayer', 'native:primesrc', 'native:asiaflix'})
        with patch.object(client, 'request') as request:
            for record in new:
                provider = getattr(importlib.import_module(record['module']), record['factory'])()
                self.assertIsNone(provider.movie('', '', 'A', 'A', [], '2020'))
                self.assertEqual(provider.sources(None, []), [])
        request.assert_not_called()

    def test_authenticated_local_player_data_and_backend_headers(self):
        import base64
        from moviehub_native import cinestream_crypto as crypto
        from resolveurl.lib.aesgcm import python_aesgcm
        iv = bytearray(b'fixture12345')
        plaintext = bytearray(b'https://cdn.example/film.m3u8')
        encrypted = python_aesgcm.new(bytearray(crypto.VIDROCK_FORMAT_KEY)).seal(iv, plaintext, bytearray())
        payload = base64.urlsafe_b64encode(bytes(iv) + bytes(encrypted)).decode().rstrip('=')
        provider = crypto.Vidrock()
        with patch.object(client, 'request', return_value={'one': {'url': payload}, 'two': {'url': 'error'}}):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['source'], 'Vidrock one')
        self.assertIn('Origin=https%3A%2F%2Fvidrock.ru', rows[0]['url'])
        corrupted = bytearray(encrypted)
        corrupted[-1] ^= 1
        with self.assertRaises(ValueError):
            crypto.gcm(crypto.VIDROCK_FORMAT_KEY, iv, corrupted)

    def test_moviebox_preserves_distinct_download_play_and_dash_without_vip_links(self):
        from moviehub_native.cinestream_moviebox import MovieBox
        provider = MovieBox()
        response = requests.Response()
        response.headers['x-user'] = json.dumps(dict(token='ephemeral'))
        def request(url, **kw):
            if '/search' in url:
                return {'data': {'items': [dict(subjectId='wrong', title='Other'),
                    dict(subjectId='id', title='A Film', releaseDate='2020-01-01')]}}
            if '/post/list/' in url:
                self.assertNotIn('Authorization', kw.get('headers') or {})
                return {'data': {'items': [dict(subject=dict(detailPath='film'))]}}
            self.assertEqual(kw['headers']['Authorization'], 'Bearer ephemeral')
            if '/download?' in url:
                return {'data': {'downloads': [dict(url='https://cdn.example/locked.mp4', vipLocked=True),
                    dict(url='https://cdn.example/a.mp4', resolution=1080)]}}
            return {'data': {'streams': [dict(url='https://cdn.example/b.mp4', resolution=1080)],
                             'dash': [dict(url='https://cdn.example/c.mpd')]}}
        with patch.object(client, 'fetch', return_value=response), patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 3)
        self.assertEqual(len({r['source'] for r in rows}), 3)
        self.assertTrue(all('ephemeral' not in r['url'] and 'locked.mp4' not in r['url'] for r in rows))

    def test_allmovieland_selects_exact_season_and_episode_before_playlist(self):
        provider = api.AllMovieLand()
        def request(url, **kw):
            if 'player.js' in url:
                return "const AwsIndStreamDomain = 'https://player.example';"
            if '/play/' in url:
                return 'var playlist = {"file":"/catalogue","key":"issued"};'
            if url.endswith('/catalogue'):
                return json.dumps([dict(id='1', folder=[]), dict(id='2', folder=[dict(episode='3', folder=[dict(file='right', title='English')])])])
            self.assertTrue(url.endswith('/playlist/right.txt'))
            self.assertEqual(kw['headers']['X-CSRF-TOKEN'], 'issued')
            return 'https://cdn.example/right.m3u8'
        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 1)
        self.assertNotIn('X-CSRF', rows[0]['url'])

    def test_doo_ajax_uses_exact_episode_and_skips_trailers(self):
        from moviehub_native.cinestream_embeds import Multimovies
        provider = Multimovies()
        page = '<ul id="playeroptionsul"><li data-post="12" data-nume="trailer" data-type="tv"></li><li data-post="12" data-nume="1" data-type="tv"></li></ul>'
        with patch.object(client, 'request', side_effect=[{}, page, {'embed_url': '<iframe src="https://cdn.example/master.m3u8"></iframe>'}]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(request.call_count, 3)
        self.assertTrue(request.call_args_list[1].args[0].endswith('/episodes/a-film-2x3/'))
        self.assertEqual(request.call_args.kwargs['post'], dict(action='doo_player_ajax', post='12', nume='1', type='tv'))
        self.assertIn('Referer=https%3A%2F%2Fm1.multimovies.courses%2Fepisodes%2Fa-film-2x3%2F', rows[0]['url'])

    def test_toonstream_keeps_two_distinct_embed_levels(self):
        from moviehub_native.cinestream_embeds import Toonstream
        provider = Toonstream()
        with patch.object(client, 'request', side_effect=[
            '<div class="video"><iframe data-src="https://wrapper.example/a"></iframe></div>',
            '<div class="Video"><iframe src="https://host.example/e/one"></iframe></div>'
        ]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertTrue(request.call_args_list[0].args[0].endswith('/episode/a-film-2x3/'))
        self.assertEqual(rows[0]['url'], 'https://host.example/e/one')
        self.assertFalse(rows[0]['direct'])

    def test_animesalt_does_not_claim_hardcoded_1080_quality(self):
        from moviehub_native.cinestream_embeds import AnimeSalt
        provider = AnimeSalt()
        with patch.object(client, 'request', side_effect=[
            '<iframe src="https://as-cdn1.top/video/ab12"></iframe>',
            {'videoSource': 'https://cdn.example/auto.m3u8'}
        ]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(rows[0]['quality'], 'Auto')
        self.assertEqual(request.call_args.kwargs['post']['hash'], 'ab12')

    def test_kisskh_rejects_first_wrong_season_and_selects_exact_episode(self):
        from moviehub_native.cinestream_embeds import KissKH
        provider = KissKH()
        with patch.object(client, 'request', side_effect=[
            [dict(id=1, title='Other'), dict(id=2, title='A Film Season 2')],
            {'episodes': [dict(id=20, number=2), dict(id=30, number=3)]},
            {'result': 'issued'}, {'Video': 'https://cdn.example/right.m3u8'}
        ]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertTrue(request.call_args_list[1].args[0].endswith('/Drama/2?isq=false'))
        self.assertIn('/Episode/30.png?', request.call_args.args[0])
        self.assertEqual(rows[0]['source'], 'KissKH Video')
        self.assertEqual(rows[0]['quality'], 'Auto')

    def test_akwam_follows_download_hops_only_after_matching_imdb(self):
        provider = sites.Akwam()
        pages = ['<a class="box" href="/film">A Film</a>', '<a href="https://imdb.com/title/tt1234/">IMDb</a><a class="link-download" href="/one">download</a>', '<a class="download-link" href="/two">next</a>', '<a class="link" href="https://cdn.example/film.mp4">file</a>']
        with patch.object(client, 'request', side_effect=pages) as request:
            rows = provider.sources(self.key(provider), [])
        self.assertEqual(request.call_count, 4)
        self.assertEqual(rows[0]['url'].partition('|')[0], 'https://cdn.example/film.mp4')
        with patch.object(client, 'request', side_effect=[pages[0], pages[1].replace('tt1234', 'tt9999')]) as request:
            self.assertEqual(provider.sources(self.key(provider), []), [])
        self.assertEqual(request.call_count, 2)

    def test_asiaflix_selects_requested_episode_from_nested_angular_state(self):
        provider = sites.Asiaflix()
        state = json.dumps({'nested': {'episodes': [dict(number=2, streamUrls=[dict(url='https://cdn.example/wrong.mp4')]), dict(number=3, streamUrls=[dict(url='//cdn.example/right.mp4')])]}})
        with patch.object(client, 'request', side_effect=[{'body': [dict(name='A Film', _id='id')]}, '<script id="ng-state">' + state + '</script>']):
            query = json.loads(self.key(provider, True)); query['season'] = '1'
            rows = provider.sources(json.dumps(query), [])
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0]['url'].startswith('https://cdn.example/right.mp4|'))

    def test_onetouchtv_retains_returned_media_headers_and_numbered_episode(self):
        provider = api.OneTouchTv()
        payload = dict(sources=[dict(url='https://cdn.example/film.m3u8', quality='720p', headers={'Referer': 'https://required.example/', 'Origin': 'https://required.example'})])
        with patch.object(client, 'request', side_effect=['search', {'result': [dict(title='A Film Season 2 (2020)', id=55)]}, 'playback', {'result': payload}]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertTrue(request.call_args_list[2].args[0].endswith('/web/vod/55/episode/3'))
        self.assertEqual(parse_qs(rows[0]['url'].partition('|')[2]), {'Referer': ['https://required.example/'], 'Origin': ['https://required.example']})
        self.assertEqual(rows[0]['quality'], '720p')

    def test_cinejoy_binary_state_and_separate_hls_file_qualities(self):
        import base64
        provider = api.Cinejoy()
        def request(url, **kw):
            if url.endswith('/servers'):
                return {'servers': [dict(name='one')]}
            if '/enc-cinejoy?' in url:
                target = parse_qs(urlparse(url).query)['url'][0]
                self.assertEqual(parse_qs(urlparse(target).query)['episode'], ['3'])
                return {'result': {'data': base64.urlsafe_b64encode(b'request-body').decode(), 'state': 'issued'}}
            if url.endswith('/g'):
                self.assertEqual(kw['post'], b'request-body')
                self.assertEqual(kw['headers']['Content-Type'], 'application/octet-stream')
                return b'encrypted-response'
            posted = json.loads(kw['post'])
            self.assertEqual(posted['state'], 'issued')
            self.assertEqual(base64.urlsafe_b64decode(posted['text'] + '==='), b'encrypted-response')
            return {'result': {'data': {'stream': [dict(type='hls', playlist='https://cdn.example/master.m3u8'), dict(type='file', qualities={'4k': {'url': 'https://cdn.example/film.mkv'}, '720': {'url': 'https://cdn.example/film.mp4'}})]}}}
        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual([r['quality'] for r in rows], ['Auto', '2160p', '720p'])
        self.assertTrue(all(parse_qs(r['url'].partition('|')[2]) == {'Referer': ['https://cinejoy.pk/']} for r in rows))

    def test_vidzee_cbc_response_headers_override_defaults_case_insensitively(self):
        import base64
        from moviehub_native import cinestream_crypto as crypto
        from resolveurl.lib import pyaes
        iv = b'0123456789abcdef'
        cipher = pyaes.Encrypter(pyaes.AESModeOfOperationCBC(crypto.VIDZEE_FORMAT_KEY, iv=iv))
        encrypted = cipher.feed(b'https://cdn.example/film.m3u8') + cipher.feed()
        inner = base64.b64encode(iv).decode() + ':' + base64.b64encode(encrypted).decode()
        response = {'headers': {'Referer': 'https://required.example/'}, 'url': [dict(link=base64.b64encode(inner.encode()).decode())]}
        provider = crypto.Vidzee()
        with patch.object(client, 'request', return_value=response):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 7)
        headers = parse_qs(rows[0]['url'].partition('|')[2])
        self.assertEqual([v for k, v in headers.items() if k.lower() == 'referer'], [['https://required.example/']])

    def test_rtally_keeps_distinct_backend_routes_and_direct_resources(self):
        from moviehub_native.cinestream_embeds import Rtally
        provider = Rtally()
        with patch.object(client, 'request', return_value=r'{\"small\":\"https://cdn.example/film.mp4\",\"filemoonUrl\":\"one\",\"vidhideUrl\":\"two\"}'):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual([r['url'].partition('|')[0] for r in rows], ['https://cdn.example/film.mp4', 'https://filemoon.sx/e/one', 'https://vidhideplus.com/v/two'])
        self.assertEqual([r['direct'] for r in rows], [True, False, False])

    def test_implicit_requests_use_upstream_profile_without_changing_legacy_client(self):
        legacy = client.UserAgent
        with patch.object(client, 'request', return_value='page') as request:
            api.request('https://site.example/page')
        self.assertEqual(request.call_args.kwargs['headers']['User-Agent'], hls.USER_AGENT)
        self.assertEqual(client.UserAgent, legacy)
        with patch.object(client, 'request', return_value='page') as request:
            api.request('https://site.example/page', {'user-agent': 'specific-player'})
        self.assertEqual(request.call_args.kwargs['headers'], {'user-agent': 'specific-player'})

    def test_new_lookup_keeps_cookies_between_requests_and_isolates_other_lookups(self):
        sessions = []
        class Site(api.Base):
            base_link = 'https://site.example'
            def extract(self, query):
                api.request(self.base_link + '/init')
                api.request(self.base_link + '/play')
                return []
        def request(url, **kw):
            session = kw['session']
            if url.endswith('/init'):
                self.assertIsNone(session.cookies.get('issued'))
                session.cookies.set('issued', 'ephemeral', domain='site.example', path='/')
                sessions.append(session)
            else:
                self.assertIs(session, sessions[-1])
                self.assertEqual(session.cookies.get('issued'), 'ephemeral')
            return 'page'
        provider = Site()
        with patch.object(client, 'request', side_effect=request):
            provider.sources(self.key(provider), [])
            provider.sources(self.key(provider), [])
        self.assertIsNot(sessions[0], sessions[1])
        self.assertIsNone(api.http_session())

    def test_media_only_carries_cookies_matching_its_own_domain(self):
        class Site(api.Base):
            def extract(self, query):
                session = api.http_session()
                session.cookies.set('private', 'site-cookie', domain='site.example', path='/')
                session.cookies.set('media', 'cdn-cookie', domain='cdn.example', path='/')
                yield api.item('https://cdn.example/film.mp4', {}, 'one')
                yield api.item('https://other.example/film.mp4', {}, 'two')
        provider = Site()
        rows = provider.sources(self.key(provider), [])
        self.assertEqual(parse_qs(rows[0]['url'].partition('|')[2]), {'Cookie': ['media=cdn-cookie']})
        self.assertNotIn('Cookie', parse_qs(rows[1]['url'].partition('|')[2]))
        self.assertTrue(all('site-cookie' not in row['url'] for row in rows))

    def test_hexa_excludes_failing_backends_only_from_normal_playback(self):
        provider = api.Hexa()
        response = {'result': {'sources': [{'server': name, 'url': 'https://cdn.example/' + name + '.m3u8'} for name in ('alpha', 'delta', 'echo', 'foxtrot')]}}
        for audit, expected in ((False, ['Hexa alpha']), (True, ['Hexa alpha', 'Hexa delta', 'Hexa echo', 'Hexa foxtrot'])):
            provider.cinestream_audit = audit
            with patch.object(client, 'request', side_effect=[{'result': {'token': 'issued'}}, 'encrypted', response]), patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')):
                rows = provider.sources(self.key(provider), [])
            self.assertEqual([row['source'] for row in rows], expected)

    def test_meow_fresh_guest_ticket_per_backend_and_exact_episode(self):
        provider = api.Meowtv()
        calls = []
        issued = []
        consumed = []

        def request(url, **kw):
            calls.append((url, kw))
            if url.endswith('/streams/ticket'):
                ticket = 'single-use-' + str(len(issued))
                issued.append(ticket)
                return {'ticket': ticket}
            if '/streams/tv/' in url:
                self.assertIn('/tv/123/2/3?', url)
                self.assertNotIn('turkce', url)
                ticket = kw['headers']['x-stream-ticket']
                self.assertEqual(ticket, issued[-1])
                self.assertNotIn(ticket, consumed)
                consumed.append(ticket)
                return {'encrypted': 'fixture'}
            self.assertNotIn('x-stream-ticket', kw['headers'])
            return {'result': {'url': 'https://cdn.example/movie.m3u8', 'headers': {
                'referer': 'https://media.example/', 'X-Stream-Ticket': 'never-forward'}, 'language': 'English'}}

        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 7)
        self.assertEqual(len(issued), 7)
        self.assertEqual(issued, consumed)
        for row in rows:
            headers = parse_qs(row['url'].partition('|')[2])
            self.assertEqual(headers['referer'], ['https://media.example/'])
            self.assertNotIn('Referer', headers)
            self.assertNotIn('ticket', row['url'].lower())
        self.assertEqual(rows[0]['info'], 'English')

    def test_tmdb_routes_do_not_require_an_unrelated_imdb_identifier(self):
        for cls in (api.Meowtv, api.PrimeSrc, api.Videasy):
            self.assertIsNotNone(cls().movie('', '123', 'A Film', 'A Film', [], '2020'))
        for cls in (api.VaPlayer, api.Fshare, api.AllMovieLand, sites.Akwam):
            self.assertIsNone(cls().movie('', '123', 'A Film', 'A Film', [], '2020'))

    def test_source_metadata_is_advertised_not_selected_or_measured_audio(self):
        prefix = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.640028,mp4a.40.2"\na.m3u8\n#EXT-X-STREAM-INF:CODECS="hvc1.1,ec-3",BANDWIDTH=2\nb.m3u8'
        self.assertEqual(describe(prefix, {}), 'Manifest codecs: AVC (H.264) / AAC / HEVC (H.265) / E-AC-3')
        self.assertEqual(describe(b'#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\na.m3u8', {}), '')
        self.assertEqual(describe(b'<html>CODECS="avc1"</html>', {}), '')
        self.assertEqual(describe(b'file', {'Content-Length': '65536'}), '')
        self.assertEqual(describe(b'file', {'Content-Range': 'bytes 0-65535/1073741824'}), 'File: 1.00 GiB')

    def test_current_multimovies_config_checks_episode_before_exposing_embeds(self):
        from moviehub_native.cinestream_embeds import Multimovies
        provider = Multimovies()
        search = {'tv_shows': [dict(title='A Film', url='/series/a-film-99')]}
        for episode, expected in ((3, 1), (2, 0)):
            config = dict(season=2, episode=episode, initialServers=[
                dict(name='Real backend', type='iframe', url='https://host.example/e/1')])
            page = 'const watchConfig = ' + json.dumps(config) + ';'
            with patch.object(client, 'request', side_effect=[search, page]) as request:
                rows = provider.sources(self.key(provider, True), [])
            self.assertEqual(len(rows), expected)
            self.assertTrue(request.call_args.args[0].endswith('/series/a-film-99/season/2/episode/3'))
            if rows:
                self.assertEqual(rows[0]['source'], 'Multimovies Real backend')
                self.assertFalse(rows[0]['direct'])

    def test_current_animesalt_retains_advertised_hosts_and_languages(self):
        import base64
        from moviehub_native.cinestream_embeds import AnimeSalt
        provider = AnimeSalt()
        encoded = base64.b64encode(json.dumps([dict(language='English', link='https://host.example/e/english')]).encode()).decode()
        page = ('<div class="video"><iframe data-src="https://other.example/e/1"></iframe></div>'
                '<div class="video"><iframe data-src="https://animesalt.cx/wp-content/themes/torofilm/public/plyr/player.php?data=' + encoded + '"></iframe></div>')
        with patch.object(client, 'request', return_value=page):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]['source'], 'AnimeSalt English')
        self.assertEqual(rows[1]['url'], 'https://host.example/e/english')
        self.assertTrue(all(not row['direct'] and row['quality'] == 'Auto' for row in rows))

    def test_primesrc_forwards_only_advertised_server_token_and_accepts_tmdb(self):
        provider = api.PrimeSrc()
        key = provider.movie('', '123', 'A Film', 'A Film', [], '2020')
        with patch.object(client, 'request', side_effect=[
                {'servers': [dict(key='one', token='issued', name='Host A')]},
                {'link': 'https://host.example/e/one'}]) as request:
            rows = provider.sources(key, [])
        self.assertEqual(parse_qs(urlparse(request.call_args_list[0].args[0]).query), {'type': ['movie'], 'tmdb': ['123']})
        self.assertEqual(parse_qs(urlparse(request.call_args.args[0]).query), {'key': ['one'], 'token': ['issued']})
        self.assertNotIn('issued', rows[0]['url'])

    def test_unattended_audits_block_external_decoder_before_network(self):
        from audit_transport import block_external_decoder
        with patch.object(client, 'request', return_value={'public': True}) as request:
            block_external_decoder()
            with self.assertRaises(PermissionError):
                client.request(api.DECODER + '/enc-vidfast?text=private-fixture')
            request.assert_not_called()
            self.assertEqual(client.request('https://site.example/'), {'public': True})

    def test_meow_refreshes_a_rejected_ticket_once_and_preserves_acme_streams(self):
        provider = api.Meowtv()
        calls = []

        def request(url, **kw):
            calls.append(url)
            if url.endswith('/streams/ticket'):
                return {'ticket': str(len(calls))}
            if '/streams/movie/' in url:
                name = parse_qs(urlparse(url).query)['s'][0]
                if name != 'acme':
                    raise ValueError('backend unavailable')
                self.assertEqual(parse_qs(urlparse(url).query)['list'], ['1'])
                if sum('s=acme' in call for call in calls) == 1:
                    return {'error': 'Ticket expired'}
                return {'encrypted': 'fixture'}
            return {'result': {'streams': [dict(url='https://cdn.example/en.m3u8', language='English'),
                                          dict(url='https://cdn.example/hi.m3u8', language='Hindi')]}}

        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider), [])
        self.assertEqual([row['info'] for row in rows], ['English', 'Hindi'])
        self.assertEqual(sum('s=acme' in call for call in calls), 2)
        self.assertTrue(any('s=turkce' in call for call in calls))

    def test_vidy_renditions_preserve_origin_and_external_audio_contract(self):
        from moviehub_native import movy
        provider = api.Videasy()
        provider.cinestream_audit_backends = 'phoenix'
        payload = {'sources': [dict(url='https://cdn.example/master.m3u8', quality='Streamwish')]}
        body = '#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720,CODECS="avc1,mp4a"\n720.m3u8'
        with patch.object(client, 'request', side_effect=[{'seed': 'issued'}, 'encoded']), patch.object(movy, 'decode', return_value=payload), patch.object(client, 'fetch', return_value=self.playlist(body)) as fetch:
            rows = provider.sources(self.key(provider), [])
        self.assertEqual([row['quality'] for row in rows], ['720p', 'Auto'])
        self.assertTrue(rows[0]['url'].startswith('https://cdn.example/720.m3u8|'))
        self.assertEqual(fetch.call_args.args[1]['Origin'], 'https://vidy.st')
        self.assertEqual(fetch.call_args.args[1]['Referer'], 'https://vidy.st/')
        self.assertTrue(all(parse_qs(row['url'].partition('|')[2])['Origin'] == ['https://vidy.st'] for row in rows))
        self.assertTrue(all(row['info'] == 'Streamwish' for row in rows))
