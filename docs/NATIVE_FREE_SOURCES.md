# Native free TV — implementation and verification

MovieHub resolves Pluto TV directly on Android. It does not bundle or start Masqueradarr,
Node.js, MongoDB, Chromium or the Rust proxy. An optional user-hosted Masqueradarr M3U export
remains separate from Xtream and personal M3U. Settings → Free channels contains all free-source
controls; the FreeLinks entry remains disabled and that repository is unchanged.

## Native Pluto TV

- Anonymous `/v4/start` establishes the actual IP region without location overrides. Its returned
  channel API supplies `/v2/guide/channels`. Only valid, unique channel IDs enter the catalogue.
- SQLite stores encrypted stable `https://native.moviehub.invalid/pluto/<id>` locators. They are
  internal identifiers, never fetched as websites. Favourites therefore survive token rotation.
- Resolve-on-play validates channel membership in the current regional catalogue and constructs
  the normal provider HLS request with the anonymous session and server-provided parameters.
  Ads, HLS keys and provider access checks are retained. No DRM circumvention is implemented.
- Sessions and regional catalogues are cached in memory for at most ten minutes. Signed stream
  URLs are never persisted. Native requests use HTTPS, eight-second connect/read timeouts,
  four redirect attempts and eight-MiB response limits. Bearer tokens stay on their original
  authority. Returned API hosts must be Pluto subdomains.
- The shared LivePlayer resolves on a bounded two-thread executor. Channel changes disconnect
  and interrupt the previous request. A generation check drops late results. Moving the same
  locator between the guide and fullscreen retains the player and the resolution.
- Existing duplicate detection and same-channel backup selection are reused. Failures move to
  the next copy; no unlimited retry loop is added. End-of-stream on Live TV also uses this path.
  A failed initial resolution waits for the initial backup lookup rather than losing fallback.
- Catalogue refresh uses the existing twelve-hour WorkManager schedule and atomic source writes.
  Network fetching occurs before the native database transaction. Failure keeps the last good
  catalogue. Automatic imports have a ten-minute cooldown after any completed attempt; manual
  refresh remains available. Disabled sources are removed even if another source is unavailable.
- `/v2/guide/timelines` is fetched in batches of 20 for 24 hours. Only display metadata is retained
  between batches, capped at 50,000 programmes. The guide is committed atomically after fetching;
  a failure keeps its previous data. Native and existing XMLTV listings refresh independently.
- LibVLC handles HLS adaptation and transport streams. No claim of 4K, HDR, surround or low latency
  is made. Native Pluto carries the tested browser User-Agent; other sources retain their rules.

The global free-channel choice applies to native Pluto as well as public lists. The default
is automatic only without a connected TV provider. The Pluto switch independently disables
that adapter. The popup shows the last successful native catalogue count; recovered failures
appear by operation/type in Troubleshooting, without URLs or tokens.

## Upstream audit and provider scope

Audit: public Masqueradarr commit `e77cc7d42bdf0abfe8fc14c2ed879b5991254cc5`.
The public root is MIT. Actual server adapters are a git submodule at
`iFlip721/masqueradarr-source`, commit `88804afdd13461cf4d5ab00cf4eabbf0b92c6e0d`.
That repository returns 404 to this environment and cannot be cloned. Its implementation,
dependencies and independent licensing cannot be verified. No code from that submodule is copied.
The public README, `docs/diagrams/_gen.mjs` adapter taxonomy and proxy Cargo manifest were inspected.
The public server architecture is Express/Mongoose control plane plus a Rust data plane and
optional streamed Chromium login; importing an M3U is not that implementation.

The following mechanisms are **described by upstream's public taxonomy**, not verified adapter
source code. Missing native support does not establish that a server is inherently necessary.

| Provider | Public upstream description | MovieHub status / remaining constraint |
| --- | --- | --- |
| Pluto TV | Regional boot session, on-play HLS, timelines | Native implementation; regional catalogue and media decoding verified below |
| Samsung TV Plus | jmp2.uk redirect to CDN per play | Not ported; third-party redirect is not a verified intended provider interface |
| Plex | Anonymous JWT, compound IDs, signed master, guide fanout | Not ported; current regional API/session and playback need independent verification |
| Roku Channel | Session boot, per-play ID, guide fanout | Not ported; device/session/territory requirements not verified |
| Tubi | Programming API → per-play JWT manifest | Not ported; current access/territory/DRM requirements not verified |
| Xumo Play | Three-hop broadcast resolution, market guide | Not ported; current market/device interface not verified |
| LG Channels | HLS macros expanded at play | Not ported; macro meanings, device entitlements and permissible client use not verified |
| DistroTV | VAST macros and tvg-id guide | Not ported; candidate public API probes returned 503; website probe served a landing page, not a usable catalogue |
| Vizio WatchFree+ | Direct channelUrls master and airings API | Not ported; current API access and device restrictions not verified |
| Vidaa | Catalogue-time macros and uid guide | Not ported; current API/device requirements not verified |
| STIRR / TCL / Whale / Free Live Sports | Play API or macros, source-specific guide | Not ported; no independently verified native interface in this change |
| dulo / DaddyLive | Captured authenticated session / multi-hop scrape | Not ported; no account capture or access-control workarounds added |

Those providers remain available only through an independently configured, authorised external
playlist/server if supported there. MovieHub does not advertise them as native adapters.

## Verification record — 19 October 2026

- Real anonymous Pluto bootstrap: successful; provider returned GB region.
- Native Java adapter smoke test: successful anonymous bootstrap, **187 channels**, valid on-play HLS and 20 channels in the first guide batch.
- Real regional catalogue: **187 channels**. This count is regional and time-dependent; it is
  not a count of channels all individually played.
- Resolve-on-play sample: **CSI: Crime Scene Investigation**, ID `6304f8d5ffb61000071ab478`.
  HLS master and media playlists returned successfully; normal HLS key/segment requests succeeded.
- Genuine media decoding: three downloaded provider segments with their normal HLS key were
  passed to FFmpeg's HLS demuxer. Six seconds decoded successfully (exit 0, 178 video frames),
  H.264 High, 1216×684 at 30 fps, AAC LC 48 kHz stereo. This was desktop decoding of actual media,
  **not Android/LibVLC or physical-TV playback**. An earlier direct FFmpeg network attempt timed out;
  downloading through the environment's supported HTTP route resolved the tooling issue.
- Provider guide: a 24-hour request for the sample returned 31 programmes successfully.
- Fixtures: NativeSourcesTest covers catalogue deduplication, invalid/incomplete responses,
  session host boundaries and lifetime, stable encrypted favourite keys, rollback and guide
  coexistence. PlaybackTest adds native resolution, cancellation, same-channel fallback and
  fullscreen/guide handoff without resolving again. Existing tests cover migration, local HLS/TS,
  guide navigation, movies and series.
- All 223 existing pure-Java core checks passed locally. The initial debug build, lint, unit-test task and instrumentation APK build passed. Final build/lint/device CI results are recorded
  in the PR; fixture success must not be described as provider playback.

Physical TV checks remain necessary: real Pluto playback, repeated channel changes, audio/subtitle
tracks, remote navigation, fullscreen → guide continuity, network loss/recovery, resource usage
and signed update installation preserving accounts/favourites. No physical box is available here.

References: [Masqueradarr](https://github.com/TheBinaryNinja/masqueradarr),
[Streamlink's current Pluto API usage](https://github.com/streamlink/streamlink/blob/master/src/streamlink/plugins/pluto.py).
This is an independently written provider client; no Streamlink ad-filtering code is included.
