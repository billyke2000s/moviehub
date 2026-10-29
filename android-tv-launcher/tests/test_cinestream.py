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
        provider = api.Videasy()
        seen = []
        def request(url, **kwargs):
            seen.append((url, kwargs))
            if '/seed?' in url:
                return {'seed': 'issued'}
            if '/myflixerzupcloud/' in url:
                raise ValueError('refused')
            if '/sources-with-title?' in url:
                args = parse_qs(urlparse(url).query)
                self.assertEqual(args['seasonId'], ['2'])
                self.assertEqual(args['episodeId'], ['3'])
                self.assertEqual(args['title'], ['A%20Film'])
                return 'encoded'
            return {'result': {'sources': [{'url': 'https://cdn.example/master.m3u8'}]}}
        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(len(rows), 10)
        self.assertEqual(len({r['source'] for r in rows}), 10)
        self.assertTrue(all(r['quality'] == 'Auto' for r in rows))
        self.assertTrue(all('Origin=https%3A%2F%2Fplayer.videasy.to' in r['url'] for r in rows))
        decoder_posts = [kw for url, kw in seen if '/dec-videasy' in url]
        self.assertTrue(all(json.loads(kw['post']) == dict(text='encoded', id=123, seed='issued') for kw in decoder_posts))

    def test_token_servers_use_exact_track_route_and_do_not_forward_csrf_to_media(self):
        for cls in (api.Vidup, api.Vidcore, api.Vidfast):
            with self.subTest(cls=cls.__name__):
                provider = cls()
                calls = []
                def request(url, **kw):
                    calls.append((url, kw))
                    if '/tv/' in url:
                        return r'{\"token\":\"literal\"}'
                    if urlparse(url).path.startswith('/api/enc-'):
                        return {'status': 200, 'result': dict(token='csrf', servers=provider.base_link + '/servers', stream=provider.base_link + '/stream')}
                    if url.endswith('/servers') or '/stream/' in url:
                        self.assertEqual(kw['post'], '')
                        self.assertEqual(kw['headers']['X-CSRF-Token'], 'csrf')
                        return 'encrypted'
                    if urlparse(url).path.startswith('/api/dec-'):
                        posts = sum('/dec-' in u for u, k in calls)
                        return {'status': 200, 'result': [dict(name='one', data='hash')] if posts == 1 else dict(url='https://cdn.example/film.m3u8')}
                    raise AssertionError(url)
                with patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXTINF:600,\na.ts')), patch.object(client, 'request', side_effect=request):
                    rows = provider.sources(self.key(provider, True), [])
                self.assertEqual(len(rows), 1)
                self.assertTrue(calls[0][0].rstrip('/').endswith('/tv/123/2/3'))
                self.assertEqual('X-CSRF-Token=csrf' in rows[0]['url'], cls is api.Vidfast)
                self.assertEqual('X-Requested-With' in rows[0]['url'], False)

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
        with patch.object(client, 'request', side_effect=[{'result': {'token': 'issued'}}, 'encrypted', {'result': {'sources': [{'server': 'one', 'url': 'https://cdn.example/master.m3u8'}]}}]), patch.object(client, 'fetch', return_value=self.playlist('#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\n1080.m3u8')):
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual([r['quality'] for r in rows], ['1080p', 'Auto'])
        self.assertTrue(all(r['source'] == 'Hexa one' for r in rows))
        self.assertTrue(all('Referer=https%3A%2F%2Fhexa.su%2F' in r['url'] for r in rows))

    def test_a_refused_hexa_playlist_does_not_discard_other_servers(self):
        provider = api.Hexa()
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
        self.assertEqual(len(groups), 38)
        auditable = json.loads(vod.search_providers(include_retired=True))
        self.assertTrue({r['id'] for r in new} <= {a for g in auditable for a in g['adapters']})
        self.assertTrue(all(r.get('pending') for r in new))
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
        with patch.object(client, 'request', side_effect=[page, {'embed_url': '<iframe src="https://cdn.example/master.m3u8"></iframe>'}]) as request:
            rows = provider.sources(self.key(provider, True), [])
        self.assertEqual(request.call_count, 2)
        self.assertTrue(request.call_args_list[0].args[0].endswith('/episodes/a-film-2x3/'))
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

    def test_source_metadata_is_advertised_not_selected_or_measured_audio(self):
        prefix = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.640028,mp4a.40.2"\na.m3u8\n#EXT-X-STREAM-INF:CODECS="hvc1.1,ec-3",BANDWIDTH=2\nb.m3u8'
        self.assertEqual(describe(prefix, {}), 'Manifest codecs: AVC (H.264) / AAC / HEVC (H.265) / E-AC-3')
        self.assertEqual(describe(b'#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\na.m3u8', {}), '')
        self.assertEqual(describe(b'<html>CODECS="avc1"</html>', {}), '')
        self.assertEqual(describe(b'file', {'Content-Length': '65536'}), '')
        self.assertEqual(describe(b'file', {'Content-Range': 'bytes 0-65535/1073741824'}), 'File: 1.00 GiB')
