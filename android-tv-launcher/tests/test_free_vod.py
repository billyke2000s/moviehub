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

if __name__=='__main__':unittest.main()
