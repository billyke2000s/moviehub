"""Bounded public-page structure inspection; never emit media URLs or cookies."""
import collections
import hashlib
import json
import re
from html.parser import HTMLParser
from urllib.parse import parse_qs, urljoin, urlsplit
import requests

ORIGIN = "https://seriezloaded.tv"
HEADERS = {"User-Agent": "Mozilla/5.0 (Android 11; TV) AppleWebKit/537.36 Chrome/130.0 Safari/537.36",
           "Accept": "*/*", "DNT": "1"}
PAGES = ["/", "/?s=Inception", "/?s=Dune",
         "/?s=Breaking+Bad+Season+1+Episode+1", "/?s=Breaking+Bad"]

class Structure(HTMLParser):
    def __init__(self):
        super().__init__()
        self.classes = collections.Counter()
        self.links, self.forms, self.headings = [], [], []
        self.anchor = self.heading = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("div", "article", "li", "h3"):
            self.classes[(tag, attrs.get("class", "")[:160])] += 1
        if tag == "a":
            self.anchor = dict(text="", href=attrs.get("href", ""),
                               id=attrs.get("id", ""), rel=attrs.get("rel", ""),
                               css=attrs.get("class", ""))
        if tag in ("h1", "h2"):
            self.heading = ""
        if tag == "form":
            self.forms.append(dict(method=attrs.get("method", "get"),
                                   action=safe_url(attrs.get("action", ""))))
        if tag == "input" and self.forms:
            self.forms[-1].setdefault("fields", []).append(attrs.get("name", "")[:80])

    def handle_data(self, text):
        if self.anchor is not None:
            self.anchor["text"] += text[:300]
        if self.heading is not None:
            self.heading += text[:300]

    def handle_endtag(self, tag):
        if tag == "a" and self.anchor is not None:
            self.links.append(self.anchor)
            self.anchor = None
        if tag in ("h1", "h2") and self.heading is not None:
            self.headings.append(self.heading.strip()[:200])
            self.heading = None

def safe_url(url):
    parts = urlsplit(urljoin(ORIGIN, url))
    return dict(host=parts.hostname, scheme=parts.scheme,
                pathDigest=hashlib.sha256(parts.path.encode()).hexdigest()[:12],
                extension=re.search(r"\.[a-zA-Z0-9]{1,5}$", parts.path).group(0)
                          if re.search(r"\.[a-zA-Z0-9]{1,5}$", parts.path) else "",
                queryKeys=sorted(parse_qs(parts.query))[:12])

def inspect(path, depth=0):
    result = dict(page=path)
    try:
        with requests.get(ORIGIN + path, timeout=(10, 20), stream=True,
                          allow_redirects=False, headers=HEADERS) as response:
            result.update(status=response.status_code,
                          contentType=response.headers.get("Content-Type", "").split(";")[0])
            if response.is_redirect:
                result["redirect"] = safe_url(response.headers.get("Location", ""))
                return result
            content = bytearray()
            for chunk in response.iter_content(8192):
                content.extend(chunk)
                if len(content) > 524288:
                    result["bodyCapped"] = True
                    return result
        html = content.decode(response.encoding or "utf-8", errors="replace")
        result["accessChallenge"] = bool(re.search(r"cf-chl|challenge-platform|just a moment", html, re.I))
        parser = Structure()
        parser.feed(html)
        result.update(bytes=len(content), headings=parser.headings[:8],
                      forms=parser.forms[:3],
                      containers=[dict(tag=k[0], css=k[1], count=v)
                                  for k, v in parser.classes.most_common(30)])
        selected = [a for a in parser.links if
                    re.search(r"inception|dune|breaking bad|download|episode", a["text"], re.I)]
        result["links"] = [dict(text=re.sub(r"\s+", " ", a["text"]).strip()[:180],
                                id=a["id"][:100], rel=a["rel"][:100], css=a["css"][:100],
                                target=safe_url(a["href"]))
                           for a in selected[:30]]
        result["interactiveChallengeAdvertised"] = bool(re.search(r"captchaEnabled\s*=\s*true", html))
        if depth == 0 and path.startswith("/?s="):
            posts = [a for a in parser.links if a["rel"] == "bookmark" and
                     re.match(r"^(?:Inception \(2010\)|Dune: Part Two \(2024\)|Breaking Bad Season 1 Episode 1\s*[–-]\s*7)", re.sub(r"\s+", " ", a["text"]).strip())]
            result["linkedPostStructures"] = []
            if not posts:
                raise ValueError("Expected advertised catalogue post not found")
            for post in posts[:2]:
                target = urlsplit(urljoin(ORIGIN, post["href"]))
                if target.scheme == "https" and target.netloc == urlsplit(ORIGIN).netloc:
                    result["linkedPostStructures"].append(inspect(target.path, depth=1))

    except Exception as error:
        result["errorType"] = type(error).__name__
    return result

if __name__ == "__main__":
    for page in PAGES:
        print("VOD_PAGE_STRUCTURE " + json.dumps(inspect(page)), flush=True)
