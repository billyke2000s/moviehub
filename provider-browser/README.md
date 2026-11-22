# MovieHub Playwright provider inspection

This is an **external diagnostic**, not part of the Android APK, a video proxy, or
an automatically deployed extractor. It drives Chromium against public provider
pages, observes DOM server controls and browser network behaviour, and records
only redacted request signatures. The app's existing source search, player,
signed updater, account handling and failover are unchanged.

## Run on a disposable machine / CI worker

Node.js 22+, Python 3.11+ and Chromium dependencies are required. Use a
disposable, network-isolated VM/runner. **Do not run a public-website browser
with access to Proxmox, your router, internal services or personal credentials.**

```bash
npm install --prefix provider-browser
./provider-browser/node_modules/.bin/playwright install --with-deps chromium
python -m pip install requests==2.32.5 six==1.17.0

node --test provider-browser/tests/*.test.mjs
python provider-browser/inventory.py --kind live --shard 0/1 > /tmp/moviehub-live.json
node provider-browser/inspect.mjs --targets /tmp/moviehub-live.json --output /tmp/moviehub-inspection

python provider-browser/inventory.py --kind vod --shard 0/4 > /tmp/moviehub-vod.json
node provider-browser/inspect.mjs --targets /tmp/moviehub-vod.json --output /tmp/moviehub-vod-inspection
```

With a previous report, add `--baseline /path/to/previous/report.json`.
The output includes a JSON evidence file and a readable Markdown summary;
both are intentionally stripped of query values, signed media addresses,
request bodies, cookies, authentication and screenshots. Only public source
origins are admitted. Browser-generated **media requests are aborted** to
avoid downloading video. Observing an endpoint is not playback verification.

The inventory includes known public Live TV website landing pages and the
enabled film/series site roots. It also inspects two concrete public Live TV
watch-page fixtures: DaddyLive's channel 302 (up to four player-button clicks)
and FreeShot's Sky Sports Action page (passive iframe observation).
The separately provided **daddylive.app** is a different live-event service:
its page documents /api/events and rotating /player/embed.php?id=...&source=tvN
links. The inspection resolves one currently advertised Live Events embed at
runtime and records only safe route metadata, never the returned embed URL,
token or media payload. This does not add a playable source to the APK.
The PrimeWire movie watch-page fixture remains a clickable VOD target. These
fixtures help catch changes that homepage-only checks missed. Media requests
are still aborted, so they cannot establish decoded audio or video. ZLive's
landing-page requests also remain **diagnostic only**, not stream verification;
its live stream protocol must be checked separately.
Private Xtream/M3U, Nuvio, Masqueradarr or Premiumize accounts are never
inspected by the GitHub Actions job.

## Automation

`.github/workflows/playwright-provider-inspection.yml` runs every two hours
for its public Live TV browser targets and every twelve hours for VOD site
checks (four shards). It attempts to load the previous successful CI artifact
as a baseline and reports added/missing host/route/media patterns. A source
with changed JavaScript, a CAPTCHA or an unavailable media backend may still
be unresolvable. **No executable provider code is generated, installed or
published automatically.** Implementing new extractors requires reviewed code,
content/episode identity validation, decoded playback testing and a valid
signed update channel. Without those steps, this tool is an investigation
layer, not an autonomous repair agent.

App-level DNS checks are not DNS pinning. For additional assurance, restrict
outbound network access at the VM/container boundary and deny cloud metadata
and all private network ranges. Do not put API keys or browser cookies in a
public inspection job.
