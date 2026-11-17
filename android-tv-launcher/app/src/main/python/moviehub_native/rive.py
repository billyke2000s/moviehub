"""Rive's anonymous TMDB lookup using its current advertised backend directory."""
import json
import re
import time
from urllib.parse import urlencode

from moviehub_compat import client
from moviehub_compat.current_hosts import direct


def positive(value):
    text = str(value)
    return text if text.isdigit() and int(text) > 0 else None


class source:
    base_link = 'https://www.rivestream.app'
    api = 'https://scrapper.rivestream.app/api/provider'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return self.tvshow(imdb, tmdb, None, title, localtitle, aliases, year)

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        identity = positive(tmdb)
        return json.dumps({'id': identity}) if identity else None

    def episode(self, key, imdb, tmdb, tvdb, title, premiered, season, episode):
        if key is None or not str(season).isdigit() or not positive(episode):
            return None
        query = json.loads(key)
        query.update(season=str(season), episode=str(episode))
        return json.dumps(query)

    def sources(self, key, hosts):
        if key is None:
            return []
        query = json.loads(key)
        results = []
        # Reserve the rest of the lookup for validating collected links. A slow
        # late backend must not consume the scope and discard earlier results.
        extraction_end = time.monotonic() + min(60, client.remaining() * .6)
        backends = self.services('season' in query)
        for backend in backends:
            client.check()
            available = extraction_end - time.monotonic()
            if available <= 0:
                break
            try:
                results.extend(self.backend(query, backend, min(6, available)))
            except Exception as error:
                client.host_result('Rive ' + backend, type(error).__name__)
        return results

    def services(self, series):
        # Phisher's current implementation discovers this directory instead of
        # assuming that historically working provider names still exist.
        try:
            response = client.request(self.base_link + '/api/backendfetch?' + urlencode(dict(
                requestID='VideoProviderServices', secretKey='rive')),
                referer=self.base_link + '/', output='json', timeout=12)
            names = response.get('data')
            if not isinstance(names, list):
                raise ValueError('Invalid Rive provider directory')
            names = list(dict.fromkeys(name for name in names if isinstance(name, str)
                         and re.fullmatch(r'[a-z][a-z0-9_-]{0,31}', name)))[:24]
            if not names:
                raise ValueError('Empty Rive provider directory')
            # Discovery is broader than playback compatibility. Pulse crashed
            # packaged LibVLC in the 17 November episode audit; other newly
            # advertised routes also failed decoding. Keep them independently
            # testable without exposing unverified backends in normal playback.
            if not getattr(self, 'cinestream_audit', False):
                names = [name for name in names if name in ('primevids', 'citadel', 'vanguard')]
            preferred = [name for name in ('primevids', 'citadel') if name in names]
            return preferred + [name for name in names if name not in preferred]
        except Exception as error:
            client.host_result('Rive provider directory', type(error).__name__)
            return ('vanguard', 'primevids', 'citadel') if series else ('primevids', 'citadel')

    def backend(self, identity, backend, timeout=6):
        query = dict(identity, provider=backend)
        response = client.request(self.api + '?' + urlencode(query),
                                  referer=self.base_link + '/', output='json', timeout=timeout)
        data = response.get('data') if isinstance(response, dict) else None
        if not isinstance(data, dict):
            return []
        # Never accept an explicitly different title or episode from the API.
        for field, expected in [('tmdbId', query['id']), ('tmdb_id', query['id']),
                                ('season', query.get('season')), ('episode', query.get('episode'))]:
            if field in data and (expected is None or str(data[field]) != str(expected)):
                return []
        rows = data.get('sources')
        if not isinstance(rows, list):
            return []
        results = []
        for row in rows[:32]:
            client.check()
            if not isinstance(row, dict) or row.get('drm'):
                continue
            claim = ' '.join(str(row.get('quality', '')).split())[:100]
            # Other Primevids carriers have not passed decode/audio checks.
            if backend == 'primevids' and claim.casefold() != 'dcloud':
                continue
            if str(row.get('format', '')).lower() not in ('hls', 'm3u8', 'mp4', 'mkv', 'video', 'file', 'dash', 'mpd'):
                continue
            url = row.get('url')
            if not isinstance(url, str):
                continue
            try:
                client.safe(url)
            except (ValueError, OSError):
                continue
            quality = re.search(r'\b(2160|1080|720|480|360)p\b', claim, re.I)
            quality_name = quality[1] + 'p' if quality else 'Auto'
            if re.search(r'\b4K\b', claim, re.I):
                quality_name = '2160p'
            language = claim.partition('|')[2].strip()
            detail = language if backend == 'citadel' else claim
            label = backend.capitalize() + (' · ' + detail if detail else '')
            item = direct(url, self.base_link + '/', label,
                          quality_name)
            item['info'] = claim
            # These routes serve raw TS with image/jpeg headers. Opt into the existing
            # whole-entity carrier path so a player's zero range retains the real TS type.
            item['segmentContainers'] = backend == 'citadel'
            results.append(item)
        return results

    def resolve(self, url):
        return url
