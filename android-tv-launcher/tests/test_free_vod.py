"""Offline tests of real bundled provider code and its MovieHub adapter."""
import importlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import requests
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'app/src/main/python'))
import moviehub_vod as vod
from moviehub_compat import client

class FreeVodTests(unittest.TestCase):
    def test_extended_transport_contract_drives_real_watchseries_provider(self):
        from moviehub_scrubs import scrape_sources
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        calls=[]
        def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=client.MAX_BODY):
            self.assertIsInstance(headers,dict)
            calls.append((url,headers))
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='text/html'
            response.cookies.set('fixture','session')
            if '?s=' in url:
                response._content=b'<div class="item"><a href="https://fixture.example/film" title="Fixture Film (2020)">Film</a></div>'
            elif url.endswith('/film'):
                response._content=b'<div data-vs="https://fixture.example/redirect"></div>'
            elif url.endswith('/redirect'):
                response.url='https://host.example/embed';response._content=b''
            else:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp'
            return response
        item={'url':'https://media.example/film.mp4','direct':True,'quality':'HD','source':'fixture'}
        with patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url),patch.object(scrape_sources,'process',return_value=[item]):
            result=json.loads(vod.query('scrubs:watchserieshd_stream',json.dumps(query),lambda:False))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(result['sources']),1)
        self.assertEqual(len(calls),4) # extended output must not make a second cookie request
        self.assertIn('User-Agent',calls[1][1])

    def test_scrape_page_keeps_upstream_default_referer_and_protocol_relative_url(self):
        with patch.object(client,'fetch') as fetch:
            client.scrapePage('//site.example/path')
            self.assertEqual(fetch.call_args.args[0],'https://site.example/path')
            self.assertEqual(fetch.call_args.args[1]['Referer'],'https://site.example')
            client.scrapePage('https://site.example/path',headers={'Referer':'https://original.example'})
            self.assertEqual(fetch.call_args.args[1]['Referer'],'https://original.example')

    def test_every_provider_lookup_contract_and_individual_outcome(self):
        import inspect
        from unittest.mock import Mock
        query={'imdb':'tt0063350','tmdb':10331,'title':'Night of the Living Dead',
               'year':'1968','season':-1,'episode':-1}
        for row in json.loads(vod.providers()):
            module=importlib.import_module(row['module'])
            provider=module.source()
            for series in (False,True):
                with self.subTest(provider=row['id'],series=series):
                    info=dict(query,season=1 if series else -1,episode=1 if series else -1)
                    calls={}
                    for method,args in [('movie',('i','t','title','title',[], '2020')),
                                        ('tvshow',('i','t','','title','title',[], '2020')),
                                        ('episode',('show','i','t','','ep','2020-01-01','1','1')),
                                        ('sources',('key',[])),('resolve',('url',))]:
                        if hasattr(provider,method):
                            inspect.signature(getattr(provider,method)).bind(*args)
                            calls[method]=Mock(return_value=[] if method=='sources' else 'key')
                    for name,call in calls.items():setattr(provider,name,call)
                    with patch.object(module,'source',return_value=provider):
                        result=json.loads(vod.query(row['id'],json.dumps(info),lambda:False))
                    if row['name'] in ('library','filepursuit'):
                        self.assertEqual(result['status'],'local_library' if row['name']=='library' else 'requires_api_key')
                        calls['sources'].assert_not_called()
                    elif not hasattr(provider,'tvshow' if series else 'movie'):
                        self.assertEqual(result['status'],'unsupported_type')
                        calls['sources'].assert_not_called()
                    else:
                        self.assertEqual(result['status'],'no_matches')
                        calls['sources'].assert_called_once()
                        self.assertNotIn('*',calls['sources'].call_args.args[1])

    def test_short_media_rejected_without_treating_unknown_duration_as_short(self):
        import struct
        from moviehub_compat.media import duration_seconds
        def mp4(seconds):
            body=bytes(12)+struct.pack('>II',1000,int(seconds*1000))+bytes(80)
            mvhd=struct.pack('>I4s',len(body)+8,b'mvhd')+body
            return struct.pack('>I4s',len(mvhd)+8,b'moov')+mvhd
        response=requests.Response();response.status_code=200
        response.headers['Content-Type']='video/mp4'
        with patch.object(client,'fetch',return_value=response),patch.object(client,'safe',side_effect=lambda url:url):
            for seconds in (7,30,119.9):
                response._content=mp4(seconds)
                self.assertAlmostEqual(duration_seconds(response.content,'video/mp4'),seconds)
                self.assertIsNone(vod.playable('https://media.example/error.mp4'))
            for seconds in (120,5400):
                response._content=mp4(seconds)
                result=vod.playable('https://media.example/movie.mp4')
                self.assertEqual(result['durationSeconds'],seconds)
                self.assertEqual(result['headers']['User-Agent'],client.UserAgent)
            response._content=b'unknown container metadata'
            self.assertIsNotNone(vod.playable('https://media.example/movie.mp4'))
            response.headers['Content-Type']='application/vnd.apple.mpegurl'
            response._content=b'#EXTM3U\n#EXTINF:7,\na.ts\n#EXT-X-ENDLIST\n'
            self.assertIsNone(vod.playable('https://media.example/error.m3u8'))
            response._content=b'#EXTM3U\n#EXTINF:7,\na.ts\n'
            self.assertIsNone(duration_seconds(response.content,'application/vnd.apple.mpegurl'))
            self.assertIsNotNone(vod.playable('https://media.example/event.m3u8'))
        self.assertIsNone(duration_seconds(b'bad bytes','video/mp4'))
        self.assertEqual(duration_seconds(b'<MPD mediaPresentationDuration="PT1M59S">','application/dash+xml'),119)

    def test_probe_stops_at_the_payload_limit(self):
        response=requests.Response();response.status_code=206;response.url='https://media.example/file.mp4'
        response.headers['Content-Type']='video/mp4'
        reads=[]
        def chunks(size):
            for n in range(3):
                reads.append(n)
                yield b'x'*32768
        response.iter_content=chunks
        response.close=lambda:None
        session=unittest.mock.Mock()
        session.request.return_value=response
        client.scope(lambda:False)
        with patch.object(client,'safe',side_effect=lambda url:url):
            result=client.fetch(response.url,max_bytes=65536,session=session)
        self.assertEqual(len(result.content),65536)
        self.assertEqual(reads,[0,1])

    def test_manifest_resolution_and_media_transfer_sample(self):
        response=requests.Response();response.status_code=200
        response.headers['Content-Type']='application/vnd.apple.mpegurl'
        response._content=b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nb.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160\nc.m3u8\n'
        with patch.object(client,'fetch',return_value=response),patch.object(client,'safe',side_effect=lambda url:url):
            result=vod.playable('https://media.example/master.m3u8',1080)
            self.assertEqual(result['verifiedQuality'],'1080p')
            self.assertEqual(result['probeMbps'],0)
            response.headers['Content-Type']='video/mp4';response._content=b'x'*65536
            with patch.object(vod.time,'monotonic',side_effect=[1,1.1]):
                result=vod.playable('https://media.example/file.mp4')
            self.assertAlmostEqual(result['probeMbps'],5.24288,places=4)
            self.assertEqual(result['responseMs'],100)

    def test_all_bundled_provider_constructors_import(self):
        rows=json.loads(vod.providers())
        self.assertEqual(len(rows),68)
        self.assertEqual(len({r['id'] for r in rows}),68)
        for row in rows:
            with self.subTest(provider=row['id']):
                self.assertTrue(hasattr(importlib.import_module(row['module']).source(),'sources'))

    def test_mp4hydra_real_provider_emits_multiple_servers(self):
        query={'imdb':'tt1234567','tmdb':12,'title':'Fixture Film','year':'2020','season':-1,'episode':-1}
        def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=client.MAX_BODY):
            response=requests.Response();response.status_code=200;response.url=url
            response.headers['Content-Type']='application/json'
            if '/s?q=' in url:
                content=[{'title':'Fixture Film (2020)','type':'movie','link':'/film'}]
            elif url.endswith('/film'):
                response._content=b"<script>var opts = {'v':'id','s':'x','t':'x','i':'/playlist'};</script>"
                return response
            elif url.endswith('/playlist'):
                content={'playlist':[{'title':'Fixture Film (2020)','src':'video.mp4'}],
                         'servers':{'one':'https://one.example','two':'https://two.example'}}
            else:
                response.headers['Content-Type']='video/mp4';response._content=b'ftyp';return response
            response._content=json.dumps(content).encode();return response
        class Results:
            def __init__(self):self.items=[]
            def ready(self,text):self.items.append(json.loads(text))
        results=Results()
        with patch.object(client,'fetch',side_effect=fetch),patch.object(client,'safe',side_effect=lambda url:url):
            result=json.loads(vod.query('free99:my4hydra_top',json.dumps(query),lambda:False,results))
        self.assertEqual(result['status'],'complete')
        self.assertEqual(len(results.items),2)
        self.assertEqual({x['url'] for x in results.items},{'https://one.example/video.mp4','https://two.example/video.mp4'})

    def test_html_error_cannot_win_and_headers_survive(self):
        response=requests.Response();response.status_code=200;response.headers['Content-Type']='text/html';response._content=b'<html>Error</html>'
        with patch.object(client,'safe',side_effect=lambda url:url),patch.object(client,'fetch',return_value=response):
            self.assertIsNone(vod.playable('https://video.example/file.mp4'))
            response.headers['Content-Type']='video/mp4'
            item=vod.playable('https://video.example/file.mp4|Referer=https%3A%2F%2Fsite.example%2F&Cookie=session%3Dfixture')
            self.assertEqual(item['headers']['Cookie'],'session=fixture')
            self.assertEqual(item['headers']['Referer'],'https://site.example/')

    def test_cancelled_search_emits_nothing(self):
        result=json.loads(vod.query('free99:my4hydra_top',json.dumps({'http':False}),lambda:True))
        self.assertEqual(result,{'status':'timeout','sources':[]})

    def test_private_and_plain_http_targets_rejected(self):
        client.scope(lambda:False)
        for url in ['file:///tmp/video.mp4','http://example.com/video.mp4','https://127.0.0.1/video.mp4','https://user:pass@example.com/video.mp4']:
            with self.subTest(url=url),self.assertRaises(ValueError):client.safe(url)

    def test_decoder_expressions_never_execute_website_code(self):
        from moviehub_compat.expression import evaluate
        self.assertEqual(evaluate("(3 + 4) * 2"),14)
        self.assertEqual(evaluate("'one' + 'two'"),'onetwo')
        self.assertEqual(evaluate("seed + 2", {'seed':3}),5)
        for expression in ["__import__('os').getcwd()", "(1).__class__", "[x for x in []]", "2**1000", "'a'*99999999"]:
            with self.subTest(expression=expression),self.assertRaises(ValueError):evaluate(expression)

if __name__=='__main__':unittest.main()
