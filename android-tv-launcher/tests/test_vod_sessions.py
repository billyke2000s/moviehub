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

    def test_refresh_preserves_quality_and_prefers_the_original_locator(self):
        def query(adapter, raw, cancelled, emit, deadline):
            for quality, locator, url in [('SD','original','wrong-quality'), ('HD','other','other-route'), ('HD','original','correct-route')]:
                emit.ready(json.dumps(dict(sourceProvider='wanted',claimedQuality=quality,
                    refresh=dict(locator=locator),url=url)))
            return '{}'
        recipe=dict(adapter='native:vixsrc',host='wanted',quality='HD',locator='original')
        with patch.object(vod,'query',side_effect=query):
            result=json.loads(vod.refresh_source(json.dumps(recipe), '{}', lambda:False))
        self.assertEqual(result['url'],'correct-route')

    def test_scene_episode_mapping_is_respected_before_filename_validation(self):
        import importlib
        module=importlib.import_module('moviehub_free99.providers.levidia_ch')
        class Provider:
            base_link='https://fixture.example'
            def tvshow(self,*args): return 'show'
            def episode(self,*args): return 'episode'
            def sources(self,*args): return [dict(url='https://cdn.example/video.mp4',source='direct',
                direct=True,filename='Show.S01E03.mkv')]
            def resolve(self,url): return url
        wanted=dict(title='The Haunting',year='2018',imdb='tt123',tmdb=1,season=2,episode=3)
        with patch.object(module,'source',return_value=Provider()), patch.object(vod,'playable',
            return_value=dict(url='https://cdn.example/video.mp4',headers={},verifiedQuality=None)):
            result=json.loads(vod.query('free99:levidia_ch',json.dumps(wanted),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(result['sources'][0]['filename'],'Show.S01E03.mkv')

    def test_expiry_uses_explicit_epochs_only(self):
        self.assertEqual(vod.expiry_millis('https://cdn.example/a?expires=1900000000'),1900000000000)
        self.assertEqual(vod.expiry_millis('https://cdn.example/a?exp=1900000000000'),1900000000000)
        for query in ('token=1900000000','expires=3600','exp=opaque'):
            self.assertEqual(vod.expiry_millis('https://cdn.example/a?'+query),0)
