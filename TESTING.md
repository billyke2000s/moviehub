# Testing

Channel identity and database tests cover backup names, normal-feed priority and regional separation;
Live guide device tests cover current-channel focus, CH+/CH- stepping, page wrap and preview placement.
Combined-release checks run on the release PR and in the signed publisher.

Older, dated sections are kept in [docs/history/TESTING.md](docs/history/TESTING.md).

## Unreleased: Live TV failover keeps working sources

Core checks: `LiveBufferingTest` (stuck after 12 s unbroken; poor after 12 s buffering in a minute;
ten half-second pauses a minute reach neither; window edges; poor counts afresh; a stall in progress
keeps its start), `LiveStallRulesTest` (verdicts: brief stalls never acted on, frequent pauses poor
but not stuck, stuck at 12 s, dead stream and reconnect budget unchanged), `LiveUpgradePlanTest`
(replacement choice, cooldown, fallback and keeping), `LiveRetryPlanTest` (growing waits, ZLive
minimum, last working copy remembered only after 10 s and tried every other time, reset on a held
picture and on another channel) and `LiveProbeTest` (playlist parsing, plus a local HTTP server:
media and master playlists, plain streams, missing playlists or segments, a web page instead of a
playlist, empty answers, refused schemes, headers sent) and `LiveStandbyCheckTest` (a background
copy is ready only after 1.5 s of unbroken playing; a break or a jump back restarts the count; 10 s
limit, including a stuttering copy that never gets there).

Device tests changed: `ExoLiveTest.aSourceThatKeepsBufferingMovesToTheNextSource` now also checks the
working copy was loaded in a second player before the switch and that player took over (no
break-before-make failover); `ExoLiveTest.unavailableZliveKeepsRetryingWithGrowingWaitsAndAllowsAChannelChange`
replaces the test that required ZLive to stop after its sources were exhausted;
`aBetterCopyThatKeepsBufferingFallsBackAndCoolsDownAcrossChannels` allows for the pre-switch check's
own segment request. These fixtures do not prove behaviour with real providers or on a TV box.

## Unreleased: over-the-air scrapers and server tooling

- `ScraperBundleTest` (core checks): package validation, path/loader refusal, compatibility and
  update-address rules.
- `test_scraper_loader.py` (Android CI, free VOD step): a package is used including subpackages;
  broken packages fall back to the APK's scrapers. Desktop CPython only, not Chaquopy on a device.
- `server/gate/test_gate.py`, `server/health/test_check_sources.py`: run with `python3`.

## Unreleased: Playwright provider analysis

`provider-browser/tests/protocol.test.mjs` contains offline network target restrictions, private IPv4 rejection, token/path redaction, signature comparison and inventory limits. The `playwright-provider-inspection.yml` PR job installs pinned Playwright 1.65.1 with headless Chromium, runs offline tests, creates a public target inventory and inspects public pages (one live and one VOD shard in PR). Scheduled checks subsequently cover the selected live websites and the complete enabled VOD registry in four shards. A browser page load, observed media request or report containing new patterns does **not** establish working playback or a repaired extractor.

The standalone Playwright [PR #230](https://github.com/billyke2000s/moviehub/pull/230) passed its [GitHub Actions inspection run](https://github.com/billyke2000s/moviehub/actions/runs/44975213357), including four offline protocol fixtures and actual headless browser inspections for the live and first VOD shard. The other three PR shard jobs intentionally skipped the browser steps. The inspected PrimeWire page had an HTTP/navigation failure; neither that nor the passing job establishes playable media. The browser tooling was merged separately from the app changes. No real TV-box playback was verified.


## Unreleased: expanded monitoring verification

The scheduled live probe runs all built-in provider-module IDs across four independent groups; the separate public-TV workflow runs a network-free parser/Pluto allow-list fixture before the public catalogue checks. These workflows report upstream HTTP/network errors and bounded catalogue/media evidence without exposing signed stream URLs or tokens. Their checks are not proof that every TV channel plays in the UK.

Provider-engine failures queue a signed update check outside the playback path, subject to the global 15-minute cooldown and configured trusted public key. Validate the final Android CI build/emulator tests and inspect the PR before merge. No current live device proof or signed OTA test with a configured key is claimed.


## Unreleased: PrimeWire and provider drift diagnostics

`android-tv-launcher/tests/test_primewire.py` checks the movie slug route, strict title/year evidence, multiple explicit player links, duplicate controls, unavailable AJAX-only buttons, and unsafe/insecure URL rejection using offline fixtures. `VodSourceIdentityTest` tests that two catalogue routes returning the same URL/headers collapse into one source but header- or token-distinct fallbacks survive. Existing `test_free_vod.py` inventory counts include the additional movie-only site.

The Live provider probe and full free-VOD site audit now also have schedules; their artifacts are diagnostics only. The PrimeWire domain did not return inspectable live page HTML through the available tools, so the new adapter has **not** been verified against its current HTML, let alone decoded on a real Android device. CI and device tests must run on the final PR head before merge.


## Sports loader regression checks — 1 November 2026

`ProviderPngSegmentsTest` covers the current image-prefix layout: 16 decorative rows, headers crossing
scanlines, RGB/RGBA, all five PNG filters, the one-MiB scan boundary and refusal beyond that boundary.
All 39 PNG checks and nine carrier checks pass locally. A current Sky Sports F1 backup segment failed
before the change with `Unknown PNG payload`; the corrected production decoder recovered its TS and
FFmpeg decoded four seconds with 200 video frames and 188 audio frames. The inspected frame showed
Sky Sports F1 coverage. This proves the sampled media/decoder path, not sustained Android playback,
availability of other channels, or restoration of DaddyLive/FreeShot.

New fixtures decrypt ZLive v6 requests using the current public client key, resolve FreeShot on
both CDN families, and reject lookalike hosts, mismatched channel paths, userinfo and custom ports.
`PlaylistSessionTest` verifies new-CDN renewal and scope. `ProviderEngineDeviceTest` verifies
old signed sports modules fall back to bundled implementations until the matching capability exists.
`LivePlaylistPolicyTest` checks short completed clips, legitimate live windows, master playlists,
longer recordings and invalid durations. The production relay fixture checks policy scope independently
of VOD. These fixtures do not establish real provider availability.

Live inspection: FreeShot's WideIPTV Main Event configuration advertised the new CDN. An eight-second
FFmpeg sample decoded both tracks (573 audio/video frame rows); the inspected frame showed Sky
Sports Main Event's Toto Wolff interview, not a site-message card. FreeShot's outer channel/embed
pages returned Site Unavailable here, so the full current chain remains unverified. ZLive's updated
resolver returned HTTP 200; F1 UK2's media URL returned 404, while F1 UK3 returned a sliding HLS
playlist with wrapped segments. A downloaded UK3 segment passed the production carrier decoder and a four-second FFmpeg
audio/video sample; the inspected picture showed the actual Sky Sports F1 broadcast. Full live
relay playback was blocked here by upstream DNS lookup failure. This sample is not a sustained
Android playback test. All 35 core suites, 3 Node tests and provider verification passed locally
(21 engine checks, 48 multi-provider checks, 17 PNG checks, 9 carrier checks, TCP/DASH relay
checks and 6 fast-runtime checks). Android CI is tracked in PR #210.


## CineStream format/source regressions

`MediaFormatsTest` adds 19 core cases for native codec names, unknown/Off behaviour and honest
provider-report parsing. `test_cinestream.py` adds protocol, exact episode, backend identity,
header/token boundary, cancellation, locked-link and metadata fixtures. The full Python runner
has 144 tests; core has 34 suites / 1,167 checks. `PlaybackTest.trackFormatsAppendToNativeNamesWithoutChangingSelection`
uses the existing H.264/AAC fixture to verify real native track suffixes and unchanged selections.
These are fixtures; live decode evidence is recorded separately below and in the source report.
The live audit uses managed VOD startup and supports explicit pending providers. Normal Hexa
audits exercise Alpha; `vodCineStreamAllBackends=true` is an explicit diagnostic override. See the
[addition report](docs/CINESTREAM_ADDITIONS_2026_10_29.md) and final PR checks for current CI evidence.

Application revision `a9974b6` passed all six Android jobs in run 40856772393: build/lint/signing,
normal/experimental suites, public provider checks and focused VOD recovery. The regular report
has 209 entries: 208 passing plus the unchanged explicit-live-audit assumption (the HTML renderer
lists it as a failure although Gradle/CI succeeds). The new native track-format regression passed.
Experimental reports cover 7 + 20 unique cases, and focused VOD reports cover 3 + 5 + 1 + 1, with
no failures/skips. Two new Hexa Alpha links passed live audio/video/runtime/midpoint checks in
run 40856772425. VaPlayer diagnostics still crashed; these routes remain pending.



## Managed VOD regression coverage

`VodLinkPolicyTest` covers short/unknown/truncated runtimes, incompatible editions, dynamic backup
ranking, independent-host preference, inherited DASH BaseURL/templates, constrained substitutions,
XML entity rejection, protected manifests, nesting and representation limits. It runs in the existing core script.
`test_vod_sessions.py` covers contradictory provider identity, same-host rediscovery with rotated
headers/URLs, failed refresh and explicit epoch expiry. The Python media fixture permits a known
60-second short while rejecting it as a substitute for a 90-minute movie.

`VodSessionTest` covers distinct header identities, expiry-triggered refresh, retained logical
identity and explicit lookup provenance. `VodSessionPlaybackTest` exercises real LibVLC initial
managed opening, one expired-link refresh and cookie-protected DASH audio/video through relative
segment templates. The six-second DASH asset is regenerated from the existing synthetic playback
fixture; see its README. Full existing normal/experimental playback, cancellation, seek, short-clip
and series fixtures remain required. Live provider/physical-TV success is not implied by fixtures.

`provider-engine/tools/verify.sh` also compiles the DASH helper and runs
`ProviderDashTransportTest`: a real TCP relay round trip verifies inherited segment templates,
initialization/media requests and cookie isolation across advertised origins.

The focused emulator job explicitly selects `moviehubAbi=x86_64`, matching the full device suites;
release packaging includes both ARM app ABIs.

The `vod-recovery-tests` CI job runs both new session classes plus the existing repeated-buffering
and late-backup-after-short-clip regressions as a focused group. Each class/method filter runs
separately and its XML report must contain the expected cases with no failures or skips; a green
Gradle exit alone does not establish that every requested filter ran. The full normal and experimental
suites still run independently. Candidate handoff requires decoded video and position alignment;
audio is verified after selection by the DASH playback fixture rather than gating a muted decoder
on native audio counters. Numeric preparation diagnostics are attached to timeout assertions and
contain no source URLs, headers or credentials.

## Optimization checks

The signed publisher also runs the APK inspector before upload. Its default requires both ARM
ABIs, so the ARM64-only 1.0.17 APK is rejected. The published APK checks are recorded above.

The Android launcher workflow builds/lints dual-ARM debug and release APKs, inspects the actual
release APK for missing/extra architectures, unequal library coverage and retained debug sections, and runs the existing Android TV
fixture suites with an explicit x86_64 emulator build. The signed compatibility job still checks
the installed updater package and signing certificate without publishing.

`CatalogueCacheTest` covers expiry, LRU/byte bounds, concurrent request coalescing, unrelated-key
independence, failed-fetch retry and memory trimming during an in-flight fetch. ArtworkDiskCacheTest
checks oversized entries and compatible cache filenames. Existing Java/Python/Node checks remain.

```sh
bash android-tv-launcher/tests/run-core-tests.sh
gradle -p android-tv-launcher assembleDebug assembleRelease lintDebug
python3 android-tv-launcher/tests/inspect-apk.py android-tv-launcher/app/build/outputs/apk/release/app-release-unsigned.apk
ORG_GRADLE_PROJECT_moviehubAbi=x86_64 bash android-tv-launcher/tests/run-device-tests.sh
```

These fixtures do not establish physical-box installation, startup/RAM improvements or sustained
provider playback. The FreeLinks port and VOD changes are published; the completed 1.0.18
validation is recorded above. Physical installed storage and memory/performance improvements
have not been measured.

## Continuous series and automatic playback

`VideoFilesTest` includes episode ordering, future-date handling and source-resolution ranking. `SeriesAutoplayTest` uses catalogue and source fixtures to check season transitions, updated history keys, missing pack episodes, show endings, uncertain filenames, bounded source fallback and cancellation. `LiveGuideTest` covers colour keys from header focus and Back/arrow/OK controls; `LiveControlsTest` covers all four physical colours during playback, learned scan-code colours, and OK/click/◀ always showing the same banner. These do not verify a particular physical remote or real-provider playback.

There are four layers of checks. CI runs all of them on every pull request that touches
`android-tv-launcher/` (workflow: `.github/workflows/android-launcher.yml`).

| Layer                          | Where                                      | Needs                                | Runs in CI step                                              |
| ------------------------------ | ------------------------------------------ | ------------------------------------ | ------------------------------------------------------------ |
| Core rule checks              | `android-tv-launcher/tests/`               | Only a JDK                           | "Verify playlist, network, channel search and episode rules" |
| Build and lint                 | Gradle                                     | Android SDK 35                       | "Build APK and run Android lint"                             |
| Device tests (instrumentation) | `android-tv-launcher/app/src/androidTest/` | Android emulator                     | `device-tests` job                                           |
| Signing compatibility          | release build vs published APK             | signing secrets (same-repo PRs only) | `signed-compatibility` job                                   |

## Core checks (fast, run these locally)

Pure-Java rules for playlists, URLs, channel search, episode matching and combining film skips. They do not need Android.

```sh
mkdir -p build/core-tests
javac -d build/core-tests \
  android-tv-launcher/app/src/main/java/local/tv/home/{Playlist,Network,VideoFiles,EpisodeOrder,SourceRanking,ChannelQuery,EpgMatch,SkyNumbers,GuideOrder,SkipQueue,ErrorLog,SpeedTest,ChannelIdentity,ChannelCategory,ProviderFailure,LiveProgress}.java \
  android-tv-launcher/tests/{PlaylistTest,NetworkTest,VideoFilesTest,ChannelQueryTest,EpgMatchTest,SkyNumbersTest,GuideOrderTest,SkipQueueTest,TroubleshootingTest,ChannelIdentityTest,ProviderFailureTest,LiveProgressTest}.java
for t in PlaylistTest NetworkTest VideoFilesTest ChannelQueryTest EpgMatchTest SkyNumbersTest GuideOrderTest SkipQueueTest TroubleshootingTest ChannelIdentityTest ProviderFailureTest LiveProgressTest; do
  java -Xmx64m -cp build/core-tests local.tv.home.$t
done
```

Expected output: 17 playlist, 9 network, 110 episode/file, 13 channel-query, 18 programme-guide
matching, 17 satellite numbering, 15 guide order, 11 film skip, 13 troubleshooting and 36 channel matching/category checks passed.

`TrackNamesTest` (core) checks Live TV track names: two- and three-letter language codes
(including broadcast forms such as "fre" and "ger"), channel layouts, audio formats and stream
labels. The one-panel Audio & Subtitles layout is covered only by the existing device tests that
open the panel by its title; no test selects a track from it.

`PhoneEntryTest` (core) runs the phone page over local sockets: secret path, short address with
the 6-digit code (wrong codes refused and counted), 404/403/405/400 responses, 8 KB limit, invalid values kept on the phone, several boxes (all required, passwords
hidden and not trimmed), one set delivered, closing after use, after 20 tries, on expiry and when
the screen is left. A request with its final body byte held past expiry is refused, and closing
the page interrupts a partially received header. It does not show the QR code or test a real phone. `PremiumizeLink` has no
automated test: it needs Premiumize's live sign-in service.

`MenuWordsTest` (core) checks the family Help page's rules (`HelpChecks`): nothing shown for a
healthy box or when nothing is known; no internet, too slow for HD or 4K (and the lower quality
offered), storage full, update ready; every problem specific and with a fix. It also checks
`PlainWords`: the Live TV player's status and events in plain words (technical ones left out),
repeated source names numbered ("copy 2") without merging, and the "no stream found" line.

Device tests changed with the menu audit sheet: `SettingsStyleTest` (Setup tab; Free channels
opened from Setup), `DiagnosticsTest` (Engineer view returns to Setup; new Help page test),
`LiveGuideTest` (one-page guide menu, colour keys always buttons; the guide opens on the playing
channel and OK goes full screen, other lists and search start at the top, the preview's channel
name stays inside the preview), `TvNavigationTest` and
`PosterFeedTest` (one Browse sheet, no Library button, Premiumize not in Accounts), `AllAppsTest`
("Box settings") and `PlaybackTest` (no raw download speed on the player). None of them proves
the layout on a real TV box.

`PhoneScaleTest` (core) checks the phone layout's scale: the short side becomes 540 units for
720p to 1600p screens, a 1080p TV box keeps 320 dpi, and very small screens stop at 120 dpi.
`PhoneModeTest` (device) chooses the phone layout, follows the rebuilt screen to Home, checks the
sideways orientation and the applied density, and plays a channel from the guide with touch
buttons. The CI emulator has a TV's density, so it cannot show the pages shrinking.

## Device tests

`ChannelDatabaseTest.versionSevenWithoutMainUpgradesAndKeepsChannelsAndFavourites` reconstructs
an actual version-7 channels table without `main`, then opens it through the app's normal helper.
It checks guide rows, encrypted URL/key preservation, favourites, search and reopening. Merely
lowering `user_version` on a version-8 table cannot catch this regression.

Instrumentation tests drive the real app with D-pad key events on an emulator. They cover the
encrypted channel database, imports and migrations, LibVLC playback, Home and dock navigation,
catalogue paging and focus, the streaming menus and artwork loading, and Live TV: guide paging and
number entry, the Options button and guide menu, moving along programmes and through time, programme information, the PIN lock, reminders, channel changing and browsing in the
banner, the satellite-box overlay (banner, information panel, pause bar), pausing live TV, the
guide's live picture, and moving the live picture between the guide and the full screen without
reopening the stream (all played from a local test stream). Provider responses are local
fixtures, so these tests **do not** prove that a real TMDB, Premiumize or Xtream account works.

```sh
bash android-tv-launcher/tests/run-device-tests.sh   # needs a running emulator or device
```

The tests run through `MovieHubTestRunner`, which marks the first-start safety notice as read and
chooses the TV layout, so neither covers the screens under test; `SafetyNoticeTest` and
`PhoneModeTest` show them on purpose.

The script runs `connectedDebugAndroidTest` and then pulls the test screenshots off the device.
CI runs it on an API 28 `tv_1080p` emulator. Note that the CI step is named "Verify real SQLite,
encryption, migrations and pagination", but it runs **every** device test, so a failure in that
step can come from any test.

Some tests save screenshots (`UiEvidence.capture`) that CI uploads as the `android-tv-device-tests`
artifact.

### Device-test reliability

Until 17 October 2026 the device tests failed at random, even on unchanged code. Running the full
suite 8 times in parallel on CI found three causes:

| Cause                                                                                                        | Kind                                         | Fix                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Quick D-pad presses moved focus to a poster just past the edge; a layout then recycled it and focus was lost | **App bug** (users could lose the selection) | Poster rows and grids keep half a screen laid out beyond each edge, and refocus the same title if focus is still lost (`PosterFeed`) |
| A Back press sent while a dialog was closing reached no window and was dropped                               | Test timing                                  | `TvNavigationTest.back()` waits for window focus before the next key                                                                 |
| Controls were looked up once, immediately after a screen change                                              | Test timing                                  | `TvNavigationTest.select()` waits up to 5 s                                                                                          |

Results of 8 parallel runs of the whole suite:

| Code                                    | Passed    |
| --------------------------------------- | --------- |
| Before the fixes                        | 3 / 8     |
| After the focus fix                     | 4 / 8     |
| After keeping off-edge posters laid out | 6 / 8     |
| After the Back fix                      | **8 / 8** |

On 19 October 2026 a further cause was found (named by the improved failure reporter):
`LivePlayer.stop()` in `PlaybackTest.cleanup` released the shared live player while a closing
player screen still used it, crashing the app ("can't get VLCObject instance"). A crash ends the
run before results are written, which is why earlier failures named no test. The player now lets
go of a shared player that was released or replaced (`forgetReleasedLivePlayer`).

Eight passing runs are good evidence, not proof. If a device test fails, read its annotation: CI
lists each failed test with its message on the run (`tests/report-test-failures.py`), and timeouts
in `ArtworkTest` and `TvNavigationTest` describe the screen state when they fail.

### Catalogue rows

`CatalogueRowsTest` drives the Movies rows page with fixture data (`CatalogueRows.testLoader`, never
set outside tests): first poster focused on entry, no captions, the hero follows focus, each focused
row is pinned at the top, only the first rows load on entry, Up reaches the header, and Back from a
grid opened from the rows returns to the same row.

### Other feature tests (October 2026)

| Test                   | Covers                                                                                                                                                                      |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ForYouTest`           | For you ranking (shared recommendations rise, seeds and played movies hidden), trending fallback, caching until signals change, old seed migration. Fixture TMDB responses. |
| `AllAppsTest`          | Apps screen: Android shortcuts and Recently used shown, search box not focused on entry, search filters by name, hide and Show hidden. Uses the emulator's installed apps.  |
| `SidePanelTest`        | Side panel focuses and ticks the current choice and runs it after closing; `ErrorMessages` explanations never include provider text.                                        |
| `DiagnosticsTest`      | Timings and private error details; Troubleshooting retains the Settings sidebar, Back returns to System, and other tabs remain usable.                                      |
| `SecretsTest`          | Encrypted on disk, cached reads, replace and remove.                                                                                                                        |
| `ArtworkDiskCacheTest` | Artwork disk cache: hit, miss, least-recently-used trimming, oversized files skipped.                                                                                       |

## Tests call app code directly

Device tests live in the same package as the app (`local.tv.home`), so they call package-private
members directly, for example `activity.libraryScreens.toggleWatchlist(title)`. The compiler
checks every one of these calls, so renaming or moving a member breaks the test build immediately
instead of failing at run time. Do not use reflection to reach app members in tests; make the
member package-private instead.

### Channel copies and automatic backups (October 2026)

| Test                                                                         | Covers                                                                                                                                                                                                                                                                                                                                                                            |
| ---------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ChannelIdentityTest` (core)                                                 | Same channel across lists and providers ("Sky Sports F1 (720p)", "… UK", "… HD", "Sky Sports Formula 1"); kept apart: other countries, +1, regions, UHD, similar networks; provider categories into the guide sections (whole words, so "TF1" is not sport). Also run over the real Free-TV and iptv-org UK/US/IE lists (20 October 2026): five new merges, all the same channel. |
| `ChannelDatabaseTest.providerCopiesJoinTheSameChannelAndSharedSections`      | A provider copy becomes a backup of the free-list copy, keeps number 407, appears in Sports, no provider group.                                                                                                                                                                                                                                                                   |
| `ChannelDatabaseTest.versionEightKeysAreRecomputedKeepingFavouritesAndLocks` | Version 8 → 9 upgrade re-keys saved channels; favourites and channel locks kept.                                                                                                                                                                                                                                                                                                  |
| `PlaybackTest.skySportsF1FailsOverToAnotherProvidersCopy`                    | **The reported problem**: the free-list copy fails (404), the provider copy plays; "isn't working" is not shown.                                                                                                                                                                                                                                                                  |
| `PlaybackTest.failoverTriesEachCopyInTurnUntilOnePlays`                      | Two failing copies, the third plays.                                                                                                                                                                                                                                                                                                                                              |
| `PlaybackTest.whenEveryCopyFailsTheChannelSaysSoWithoutRetryingForever`      | Every copy tried once, then the message; no further requests.                                                                                                                                                                                                                                                                                                                     |

These use local fixture streams. They do not show that a real provider's stream plays on the TV.

## What no automated test covers

- Real provider accounts (TMDB, Premiumize, Xtream, M3U) and live provider availability.
- Installation and updates on the actual target TV box.
- How real apps' TV banners look on the Apps screen, and the update prompt's notes from a real feed.
- How the screens look on a real TV (screenshots from the emulator are a partial substitute).

Those need a manual check on the device before a release.

## Native source tests

`NativeSourcesTest` verifies provider JSON fixtures, partial-response rejection, regional stable IDs, secure session endpoints, database rollback/favourite preservation and native/XMLTV guide coexistence. PlaybackTest additionally exercises a fixture native resolver through LibVLC, cancelled channel requests, same-channel fallback and fullscreen/guide continuity with exactly one resolution. These run in the existing device job. Real Pluto catalogue, HLS and desktop decoding evidence is separate in [NATIVE_FREE_SOURCES.md](docs/NATIVE_FREE_SOURCES.md); it does not establish TV playback.

The native-source rollout exposed a legacy migration regression: disabled-source cleanup must retain `legacy` rows when every configured provider fails, including subsequent failed refreshes after the old database was deleted. `ChannelImporterTest` verifies that case and independently verifies that disabled FreeLinks rows are still removed during another provider's outage.

Native guide lookup uses the provider's own channel identity, including outside the UK. A regression fixture gives a native US channel the same name as a different UK guide entry and verifies that it receives its own programme.

## Standalone provider runtime

Run `npm ci --ignore-scripts --prefix provider-engine`, `npm run build --prefix provider-engine` and
`npm test --prefix provider-engine`. With Rhino 1.9.1 downloaded, run
`RHINO_JAR=/absolute/path/rhino.jar bash provider-engine/tools/verify.sh` for 21 checks on the actual
JVM interpreter/signature verifier, codecs, URLs and segment handling, plus 40 multi-provider fixture checks. `ProviderEngineDeviceTest` covers isolated Binder calls and
signed package installation, tampering, downgrade protection, rollback and corrupt-slot recovery.
The normal Android workflow runs these device tests. Real provider and physical-TV evidence must
remain separate; see [evidence and limitations](docs/STANDALONE_PROVIDER_ENGINE.md).

## Runtime 2 adapters and header transport

The provider verifier now also runs `runtime-check.mjs`, which drives the compiled upstream modules through
the real JVM interpreter with recorded HTTP fixtures. It checks POST resolution, compressed catalogues,
long async walks, DaddyLive embed resolution, Tubi signatures and Samsung filtering. `ProviderAdaptersTest`
exercises isolated Android calls, API capabilities, stable compound IDs and the loopback relay with fixture
upstream connections. See [the evidence distinction](docs/STANDALONE_PROVIDER_ENGINE.md).

ZLive fixtures run the actual resolver and Node IP/subnet compatibility through token reuse, persisted
403 cooldowns, decoy detection/latching, corrupt history refusal and cancellation recovery. Device tests
exercise encrypted ledger reuse across actual isolated Binder calls, manual redirects and credential
redaction. The relay fixture also fragments upstream reads into two-byte chunks.

## DaddyLive playlist and segment regression checks

The provider JVM fixture verifies player HTML -> anonymous token -> tokenized HLS, and preserves the
historical no-token path. `ProviderPngSegmentsTest` adds 17 pure-Java checks for RGB/RGBA, every PNG row
filter, split IDAT chunks, appended TS, checksums, truncation, bad sync and oversized dimensions. It runs
through `provider-engine/tools/verify.sh`. `PlaylistTransportTest` checks renewal, one 403 refresh, channel/
authority scoping, token-free segment requests and PNG transport with two-byte fragmented reads. Its
host-JVM run uses Android-shell stubs; the Android workflow runs the real instrumentation tests.

Live desktop evidence (20 October 2026): Main Event plus Football (35), Action (37), F1 (60) and ABC USA
(51) returned tokenized HLS and unpacked media with FFmpeg exit 0. No real stream URLs, tokens or captured
media are committed. Four-second samples do not prove every channel, sustained playback or the TV box.

## Readable provider failures and compact displays

`ProviderFailureTest` adds 17 core checks for DNS/TLS/connection errors, isolated-runtime deadlines,
response limits, fixed HTTP summaries and redaction of sensitive exception messages. ProviderAdaptersTest
exercises HTTP 403 and DNS failures through the real isolated service. SettingsStyleTest checks that a
multi-line provider failure is not ellipsized or clipped by its card. LiveGuideTest shrinks the guide grid
and verifies that channel and programme font metrics fit the rows. CI screenshots include
`provider-error-card` and `live-guide-short-rows`. These fixtures do not identify a particular box's failure.

`PlaybackTest.slowLinkResolutionDoesNotSpendTheVideoStartupTimeout` holds an asynchronous native resolver past the real 15-second video-startup deadline while a backup exists, verifies the original source is still resolving, then releases it and checks actual LibVLC playback without switching sources. Existing cancellation, immediate-failure backup and full-screen/guide handoff tests remain in the full suite.

`LiveProgressTest` now has 264 core assertions for a stopped clock, stopped video while audio time advances, four minutes of healthy progress, pause/resume, missing video counters and reset timestamps. PlaybackTest drives the real shared player and its attached failure callback with a silent-video-freeze observation, verifies paused observations do not trigger recovery, and checks the error appears without sending buffering/error events. The full existing suite also checks provider retries, source backup, cancellation and view handoff.

A same-JVM desktop benchmark compared old/new PNG decoding on the same 6.8 MB Main Event container: every run produced identical 6,885,312-byte TS output, and every new run was faster (roughly 2x in later runs; timings vary). All 17 existing PNG format/filter/error checks remain applicable. This is not an ARM64 benchmark or proof of the cause of a particular TV freeze.

## Live TV control consistency

Since 27 October 2026 `LiveControlsTest` runs on `LiveStage` (see "LibVLC live tests removed"); mentions of Activity recreation below describe the removed separate player screen.

`LiveControlsTest` now opens its live fixture without the static guide session. It covers physical colours, real Activity recreation with cleared static context, saved guide snapshot independence, a learned green button using a numeric key, and visible unclipped controls with long source/channel names. Existing Back/arrow/OK tests remain. These fixtures do not verify the owner’s physical remote. Local core checks pass (733); Android CI is pending.

`LiveControlsTest.physicalColoursKeepWorkingWhileAnotherPlayerPanelOwnsFocus` sends red, green, blue and yellow without manually dismissing intermediate dialogs. `completeHeaderSurvivesProgrammeUpdatesAndBackControls` checks full-height channel-strip labels, not only footer buttons, after typing and programme updates; it saves `live-complete-channel-header.png`. These use fixtures, not the physical remote.

## Consistent live banner and standard colours

LiveControlsTest now checks actions on key-down before any release, repeats/releases across replacement dialog windows, conflicting saved colour aliases, and a constrained viewport with information/pause panels visible. Earlier tests only sent complete down/up pairs with normal mappings and ample layout space. New tests must pass Android CI; they do not establish behaviour on the owner’s physical remote.

## One live overlay and shared colour input

LiveControlsTest.entryOkClickAndBackUseTheSameFullBanner checks visible video focus, absence of an attached film overlay, repeated OK without opening Options, picture click and Back, and stable header bounds. standardColourDeliveryVariantsShareOneActionAcrossWindows covers a first received nonzero-repeat event, held/released events crossing dialogs, standalone release, multiple delivery, the standard colour scan fallback and preservation of known non-colour keys. ColourPressTest adds 22 pure-Java gesture checks. Core total: 775. Android build/emulator/signing results are recorded on the change’s pull request and release run; physical-TV confirmation remains outstanding.

## Incoming VOD traffic and ranking

`StreamRateTest` covers timed byte deltas, idle zero, counter resets, quality aliases, camera penalties, the resolution ceiling and equal-quality transfer comparisons. Provider adapter instrumentation counts actual upstream bytes once; the Android Python bridge verifies counted transport for header-free sources. Offline Python tests verify master-playlist resolution and sampled media rates. These small startup samples estimate readiness, not future host stability.

The VOD relay regression includes real file playback/seeking and a TCP check that files retain their byte lengths and range headers without being advertised as HLS. Android extractor fixtures use public literal IP addresses so transport validation never depends on DNS for `.example` test hosts.

## Player panel initial focus

The live-controls physical-key regression must receive actual window focus for Audio, Sources and Options in sequence. Panel layout and system-UI attributes are configured before the window is attached, so initial focus is not followed by a second layout/visibility change. Repeated isolated emulator runs accompany the full suite for this handoff.

Live-controls physical colour injection uses one down-time identity for both events and drains pending UI work before capturing a replacement dialog. Android Instrumentation’s convenience down/up helper constructs independently timed events, which can look like another press after a window-transfer cancellation. Existing window-focus and old-panel-closure assertions remain required.

The decoded-backup test drains the main-thread handoff before checking the Activity reference. It still requires the same standby decoder to be promoted and the old picture to keep advancing until candidate data is released.

Live-controls wait predicates and dialog snapshots execute on the main thread; the instrumentation thread receives their result through atomics. This keeps window focus checks inside Android’s UI-thread contract.

Player panels request input focus after their DecorView attaches, with the initial layout already configured. The repeated colour-key test continues to require the attached view’s actual window-focus callback and successful key-driven panel replacement.

## Free VOD failure and duration regressions

The Python suite individually checks all 70 provider lookup contracts and explicit library/API-key exclusions, rejects 7/30/119.9-second media, accepts the 120-second boundary and unknown metadata, and preserves a matching playback User-Agent. See [inventory audit](docs/FREE_VOD_AUDIT.md). `PlaybackTest` adds real-VLC manual free-source failure through a broken host and short clip to another provider, initial short-clip rejection with a late-discovered backup, and a deliberately blocked native stop while controls and original playback remain responsive. The speed regression requires visible Mb/s and received-byte labels with a positive real relay byte count. The checked-in `vod.mp4` fixture is real 150-second H.264/AAC media, generated from the original fixture with FFmpeg and fully decoded locally. Duration checks stay active; short-video regressions still use the original 30-second fixture. See the test-assets README for the reproduction command. Full Android CI validation is required; these are fixtures, not physical-TV/provider evidence.

## Provider compatibility regressions

The 17 Python tests include the actual Scrubs Levidia lookup from landing page through
bodyless POST, result, host and media probe; redirect methods for 301/302/303/307/308;
explicit/default Referer; and both families' movie title/alias and episode mapping rules.
FreeVodProvidersTest checks movie/series TMDB alias forwarding on Android. Local Python
and 814 core assertions pass (Eclipse JDT/Java 17). Android build/lint/emulator results
must be read from the final PR commit; fixtures do not prove physical-TV availability.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.

## VOD Activity destruction regression

PlaybackTest.finishingFilmRetainsDetachedTextureUntilNativeStopCompletes plays real VLC
video, blocks its native stop, finishes/destroys the Activity and verifies the detached
SurfaceTexture remains unreleased. Unblocking stop must release it. The full suite also
retains the film-to-native-Live-TV cancellation test that exposed the EGL crash.
The previous final run failed after 121 passes with SIGSEGV in EGL swapBuffers; this
regression and its correction require a new full emulator result.

## ExoPlayer Live TV and the test app

`ExoLiveTest` and `DlhdStartupTest` are ordinary device tests (in `src/androidTest`), so the normal
`device-tests` job runs them. CI's `apk` job also compiles the MovieHub Experimental test app
(`-Pexperimental assembleDebug`) and checks its package name, and `experimental-device-tests` runs the two
suites again inside that build. `ExoLiveTest` exercises Media3 itself on the TV emulator: decoded frame progress and nonblank
screenshots through two guide/fullscreen round trips without new stream requests; 404 retry
cancellation on channel change and stop; pause/resume; foreground restart and Activity
destruction; HLS audio/subtitle selection and remote banner entry. Experimental lint also runs.

Run that suite on a running emulator with:

```sh
gradle -p android-tv-launcher connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=local.tv.home.ExoLiveTest
```

These local HTTP fixtures establish decoder and lifecycle behaviour, not real-provider or
physical-TV compatibility. Freeze detection remains diagnostic; this release does not add an
automatic picture-repair algorithm.

### Experimental DaddyLive startup regression suite

DlhdStartupTest runs real isolated Binder calls with bundled DaddyLive transforms and fixture
HTTP: one process binding per complete lookup, fewer warm-page requests, fresh script globals,
expired/cancelled lookups, socket-timeout continuation, cache expiry/module separation and
exclusion of tokens, credentials and failures. It runs alongside ExoLiveTest in the dedicated
experimental emulator job. ProviderScriptFastTest uses actual Rhino on the JVM to verify one
compile across steps, fresh globals, no Java access, failed-request clock accounting and the
unchanged legacy replay contract. Fixtures do not establish physical-TV startup latency.

`VideoDetailsTest` (core) checks the information panel's picture line: size and quality name
(including wide film frames), whole and fractional frame rates, codec names from Media3 MIME types
and VLC codes, bitrates, and that nothing is shown before the first frame. `LiveControlsTest` checks
that the source name is not a button (four colour keys only), that BACK highlights the green Source
key, and that the information panel shows "Picture:" and the source. Fixtures only: the real values
come from the stream and were not checked on a TV.
`ProviderOutageTest` (core) checks the grouped "Unavailable now" line shows real failures only (HTTP 403
and script errors are never called geo-locked) and that "Hide US-only providers" hides only LG, Roku
and Local Now. `runtime-check.mjs`
checks that LG Channels asks for country US. The Plex play fix was verified only by the live probe
(two of three sampled channels opened and played from GitHub's US runner), not on a UK connection.

`ProviderEngineDeviceTest.plexListingsMatchByStableIdAndFallBackToTheUsGuide` feeds fixture XMLTV to
`ProviderGuide` (Plex spec): channels match by the id after the dash (with a different server prefix), unrelated
channels are ignored, the US file is read only when the UK file covers under half the lineup. The real
gb.xml (368 channels, all `<24 hex>-<24 hex>` ids) was inspected offline; that its ids equal the
catalogue's gridKeys is inferred, not yet seen on a device.

`LiveGuideTest.tenChannelsPerPageNumberedFrom101AndPagedWithTheRemote` checks the title line reads
"ALL CHANNELS 25 channels" for its 25-channel fixture (the whole list, not the page).

`ExoLiveTest.aFailingSourceWithAnotherWaitingMovesOnInsteadOfReopening` checks that an erroring source
is reported once and not reopened when the listener has another source. The 15-second startup
deadline (`LiveStage.START_MS`) has no device test; it uses the same failover path.

`LiveStallRulesTest` (core, 44 checks) covers the stream rules ported from the LibVLC live player:
startup and reopen startup not counted, a long stall moving on when another source waits, three
stalls a minute moving on only with another source, a long stall with none reconnecting once, pausing
never counting, a dead stream recovered while a still picture with moving sound is not, and two
reconnects refilled only by 30 s of moving picture. `ExoLiveTest.aSourceThatKeepsBufferingMovesToTheNextSource`
plays a two-copy fixture channel whose first copy sends a few seconds of HLS and then holds the
connection open: after its pictures, eight seconds of buffering move to the working copy without
reopening the stalled one. `aLongStallWithNoOtherSourceReconnectsThatSource` checks the same stall
reconnects a single-source channel. `anotherChannelStartsInAutomaticSourcesAfterAManualChoice` checks a manual
source choice does not carry over to a different channel opened full screen (it did since 1.0:
only the removed LibVLC player reset the mode when the stream changed). Provider link renewal has no device test (it needs a provider
link with an expiry); real provider streams are not exercised by fixtures.

`LiveRacePlanTest` (core, 24 checks) covers the source race rules: priority by quality then copy order,
at most three lookups and standbys together, the quickest copy playing at once, a copy taken by
`LiveUpgrade` counted as playing, no waiting after a failure, the short tie window, and exhaustion.
`LiveUpgradePlanTest` (core, 36 checks) covers upgrades: only a better claim than both the copy's name
and its measured picture, best claim first, one at a time, an unreachable copy changing nothing, a
held trial allowing a further upgrade, a failed trial falling back, a 10-minute cooldown that survives
a channel change, doubles on repeat, is capped at an hour and resets after a held trial, and picture
lines from width and height. `ExoLiveTest.aBetterCopyFoundAfterTheQuickestOneTakesOver` starts a
channel on its SD copy and checks its HD copy takes over and stays. `aBetterCopyThatKeepsBuffering
FallsBackAndCoolsDownAcrossChannels` gives the HD copy a stalling stream: it falls back to SD, cools
down, is not played again, and after another channel and back the race and upgrades still leave it
alone. `aManualSourceChoiceIsNeverUpgraded` checks a copy chosen by hand stays. `ExoLiveTest.aDeadCopyHandsOverToAWorking
OneWithoutWaitingForTheSlowOne` plays a three-copy fixture channel (404, working, slow) and checks the
working copy plays through the race and the slow one is never opened. Real provider lookups, quality
names and the eight-second deadline are not exercised by fixtures.

`EpgMatchTest` (core) checks Freeview's London-is-national aliases (BBC One/ITV1/Channel 4 London, "5"
as Channel 5, radio and other regions left alone). `EpgImporterTest.guidesInPriorityOrderLondonIsNational
AndCountriesStayApart` imports three fixture guides: Freeview beats the later UK guide, a channel only the
second guide has is filled from it, Irish channels use the Irish guide, and a German channel gets no UK
listings. `oneFailingGuideKeepsTheOthers` checks a failed download does not lose the rest. The real
files' coverage was inspected offline (Freeview: 284 channels, 47,787 programmes); epgshare01's IE and
US files were not downloadable from the workspace and are untested beyond Free-TV listing them.

`ProviderEngineDeviceTest.samsungListingsMatchTheStoredChannelIdsUnchanged` checks the Samsung spec reads
only the gb file and matches ids unchanged. The live gb.xml (273 channels, 2,550 programmes, ids such as
GBBC2300003ZQ) was inspected offline; it was not checked against the adapter's live catalogue.

`LiveGuideTest.okJumpsToATypedNumberAtOnceWithoutOpeningTheHighlightedChannel` types 112 and OK: the guide
lands on 112 before the digit wait runs out and the OK's release does not open the old row; the guide's own
number box shows "112–" while typing and is gone after the jump.

`EpgMatchTest` also covers UKTV's renamed channels (U&Dave, U&Gold, the Freeview id UAndDrama) and the
trailing "UK" word in names. The Sky London HD guide was inspected offline (377 channels, 75,677
programmes; Sky Atlantic, Sky Witness, Comedy Central, U&Dave/U&Gold present; Sky Sports and Sky Cinema
absent). Its 45 MB file is served gzip-compressed at about 5.3 MB.

`ChannelImporterTest.importsBothXtreamFormatsAndM3uAndKeepsFailedRefresh` also checks a finished refresh
records the app version (`refreshedVersion`) and `ChannelSync.updatedSinceRefresh` treats another or
unknown version as updated.

## XMLTV fallback regression (25 October 2026)

`EpgLookupTest` checks provider-name lookup, country prefixes/aliases, fixed provider country, GB/UK equivalence, timeshifts and refusal to borrow UK listings for unknown native countries. `EpgImporterTest` imports a Sky-shaped XMLTV fixture for a built-in provider channel, verifies missing/expired native fallback and native priority, separates equally named US/UK channels, and verifies stale/malformed priority guides cannot claim keys or leak partial programmes. A Freeview-shaped Gold Radio fixture cannot hide Sky’s U&Gold television listings. Existing native/XMLTV coexistence tests remain. Core checks run locally; full Android build/lint and both emulator suites must pass on the final PR commit. Live feed availability and the owner’s physical TV remain separate checks.

## Combined guide and ZLive regression checks

`CombinedEpgTest` uses loopback HTTP, real gzip/XML and SQLite to check conditional 304s, a single guide URL, native/name priority, future-only fallback and retention after malformed, empty, stale or failed updates. `ZliveSourcesTest` checks distinct labelled routes, duplicate rejection, old primary identity, permitted headers, unsupported website options and module-bound hints. Experimental `ExoLiveTest` checks exhausted ZLive failure/recovery with loopback media. Core provider tests validate fragmented PNG/WebP/raw/gzip carriers and the real TCP relay without a playlist-token session. None establishes live provider playback or a physical TV result.

The generator has independent Python/Node merge, cache, expiry, DST, priority, invalid-XML and coverage-drop tests. Its GitHub run and published reports establish server publication; review exact per-run source failures rather than assuming every configured source succeeds.

## Experimental exp13 provider repair checks

The Python suite covers distinct website inventory (63 sites and 66 adapters bundled; seven retired and 18 unverified restorations disabled after the 25 October review, leaving 38 sites and 41 adapters searched; all 68 bundled modules still validate), alternate-adapter fallback, final-host cookie scope, cross-origin credential removal and browser identity/query-list handling. `verify-vod-upstream.py` compares current official Scrubs/Free99 package versions, active provider inventory and original hashes. The on-demand audit runs movie and episode queries separately, records extraction/resolution/media outcomes and never logs source URLs or credentials. Results establish only those titles from the runner's location.

The actual JVM provider runtime encrypts a fixture session POST which Node independently decrypts and verifies, including raw channel IDs, ticket/AAD, timestamp, reuse and refusal suppression. Android fixtures check the current session round trip, encrypted contact history and fresh movie/exact-episode resume metadata. Build/lint and emulator outcomes must be read on the PR's final commit.

The first Android run of this PR failed two device tests (`ProviderAdaptersTest.coldResolversAvoidCatalogueAndTclHintsAreEncryptedAndCompact` and `FreeVodProvidersTest.javaCancellationReachesPackagedPythonBeforeAnyProviderRequest`), failing again on rerun. Both were code faults, fixed in a follow-up commit; `test_cancelled_website_search_stops_before_reading_title_fields` reproduces the second offline.

## VOD retirement and transport recovery

The Python suite checks Levidia remains in ordinary discovery, empty landing cookies, yearless results, cross-site Host/credential removal, and zero body reads for file-size, redirect, header and cookie lookups. Existing duration, cancellation, TLS/public-address, redirect-cookie and all-adapter contracts remain required. The public probe supports `--include-retired` and repeated `--provider` filters; failures are observations for the selected title and network, not proof of closure. Current inventory: 36 searchable websites, 39 adapters and 70 modules (68 Kodi plus two native). A regression requires Levidia to be the only enabled member of the original 26 exclusions.

Additional recovery regressions check Scrubs Levidia delegates Wootly once and an empty fallback does not hide a host timeout. Offline tests forbid live HTTP from upstream constructors. Local suite: 32 Python tests.

## Site-by-site VOD recovery (26 October 2026)

The site recovery suite imports `test_current_vod_sites.py` from `test_free_vod.py`. It covers canonical result links, current search/player flows, distinct providers sharing a CDN URL, authenticated Xpass responses, rejected tampering with empty AES-GCM associated data, and diagnostics without URLs or credentials. Live checks and limitations are recorded in [the 26 October audit](docs/FREE_VOD_SITE_AUDIT_2026_10_26.md).

Current site recovery: 46 offline Python tests, including Tubi’s padded challenge/issued signing key, clear-resource filtering and Waffi mirror isolation. Android instrumentation exercises named-backend deduplication through the actual selection-window key. The HTTP identity audit compares app requests, a cookie-preserving browser-style requests session and curl without executing challenge code.

## Native movie source expansion (26 October 2026)

`test_native_vod_sites.py` covers exact title/year selection, literal redirect decoding without executing scripts, isolated mirror failure, distinct returned hosts, safe filename encoding, current Vixsrc movie/episode identities and expired token rejection. The opt-in native Android audit plays each returned source independently through the actual relay and LibVLC; a completed job is not evidence every source passed. Inspect its redacted JSONL statuses and final shard marker.

The focused native-source audit also records decoded audio buffers and selected audio-language metadata; it does not call a source verified when neither decoded nor played audio buffers were observed. Device volume remains controlled by Android.

`ProviderFileRangesTest` exercises a real finite-range-only HTTP fixture: full streaming across multiple parts, middle/finite/suffix seeks, interrupted-part resumption without duplicated bytes, guarded redirects, invalid/changing metadata, changed ETags, downgrade rejection and cancellation. `PlaybackTest.finiteRangeOnlyHostPlaysAndSeeksThroughTheRealRelay` requires actual LibVLC playback through the same opt-in relay; an open-ended fixture request is rejected with HTTP 403. It covers explicit source opt-in and automatic recovery from that rejected request.

The live audit reports decoded audio and output audio-buffer counters separately, so valid passthrough does not require software audio decoding. It records selected audio-language metadata, received bytes and redacted transport exception types. Final dimensions/counters are taken after the midpoint check. Source badges ignore provider branding and prefer numeric resolution over a generic UHD source tag.

Final validation on `f5b4e63e65788c512389ca31996c6a8d66d43474`: 51 offline Python tests,
932 local core checks, all Android/build/lint/signing CI jobs and standalone engine checks passed.
The focused live audit completed for Levidia, both native sites and a catalogue-listed Rarefilmm
film. Read `docs/history/audits/NATIVE_VOD_PLAYBACK_AUDIT_2026_10_26.jsonl` for each result; a completed audit
does not assert all links passed. Product code is unchanged by the final documentation commit.

## Confirmed source quality (26 October 2026)

`test_free_vod.py` reads header slices of real FFmpeg-made files in `tests/media-headers` (854×480 and 1920×800 MP4 with the index first, 1280×720 MKV, 640×360 WebM, and an 854×480 MP4 with its index last). It checks the probe replaces a website's "1080p" with the file's 480p, makes exactly one extra bounded read for an index stored last (which also exposes a real 3-second file as a short clip), keeps the size unknown without rejecting the source when that read fails, never requests an index beyond the file's end, and stops on cancellation. The site-level test checks the website's claim is kept separately and marked unconfirmed.

`QualityClaimsTest` covers judging claims (including the reported 1080p-labelled 854×480 case), the five-result history and recovery, ranking distrusted claims as SD, played-source labels and which confirmed better source is worth mentioning. `SourceWindowTest` covers the conditional longer first-choice wait; `StreamRateTest` covers confirmed-over-claimed ranking and small-picture labels; `VideoDetailsTest` covers the numeric tier.

`PlaybackTest.playingFilmCorrectsAnOverstatedLabelAndOnlySuggestsAConfirmedBetterSource` plays the real 160×90 fixture labelled 1080p through LibVLC, with a 4K link that is only claimed and a 720p link confirmed from its file. It requires the corrected label, the website's recorded verdict, a suggestion of the confirmed 720p link only, and no automatic switch. These are fixtures: they do not show how often real websites mislabel links, or how a real TV's network affects playlist steps.

## FMHY Rive addition (26 October 2026)

`test_fmhy_rive.py`, imported by `test_free_vod.py`, checks exact movie/episode identity, separate links, quality/language labels, playback headers, unsafe URLs, protected records and cancellation. The desktop live audit decoded all seven returned Inception links and three Breaking Bad S01E01 links independently at the start and midpoint with audio. See `docs/FMHY_ADDITIONS_2026_10_26.md`. These are sampled desktop decodes and actual Python-bridge checks, not physical-TV evidence. The separate new-source workflow checks the packaged Android player without selecting existing providers.

The initial packaged Rive audit verified two movie links and rejected five with relay errors. The redacted initial results are preserved, not relabelled as successes. A real-TCP regression reproduces raw TS served with image/jpeg during a zero-to-end read: it failed before the fix, then passed with bytes preserved and the correct TS MIME type. The new-source workflow now audits movie and exact-episode links in separate jobs; all returned links must pass. See the dated report for the follow-up result.

## Continue watching rows (27 October 2026)

`ContinueWatchingTest` (core, 13 checks at the time; see below) covers the row rules: nothing under three minutes, one row
per film whichever source, the newest record of a film deciding, a series resuming its newest episode
watched properly, a finished episode offering the next one once per series, an episode opened only
for a moment leaving it to the one before, a part-way rewatch resuming, row order, and grouping by
TMDB id, then shown title, then file id. `ContinueWatchingScreenTest` (device) builds the screen from
a fixture history: a one-minute start hidden, a film from two sources shown once, a series shown once
under its name and resuming the episode watched properly, a finished episode's series kept, and
removing a series forgetting every episode record and saved position. The next-episode lookup uses
TMDB ids that do not exist, so the real catalogue lookup (next episode found, or the row going when
the series is finished) is not exercised by fixtures.

## Live TV pause and Back to live (27 October 2026)

`ExoLiveTest.pausingShowsThePauseBarAndFastForwardGoesBackToLive` pauses a fixture channel with the
remote's pause key, checks the pause bar, carries on and checks it now says behind live, then presses
fast-forward and checks the picture plays again with the bar gone. The fixture has no live window,
so this exercises the reopen path; jumping to a real HLS live edge is not covered by fixtures.

## Subtitle size and background (27 October 2026)

`SubtitleStyleTest` (core, 12 checks) covers the size choices, damaged saved values falling back to
Normal, the LibVLC options and Media3's size and background colour. Every film device test now
starts LibVLC with the two subtitle options, so CI checks that LibVLC accepts them; how subtitles
look on screen is not checked by any test.

## Thumbs, new episodes and Anime (27 October 2026)

`TitleRatingsTest` (core, 12 checks) covers the saved ratings: keys, damaged parts skipped, a new
rating moving to the front, clearing, the 300-title bound and listing liked or disliked titles.
`ContinueWatchingTest` now has 19 checks, adding six for "New episode" (aired today or up to seven
days ago; not future, missing or invalid dates). `TmdbCategoriesTest.animeRows…` (device) checks every
Anime row is Japanese animation without adult titles, routes are unique, the airing window, a genre
row, and that film rows are typed as films and series rows as series. Not covered by tests: the
thumb buttons on screen, For you actually changing after a rating (it needs real TMDB), the New
episode label on screen, and the Anime screen loading real TMDB rows.

## Broken sources marked as watched (27 October 2026)

`PlaybackEndTest` (core, 28 checks) covers when a stop counts as a finish: a source that ends with
nothing played, a short clip, an unknown length with little played and a stream cut off part-way do
not, nor does a resume point well past the end of a shorter copy; the end, the credits window and
a reading just past the length do. `playedPosition` checks cover where a backup source, Try again and
the saved resume point carry on after a dead stream. It also covers leaving the player
and the unchanged `ResumePoint` completion rule. Not covered: the player acting on it (LibVLC's
end event, failover to another source), which no device test drives with a broken source.

## Additional FMHY route checks (27 October 2026)

The combined change includes PR #172 (ratings, recent-episode labels and Anime). Local core checks pass using Eclipse JDT with Java 17; provider-engine tests pass; the Python suite passes 68 tests. New fixtures cover the Movy decoder against a frozen independent JavaScript vector, wrong identities/seeds, separate labels for identical media URLs, omitted movie episode parameters, exact episode identity, cancellation, protected/private targets and backend failure isolation.

Earlier representative Inception desktop checks decoded Movy Boise/Miami at 3840×2160 with AAC and Rive Vanguard at 3840×1600 HEVC with separate decoded audio; full playlists were about 148 minutes. Atlantic Moscow decoded 1920×800 H.264/AAC with a similar runtime. These observations establish sampled desktop playback, not Android/TV playback. Fresh integration probes obtained eight Movy links, Rive Vanguard and a decoded Moscow URL. Local DNS is unavailable: these fresh HTTP probes used curl's network transport and did not exercise the app's DNS guard. Production URL guards remain unchanged.

The existing opt-in Android live audit now also runs for these adapters on PRs, checks movie and exact-episode sources independently, and requires every selected returned link plus every named delivery route to pass. Its source-name filter affects only the audit. Build/lint, device fixtures and live Android results must be read from the integration PR; no physical-TV claim is made.

First packaged audit: all 16 Movy movie/episode links passed video, decoded/output audio, runtime and midpoint seeking, including four measured 3840×2160 links. Vanguard's episode passed at 640×360; its movie audio/seek result did not pass, so the new backend is enabled only for episodes. Atlantic's first movie check had no decoded video and its episode process crashed. Direct inspection found raw MPEG-TS segments labelled `text/html` on range requests; its adapter now opts into the already-existing segment-container/MIME handling. The correction did not resolve either Android failure. Atlantic is excluded from this integration; its draft adapter, registry entry and cipher fixture have been removed. No shared relay/player changes were made. The ratings integration also checks that disliked titles are removed from Trending when recommendation seeds are absent.

Final integration validation: `3cd19c6` passed all 17 retained live Android checks ([40501077139](https://github.com/billyke2000s/moviehub/actions/runs/40501077139)), Android build/lint, signing/updater compatibility and both device suites ([40501077161](https://github.com/billyke2000s/moviehub/actions/runs/40501077161)), plus provider-engine CI ([40501077112](https://github.com/billyke2000s/moviehub/actions/runs/40501077112)). Movy passed eight movie and eight episode links, including measured 3840×2160 with decoded/output audio on both named routes; the sampled runtimes were 8,886 seconds and 3,501 seconds. Vanguard's sampled episode passed at 640×360. Atlantic and the new Vanguard movie route are not enabled by this change. PR #173 is merged; no APK was published.
