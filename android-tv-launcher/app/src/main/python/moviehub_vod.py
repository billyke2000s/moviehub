"""Bundled Scrubs/Free99 discovery and ResolveURL host resolution for MovieHub."""
import importlib
import importlib.resources
import json
import re
import threading
import time
from urllib.parse import parse_qsl, urlparse
import moviehub_compat
from moviehub_compat import client
import resolveurl

from pathlib import Path
_RECORDS = json.loads(Path(__file__).with_name('moviehub_providers.json').read_text())
_MODULES = {r['id']: r for r in _RECORDS}
# Keep host selection headless and free-only. Never authorize a cloud service or open a popup.
_HOSTS = sorted({d.lower() for cls in resolveurl.relevant_resolvers(include_universal=False, include_popups=False)
                 if '*' not in cls.domains for d in cls.domains})

def providers():
    return json.dumps(_RECORDS)

# Websites found dead on 25 October 2026: every one of three checks from a GitHub runner, minutes
# apart, failed (no DNS record, broken TLS certificate, Cloudflare 521/522, or no connection). They are
# skipped when searching but stay bundled, so provenance checks against the original add-ons still pass.
RETIRED_SITES = frozenset({
    '123-movies.golf', '123moviestv.me', '1movies.la', '9pm.to', 'cinebox.cc', 'couchtuner.show',
    'dopebox.to', 'ev01.to', 'filmxy.online', 'flixhq.to', 'flixwave.me', 'goojara.life',
    'hdbest.net', 'hdtoday.cc', 'levidia.ch', 'm4ufree.se', 'movies2watch.tv', 'movies4fun.net',
    'mp4hydra.top', 'projectfreetv.lol', 'putlocker.gives', 'sflixz.watch', 'soap2dayz.top',
    'streamlord.com', 'watchug.com', 'www1.movie4u.live',
})

def search_providers(include_retired=False):
    """One public website per entry; alternate addon adapters remain fallbacks."""
    groups = {}
    for record in sorted(_RECORDS, key=lambda r: (r['family'] != 'free99', r['id'])):
        if record['name'] in ('library', 'filepursuit'): continue
        if record['site'] in RETIRED_SITES and not include_retired: continue
        site = record['site']
        group = groups.setdefault(site, dict(id=record['id'], family=record['family'],
            name=site, site=site, adapters=[], movies=False, series=False))
        group['adapters'].append(record['id'])
        group['movies'] |= record['movies']
        group['series'] |= record['series']
    return json.dumps(list(groups.values()))


def query_site(provider_id, raw, cancelled, emit=None):
    group = next(r for r in json.loads(search_providers(include_retired=True)) if r['id'] == provider_id)
    deadline = time.monotonic() + 45
    info = json.loads(raw)
    cancel = cancelled.get if hasattr(cancelled, "get") else cancelled
    # Stop before any adapter or provider request when Java has already cancelled the search.
    if cancel():
        return json.dumps(dict(status='timeout', sources=[], attempts=[]))
    # Treat a missing season as a film rather than failing the whole website search.
    is_movie = info.get('season', -1) < 0
    results, attempts, seen = [], [], set()
    class Emit:
        def ready(self, value):
            item = json.loads(value)
            key = (item['url'], json.dumps(item['headers'], sort_keys=True))
            if key in seen: return
            seen.add(key)
            item['adapter'] = item['provider']
            item['provider'] = group['id']
            item['providerName'] = group['site']
            results.append(item)
            if emit is not None: emit.ready(json.dumps(item))
    for adapter in group['adapters']:
        record = _MODULES[adapter]
        if not record['movies' if is_movie else 'series']: continue
        if time.monotonic() >= deadline: break
        report = json.loads(query(adapter, raw, cancelled, Emit(), deadline))
        attempts.append(dict(adapter=adapter, status=report['status'], stage=report.get('stage', ''),
                             extracted=report.get('extracted', 0), rejected=report.get('rejected', 0),
                             resolutionErrors=report.get('resolutionErrors', 0),
                             unsupportedHosts=report.get('unsupportedHosts', 0)))
        # Do not hit the same website twice when the first adapter already supplied playable links.
        if results: break
    status = 'complete' if results else (attempts[-1]['status'] if attempts else 'unsupported_type')
    return json.dumps(dict(status=status, sources=results, attempts=attempts,
        extracted=sum(a['extracted'] for a in attempts), rejected=sum(a['rejected'] for a in attempts),
        resolutionErrors=sum(a['resolutionErrors'] for a in attempts),
        unsupportedHosts=sum(a['unsupportedHosts'] for a in attempts)))


def validate():
    """Import and instantiate every bundled adapter without contacting a provider."""
    for record in _RECORDS:
        importlib.import_module(record['module']).source()
    return len(_RECORDS)


def query(provider_id, raw, cancelled, emit=None, deadline=None):
    deadline = deadline or time.monotonic() + 45
    cancel = cancelled.get if hasattr(cancelled, "get") else cancelled
    client.scope(cancel, timeout=max(.1, min(30, deadline-time.monotonic())), http=json.loads(raw).get('http', False))
    info = json.loads(raw)
    record = _MODULES[provider_id]
    # Kodi's local library is not an Internet provider. Its module remains bundled for provenance.
    if record['name'] == 'library': return json.dumps({'status': 'local_library', 'sources': []})
    if record['name'] == 'filepursuit': return json.dumps({'status': 'requires_api_key', 'sources': []})
    stage = "lookup"
    try:
        client.check()
        utils = importlib.import_module('moviehub_'+record['family']+'.source_utils')
        utils._seen.websites = set()
        provider = importlib.import_module(record['module']).source()
        imdb, tmdb = info.get('imdb', ''), str(info.get('tmdb', ''))
        title, year = info.get('title', ''), str(info.get('year', ''))
        # Use each add-on's title preparation before constructing its lookup key.
        clean = importlib.import_module('moviehub_'+record['family']+'.cleantitle')
        localtitle = info.get('localTitle') or title
        season, episode = str(info['season']), str(info['episode'])
        original_title = title
        if info['season'] < 0:
            title, imdb, year = clean.scene_title(title, imdb, year)
        else:
            title, imdb, year, season, episode = clean.scene_tvtitle(title, imdb, year, season, episode)
        aliases = []
        seen_titles = set()
        for candidate in [title, original_title, localtitle] + info.get('aliases', []):
            if not isinstance(candidate, str) or not candidate.strip(): continue
            candidate = candidate.strip()
            if candidate.casefold() in seen_titles: continue
            seen_titles.add(candidate.casefold())
            aliases.append({'title': candidate, 'country': 'us'})
        if info['season'] < 0:
            if not hasattr(provider, 'movie'): return json.dumps({'status':'unsupported_type', 'sources':[]})
            key = provider.movie(imdb, tmdb, title, localtitle, aliases, year)
        else:
            if not hasattr(provider, 'tvshow'): return json.dumps({'status':'unsupported_type', 'sources':[]})
            key = provider.tvshow(imdb, tmdb, '', title, localtitle, aliases, year)
            key = provider.episode(key, imdb, tmdb, '', info.get('episodeTitle',''), info.get('premiered',''),
                                   season, episode)
        stage = "extraction"
        candidates = provider.sources(key, list(_HOSTS)) or []
        client.check()
        extracted = len(candidates)
        rejected = 0
        errors = 0
        unsupported = 0
        lookup_failure = client.failure()
        # Discovery cannot consume the separate, bounded host/media resolution budget.
        client.scope(cancel, timeout=max(.1, min(15, deadline-time.monotonic())), http=info.get('http', False))
        sources=[]; seen=set(); timed_out=False
        for item in candidates[:200]:
            try:
                client.check()
                stage = "resolution"
                url=provider.resolve(item['url'])
                if not item.get('direct', False):
                    host=resolveurl.HostedMediaFile(url=url, include_universal=False, include_popups=False)
                    if not host.valid_url():
                        unsupported += 1
                        continue
                    url=host.resolve()
                if not isinstance(url,str) or url in seen: continue
                stage = "media"
                result=playable(url, info.get("maxQuality", 2160))
                if result is None:
                    rejected += 1
                    continue
                seen.add(url)
                result.update({'provider':provider_id, 'providerName':urlparse(provider.base_link).hostname or record['name'], 'quality':result.pop('verifiedQuality', None) or str(item.get('quality','SD')),
                               'info':str(item.get('info','')), 'title': title})
                sources.append(result)
                if emit is not None: emit.ready(json.dumps(result))
            except client.Cancelled:
                timed_out = True
                break
            except Exception:
                errors += 1
                continue
        status = ('complete' if sources else 'timeout' if timed_out else client.failure() or lookup_failure or
                  ('rejected_media' if rejected else 'resolution_failed' if errors else
                   'unsupported_host' if unsupported else 'no_matches'))
        return json.dumps({'status':status,'sources':sources,'extracted':extracted,
                           'rejected':rejected,'resolutionErrors':errors,'unsupportedHosts':unsupported,
                           'stage':stage,'candidateLimitReached':len(candidates)>200})
    except client.Cancelled:
        return json.dumps({'status':'timeout','sources':[],'stage':stage})
    except Exception as error:
        return json.dumps({'status':type(error).__name__,'sources':[],'stage':stage})

def playable(raw, preferred=2160):
    url, sep, suffix=raw.partition('|')
    client.safe(url)
    headers=dict(parse_qsl(suffix)) if sep else {}
    original_headers = dict(headers)
    if not any(k.lower() == 'user-agent' for k in headers): headers['User-Agent'] = client.UserAgent
    allowed={'user-agent','referer','origin','cookie','accept','authorization'}
    if any(k.lower() not in allowed or '\r' in v or '\n' in v for k,v in headers.items()): return None
    # A host page or expired error response must not win the three-second window.
    path=urlparse(url).path.lower()
    started=time.monotonic()
    response=client.fetch(url, dict(headers, Range='bytes=0-65535'), timeout=8, max_bytes=65536)
    # Keep cookies issued by the media host, scoped to the final URL after redirects.
    final_url = response.url or url
    client.safe(final_url)
    if urlparse(final_url).netloc != urlparse(url).netloc:
        headers = {k:v for k,v in headers.items() if k.lower() not in ('cookie','authorization')}
        original_headers = {k:v for k,v in original_headers.items() if k.lower() not in ('cookie','authorization')}
    cookie = client.requests.cookies.get_cookie_header(response.cookies,
        client.requests.Request('GET', final_url).prepare())
    if cookie:
        existing = next((v for k,v in headers.items() if k.lower() == 'cookie'), '')
        headers = {k:v for k,v in headers.items() if k.lower() != 'cookie'}
        headers['Cookie'] = '; '.join(v for v in (existing, cookie) if v)
        original_headers['Cookie'] = headers['Cookie']
    url = final_url
    path = urlparse(url).path.lower()
    elapsed=max(.001, time.monotonic()-started)
    verified=None
    kind=response.headers.get('Content-Type','').lower()
    if response.status_code not in (200,206) or 'text/html' in kind: return None
    prefix=response.content.lstrip()
    if '.m3u8' in path or 'mpegurl' in kind:
        if not prefix.startswith((b'#EXTM3U', b'\xef\xbb\xbf#EXTM3U')): return None
        heights=[int(x) for x in re.findall(rb'RESOLUTION=\d+x(\d+)', prefix)]
        if heights:
            allowed=[h for h in heights if h <= preferred]
            verified=str(max(allowed) if allowed else min(heights))+'p'
    elif '.mpd' in path or 'dash+xml' in kind:
        # This VOD path has no DRM session integration.
        if b'<MPD' not in prefix or b'ContentProtection' in prefix: return None
    elif not kind.startswith(('video/','audio/')):
        known=re.search(r'\.(?:mp4|mkv|webm|avi|ts|mov|m4v)(?:$|/)',path)
        if not known or 'application/octet-stream' not in kind: return None
    from moviehub_compat.media import duration_seconds
    duration = duration_seconds(prefix, kind)
    if duration is not None and 0 < duration < 120: return None
    playlist = '.m3u8' in path or '.mpd' in path or 'mpegurl' in kind or 'dash+xml' in kind
    # A manifest's byte size says nothing about its media throughput. Only compare media samples.
    rate=len(response.content)*8/(elapsed*1000000) if not playlist and len(response.content)>=32768 else 0
    # Keep header-free DASH native: a loopback URL would change relative MPD base paths.
    if '.mpd' in path or 'dash+xml' in kind: headers = original_headers
    return {'url':url,'headers':headers,'verifiedQuality':verified,
            'format':'dash' if '.mpd' in path or 'dash+xml' in kind else 'stream',
            'probeMbps':rate,'responseMs':round(elapsed*1000),'durationSeconds':duration}

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
    session=client.requests.Session()
    session.cookies.update(self._cj)
    try:
        response=client.fetch(url,out,form_data,timeout,redirect,session=session,method=method)
        for cookie in session.cookies: self._cj.set_cookie(cookie)
        response.cookies=session.cookies
        return Response(response)
    finally:
        session.close()
Net._fetch=net_fetch


# A few host plugins use urllib directly instead of Net. Route both through the same
# public-target, TLS, cancellation, response-size and redirect checks.
import io
import email.message
import urllib.request
import urllib.response

def urllib_open(request, data=None, timeout=8, **kwargs):
    if isinstance(request,str):
        address,headers,method=request,{},('POST' if data is not None else 'GET')
    else:
        address=request.full_url
        headers=dict(request.header_items())
        data=request.data if data is None else data
        method=request.get_method()
    response=client.fetch(address,headers,data,timeout,method=method)
    message=email.message.Message()
    for key,value in response.headers.items(): message[key]=value
    result=urllib.response.addinfourl(io.BytesIO(response.content),message,response.url,response.status_code)
    result.msg=response.reason
    return result

class SafeOpener:
    def __init__(self): self.addheaders=[]
    def open(self,request,data=None,timeout=8):
        if isinstance(request,str): request=urllib.request.Request(request,data=data,headers=dict(self.addheaders))
        return urllib_open(request,data,timeout)
    def add_handler(self,handler): pass

urllib.request.urlopen=urllib_open
urllib.request.build_opener=lambda *handlers: SafeOpener()
