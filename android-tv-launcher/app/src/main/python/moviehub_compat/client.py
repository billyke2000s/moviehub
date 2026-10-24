"""Bounded requests adapter shared by bundled provider modules."""
import ipaddress
import json
import socket
import threading
import time
from urllib.parse import urlparse, parse_qsl
import requests

UserAgent = 'Mozilla/5.0 (Android 11; TV) AppleWebKit/537.36 Chrome/130.0 Safari/537.36'
dnt_headers = {'User-Agent': UserAgent, 'Accept': '*/*', 'DNT': '1'}
_STATE = threading.local()
MAX_BODY = 8 * 1024 * 1024

class Cancelled(BaseException): pass

def scope(cancel, timeout=18, http=False):
    _STATE.cancel, _STATE.deadline, _STATE.http = cancel, time.monotonic()+timeout, http
    _STATE.failure = None

def check():
    callback = getattr(_STATE, 'cancel', None)
    if callback and callback(): raise Cancelled()
    if time.monotonic() >= getattr(_STATE, 'deadline', float('inf')): raise Cancelled()

def safe(url):
    check()
    parsed = urlparse(url)
    schemes = ('http','https') if getattr(_STATE, 'http', False) else ('https',)
    if parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('Unsupported provider URL')
    if len(url)>16384 or '\r' in url or '\n' in url: raise ValueError('Provider URL limit')
    for item in socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme=='https' else 80), type=socket.SOCK_STREAM):
        address=ipaddress.ip_address(item[4][0])
        if not address.is_global: raise ValueError('Private provider address')
    return url

def fetch(url, headers=None, post=None, timeout=8, redirect=True, max_bytes=MAX_BODY, session=None, method=None):
    try:
        current = safe(url)
    except Exception as error:
        _STATE.failure = type(error).__name__
        raise
    owned = session is None
    session = session or requests.Session()
    session.trust_env = False
    method = method or ('POST' if post is not None else 'GET')
    data = post
    try:
        for attempt in range(6):
            check()
            remaining = max(.1, min(float(timeout), 8, getattr(_STATE, 'deadline', time.monotonic()+8)-time.monotonic()))
            response=session.request(method, current, data=data, headers=headers or dnt_headers,
                                     timeout=(remaining, remaining), allow_redirects=False, stream=True)
            if response.is_redirect and redirect:
                target = requests.compat.urljoin(current, response.headers['Location'])
                safe(target)
                if urlparse(target).netloc != urlparse(current).netloc:
                    headers={k:v for k,v in (headers or {}).items() if k.lower() not in ('cookie','authorization')}
                if response.status_code == 303 or response.status_code in (301,302) and method == 'POST':
                    method, data = 'GET', None
                response.close()
                current=target
                continue
            chunks=[]; size=0
            for chunk in response.iter_content(32768):
                check(); size+=len(chunk)
                if size>max_bytes:
                    if max_bytes == MAX_BODY: raise ValueError('Provider response limit')
                    chunks.append(chunk[:max(0,max_bytes-(size-len(chunk)))]); break
                chunks.append(chunk)
                if max_bytes != MAX_BODY and size >= max_bytes: break
            if response.status_code >= 400: _STATE.failure = 'http_' + str(response.status_code)
            response._content=b''.join(chunks)
            response._content_consumed=True
            response.encoding='utf-8'
            response.close()
            return response
        raise ValueError('Redirect limit')
    except Exception as error:
        _STATE.failure = type(error).__name__
        raise
    finally:
        if owned: session.close()

def scrapePage(url, referer=None, headers=None, post=None, cookie=None, timeout=8):
    out=dict(dnt_headers); out.update(headers or {})
    if referer: out['Referer']=referer
    if cookie: out['Cookie']=cookie
    return fetch(url, out, post, timeout)

def request(url, close=True, redirect=True, error=False, verify=True, post=None, headers=None,
            mobile=False, XHR=False, limit=None, referer=None, cookie=None, compression=False,
            output='', timeout=8, as_bytes=False):
    out=dict(dnt_headers); out.update(headers or {})
    if referer: out['Referer']=referer
    if cookie: out['Cookie']=cookie
    if XHR: out['X-Requested-With']='XMLHttpRequest'
    raw, sep, suffix=url.partition('|')
    if sep: out.update(dict(parse_qsl(suffix)))
    result=fetch(raw,out,post,timeout,redirect)
    if output=='cookie': return '; '.join(k+'='+v for k,v in result.cookies.items())
    if output=='geturl': return result.url
    if output=='headers': return result.headers
    if output=='response': return result.status_code,result.text
    if output=='json': return result.json()
    if output=='extended': return result.text,result.status_code,result.headers,result.headers,request(raw,output='cookie')
    return result.content if as_bytes else result.text

def url_ok(url):
    try: return fetch(url).status_code < 400
    except Exception: return False

def failure():
    return getattr(_STATE, "failure", None)
