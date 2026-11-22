"""Tests for the MovieHub gate. Run: python3 server/gate/test_gate.py"""
import http.client
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlencode

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate as g  # noqa: E402

KEY = 'k' * 32


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class GateLogic(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.clock = Clock()
        self.gate = g.Gate(self.dir, KEY, digits=4, minutes=5, clock=self.clock)

    def test_key_required(self):
        with self.assertRaises(ValueError):
            g.Gate(self.dir, '')

    def test_code_works_once(self):
        code, _ = self.gate.new_code()
        self.assertEqual(len(code), 4)
        self.assertTrue(self.gate.unlock(code, 'a'))
        self.assertIsNone(self.gate.unlock(code, 'b'))  # the mate trying it straight after

    def test_code_expires(self):
        code, _ = self.gate.new_code()
        self.clock.now += 5 * 60 + 1
        self.assertIsNone(self.gate.unlock(code, 'a'))

    def test_no_code_means_nothing_to_find(self):
        for guess in ('0000', '1234', '6969'):
            self.assertIsNone(self.gate.unlock(guess, 'x%s' % guess))

    def test_wrong_guess_limit(self):
        code, _ = self.gate.new_code()
        for i in range(g.WRONG_LIMIT):
            wrong = '%04d' % i if '%04d' % i != code else '9999x'
            self.assertIsNone(self.gate.unlock(wrong, 'attacker'))
        self.assertEqual(self.gate.unlock(code, 'attacker'), 'blocked')
        self.assertTrue(self.gate.unlock(code, 'family'))  # other addresses unaffected
        self.clock.now += g.WRONG_WINDOW + 1
        self.assertFalse(self.gate.blocked('attacker'))

    def test_link_is_one_address_short_lived_and_limited(self):
        code, _ = self.gate.new_code()
        token = self.gate.unlock(code, 'a')
        self.assertFalse(self.gate.take_link(token, 'someone-else'))
        self.assertTrue(self.gate.take_link(token, 'a', count=False))  # HEAD does not use it up
        for _ in range(g.LINK_USES):
            self.assertTrue(self.gate.take_link(token, 'a'))
        self.assertFalse(self.gate.take_link(token, 'a'))
        code, _ = self.gate.new_code()
        token = self.gate.unlock(code, 'a')
        self.clock.now += 5 * 60 + 1
        self.assertFalse(self.gate.take_link(token, 'a'))

    def test_active_codes_are_capped(self):
        codes = [self.gate.new_code()[0] for _ in range(g.MAX_ACTIVE_CODES + 2)]
        self.assertLessEqual(len(self.gate.codes), g.MAX_ACTIVE_CODES)
        self.assertTrue(self.gate.unlock(codes[-1], 'a'))

    def test_feed_paths(self):
        (Path(self.dir) / 'scrapers').mkdir()
        (Path(self.dir) / 'scrapers' / 'scrapers.zip').write_bytes(b'zip')
        (Path(self.dir) / 'secret.txt').write_text('no')
        self.assertIsNotNone(self.gate.feed_file('scrapers', 'scrapers.zip'))
        self.assertIsNone(self.gate.feed_file('scrapers', '../secret.txt'))
        self.assertIsNone(self.gate.feed_file('..', 'secret.txt'))
        self.assertIsNone(self.gate.feed_file('other', 'scrapers.zip'))
        self.assertIsNone(self.gate.feed_file('scrapers', '.hidden'))
        self.assertTrue(self.gate.key_ok('Bearer ' + KEY))
        self.assertFalse(self.gate.key_ok('Bearer wrong'))
        self.assertFalse(self.gate.key_ok(None))


class GateHttp(unittest.TestCase):
    """The real HTTP servers on local ports, as the Downloader app and the boxes would use them."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        (self.dir / 'app').mkdir()
        (self.dir / 'app' / 'home.apk').write_bytes(b'APK' * 1000)
        (self.dir / 'app' / 'update.json').write_text('{"versionCode": 1}')
        self.gate = g.Gate(self.dir, KEY)
        self.servers = g.serve(self.gate, ('127.0.0.1', 0), ('127.0.0.1', 0))
        for s in self.servers:
            self.addCleanup(s.server_close)
            self.addCleanup(s.shutdown)
        self.public = self.servers[0].server_address[1]
        self.admin = self.servers[1].server_address[1]

    def request(self, port, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        conn.close()
        return response, data

    def unlock(self, code):
        return self.request(self.public, 'POST', '/unlock', urlencode({'code': code}),
                            {'Content-Type': 'application/x-www-form-urlencoded'})

    def test_whole_install_flow(self):
        page, body = self.request(self.public, 'GET', '/')
        self.assertEqual(page.status, 200)
        self.assertIn(b'Enter login', body)
        made, body = self.request(self.admin, 'POST', '/new')
        code = body.split(b'class="code">')[1].split(b'<')[0].decode()
        response, _ = self.unlock(code)
        self.assertEqual(response.status, 303)
        link = response.getheader('Location')
        self.assertTrue(link.startswith('/get/') and link.endswith('/MovieHub.apk'))
        apk, data = self.request(self.public, 'GET', link)
        self.assertEqual(apk.status, 200)
        self.assertEqual(apk.getheader('Content-Type'), g.APK_TYPE)
        self.assertEqual(data, b'APK' * 1000)
        again, _ = self.unlock(code)
        self.assertEqual(again.status, 403)  # used once, gone

    def test_wrong_code_and_made_up_links(self):
        response, body = self.unlock('1234')
        self.assertEqual(response.status, 403)
        self.assertIn(b'not valid', body)
        self.assertEqual(self.request(self.public, 'GET', '/get/madeup/MovieHub.apk')[0].status, 404)

    def test_feed_needs_key_and_hides_everything_else(self):
        self.assertEqual(self.request(self.public, 'GET', '/feed/app/update.json')[0].status, 404)
        ok, data = self.request(self.public, 'GET', '/feed/app/update.json',
                                headers={'Authorization': 'Bearer ' + KEY})
        self.assertEqual(ok.status, 200)
        self.assertEqual(data, b'{"versionCode": 1}')
        for path in ('/feed/', '/feed/app/', '/app/home.apk', '/feed/app/..%2fhome.apk', '/admin'):
            self.assertEqual(self.request(self.public, 'GET', path,
                                         headers={'Authorization': 'Bearer ' + KEY})[0].status, 404, path)

    def test_admin_page_not_on_public_port(self):
        self.assertEqual(self.request(self.public, 'POST', '/new')[0].status, 404)
        self.assertEqual(self.request(self.admin, 'GET', '/')[0].status, 200)


if __name__ == '__main__':
    unittest.main(verbosity=2)
