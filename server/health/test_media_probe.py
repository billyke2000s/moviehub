"""Exercise the production FFmpeg probe against an HTTP 200 fixture server on loopback."""
import functools
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest


class MediaProbe(unittest.TestCase):
    def test_http_200_needs_both_decoded_audio_and_video(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, 'page.html').write_text('<html>Player unavailable</html>')
            for filename, extra in [('av.mkv', ['-f', 'lavfi', '-i', 'sine', '-c:a', 'pcm_s16le']),
                                    ('silent.mkv', [])]:
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=size=64x48:rate=25',
                                *extra, '-t', '2', '-c:v', 'ffv1', str(Path(temp) / filename)], check=True)
            handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=temp)
            with http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    for filename, expected in [('page.html', False), ('silent.mkv', False), ('av.mkv', True)]:
                        with self.subTest(filename=filename):
                            url = 'http://127.0.0.1:%d/%s' % (server.server_port, filename)
                            code = ('import {decode} from "./provider-engine/tools/live-probe.mjs";'
                                    'console.log(JSON.stringify(decode({url: process.argv[1]})));')
                            output = subprocess.check_output(['node', '--input-type=module', '-e', code, url],
                                env={**os.environ, 'MOVIEHUB_PROBE_LIBRARY': '1'}, cwd=root, text=True, timeout=50)
                            result = json.loads(output)
                            self.assertEqual(result['ok'], expected, result)
                            self.assertEqual(result['status'], 'decoded' if expected else 'media_failed')
                            if expected:
                                self.assertGreaterEqual(min(result['frames']), 10)
                    # Exercise a local tool failure with the same generic diagnostic seen in CI.
                    fake = Path(temp, 'ffmpeg')
                    fake.write_text('#!/bin/sh\necho "Error opening output files: Invalid argument" >&2\nexit 1\n')
                    fake.chmod(0o755)
                    env = {**os.environ, 'MOVIEHUB_PROBE_LIBRARY': '1', 'PATH': temp + ':' + os.environ['PATH']}
                    result = subprocess.check_output(['node', '--input-type=module', '-e', code, url],
                                                     cwd=root, env=env, text=True, timeout=10)
                    self.assertEqual(json.loads(result)['status'], 'unverified')
                finally:
                    server.shutdown()
                    thread.join()
