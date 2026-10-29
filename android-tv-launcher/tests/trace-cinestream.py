"""Opt-in loading-path evidence. Never logs URLs, response bodies, keys or headers."""
import concurrent.futures
import importlib
import json
from pathlib import Path
import re
import sys
import threading
import time
import traceback

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
import moviehub_vod as vod
from moviehub_compat import client
from moviehub_native import PROVIDERS
from audit_transport import block_external_decoder

block_external_decoder()

local = threading.local()
original_fetch = client.fetch


def fetch(*args, **kwargs):
    response = original_fetch(*args, **kwargs)
    body = response.content
    if body.lstrip(b'\xef\xbb\xbf\r\n ').startswith(b'#EXTM3U') and len(local.playlists) < 24:
        # Tags only: URI lines, attribute values and access tokens stay private.
        text = body.decode('utf-8-sig', errors='replace')
        row = dict(variants=text.count('#EXT-X-STREAM-INF:'), segments=text.count('#EXTINF:'),
                   discontinuities=text.count('#EXT-X-DISCONTINUITY\n'),
                   externalAudio=bool(re.search(r'#EXT-X-MEDIA:.*TYPE=AUDIO.*URI=', text)))
        for tag in ('MEDIA-SEQUENCE', 'DISCONTINUITY-SEQUENCE', 'TARGETDURATION'):
            match = re.search(r'^#EXT-X-' + tag + r':(\d+)', text, re.M)
            if match:
                row[tag] = int(match[1])
        local.playlists.append(row)
    return response


client.fetch = fetch


def probe(record):
    local.playlists = []
    frames = []
    module = importlib.import_module(record['module'])
    cls = getattr(module, record['factory'])
    original_sources = cls.sources

    def sources(self, *args, **kwargs):
        try:
            return original_sources(self, *args, **kwargs)
        except Exception:
            frames.extend(dict(file=Path(frame.filename).name, line=frame.lineno, function=frame.name)
                          for frame in traceback.extract_tb(sys.exc_info()[2]))
            raise

    # Each provider has its own class; instrumentation affects this audit process only.
    cls.sources = sources
    series = '--series' in sys.argv
    query = dict(imdb='tt0903747' if series else 'tt0063350', tmdb=1396 if series else 10331,
                 title='Breaking Bad' if series else 'Night of the Living Dead',
                 year='2008' if series else '1968', season=1 if series else -1,
                 episode=1 if series else -1, http=False, cinestreamAudit=True)
    started = time.monotonic()
    result = json.loads(vod.query_site(record['id'], json.dumps(query),
                                     lambda: time.monotonic() - started > 180))
    return dict(provider=record['id'], status=result.get('status'),
                sources=len(result.get('sources', [])), attempts=result.get('attempts'),
                hostAttempts=result.get('hostAttempts'), stack=frames, playlists=local.playlists)


records = [record for record in PROVIDERS if record.get('factory') and
           record['series' if '--series' in sys.argv else 'movies']]
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
    for result in workers.map(probe, records):
        print(json.dumps(result), flush=True)
