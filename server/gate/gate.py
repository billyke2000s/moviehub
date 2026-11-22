#!/usr/bin/env python3
"""MovieHub gate: one-time install codes and the keyed update feed. Python standard library only.

Two web servers in one process:

PUBLIC (put this behind Cloudflare; default port 8080)
  GET  /                 a plain white "Enter login" page (works in the Downloader app's browser)
  POST /unlock           code -> one-time download link (5 minutes, this connection only)
  GET  /get/<token>/MovieHub.apk   the APK, for that link only
  GET  /feed/<area>/<file>         update files for boxes; needs "Authorization: Bearer <FEED_KEY>"
  anything else          404, no file listings

ADMIN (home network only, never expose it; default port 8081)
  GET  /                 a "New code" button; add it to your phone's home screen
  POST /new              makes a code, valid once, for CODE_MINUTES

Settings (environment variables):
  FEED_DIR       folder holding app/, experimental/, providers/, scrapers/ ... (default /srv/moviehub/feed)
  FEED_KEY       the key built into the app (MOVIEHUB_FEED_KEY); required
  PUBLIC_PORT    default 8080       ADMIN_PORT  default 8081
  PUBLIC_BIND    default ::         ADMIN_BIND  default 0.0.0.0 (firewall it to your LAN)
  CODE_DIGITS    default 4          CODE_MINUTES default 5
  TRUST_CLOUDFLARE  1 when requests arrive through Cloudflare (uses CF-Connecting-IP for limits)
"""
import hmac
import html
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs

FEED_AREAS = frozenset({'app', 'experimental', 'providers', 'scrapers', 'scrapers-experimental'})
FILE_NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,80}')
APK_TYPE = 'application/vnd.android.package-archive'
MAX_ACTIVE_CODES = 5
WRONG_LIMIT = 5            # wrong codes allowed per address ...
WRONG_WINDOW = 10 * 60     # ... in this many seconds
LINK_USES = 3              # Downloader may retry; the link still dies after 5 minutes


class Gate:
    """All state lives in memory; restarting the service clears every code and link."""

    def __init__(self, feed_dir, feed_key, digits=4, minutes=5, clock=time.monotonic):
        if not feed_key or len(feed_key) < 16:
            raise ValueError('FEED_KEY must be set (at least 16 characters)')
        self.feed_dir = Path(feed_dir)
        self.feed_key = feed_key
        self.digits = digits
        self.lifetime = minutes * 60
        self.clock = clock
        self.lock = threading.Lock()
        self.codes = {}     # code -> expiry
        self.links = {}     # token -> [expiry, address, uses left]
        self.wrong = {}     # address -> [times]

    def _sweep(self, now):
        self.codes = {c: e for c, e in self.codes.items() if e > now}
        self.links = {t: v for t, v in self.links.items() if v[0] > now and v[2] > 0}
        self.wrong = {a: [t for t in ts if t > now - WRONG_WINDOW] for a, ts in self.wrong.items()}
        self.wrong = {a: ts for a, ts in self.wrong.items() if ts}

    def new_code(self):
        with self.lock:
            now = self.clock()
            self._sweep(now)
            if len(self.codes) >= MAX_ACTIVE_CODES:
                oldest = min(self.codes, key=self.codes.get)
                del self.codes[oldest]
            while True:
                code = ''.join(secrets.choice('0123456789') for _ in range(self.digits))
                if code not in self.codes:
                    break
            self.codes[code] = now + self.lifetime
            return code, self.lifetime

    def blocked(self, address):
        with self.lock:
            self._sweep(self.clock())
            return len(self.wrong.get(address, [])) >= WRONG_LIMIT

    def unlock(self, code, address):
        """Returns a one-time download token, None for a wrong code, or 'blocked'."""
        with self.lock:
            now = self.clock()
            self._sweep(now)
            if len(self.wrong.get(address, [])) >= WRONG_LIMIT:
                return 'blocked'
            code = (code or '').strip()
            match = None
            for candidate in self.codes:
                if hmac.compare_digest(candidate.encode(), code.encode()):
                    match = candidate
            if match is None:
                self.wrong.setdefault(address, []).append(now)
                return None
            del self.codes[match]  # used once, gone
            token = secrets.token_urlsafe(24)
            self.links[token] = [now + self.lifetime, address, LINK_USES]
            return token

    def take_link(self, token, address, count=True):
        """True when the link is still valid for this address; uses it up when count is set."""
        with self.lock:
            now = self.clock()
            self._sweep(now)
            link = self.links.get(token)
            if not link or link[1] != address:
                return False
            if count:
                link[2] -= 1
            return True

    def key_ok(self, header):
        expected = 'Bearer ' + self.feed_key
        return hmac.compare_digest((header or '').encode(), expected.encode())

    def feed_file(self, area, name):
        if area not in FEED_AREAS or not FILE_NAME.fullmatch(name) or '..' in name:
            return None
        path = self.feed_dir / area / name
        return path if path.is_file() else None

    def apk(self):
        return self.feed_file('app', 'home.apk')


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>Login</title><style>
body{{background:#fff;color:#111;font:28px sans-serif;display:flex;min-height:90vh;align-items:center;justify-content:center;margin:0}}
form{{text-align:center}}input{{font-size:48px;width:6em;text-align:center;letter-spacing:.2em;margin:24px 0;padding:8px}}
button{{font-size:32px;padding:12px 48px}}p{{color:#a00;font-size:24px}}
</style></head><body><form method="post" action="/unlock">
<div>Enter login</div><input name="code" inputmode="numeric" autocomplete="off" autofocus maxlength="12">
<div><button type="submit">OK</button></div>{message}</form></body></html>"""

ADMIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="mobile-web-app-capable" content="yes">
<link rel="manifest" href="/manifest.json"><title>MovieHub codes</title><style>
body{{font:22px sans-serif;text-align:center;padding:40px 16px;background:#111;color:#eee}}
button{{font-size:30px;padding:20px 40px;border-radius:12px;border:0;background:#2a6;color:#fff}}
.code{{font-size:96px;letter-spacing:.15em;margin:30px 0 10px}}.small{{color:#aaa}}
</style></head><body><form method="post" action="/new"><button>New code</button></form>{body}</body></html>"""

MANIFEST = ('{"name":"MovieHub codes","short_name":"Codes","start_url":"/","display":"standalone",'
            '"background_color":"#111111","theme_color":"#111111"}')


def make_handler(gate, admin, trust_cloudflare):
    class Handler(BaseHTTPRequestHandler):
        server_version = 'nginx'
        sys_version = ''

        def log_message(self, fmt, *args):  # one short line; never logs codes, keys or tokens
            path = self.path.split('?')[0]
            if path.startswith('/get/'):
                path = '/get/…'
            sys.stderr.write('%s %s %s %s\n' % (time.strftime('%H:%M:%S'), 'admin' if admin else 'public',
                                                self.command, path))

        def address(self):
            if trust_cloudflare and self.headers.get('CF-Connecting-IP'):
                return self.headers['CF-Connecting-IP'].strip()
            return self.client_address[0]

        def send(self, status, body=b'', kind='text/html; charset=utf-8', extra=None, head=False):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(status)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Robots-Tag', 'noindex, nofollow')
            self.send_header('Referrer-Policy', 'no-referrer')
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if not head:
                self.wfile.write(body)

        def send_file(self, path, kind, extra=None, head=False):
            size = path.stat().st_size
            self.send_response(200)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Robots-Tag', 'noindex, nofollow')
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if head:
                return
            with open(path, 'rb') as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)

        def not_found(self, head=False):
            self.send(404, 'Not found', 'text/plain', head=head)

        def form(self):
            length = int(self.headers.get('Content-Length') or 0)
            if length > 1024:
                return {}
            return parse_qs(self.rfile.read(length).decode(errors='replace'))

        # ---------------------------------------------------------------- GET / HEAD
        def do_HEAD(self):
            self.do_GET(head=True)

        def do_GET(self, head=False):
            path = self.path.split('?')[0]
            if admin:
                if path == '/':
                    return self.send(200, ADMIN_PAGE.format(body=''), head=head)
                if path == '/manifest.json':
                    return self.send(200, MANIFEST, 'application/manifest+json', head=head)
                return self.not_found(head)
            if path == '/':
                return self.send(200, PAGE.format(message=''), head=head)
            if path == '/robots.txt':
                return self.send(200, 'User-agent: *\nDisallow: /\n', 'text/plain', head=head)
            parts = path.split('/')
            if len(parts) == 4 and parts[1] == 'get' and parts[3] == 'MovieHub.apk':
                apk = gate.apk()
                if apk is None or not gate.take_link(parts[2], self.address(), count=not head):
                    return self.not_found(head)
                return self.send_file(apk, APK_TYPE, {
                    'Content-Disposition': 'attachment; filename="MovieHub.apk"'}, head=head)
            if len(parts) == 4 and parts[1] == 'feed':
                if not gate.key_ok(self.headers.get('Authorization')):
                    return self.not_found(head)  # look exactly like a missing file
                found = gate.feed_file(parts[2], parts[3])
                if found is None:
                    return self.not_found(head)
                kind = APK_TYPE if found.suffix == '.apk' else 'application/octet-stream'
                if found.suffix == '.json':
                    kind = 'application/json'
                return self.send_file(found, kind, head=head)
            return self.not_found(head)

        # ---------------------------------------------------------------- POST
        def do_POST(self):
            path = self.path.split('?')[0]
            if admin:
                if path != '/new':
                    return self.not_found()
                code, seconds = gate.new_code()
                body = ('<div class="code">%s</div><div class="small">Works once, for %d minutes.</div>'
                        % (html.escape(code), seconds // 60))
                return self.send(200, ADMIN_PAGE.format(body=body))
            if path != '/unlock':
                return self.not_found()
            code = (self.form().get('code') or [''])[0]
            result = gate.unlock(code, self.address())
            if result == 'blocked':
                return self.send(429, PAGE.format(message='<p>Too many tries. Wait 10 minutes.</p>'))
            if result is None:
                return self.send(403, PAGE.format(message='<p>That code is not valid.</p>'))
            if gate.apk() is None:
                return self.send(503, PAGE.format(message='<p>Not available right now.</p>'))
            return self.send(303, '', extra={'Location': '/get/%s/MovieHub.apk' % result})

    return Handler


class DualStackServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler):
        import socket
        self.address_family = socket.AF_INET6 if ':' in address[0] else socket.AF_INET
        super().__init__(address, handler)

    def server_bind(self):
        import socket
        if self.address_family == socket.AF_INET6:
            try:
                self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            except OSError:
                pass
        super().server_bind()


def serve(gate, public=('::', 8080), admin=('0.0.0.0', 8081), trust_cloudflare=False):
    servers = [DualStackServer(public, make_handler(gate, False, trust_cloudflare)),
               DualStackServer(admin, make_handler(gate, True, False))]
    threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in servers]
    for t in threads:
        t.start()
    return servers


def main():
    env = os.environ
    gate = Gate(env.get('FEED_DIR', '/srv/moviehub/feed'), env.get('FEED_KEY', ''),
                int(env.get('CODE_DIGITS', '4')), int(env.get('CODE_MINUTES', '5')))
    servers = serve(gate,
                    (env.get('PUBLIC_BIND', '::'), int(env.get('PUBLIC_PORT', '8080'))),
                    (env.get('ADMIN_BIND', '0.0.0.0'), int(env.get('ADMIN_PORT', '8081'))),
                    env.get('TRUST_CLOUDFLARE') == '1')
    sys.stderr.write('MovieHub gate running: public %s, admin %s\n'
                     % (servers[0].server_address[:2], servers[1].server_address[:2]))
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
