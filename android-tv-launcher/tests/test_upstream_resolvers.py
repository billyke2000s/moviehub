"""Real resolver-flow regressions; these fixtures do not establish live playback."""
import json
import importlib.util
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch
import requests
from moviehub_compat import client
from moviehub_native import moviebox_mobile as mobile
from moviehub_native.cinestream_crypto import Vidzee


class UpstreamResolverTests(unittest.TestCase):
    def setUp(self):
        self.transport = patch.object(requests.Session, 'request', side_effect=AssertionError('Unexpected network'))
        self.transport.start()
        self.addCleanup(self.transport.stop)
        safe = patch.object(client, 'safe', side_effect=lambda url: url)
        safe.start()
        self.addCleanup(safe.stop)
        client.scope(lambda: False, timeout=30)

    def test_moviebox_signature_covers_exact_body_and_canonical_query(self):
        body = '{"keyword":"A Film","page":1}'
        target = 'https://apig.inmoviebox.com/path?z=2&a=1'
        canonical = mobile.canonical('post', target, body, 123, 'application/json; charset=utf-8')
        self.assertTrue(canonical.startswith('POST\napplication/json\napplication/json; charset=utf-8\n29\n123\n'))
        self.assertTrue(canonical.endswith('/path?a=1&z=2'))
        first = mobile.headers('POST', target, body, device='fixture', timestamp=123)
        self.assertNotEqual(first['x-tr-signature'], mobile.headers('POST', target, body + ' ', timestamp=123)['x-tr-signature'])
        self.assertEqual(first['x-tr-signature'], mobile.headers('POST', 'https://api3.aoneroom.com/path?a=1&z=2', body, timestamp=123)['x-tr-signature'])
        self.assertNotIn('Authorization', first)

    def test_moviebox_mobile_selects_exact_episode_and_keeps_token_off_media(self):
        provider = mobile.MobileMovieBox()
        q = dict(title='A Film', year='2020', tmdb='42', imdb='tt1234', season='2', episode='3')
        calls = []
        def fetch(url, headers, **kw):
            calls.append((url, headers, kw))
            response = requests.Response()
            response.status_code = 200
            if '/ranking-list?' in url:
                response.headers['x-user'] = '{"token":"ephemeral"}'
                result = {}
            elif '/search/v2' in url:
                self.assertEqual(headers['Authorization'], 'Bearer ephemeral')
                self.assertEqual(json.loads(kw['post'])['keyword'], q['title'])
                result = {'data': {'items': [dict(subjectId='wrong', title='Another Film', year='2020'),
                                           dict(subjectId='right', title='A Film', year='2020')]}}
            else:
                args = parse_qs(urlparse(url).query)
                self.assertEqual(args, dict(subjectId=['right'], se=['2'], ep=['3']))
                result = {'data': {'streams': [dict(url='https://cdn.example/a.mp4', resolution=1080),
                          dict(url='https://cdn.example/locked.mp4', vipLocked=True)]}}
            response._content = json.dumps(result).encode()
            return response
        with patch.object(mobile, 'HOSTS', (mobile.HOSTS[0],)), patch.object(client, 'fetch', side_effect=fetch):
            rows = provider.sources(json.dumps(q), [])
        self.assertEqual(len(rows), 1)
        self.assertEqual(len(calls), 3)
        self.assertNotIn('ephemeral', rows[0]['url'])
        self.assertNotIn('Authorization', rows[0]['url'])
        self.assertNotIn('/wefeed-h5', ''.join(call[0] for call in calls))

    def test_moviebox_rejects_explicit_id_year_mismatches_and_ambiguous_titles(self):
        q = dict(title='A Film', year='2020', tmdb='42', imdb='tt1234')
        for row in [dict(title='Other'), dict(title='A Film', year='1990'),
                    dict(title='A Film', imdbId='tt9999'), dict(title='A Film', tmdbId='123')]:
            self.assertFalse(mobile.matches(row, q))
        self.assertTrue(mobile.matches(dict(title='A Film [English]', releaseDate='2020-01-01'), q))

    def test_vidzee_current_api_uses_named_servers_and_exact_episode(self):
        provider = Vidzee()
        q = dict(title='A Film', year='2020', tmdb='42', imdb='tt1234', season='2', episode='3')
        calls = []
        def request(url, **kw):
            calls.append(url)
            self.assertEqual(kw['headers']['Origin'], 'https://player.vidzee.wtf')
            if '/languages/' in url:
                return {'languages': ['French']}
            self.assertIn('/streams/tv/42/2/3?', url)
            args = parse_qs(urlparse(url).query)
            self.assertEqual(args['e'], ['0'])
            if args['s'] == ['doppler']:
                raise ValueError('One backend failed')
            return {'url': 'https://cdn.example/' + args['s'][0].replace(':', '-') + '.m3u8',
                    'language': 'English', 'headers': {'referer': 'https://required.example/'}}
        with patch.object(client, 'request', side_effect=request):
            rows = provider.sources(json.dumps(q), [])
        self.assertEqual(len(rows), 6)
        self.assertTrue(all('/api/server' not in url for url in calls))
        self.assertTrue(any('v4%3AFrench' in url for url in calls))
        self.assertTrue(all(parse_qs(row['url'].partition('|')[2])['referer'] ==
                            ['https://required.example/'] for row in rows))

    def test_new_host_mirrors_and_peestream_are_registered(self):
        import resolveurl
        for url in ('https://mxdrop.top/e/abc', 'https://mixdrop.cv/e/abc',
                    'https://frstream.life/e/abc', 'https://bufsao.com/e/abc',
                    'https://peeplayer.com/e/abc'):
            with self.subTest(url=url):
                self.assertTrue(resolveurl.HostedMediaFile(url=url, include_disabled=True))

    def audit_module(self):
        spec = importlib.util.spec_from_file_location('audit_all_vod',
                   Path(__file__).with_name('audit-all-vod.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_every_adapter_is_in_exactly_one_audit_shard_including_pending(self):
        audit = self.audit_module()
        shards = [audit.inventory(index, 8) for index in range(8)]
        rows = [row for shard in shards for row in shard]
        self.assertEqual(len(rows), 111)
        self.assertEqual(len({row['id'] for row in rows}), 111)
        self.assertEqual({row['id'] for row in rows}, {row['id'] for row in audit.vod._RECORDS})
        self.assertTrue(any(row.get('pending') for row in rows))

    def test_audit_calls_each_adapter_directly_without_website_fallback(self):
        audit = self.audit_module()
        row = next(row for row in audit.vod._RECORDS if row['id'] == 'scrubs:levidia_ch')
        with patch.object(audit.vod, 'query', return_value='{"status":"no_matches","sources":[]}') as query:
            result = audit.probe(row, 'movies', 10)
        self.assertEqual(query.call_args.args[0], 'scrubs:levidia_ch')
        self.assertEqual(result['adapter'], 'scrubs:levidia_ch')
        self.assertTrue(result['attempted'])
        self.assertEqual(result['sources'], 0)

    def test_comparison_covers_the_entire_inventory_without_claiming_playback(self):
        import moviehub_vod as vod
        path = Path(__file__).resolve().parents[2] / 'docs/UPSTREAM_RESOLVER_COMPARISON_2026_11_17.json'
        report = json.loads(path.read_text())
        self.assertEqual({row['adapter'] for row in report['adapters']}, {row['id'] for row in vod._RECORDS})
        self.assertEqual(len(report['adapters']), 111)
        self.assertEqual(sum(row['adapter'].startswith('scrubs:') for row in report['adapters']), 59)


if __name__ == '__main__':
    unittest.main()
