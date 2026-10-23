"""Bundled Scrubs/Free99 discovery and ResolveURL host resolution for MovieHub."""
import importlib
import importlib.resources
import json
import re
import threading
from urllib.parse import parse_qsl, urlparse
import moviehub_compat
from moviehub_compat import client
import resolveurl

from pathlib import Path
_RECORDS = json.loads(Path(__file__).with_name('moviehub_providers.json').read_text())
_MODULES = {r['id']: r for r in _RECORDS}
# Keep host selection headless and free-only. Never authorize a cloud service or open a popup.
_HOSTS = [d for cls in resolveurl.relevant_resolvers(include_universal=False, include_popups=False) for d in cls.domains]

def providers():
    return json.dumps(_RECORDS)

def validate():
    """Import and instantiate every bundled adapter without contacting a provider."""
    for record in _RECORDS:
        importlib.import_module(record['module']).source()
    return len(_RECORDS)


def query(provider_id, raw, cancelled, emit=None):
    client.scope(cancelled.get if hasattr(cancelled, "get") else cancelled, timeout=18, http=json.loads(raw).get('http', False))
    info = json.loads(raw)
    record = _MODULES[provider_id]
    # Kodi's local library is not an Internet provider. Its module remains bundled for provenance.
    if record['name'] == 'library': return json.dumps({'status': 'local_library', 'sources': []})
    try:
        client.check()
        utils = importlib.import_module('moviehub_'+record['family']+'.source_utils')
        utils._seen.websites = set()
        provider = importlib.import_module(record['module']).source()
        imdb, tmdb = info.get('imdb', ''), str(info.get('tmdb', ''))
        title, year = info.get('title', ''), str(info.get('year', ''))
        aliases = [{'title': title, 'country': 'us'}]
        aliases += [{'title': t, 'country': ''} for t in info.get('aliases', []) if t != title]
        if info['season'] < 0:
            if not hasattr(provider, 'movie'): return json.dumps({'status':'unsupported_type', 'sources':[]})
            key = provider.movie(imdb, tmdb, title, title, aliases, year)
        else:
            if not hasattr(provider, 'tvshow'): return json.dumps({'status':'unsupported_type', 'sources':[]})
            key = provider.tvshow(imdb, tmdb, '', title, title, aliases, year)
            key = provider.episode(key, imdb, tmdb, '', info.get('episodeTitle',''), info.get('premiered',''),
                                   str(info['season']), str(info['episode']))
        candidates = provider.sources(key, list(_HOSTS)) or []
        sources=[]; seen=set()
        for item in candidates[:40]:
            client.check()
            try:
                url=provider.resolve(item['url'])
                if not item.get('direct', False):
                    host=resolveurl.HostedMediaFile(url=url, include_universal=False, include_popups=False)
                    if not host.valid_url(): continue
                    url=host.resolve()
                if not isinstance(url,str) or url in seen: continue
                result=playable(url)
                if result is None: continue
                seen.add(url)
                result.update({'provider':provider_id, 'quality':str(item.get('quality','SD')),
                               'info':str(item.get('info','')), 'title': title})
                sources.append(result)
                if emit is not None: emit.ready(json.dumps(result))
            except client.Cancelled:
                break
            except Exception:
                continue
        return json.dumps({'status':'complete','sources':sources})
    except client.Cancelled:
        return json.dumps({'status':'timeout','sources':[]})
    except Exception as error:
        return json.dumps({'status':type(error).__name__,'sources':[]})

def playable(raw):
    url, sep, suffix=raw.partition('|')
    client.safe(url)
    headers=dict(parse_qsl(suffix)) if sep else {}
    allowed={'user-agent','referer','origin','cookie','accept','authorization'}
    if any(k.lower() not in allowed or '\r' in v or '\n' in v for k,v in headers.items()): return None
    # A host page or expired error response must not win the three-second window.
    path=urlparse(url).path.lower()
    response=client.fetch(url, dict(headers, Range='bytes=0-1023'), timeout=3, max_bytes=1024)
    kind=response.headers.get('Content-Type','').lower()
    if response.status_code not in (200,206) or 'text/html' in kind: return None
    prefix=response.content.lstrip()
    if '.m3u8' in path or 'mpegurl' in kind:
        if not prefix.startswith((b'#EXTM3U', b'\xef\xbb\xbf#EXTM3U')): return None
    elif '.mpd' in path or 'dash+xml' in kind:
        # This VOD path has no DRM session integration.
        if b'<MPD' not in prefix or b'ContentProtection' in prefix: return None
    elif not kind.startswith(('video/','audio/')):
        known=re.search(r'\.(?:mp4|mkv|webm|avi|ts|mov|m4v)(?:$|/)',path)
        if not known or 'application/octet-stream' not in kind: return None
    return {'url':url,'headers':headers}

# Replace the upstream HTTP fetcher with the bounded transport; retain Net's cookie and UA API.
from resolveurl.lib.net import Net
class Response:
    def __init__(self, response): self.response=response
    @property
    def content(self): return self.response.text
    def json(self): return self.response.json()
    def get_headers(self, as_dict=False): return self.response.headers
    def get_cookies(self, as_dict=False):
        cookies=self.response.cookies.get_dict()
        return cookies if as_dict else '; '.join(k+'='+v for k,v in cookies.items())
    def get_url(self): return self.response.url
    def get_redirect_url(self): return self.response.headers.get('Location',self.response.url)
    def close(self): self.response.close()
    def __enter__(self): return self
    def __exit__(self,*args): self.close()
    def nodecode(self, value): return self

def net_fetch(self,url,form_data=None,headers=None,compression=True,jdata=False,redirect=True,timeout=8,method=None):
    out=dict(headers or {})
    out.setdefault('User-Agent', self.get_user_agent() or client.UserAgent)
    if jdata and form_data is not None:
        form_data=json.dumps(form_data);out['Content-Type']='application/json'
    return Response(client.fetch(url,out,form_data,timeout,redirect))
Net._fetch=net_fetch
