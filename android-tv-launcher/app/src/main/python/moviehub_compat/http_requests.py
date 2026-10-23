"""Requests subset used by upstream providers, retaining cookies through bounded transport."""
from urllib.parse import urlencode, urlparse
import requests as _requests
from moviehub_compat import client

class Session:
    def __init__(self):
        self._session=_requests.Session()
        self._session.trust_env=False
        self.headers=self._session.headers
        self.cookies=self._session.cookies
    def _request(self,url,headers=None,timeout=8,data=None,cookies=None,params=None,allow_redirects=True,**kwargs):
        if params:
            url += ('&' if '?' in url else '?') + urlencode(params)
        if cookies:
            if hasattr(cookies,'get_dict'): cookies=cookies.get_dict()
            for key,value in cookies.items():
                self.cookies.set(key,value,domain=urlparse(url).hostname,path='/')
        out=dict(self.headers);out.update(headers or {})
        return client.fetch(url,out,data,timeout,allow_redirects,session=self._session)
    def get(self,url,**kwargs): return self._request(url,**kwargs)
    def post(self,url,data=None,**kwargs): return self._request(url,data=data,**kwargs)
    def __enter__(self): return self
    def __exit__(self,*args): self.close()
    def close(self): self._session.close()

def get(url,**kwargs):
    with Session() as session:return session.get(url,**kwargs)
def post(url,**kwargs):
    with Session() as session:return session.post(url,**kwargs)
