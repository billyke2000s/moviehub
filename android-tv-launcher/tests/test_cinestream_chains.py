"""Offline redirect-chain regressions; these do not establish live playback."""
import base64
import json
import unittest
from unittest.mock import patch

import requests
from moviehub_compat import client
from moviehub_native.cinestream_dom import tree
from moviehub_native.cinestream_downloads import DownloadCatalogue, matches_number
from moviehub_native.cinestream_links import Links, same_origin


class CineStreamChainTests(unittest.TestCase):
    def setUp(self):
        for mock in (patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network')),
                     patch.object(client, 'safe', side_effect=lambda url: url)):
            mock.start()
            self.addCleanup(mock.stop)
        client.scope(lambda: False, timeout=30)

    def run_chain(self, pages, start='https://links.example/start'):
        calls = []

        def fetch(url, headers, **kwargs):
            calls.append((url, headers, kwargs))
            entry = pages[(url, bool(kwargs.get('post')))]
            response = requests.Response()
            response.url = url
            response.status_code = 200
            if isinstance(entry, tuple):
                response.status_code, response.headers, entry = entry
            response._content = entry.encode()
            return response

        with patch.object(client, 'fetch', side_effect=fetch):
            rows = list(Links('Fixture').follow(start, info='A Film 1080p EAC3'))
        return rows, calls

    def test_redirect_and_new_gdflix_subdomain_keep_media_and_metadata(self):
        rows, calls = self.run_chain({
            ('https://links.example/start', False): (302, {'Location': 'https://new7.gdflix.io/file/id'}, ''),
            ('https://new7.gdflix.io/file/id', False): '<a class="btn" href="/cloud/id">FAST CLOUD</a>',
            ('https://new7.gdflix.io/cloud/id', False): '<a href="https://cdn.example/file.mkv">DIRECT DL</a>',
        })
        self.assertEqual(len(rows), 1)
        self.assertIn('file.mkv', rows[0]['url'])
        self.assertEqual(rows[0]['quality'], '1080p')
        self.assertIn('EAC3', rows[0]['info'])
        self.assertTrue(all(call[2]['redirect'] is False for call in calls))

    def test_current_lp_land_posts_issued_fields_only_to_same_origin(self):
        rows, calls = self.run_chain({
            ('https://links.example/start', False): '<form id="lp-land" action="/step"><input name="_lp_http" value="issued"></form>',
            ('https://links.example/step', True): '<meta http-equiv="refresh" content="0;url=https://cdn.example/file.mp4">',
        })
        self.assertEqual(len(rows), 1)
        self.assertEqual(calls[1][2]['post'], {'_lp_http': 'issued'})
        self.assertNotIn('issued', rows[0]['url'])
        with self.assertRaises(ValueError):
            same_origin('https://links.example/', 'https://other.example/post')

    def test_hind_links_use_current_site_signer_without_embedded_secret(self):
        page = """<a class="get-link-btn" href="https://hshare.lol/?id=A%20Film.mkv">Get Links</a>
        <script>var AJAX_URL='https://links.example/sign'; action='hindshare_sign';</script>"""
        rows, calls = self.run_chain({
            ('https://links.example/start', False): page,
            ('https://links.example/sign', True): json.dumps({'success': True, 'data': {'url': 'https://cdn.example/file.mkv'}}),
        })
        self.assertEqual(len(rows), 1)
        self.assertEqual(calls[1][2]['post'], {'action': 'hindshare_sign', 'd': base64.urlsafe_b64encode(b'A Film.mkv').decode().rstrip('=')})
        self.assertEqual(len(calls), 2)

    def test_challenge_and_redirect_loop_never_become_media(self):
        for page in ('<title>Just a moment</title><a href="https://cdn.example/ad.mp4">Download</a>',
                     '<meta http-equiv="refresh" content="0;url=/start">'):
            rows, calls = self.run_chain({('https://links.example/start', False): page})
            self.assertEqual(rows, [])
            self.assertLessEqual(len(calls), 2)

    def test_unrelated_ads_and_branding_are_not_download_entries(self):
        doc = tree('<h5>A Film 720p</h5><p><a href="https://brand.example">Movie Site</a>'
                   '<a href="https://links.example/file">Download 720p</a></p>')
        self.assertEqual(list(DownloadCatalogue().movie_entries(doc)),
                         [('https://links.example/file', 'A Film 720p')])

    def test_episode_matching_does_not_accept_prefix_or_decimal_special(self):
        self.assertTrue(matches_number('Episode 03', 'episode', '3'))
        self.assertFalse(matches_number('Episode 30', 'episode', '3'))
        self.assertFalse(matches_number('Episode 3.5', 'episode', '3'))

    def test_season_and_episode_are_both_required_without_batch_fallback(self):
        provider = DownloadCatalogue()
        query = {'season': '2', 'episode': '3'}
        doc = tree('<h3>Season 1</h3><h4>Episode 3</h4><a href="wrong.mp4">Download</a>'
                   '<h3>Season 2</h3><h4>Episode 30</h4><a href="wrong2.mp4">Download</a>'
                   '<h4>Episode 3</h4><a href="right.mp4">Download</a>')
        self.assertEqual(list(provider.episode_entries(doc, query)), [('right.mp4', 'Episode 3')])

    def test_wrong_imdb_or_year_does_not_match_title_only(self):
        provider = DownloadCatalogue()
        query = {'title': 'A Film', 'year': '2020', 'imdb': 'tt1234'}
        self.assertFalse(provider.verified(tree('<h1>A Film 2020</h1><a href="https://imdb.com/title/tt5678">IMDb</a>'), query))
        self.assertFalse(provider.verified(tree('<h1>A Film 2021</h1>'), query))
        self.assertTrue(provider.verified(tree('<h1>A Film 2020</h1>'), query))

    def test_multistep_current_landing_and_literal_fastdl_destination(self):
        rows, calls = self.run_chain({
            ('https://links.example/start', False): '<form id="lp-land" action="/first"><input name="_lp_http" value="one"></form>',
            ('https://links.example/first', True): '<form id="lp-s1-form" action="/second"><input name="_lp_http2" value="two"></form>',
            ('https://links.example/second', True): '<meta http-equiv="refresh" content="0;url=https://fastdl.beer/r/id">',
            ('https://fastdl.beer/r/id', False): '<script>var reurl = "https://cdn.example/film.mp4";</script>',
        })
        self.assertEqual(len(rows), 1)
        self.assertEqual(calls[2][2]['post'], {'_lp_http2': 'two'})
        self.assertNotIn('_lp_', rows[0]['url'])

    def test_vcloud_double_base64_literal_is_data_without_javascript_execution(self):
        encoded = base64.b64encode(base64.b64encode(b'https://cdn.example/file.mp4')).decode()
        rows, _ = self.run_chain({
            ('https://vcloud.example/file/id', False): "<script>var url=atob(atob('" + encoded + "'));</script>",
        }, start='https://vcloud.example/file/id')
        self.assertEqual(len(rows), 1)
        self.assertIn('cdn.example/file.mp4', rows[0]['url'])

    def test_resume_worker_posts_token_to_issuing_origin(self):
        rows, calls = self.run_chain({
            ('https://driveleech.example/file/id', False): "<script>formData.append('token', 'issued');fetch('/download?id=one');</script>",
            ('https://driveleech.example/download?id=one', True): json.dumps({'url': 'https://cdn.example/film.mkv'}),
        }, start='https://driveleech.example/file/id')
        self.assertEqual(len(rows), 1)
        self.assertEqual(calls[1][2]['post'], {'token': 'issued'})
        self.assertNotIn('issued', rows[0]['url'])

    def test_failed_mirror_does_not_drop_other_advertised_mirror(self):
        rows, _ = self.run_chain({
            ('https://links.example/start', False): '<a class="maxbutton" href="/bad">Download Server 1</a><a class="maxbutton" href="https://cdn.example/good.mp4">Download Server 2</a>',
            ('https://links.example/bad', False): (503, {}, 'Unavailable'),
        })
        self.assertEqual(len(rows), 1)
        self.assertIn('good.mp4', rows[0]['url'])

    def test_filename_in_query_is_not_a_direct_media_file(self):
        rows, calls = self.run_chain({
            ('https://links.example/start', False): '<a class="get-link-btn" href="https://hshare.lol/?id=Film.mkv">Get Links</a>',
            ('https://hshare.lol/?id=Film.mkv', False): '<p>No files</p>',
        })
        self.assertEqual(rows, [])
        self.assertEqual(len(calls), 2)

    def test_fibwatch_does_not_fall_back_to_first_episode(self):
        from moviehub_native.cinestream_indexes import Fibwatch
        provider = Fibwatch()
        search = tree('<div class="video-thumb"><a href="/show"><p class="hptag">A Film 2020</p></a></div>')
        detail = tree('<h1>A Film 2020</h1><input id="video-id" value="show">')
        with patch.object(provider, 'page', side_effect=[(search, provider.base_link, ''), (detail, provider.base_link, '')]), \
                patch('moviehub_native.cinestream_indexes.get', return_value={'episodes': [{'title': 'S01E01', 'url': '/wrong'}]}) as request:
            self.assertEqual(list(provider.extract({'title': 'A Film', 'year': '2020', 'imdb': 'tt1234', 'season': '2', 'episode': '3'})), [])
        self.assertEqual(request.call_count, 1)

    def test_m4u_tv_uses_exact_episode_and_keeps_csrf_out_of_playback_headers(self):
        from moviehub_native.cinestream_indexes import M4ufreeLat
        provider = M4ufreeLat()
        search = tree('<div class="item"><a href="/show" title="A Film 2020">A Film</a></div>')
        detail = tree('<meta name="csrf-token" content="issued"><button class="episode" idepisode="wrong">S02-E30</button><button class="episode" idepisode="right">S02-E03</button>')
        with patch.object(provider, 'page', side_effect=[(search, provider.base_link, ''), (detail, provider.base_link + '/show', '')]), \
                patch('moviehub_native.cinestream_indexes.request', side_effect=['<span data="server"></span>', '<iframe src="https://cdn.example/film.mp4"></iframe>']) as request:
            rows = list(provider.extract({'title': 'A Film', 'year': '2020', 'season': '2', 'episode': '3'}))
        self.assertEqual(request.call_args_list[0].kwargs['post']['idepisode'], 'right')
        self.assertEqual(len(rows), 1)
        self.assertNotIn('issued', rows[0]['url'])
        self.assertNotIn('_token', rows[0]['url'])

    def test_fibwatch_playback_profile_does_not_change_other_chain_headers(self):
        from urllib.parse import parse_qs
        headers = {'Referer': 'https://urlshortlink.top/', 'Origin': 'https://urlshortlink.top'}
        fib = list(Links('Fibwatch', headers).follow('https://cdn.example/file.mp4', 'https://fibwatch.art/'))[0]
        other = list(Links('Other').follow('https://cdn.example/file.mp4', 'https://other.example/'))[0]
        self.assertEqual(parse_qs(fib['url'].split('|')[1])['Origin'], ['https://urlshortlink.top'])
        self.assertNotIn('Origin', parse_qs(other['url'].split('|')[1]))

    def test_moviesdrive_downloads_outside_intro_block_are_retained(self):
        from moviehub_native.cinestream_downloads import MoviesDrive
        doc = tree('<article class="post-content"><main class="page-body">'
                   '<div class="entry-content"><h4>Inception 480p</h4><a href="/?s=480p">480p</a></div>'
                   '<div><h5>Inception 2010 720p HEVC</h5><p><a href="https://mdrive.lol/archive/id">Download 720p</a></p></div>'
                   '</main></article>')
        provider = MoviesDrive()
        self.assertEqual(list(provider.movie_entries(provider.content(doc))),
                         [('https://mdrive.lol/archive/id', 'Inception 2010 720p HEVC')])

    def test_current_final_landing_link_is_decoded_without_executing_script(self):
        rows, calls = self.run_chain({
            ('https://links.example/start', False): '<a id="lp-btn-go" href="#">Go to download</a><script>bg.setAttribute(\'href\',"https:\\/\\/links.example\\/?lp_go=issued");</script>',
            ('https://links.example/?lp_go=issued', False): (302, {'Location': 'https://cdn.example/file.mkv'}, ''),
        })
        self.assertEqual(len(rows), 1)
        self.assertEqual(len(calls), 2)

    def test_advertised_hubcloud_generator_exposes_extensionless_file_servers(self):
        url = 'https://gamerxyt.example/hubcloud.php?host=hubcloud&id=public'
        rows, calls = self.run_chain({
            (url, False): '<div class="card-header">Film 1080p HEVC.mkv</div><i id="size">2.5 GB</i><h2><a href="https://cdn.example/opaque-file?signature=issued">Download [FSL Server]</a></h2>',
        }, start=url)
        self.assertEqual(len(rows), 1)
        self.assertEqual(len(calls), 1)
        self.assertIn('HEVC', rows[0]['info'])
        self.assertIn('2.5 GB', rows[0]['info'])

    def test_compact_episode_labels_retain_both_season_and_episode(self):
        provider = DownloadCatalogue()
        doc = tree('<h4>S01E03 720p</h4><a href="wrong.mp4">Download</a>'
                   '<h4>S02E03 1080p</h4><a href="right.mp4">Download</a>'
                   '<h4>S02E30 1080p</h4><a href="wrong2.mp4">Download</a>')
        self.assertEqual(list(provider.episode_entries(doc, {'season': '2', 'episode': '3'})),
                         [('right.mp4', 'S02E03 1080p')])
