# Standalone provider engine — experimental, incomplete

This branch adds real local JavaScript execution and a FreeLiveSports adapter. It is **not full
Masqueradarr parity** and must not be released as such. No external server is used by this path.
FreeLiveSports is opt-in under Settings → Accounts → Free channels → Local provider engine.
Existing native Pluto, IPTV accounts, public lists and VOD remain separate.

## Source audit and hard blockers

Current public upstream: `e77cc7d42bdf0abfe8fc14c2ed879b5991254cc5`. Its `server` gitlink points
to `iFlip721/masqueradarr-source` at `88804afdd13461cf4d5ab00cf4eabbf0b92c6e0d`. The connected
GitHub account receives 404 for that repository. The current provider code, dependency tree and
licence cannot be inspected. Automatically inheriting **current** providers is therefore blocked
by authorised source access, not by the TV box's RAM. Public README descriptions are not source.

Last public server snapshot: `3b860294ecb4a9ebca8819adfc061162fbbb7f99`, before the private move
`cd7b6ca6f7e48ab59e1c1179f754c07da02a1e32`. Its source registry, adapter contract, FAST template,
leaf modules and package dependencies were inspected. `provider-engine/upstream/freelivesports.ts`
is copied unchanged from that snapshot; its MIT notice is retained alongside it. The compiled
asset records both the upstream SHA and source SHA-256. PRs #80 and #81 are merged in the baseline.

The full historical server depends on Express, Mongoose/MongoDB, Undici, Puppeteer/Chromium,
filesystem snapshots, settings services and a Rust proxy. Its `SourceAdapter` is not a standalone
plugin API: normalisation, after-sync hooks, EPG writes and resolution reach those services.
Pretending those imports work with empty stubs would conceal failures. The reusable
FreeLiveSports leaf instead supplies real parsing and per-play macro functions without rewriting
those functions in Java. Server DB/EPG hooks are replaced by MovieHub's existing SQLite writers.

The latest published nodejs-mobile core release inspected is **18.20.4**, with ARM64, ARMv7 and
x86_64 libraries. Node 18 is end-of-life. This branch does not bundle that obsolete runtime.
A supported Node build plus JNI, Android ABI/page-size validation, service lifecycle and a safe
broker would require further work. Rhino 1.9.1 (MPL-2.0, pure Java) is used as the constrained
alternative. It has no native ABI dependency, so the same code targets ARM64 and x86_64; actual
ARM64 TV execution is still unverified. TypeScript 5.7.2 (Apache-2.0) is build-time only.

## Implemented architecture

- TypeScript compiles to CommonJS/ES5 in `provider-engine/tools/build.mjs`. Only the audited
  leaf and `node:crypto.randomUUID` are supported; new import dependencies fail compilation.
- `ProviderService` runs under an Android **isolated UID**, without INTERNET or app account/file
  access. Rhino starts in interpreter mode with safe standard objects, Java class denial, a
  128-frame interpreter stack and a three-second instruction deadline. The only host callback
  produces UUID strings. `fetch`, filesystem, sockets, subprocesses, Java and native modules are absent.
- `ProviderBridge` sends JSON through temporary file descriptors rather than large Binder
  payloads. Calls run off the UI thread, serialize provider work, have a startup timeout and a
  ten-second unbind watchdog. Unbinding terminates the isolated process and discards its heap.
  A process death or OOM fails the operation without taking the player/app process with it.
  This is not a claim of a complete JS-engine security audit.
- Discovery HTTP stays in the host's cancellable, bounded HTTPS transport. FreeLiveSports can
  request only its pinned Unreel catalogue host/path. No provider code receives account secrets.
  Redirects do not carry custom headers to another authority. Catalogue requests are bounded
  at eight MiB; package bytes at 256 KiB; provider rows at 5,000; guide programmes at 50,000.
- The upstream parser sorts and deduplicates the real catalogue and retains inline EPG. SQLite
  stores encrypted stable channel locators (`enginefls` source), so refreshed macros do not change
  favourite keys. Catalogue writes are atomic; failure preserves the previous rows. Provider EPG
  commits independently and coexists with Pluto and XMLTV without cross-matching names.
- Resolve-on-play selects a channel from the fetched catalogue and runs upstream macro expansion
  afresh. LivePlayer preserves its stable locator across fullscreen/guide handoff and uses the
  existing same-channel fallback. On a failed/ended FreeLiveSports stream it refreshes once before
  normal fallback. Channel cancellation drops late results and disconnects HTTP. No film rules
  are added to Live TV.
- LibVLC 3 receives User-Agent and Referer. The complete upstream header object is retained in
  the resolution result. **Origin and arbitrary per-segment headers are not supported by this
  LibVLC path**; channels requiring them remain unsupported pending a bounded local proxy or a
  verified transport option. No proxy, segment unwrapping, DRM bypass, geo override or ad removal
  is implemented. Initial playback URLs reject local/private DNS results; this preflight is not
  DNS pinning and does not constrain every LibVLC HLS child request.
- Settings expose opt-in, refresh, last successful catalogue, runtime/package version, update
  status, manual checks and rollback, using the existing sidebar and cards.

## Signed updates and what is not automatic yet

`ProviderSignature` verifies a detached SHA256withRSA signature with an APK-pinned RSA public key
(minimum 3072 bits) over the **exact package bytes**, before parsing/evaluation. Runtime and module
capabilities are validated. Health checks run the candidate in the actual Android isolated
runtime before installation. Atomic active/previous slots retain independently verified packages.
A durable monotonic high-water version rejects network downgrades. Explicit rollback can restore
a previous signed package (or the APK baseline) but cannot lower that high-water mark; it pauses
automatic updates. Corrupted active data falls back to a verified previous package, then the APK.

**The trust asset intentionally has no public key. Remote code updates are disabled.** No private
key is generated, committed or silently trusted in this work. Maintainer setup requires:

1. Generate an RSA >=3072 key offline. Put only base64 DER/SPKI public bytes in
   `assets/providers/trust.json`; retain the private key in GitHub Actions secret
   `PROVIDER_SIGNING_KEY_B64` (base64 PEM private key).
2. Provide authorised source-read access as `MASQUERADARR_READ_TOKEN` if the private submodule's
   owner grants it. The pipeline follows the exact gitlink, never a guessed public fork.
3. After review, set repository variable `PROVIDER_UPDATES_ENABLED=true` and merge the workflow.
   Schedules run only from the default branch. No setup is performed by this draft PR.

`provider-engine.yml` verifies reproducible compilation, fixtures and the real interpreter/signature
checks on PRs. Its gated 12-hour update job retrieves the pinned current leaf, rejects new imports,
compiles without executing provider code on Node, tests it in the restricted interpreter, signs
only with the matching pinned key, and publishes versioned provider releases plus a latest feed.
A missing/incompatible module, inaccessible source, failed test, key mismatch or failed signature
retains the existing installed package. The Android host checks that feed during scheduled channel
refresh (at most every 12 hours), or manually. No production APK is published by this workflow.

Runtime v1 supports **FreeLiveSports only**. New provider modules are not automatically enabled.
There is no trustworthy universal discovery interface in the accessible upstream snapshot.
Additional adapters need an audited host capability contract, a wrapper and tests; full Node,
Chromium, MongoDB, native modules and arbitrary ESM do not work. Claiming automatic parity or
"all new providers work" would be incorrect. Inaccessible current upstream remains the primary
blocker even after a signing key is configured.

## Provider status

| Provider | Implementation / status in this branch | Exact remaining constraint |
| --- | --- | --- |
| Pluto TV | Existing native adapter retained | Prior live evidence in NATIVE_FREE_SOURCES.md; physical TV checks outstanding |
| FreeLiveSports | Local upstream JS discovery/parser, inline EPG, fresh resolution, SQLite and player wiring | Real desktop catalogue/media evidence below; Android bridge/TV playback must pass checks; Origin-dependent channels unsupported |
| DaddyLive | Not implemented | Current scraper private; historical multi-hop embeds, player memory, dynamic hosts and disguised segments require further compatibility/transport work |
| dulo | Not implemented | Authenticated device-bound sessions, settings and streamed Chromium login unavailable in runtime v1; needs authorised account testing |
| Local Now | Not implemented | Rotating market-scoped resolver and US access; no current provider source/interface validation |
| Tubi | Not implemented | Signed device/session and per-play API, guide hooks; regional/DRM rules must be preserved |
| Xumo | Not implemented | Three-hop API and market guide need current source and host API integration |
| STIRR | Not implemented | One-hop POST resolver plus guide fetch; runtime v1 has no generic POST/broker interface |
| TCL | Not implemented | POST format-stream-url resolver plus guide; same missing broker capability |
| Roku | Not implemented | Session/bootstrap/playId pipeline unverified; prior probes returned HTML |
| Plex | Not implemented | Anonymous bootstrap/per-play JWT interface unverified; prior probe returned 404 |
| Samsung | Not implemented | Gzip community catalogue and third-party redirect resolution need bounded host capabilities |
| LG | Not implemented | Compressed catalogue requires zlib/Buffer; regional interface not verified |
| Whale | Not implemented | Token bootstrap, per-play macros and separate guide require broker and regional validation |
| Distro | Not implemented | Feed/geo flow, URL APIs and guide require further capabilities; prior live feed probe failed |
| Vizio | Not implemented | US/account availability requirements not validated for intended Android client; no foreign-region enablement |
| Vidaa | Not implemented | Device/domain/tenant configuration and guide interface not verified |
| direct imports | Existing M3U/Xtream retained | Not a new provider engine adapter |
| HDHomeRun | Not implemented by JS engine | LAN discovery/TS remux are outside runtime v1; upstream itself marks playback dormant |

## Evidence — 19 October 2026

- All **223 existing core Java checks** pass locally.
- **11 new JVM checks** pass using Rhino 1.9.1 with a 96 MiB heap: actual compiled catalogue and
  resolve functions, absent Java/network globals, denied Java execution, non-catchable deadline,
  unsupported operation, valid RSA signature, tampered bytes/signature, oversize package and weak key.
- **3 Node compiler fixture tests** pass: ordering/deduplication/guide fields, macros/headers and
  rejected operation. These are fixtures, not provider playback.
- Real FreeLiveSports HTTPS catalogue returned **108 channels / 12,047 programme entries**.
  The actual compiled JavaScript parsed it in Rhino with a 96 MiB JVM heap, producing ~3.24 MB
  normalised JSON. That heap setting is not an Android RSS measurement.
- One real sample's master, variant and ~2.15 MB MPEG-TS segment returned successfully with
  User-Agent/Referer and without an Origin header. FFmpeg decoded three seconds locally, exit 0.
  This validates one desktop sample, not all 108 channels or Android/LibVLC playback.
- Instrumentation includes real Binder/isolated-runtime calls, failure recovery, signed install,
  tamper/downgrade rejection, explicit rollback, high-water preservation, corrupt-slot recovery
  and incompatible-runtime/locator rejection. CI results belong in the PR; added tests are not
  reported as passed until they actually run.
- No physical S905X5M box or accelerated emulator is available locally. Required hardware checks:
  ARM64 startup/RSS, rapid zapping, HTTP cancellation, EPG/favourites, network loss, expired URL
  retry, fullscreen/mini-player handoff, Android service cleanup and signed package installation.

The PR is deliberately a draft until build, lint, device tests and the stated blockers are resolved.
