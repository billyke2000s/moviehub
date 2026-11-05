"""Inspect only advertised SeriezLoaded download handoffs; redact identifiers."""
import json
import re
import sys
import traceback
from pathlib import Path
from urllib.parse import urlsplit
import requests
from vod_site_inspection import HEADERS, ORIGIN, Structure, safe_url

POSTS = ["/inception-2010/", "/movie-dune-part-two-2024-hollywood-movie/",
         "/series-breaking-bad-season-1-episode-1-7-complete/"]

def read(url, redirects=0):
    with requests.get(url, headers=HEADERS, timeout=(10, 20),
                      allow_redirects=False, stream=True) as response:
        info = dict(status=response.status_code,
                    contentType=response.headers.get("Content-Type", "").split(";")[0])
        if response.is_redirect:
            target = requests.compat.urljoin(url, response.headers.get("Location", ""))
            info["redirect"] = safe_url(target)
            destination = urlsplit(target)
            if (redirects < 4 and destination.scheme == "https" and
                    destination.netloc == urlsplit(url).netloc and
                    not destination.username and not destination.password):
                response.close()
                final, html = read(target, redirects + 1)
                final["redirectCount"] = max(final.get("redirectCount", 0), redirects + 1)
                return final, html
            return info, ""
        if "html" not in info["contentType"]:
            return info, ""
        body = bytearray()
        for chunk in response.iter_content(8192):
            body.extend(chunk)
            if len(body) > 524288:
                return dict(info, bodyCapped=True), ""
        html = body.decode(response.encoding or "utf-8", errors="replace")
        info.update(bytes=len(body),
                    accessChallenge=bool(re.search(r"cf-chl|challenge-platform|just a moment", html, re.I)),
                    challengePage=bool(re.search(r"<title>[^<]*(?:just a moment|attention required)", html, re.I)),
                    interactiveWidget=bool(re.search(r"cf-turnstile-response|g-recaptcha-response|h-captcha-response", html, re.I)),
                    captchaAdvertised=bool(re.search(r"captchaEnabled\s*=\s*true", html)),
                    deletedFile=bool(re.search(r"file (?:you were looking for doesn.t exist|not found|deleted|has been removed)", html, re.I)))
        parser = Structure()
        parser.feed(html)
        info["forms"] = parser.forms[:3]
        return info, html


def diagnose_send(media_id):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] /
                           "android-tv-launcher/app/src/main/python"))
    import moviehub_vod
    from moviehub_compat import client
    from resolveurl.plugins.send import SendResolver
    client.scope(lambda: False, timeout=25)
    result = {}
    try:
        url = SendResolver().get_media_url("send.cm", media_id)
        result["returnedMediaLocator"] = isinstance(url, str) and bool(url)
        if isinstance(url, str):
            result["target"] = safe_url(url.partition("|")[0])
    except Exception as error:
        result["errorType"] = type(error).__name__
        result["origin"] = [dict(file=Path(frame.filename).name,
                                 function=frame.name, line=frame.lineno)
                            for frame in traceback.extract_tb(error.__traceback__)[-5:]]
    result["requests"] = client.diagnostics()["requests"]
    return result

for post in POSTS:
    result = dict(post=post, downloads=[])
    try:
        result["page"], html = read(ORIGIN + post)
        parser = Structure()
        parser.feed(html)
        buttons = [a for a in parser.links if "sl-post-download-button" in a["css"]]
        if "breaking-bad" in post:
            buttons = [a for a in buttons if re.fullmatch(
                r"DOWNLOAD EPISODE 0*1", re.sub(r"\s+", " ", a["text"]).strip(), re.I)]
        elif len(buttons) > 3:
            buttons = [buttons[0], buttons[len(buttons) // 2], buttons[-1]]
        for button in buttons[:3]:
            entry = dict(label=re.sub(r"\s+", " ", button["text"]).strip()[:160])
            result["downloads"].append(entry)
            target = requests.compat.urljoin(ORIGIN, button["href"])
            parts = urlsplit(target)
            if parts.scheme != "https" or parts.netloc != urlsplit(ORIGIN).netloc:
                entry["unfollowedTarget"] = safe_url(target)
                continue
            entry["landing"], landing = read(target)
            if entry["landing"].get("accessChallenge") or entry["landing"].get("captchaAdvertised"):
                continue
            match = re.search(r"""window\.location\.assign\(\s*["']([^"']+)""", landing)
            if not match:
                entry["literalHandoffFound"] = False
                continue
            handoff = match[1].replace("\\/", "/")
            entry["handoff"] = safe_url(handoff)
            destination = urlsplit(handoff)
            if destination.hostname in ("send.cm", "send.now", "sendit.cloud"):
                media = re.match(r"/(?:f/embed/|d/)?([0-9a-zA-Z]+)", destination.path)
                if media:
                    entry["existingResolverPage"], _ = read("https://send.now/" + media[1])
                    page = entry["existingResolverPage"]
                    if not page.get("challengePage") and not page.get("interactiveWidget"):
                        entry["actualBundledResolver"] = diagnose_send(media[1])
    except Exception as error:
        result["errorType"] = type(error).__name__
    print("VOD_ADVERTISED_HANDOFF " + json.dumps(result), flush=True)
