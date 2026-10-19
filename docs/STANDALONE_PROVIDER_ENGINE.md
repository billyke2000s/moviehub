# Standalone provider engine — experimental Android port

This draft ports the available historical Masqueradarr provider code to Android. It uses no home
server. It is opt-in under Settings → Accounts → Free channels → Local provider engine. Main and
production releases are unchanged. Existing native Pluto, IPTV/M3U/Xtream and VOD remain intact.

## Source provenance and compatibility

Current public upstream SHA inspected: `e77cc7d42bdf0abfe8fc14c2ed879b5991254cc5`. Its server
submodule is `iFlip721/masqueradarr-source` at `88804afdd13461cf4d5ab00cf4eabbf0b92c6e0d`.
That repository returns 404 with available access. This blocks current-version parity and automated
retrieval, **not** implementing the available code locally. A server dependency alone is not a reason
to exclude a provider.

The port uses public MIT source snapshot `3b860294ecb4a9ebca8819adfc061162fbbb7f99`. Files under
`provider-engine/upstream/` retain original bytes. FreeLiveSports and the FAST leaf modules execute
as compiled JavaScript. DaddyLive's directory parser, extractors, multi-player resolver, transport
classification and per-operation player/cache functions are bundled together. Tubi's actual signing,
PKCE, anonymous device-token, catalog and resolve modules run together. Samsung's four catalog,
normalization and resolution functions are selected from its adapter AST; server database/EPG hooks
are not executed. The Java segment-prefix port names its Rust upstream provenance. MIT notices ship
in the APK; Rhino 1.9.1 is MPL-2.0 and TypeScript 5.7.2 is build-time Apache-2.0.

A full Node/JNI runtime is not embedded: the inspected nodejs-mobile core was Node 18.20.4 (EOL).
The alternative is an explicit compatibility layer, rather than replacing the providers with playlists.
Native Chromium login, filesystem snapshots, MongoDB writes, arbitrary Node/native packages and the
Rust ingest/remux machinery still need separate Android implementations where required.

## Runtime 2

- TypeScript is downlevelled to CommonJS/ES5. An audited import registry rejects new dependencies.
  Node crypto supports UUID/random bytes, SHA-256 and HMAC-SHA256; Java bounds base64, UTF-8, gzip and
  zlib operations. URL and URLSearchParams supply the subset used by these adapters.
- `ProviderScript` runs in interpreter mode in `ProviderService`, an unexported Android isolated UID
  with no app files/accounts or INTERNET permission. Java classes are denied. Each evaluation has a
  three-second instruction deadline (eight seconds for stream resolution), 128-frame limit and ten-second process watchdog (15 seconds for resolution). Process teardown
  discards the heap. This is not a complete engine security audit or physical ARM64 performance result.
- Async/await executes through a microtask queue and deterministic HTTP replay. An operation yields
  pending requests; the host fulfils them and resumes by replaying recorded responses without repeating
  completed network requests. A fresh seed per operation gives fresh signatures/macros; recorded completion
  times advance the clock reproducibly. DaddyLive player preference/failure hints are retained in a bounded 256-channel, five-minute host cache and restored through upstream player-memory functions; playback failures retire the current player. Cookies and anonymous tokens remain in operation-local JS/replay
  state. Bridge IPC temporarily serializes these values into app-private cache files passed by descriptor and deletes them on completion; there is no durable token/session store. Anonymous bootstrap may therefore repeat on the next channel.
- `ProviderBroker` limits network work to 64 GET/POST requests, 3 MiB per response and 4 MiB aggregate
  response data. Catalogue work has a 240-second deadline and playback resolution 45 seconds, with channel
  cancellation and bounded per-hop HTTP timeouts. Timers are bounded to three seconds per request.
- API host capabilities are fixed in the APK and checked on redirects. DaddyLive explicitly has the
  broader public-HTTPS-GET capability because its iframe/CDN hosts rotate; it cannot POST or receive app
  credentials. All paths reject userinfo, insecure schemes, nonstandard ports, localhost/private DNS targets
  and header injection. DNS checking is not DNS pinning; rebinding remains a security limitation.
- `AdapterSources` writes encrypted stable locators into existing SQLite channel storage; category groups,
  favourites and source fallback remain in MovieHub. Base64url locator ids support compound upstream ids.
  Each provider refresh is atomic and a failure keeps its previous rows. Ten-minute per-provider caches
  invalidate on refresh/package changes. LG and Tubi inline guides have independent source scopes retained
  through XMLTV refresh. Separate remote guides for other adapters are not ported yet.
- Direct resolved streams go to LibVLC. `ProviderRelay` is used only when additional headers are required,
  or for DaddyLive's transport. It binds loopback on an ephemeral port, uses random per-session grants,
  rewrites relative playlist/URI attributes, preserves required headers and byte ranges, and streams media
  through two workers with a bounded queue and 2,048 target entries. It closes on stop/channel cancellation;
  fullscreen/mini-player handoff retains the same session. Credential headers cannot cross the original
  stream authority. A bounded Java port of upstream's five-packet/RIFF prefix detection handles disguised TS.
  Full upstream ingest/remux, keyframe repair and transport-stream normalization are not implemented.
- Settings expose individual opt-ins, channel counts, last successful refresh, failure state, package
  version, refresh/update actions and rollback. All experimental adapters default off.

## Provider status and live evidence

Counts are desktop discovery results through the same compiled modules and Java interpreter; they
are not assertions that every channel plays or that a real TV has passed playback tests.

| Provider         | Android port                                                                     | Live result during this work                                                                                                                                             |
| ---------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Pluto TV         | Existing native adapter preserved                                                | Earlier GB discovery: 187; desktop media sample decoded                                                                                                                  |
| FreeLiveSports   | Runtime 1 compatible module retained; relay now supports Origin                  | Earlier 108 channels / 12,047 guide entries; one desktop sample decoded                                                                                                  |
| STIRR            | Upstream catalog and POST resolver                                               | 123 channels; fresh stream resolved                                                                                                                                      |
| TCL TV+          | Upstream category walk and POST resolver/fallback                                | 406 channels; live playback still unverified                                                                                                                             |
| Xumo Play        | Upstream catalog, broadcast/asset resolution and macros                          | 434 channels; fresh stream resolved                                                                                                                                      |
| LG Channels      | Upstream compressed catalog, inline guide and macros                             | 188 channels; fresh stream resolved                                                                                                                                      |
| Whale TV+        | Upstream anonymous token/catalog and macros                                      | 138 channels; fresh stream resolved                                                                                                                                      |
| Vizio WatchFree+ | Upstream catalog, entitlement/DRM filtering and macros                           | 465 surfaced channels; fresh stream resolved                                                                                                                             |
| Plex             | Upstream anonymous bootstrap/catalog/tokenized resolution                        | 691 channels; fresh stream resolved but sampled media returned HTTP 404                                                                                                                                  |
| DaddyLive        | Upstream directory, decoder chain and multi-player resolver                      | 899 channels; fresh stream and a desktop sample decoded after downstream failures; Android playback unverified                                                                  |
| Tubi             | Upstream signed bootstrap, catalog, per-play resolution and inline guide mapping | 36 channels; fresh stream resolved and a desktop media sample decoded                                                                   |
| Samsung TV Plus  | Upstream functions, gzip catalog and per-play redirect; GB catalog               | 252 GB channels; fresh stream resolved and a desktop media sample decoded                                                                                    |
| Roku Channel     | Upstream cookie/CSRF session, catalog, playId and POST playback                  | Live bootstrap returned HTML without usable session; **not verified playable**                                                                                           |
| DistroTV         | Upstream feed, geographic flow and macros                                        | Feed returned HTTP 503 from this environment                                                                                                                             |
| Vidaa            | Upstream tenant/bootstrap, regional catalog and filters                          | Configuration endpoint returned HTTP 503 from this environment                                                                                                           |
| dulo             | Not yet ported                                                                   | Device-bound authenticated Supabase sessions and the real browser login must be implemented on Android; no authorized account/device session is available to validate it |
| Local Now        | Not yet ported                                                                   | No implementation exists in the accessible historical snapshot; current private source is unavailable                                                                    |
| HDHomeRun        | Not yet ported                                                                   | LAN discovery and TS transport require separate Android work; historical upstream marks playback dormant                                                                 |
| Direct imports   | Existing M3U/Xtream retained                                                     | Independent of the JS port                                                                                                                                               |

Geographic/entitlement/DRM filtering is retained. No geo override, login bypass or DRM bypass is
implemented. Ports with unavailable live endpoints stay opt-in and report failure rather than import
invented snapshot streams. Source updates may be needed for historical endpoints that have changed.

## Signed automatic updates

Runtime-2 packages contain the audited module set (runtime 1 remains readable). Maximum signed payload
is 1 MiB, authenticated over exact bytes by SHA256withRSA and an APK-pinned RSA >=3072 public key.
Validation rejects unknown modules and native capabilities; candidates undergo actual isolated Android
runtime checks before atomic activation. Independently verified active/previous slots and monotonic
high-water version state provide rollback and downgrade protection. Explicit rollback pauses updates.
An older runtime-1 package cannot remove newer APK-bundled adapters; missing modules use that baseline.

The update workflow retrieves **all** required source files at the exact upstream gitlink, verifies the
audited MIT licence, compiles without running provider code on Node, runs actual JVM fixtures and signs
only if tests pass. Partial retrieval, unknown imports, changed adapter functions or incompatible packages
fail closed. Existing packages remain installed. The host checks during channel refresh at most every
12 hours or on request. Provider-only releases use `--latest=false`, never the production APK feed.

**Remote updates remain disabled:** `trust.json` has no public key. Maintainers must configure the
public key in the APK, `PROVIDER_SIGNING_KEY_B64`, authorized `MASQUERADARR_READ_TOKEN` source access,
and `PROVIDER_UPDATES_ENABLED=true` after review. No keys/secrets or publishing settings were changed.
New unseen providers still need an audited wrapper/capability contract and tests; this is not automatic
parity with an inaccessible repository or arbitrary new Node/native dependencies.

## Validation

- Existing 223 core checks must remain green.
- The JVM checks run actual Rhino, Java-denial/deadline/signatures/codec/URL logic and segment prefix rules.
- `runtime-check.mjs` invokes Java (never executes candidate modules on Node). Fixtures exercise module
  metadata, STIRR POST/nonce, Xumo broadcast/asset/macros, LG compressed guide, Vizio filtering, a 36-category
  TCL async walk plus POST resolve, DaddyLive directory/iframe/base64/HLS resolution, Tubi signatures checked
  against Node crypto, and Samsung gzip/DRM filtering. Fixtures prove compatibility, not live availability.
- `ProviderAdaptersTest` adds real isolated Binder POST-resolution fixtures, capability/locator rejection,
  and a real loopback relay socket using fixture upstream connections, including headers and cleanup.
- Build/lint, instrumentation and CI outcomes are recorded in PR #83. Do not carry earlier green run
  results forward as evidence for changed code. Physical S905X5M startup/RSS, rapid switching, network loss,
  live playback, guide handoff and installation remain unverified. There is no local accelerated emulator.

### Additional desktop media checks

Fresh STIRR, Xumo, LG and Whale masters and variants returned HTTP 200. One media sample from
each decoded two seconds successfully with FFmpeg (exit 0). Sample sizes were 439,356; 3,286,616;
363,780; and 355,132 bytes respectively. Vizio's first sample returned HTTP 400. These desktop
checks do not establish Android/LibVLC playback or availability of every discovered channel.

Tubi and Samsung GB also returned valid masters, variants and media samples; FFmpeg decoded two seconds
from 2,063,300 and 573,024 bytes respectively. Plex resolved a URL whose manifest returned HTTP 404.
Live redirects revealed Samsung's catalog redirect to its specific public GitHub mirror and Tubi's
Adrise CDN redirect; host capabilities now allow those observed paths rather than failing after discovery.

The initial DaddyLive live walk also hit the interpreter's three-second CPU deadline on a large embed.
The stream-resolution budget is now eight seconds per evaluation (with a 15-second process watchdog),
while other operations retain the shorter limit. Cached replay completed in 2.07 seconds after the codec
improvements; this does not establish a playable DaddyLive stream.

After extending the bounded decoder budget, the DaddyLive live walk resolved a fresh stream through a later
player. Its original default-player failures are retained as evidence rather than treated as proof that the
provider cannot run locally. Real media and Android playback remain separate checks.

DaddyLive's resolved media playlist returned HTTP 200 and a 793,924-byte TS sample decoded two seconds
with FFmpeg exit 0 (it logged an initial H.264 buffering/SPS warning). This is one desktop sample, not
proof of all 899 channels or Android playback. The regression fixtures also verify restored preferred
player selection while a failed player is excluded.

Vizio's first URL also decoded after percent-encoding the literal braces left by upstream macros, as
`ProviderUrls` now does: master and variant HTTP 200, 111,672-byte media sample, FFmpeg exit 0.
Eight newly ported providers therefore have one decoded desktop sample each. The first Runtime-2 CI
run passed provider verification, APK/lint, signing compatibility and the full emulator suite:
https://github.com/billyke2000s/moviehub/actions/runs/39335954185 . Follow-up fixes need their own run.

The final local checks passed: 223 existing core checks, 21 runtime/signature/URL/segment checks,
25 multi-provider JVM fixtures and three compiler fixtures. Debug APK, lint and instrumentation
compilation passed. All 28 vendored TypeScript files match the historical upstream bytes and the
package provenance hashes. The APK contains ARM64 LibVLC libraries; the provider runtime is pure Java.
This is ABI packaging evidence, not physical ARM64 execution.
