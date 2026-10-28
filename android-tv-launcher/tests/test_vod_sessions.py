"""Session recovery and identity regressions: fixtures never contact a provider."""
import json
import unittest
from unittest.mock import patch
import moviehub_vod as vod
from moviehub_identity import matches

class SessionTests(unittest.TestCase):
    def test_rejects_contradictory_episode_and_real_filename_evidence(self):
        wanted = dict(imdb='tt123', tmdb=4, season=2, episode=3)
        for item in [dict(imdb='tt456'), dict(tmdb=5), dict(season=1), dict(episode=4),
                     dict(filename='Show.S02E04.mkv')]:
            with self.subTest(item=item): self.assertFalse(matches(item, wanted))
        self.assertTrue(matches(dict(filename='Show.S02E03.mkv'), wanted))
        self.assertTrue(matches({}, wanted)) # explicitly provider-lookup evidence, not invented filename proof

    def test_refresh_rediscovers_same_host_with_new_url_and_headers(self):
        def query(adapter, raw, cancelled, emit, deadline):
            self.assertEqual(adapter, 'native:vixsrc')
            self.assertEqual(json.loads(raw)['episode'], 3)
            emit.ready(json.dumps(dict(sourceProvider='other', url='https://other.example/movie')))
            emit.ready(json.dumps(dict(sourceProvider='wanted', url='https://cdn.example/new', headers={'Cookie':'fresh'})))
            return json.dumps(dict(status='complete'))
        with patch.object(vod, 'query', side_effect=query):
            result = json.loads(vod.refresh_source(json.dumps(dict(adapter='native:vixsrc',host='wanted')),
                                                 json.dumps(dict(episode=3)), lambda:False))
        self.assertEqual(result['url'], 'https://cdn.example/new')
        self.assertEqual(result['headers']['Cookie'], 'fresh')

    def test_refresh_does_not_silently_change_host(self):
        with patch.object(vod,'query',return_value=json.dumps(dict(status='no_matches'))):
            with self.assertRaises(ValueError):
                vod.refresh_source(json.dumps(dict(adapter='native:vixsrc',host='wanted')), '{}', lambda:False)

    def test_expiry_uses_explicit_epochs_only(self):
        self.assertEqual(vod.expiry_millis('https://cdn.example/a?expires=1900000000'),1900000000000)
        self.assertEqual(vod.expiry_millis('https://cdn.example/a?exp=1900000000000'),1900000000000)
        for query in ('token=1900000000','expires=3600','exp=opaque'):
            self.assertEqual(vod.expiry_millis('https://cdn.example/a?'+query),0)
