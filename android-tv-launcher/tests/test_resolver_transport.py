"""Host redirect helpers must use the guarded transport and retain redirect metadata."""
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs
import urllib.request
import requests
import moviehub_vod as vod
from moviehub_compat import client
from resolveurl.lib import helpers
from six.moves import urllib_request

class ResolverTransportTests(unittest.TestCase):
    def response(self, url, status=200, location=None):
        response = requests.Response()
        response.status_code = status
        response.reason = "Found" if status == 302 else "OK"
        response.url = url
        response._content = b""
        if location:
            response.headers["Location"] = location
        return response

    def test_legacy_download_form_keeps_issued_redirect_without_fetching_media(self):
        page = "https://download.example/"
        media = "https://cdn.example/issued-file.mp4"
        calls = []
        def fetch(url, headers, data, timeout, **kwargs):
            calls.append((url, data, kwargs))
            self.assertEqual(url, page)
            self.assertEqual(parse_qs(data.decode()), {"op": ["download2"], "id": ["opaque"]})
            self.assertEqual(kwargs["method"], "POST")
            # Following this redirect loses its Location and starts fetching the file.
            return (self.response(page, 302, media) if not kwargs.get("redirect", True)
                    else self.response(media))
        with patch.object(client, "fetch", side_effect=fetch), \
             patch.object(urllib.request.OpenerDirector, "open",
                          side_effect=AssertionError("Host helper bypassed bounded transport")):
            result = helpers.get_redirect_url(page, form_data={"op": "download2", "id": "opaque"})
        self.assertEqual(result, media)
        self.assertEqual(len(calls), 1)
        self.assertFalse(calls[0][2]["redirect"])

    def test_default_urllib_openers_still_follow_through_the_guard(self):
        for transport in (urllib.request, urllib_request):
            with self.subTest(transport=transport.__name__), \
                 patch.object(client, "fetch",
                              return_value=self.response("https://cdn.example/final")) as fetch, \
                 patch.object(urllib.request.OpenerDirector, "open",
                              side_effect=AssertionError("Unguarded urllib request")):
                result = transport.build_opener().open("https://download.example/")
                self.assertEqual(result.geturl(), "https://cdn.example/final")
                self.assertTrue(fetch.call_args.kwargs["redirect"])
                self.assertEqual(fetch.call_args.kwargs["method"], "GET")

    def test_legacy_net_no_redirect_handler_is_also_preserved(self):
        from resolveurl.lib.net import NoRedirection
        with patch.object(client, "fetch", return_value=self.response(
                "https://download.example/", 302, "https://cdn.example/file")) as fetch, \
             patch.object(urllib.request.OpenerDirector, "open",
                          side_effect=AssertionError("Unguarded urllib request")):
            result = urllib_request.build_opener(NoRedirection()).open("https://download.example/")
        self.assertEqual(result.headers["Location"], "https://cdn.example/file")
        self.assertFalse(fetch.call_args.kwargs["redirect"])
