"""Offline PrimeWire discovery fixtures. These do not establish real-site playback."""
import unittest
from unittest.mock import patch
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))

from moviehub_native.primewire import PrimeWire, PlayerLinks, matching_title


class PrimeWireTests(unittest.TestCase):
    def test_visible_title_and_year_must_match_before_discovery(self):
        page = '<title>PrimeWire</title><h2>Now Playing</h2><div>The Odyssey (2026)</div>'
        self.assertTrue(matching_title(page, 'The Odyssey', '2026'))
        self.assertFalse(matching_title(page, 'The Odyssey', '2025'))
        self.assertFalse(matching_title(page, 'Another Film', '2026'))
        self.assertFalse(matching_title(
            '<script>const title = "The Odyssey (2026)"</script>', 'The Odyssey', '2026'))

    def test_only_explicit_player_links_and_one_unique_url_are_returned(self):
        markup = '''
        <title>The Odyssey (2026)</title>
        <button class="server" data-server="vidcore"
                data-url="https://vidcore.io/embed/123">Vidcore</button>
        <button class="server" data-server="vidcore"
                data-url="https://vidcore.io/embed/123">Vidcore duplicate</button>
        <button class="server" data-server="vixsrc"
                data-src="https://vixsrc.to/embed/123">VixSrc</button>
        <button class="server" data-server="not-ready" data-id="7">No link</button>
        <button class="server" data-server="internal"
                data-url="/ajax/player/7">Internal AJAX is not a media URL</button>
        <a href="https://ad.example/banner">Advertisement</a>
        '''
        provider = PrimeWire()
        key = provider.movie('tt1234', '123', 'The Odyssey', 'The Odyssey', [], '2026')
        with patch('moviehub_native.primewire.request', return_value=markup) as req, \
             patch('moviehub_native.primewire.client.safe', side_effect=lambda x: x):
            rows = provider.sources(key, [])
        self.assertEqual(req.call_args.args[0], 'https://primewire1.buzz/watch/the-odyssey')
        self.assertEqual(len(rows), 2)
        self.assertEqual({x['url'] for x in rows},
                         {'https://vidcore.io/embed/123', 'https://vixsrc.to/embed/123'})
        self.assertTrue(all(not x['direct'] for x in rows))

    def test_no_source_from_unlinked_server_names_or_wrong_year(self):
        page = '<h1>The Odyssey (2026)</h1><button class="server">Vidrock</button>'
        provider = PrimeWire()
        with patch('moviehub_native.primewire.request', return_value=page):
            key = provider.movie('tt1234', '123', 'The Odyssey', 'The Odyssey', [], '2026')
            self.assertEqual(provider.sources(key, []), [])
            key = provider.movie('tt1234', '123', 'The Odyssey', 'The Odyssey', [], '2025')
            self.assertEqual(provider.sources(key, []), [])

    def test_unsafe_schemes_and_same_origin_are_ignored(self):
        parser = PlayerLinks('https://primewire1.buzz/watch/the-odyssey')
        parser.feed('''<div class="server" data-url="javascript:alert(1)"></div>
                       <div class="server" data-src="http://insecure.example/vid"></div>
                       <div class="server" data-src="https://primewire1.buzz/ajax/3"></div>
                       <iframe src="https://vidfast.vc/embed/123"></iframe>''')
        self.assertEqual(parser.links, [('https://vidfast.vc/embed/123', 'vidfast.vc')])


if __name__ == '__main__':
    unittest.main()
