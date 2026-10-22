# Testing

## Continuous series and automatic playback

`VideoFilesTest` includes episode ordering, future-date handling and source-resolution ranking. `SeriesAutoplayTest` uses catalogue and source fixtures to check season transitions, updated history keys, missing pack episodes, show endings, uncertain filenames, bounded source fallback and cancellation. `LiveGuideTest` covers colour keys from header focus and Back/arrow/OK controls; `LiveControlsTest` covers all four physical colours during playback. These do not verify a particular physical remote or real-provider playback.


There are three layers of checks. CI runs all of them on every pull request that touches
`android-tv-launcher/` (workflow: `.github/workflows/android-launcher.yml`).

| Layer | Where | Needs | Runs in CI step |
| --- | --- | --- | --- |
| Core checks (540) | `android-tv-launcher/tests/` | Only a JDK | "Verify playlist, network, channel search and episode rules" |
| Build and lint | Gradle | Android SDK 35 | "Build APK and run Android lint" |
| Device tests (instrumentation) | `android-tv-launcher/app/src/androidTest/` | Android emulator | `device-tests` job |
| Signing compatibility | release build vs published APK | signing secrets (same-repo PRs only) | `signed-compatibility` job |

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

| Cause | Kind | Fix |
| --- | --- | --- |
| Quick D-pad presses moved focus to a poster just past the edge; a layout then recycled it and focus was lost | **App bug** (users could lose the selection) | Poster rows and grids keep half a screen laid out beyond each edge, and refocus the same title if focus is still lost (`PosterFeed`) |
| A Back press sent while a dialog was closing reached no window and was dropped | Test timing | `TvNavigationTest.back()` waits for window focus before the next key |
| Controls were looked up once, immediately after a screen change | Test timing | `TvNavigationTest.select()` waits up to 5 s |

Results of 8 parallel runs of the whole suite:

| Code | Passed |
| --- | --- |
| Before the fixes | 3 / 8 |
| After the focus fix | 4 / 8 |
| After keeping off-edge posters laid out | 6 / 8 |
| After the Back fix | **8 / 8** |

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

| Test | Covers |
| --- | --- |
| `ForYouTest` | For you ranking (shared recommendations rise, seeds and played movies hidden), trending fallback, caching until signals change, old seed migration. Fixture TMDB responses. |
| `AllAppsTest` | Apps screen: Android shortcuts and Recently used shown, search box not focused on entry, search filters by name, hide and Show hidden. Uses the emulator's installed apps. |
| `SidePanelTest` | Side panel focuses and ticks the current choice and runs it after closing; `ErrorMessages` explanations never include provider text. |
| `DiagnosticsTest` | Timings and private error details; Troubleshooting retains the Settings sidebar, Back returns to System, and other tabs remain usable. |
| `SecretsTest` | Encrypted on disk, cached reads, replace and remove. |
| `ArtworkDiskCacheTest` | Artwork disk cache: hit, miss, least-recently-used trimming, oversized files skipped. |

## Tests call app code directly

Device tests live in the same package as the app (`local.tv.home`), so they call package-private
members directly, for example `activity.libraryScreens.toggleWatchlist(title)`. The compiler
checks every one of these calls, so renaming or moving a member breaks the test build immediately
instead of failing at run time. Do not use reflection to reach app members in tests; make the
member package-private instead.

### Channel copies and automatic backups (October 2026)

| Test | Covers |
| --- | --- |
| `ChannelIdentityTest` (core) | Same channel across lists and providers ("Sky Sports F1 (720p)", "… UK", "… HD", "Sky Sports Formula 1"); kept apart: other countries, +1, regions, UHD, similar networks; provider categories into the guide sections (whole words, so "TF1" is not sport). Also run over the real Free-TV and iptv-org UK/US/IE lists (20 October 2026): five new merges, all the same channel. |
| `ChannelDatabaseTest.providerCopiesJoinTheSameChannelAndSharedSections` | A provider copy becomes a backup of the free-list copy, keeps number 407, appears in Sports, no provider group. |
| `ChannelDatabaseTest.versionEightKeysAreRecomputedKeepingFavouritesAndLocks` | Version 8 → 9 upgrade re-keys saved channels; favourites and channel locks kept. |
| `PlaybackTest.skySportsF1FailsOverToAnotherProvidersCopy` | **The reported problem**: the free-list copy fails (404), the provider copy plays; "isn't working" is not shown. |
| `PlaybackTest.failoverTriesEachCopyInTurnUntilOnePlays` | Two failing copies, the third plays. |
| `PlaybackTest.whenEveryCopyFailsTheChannelSaysSoWithoutRetryingForever` | Every copy tried once, then the message; no further requests. |

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

## Live picture handoff evidence

PlaybackTest repeats guide → full screen → guide twice for both direct and resolved fixture
streams. LiveHandoffEvidence checks that the same `LiveVideoOutput` and player survive, that
VLC's decoded-video count rises again in the destination, that the picture returns within
3 seconds of the move (`HANDOFF_LIMIT_MS`, logged as MovieHubHandoff), and that the SurfaceView's
actual pixels (sampled from a screenshot where the video surface is; PixelCopy cannot read a hardware decoder's surface) are not blank. Stream-open and resolver counters assert no
restart or re-resolution. This verifies fixtures on the API 28 emulator, not the TV.

After a move, VLC's hardware decoder shows nothing until the stream's next keyframe, so the
blink lasts up to one keyframe interval. `androidTest/assets/playback.ts` was re-encoded on
21 October 2026 with a keyframe every second (320x180, 15 fps, 30 s, audio unchanged), like
typical live sports streams; the earlier file had keyframes 16.7 s apart, which no live channel
uses and which made every move wait up to 16.7 s.

Before 21 October 2026 the live picture used a retained SurfaceTexture/TextureView; its handoff
times (47–104 ms on the emulator, 20 October 2026) are not comparable with the SurfaceView move.

## Early video freeze and reload investigation

PlaybackTest `aPictureRefreshKeepsTheSameStreamAndSound` calls the watchdog's repair
(`refreshView`, a video-track restart) during playback and checks unchanged player/output, stream-open and resolver
counts, displayed pictures advancing again and audio output advancing. A wrapped-PNG HLS fixture
exercises the production relay and LibVLC through three reloads, requiring displayed pictures and
audio output counters to advance. The EGL/SurfaceTexture test (LiveVideoOutputTest) was removed
with the TextureView output it tested.

The opt-in dlhd-playback workflow resolves through the actual bundled interpreter and decodes
both tracks through production Java relay/session/PNG classes for 150 seconds per selected
channel. It replaces only Android-dependent DTO/logging/DNS seams on the JVM and uses FFmpeg,
so it does not prove Android hardware decoding. Only numerical results are uploaded; no fetched
media, URLs or tokens. A green diagnostic workflow does not imply bothDecoded=true.
Android validation passed in run 39513033996; local core/provider checks are also available.

LiveControlsTest holds the selected native resolver pending while opening controls and Sources, asserts no backup resolution, checks first Back reveals controls and second Back leaves, and checks manual pinning plus restoring bounded Automatic failover. Local execution became available again: core and provider checks are run locally; Android build/lint and real EGL/LibVLC regressions require CI.

FreeShot adds actual-Rhino fixtures for catalogue deduplication/sports filtering, a three-request selected-channel resolver, headers/token lifetime and rejecting invalid identities before HTTP. PlaylistSessionTest verifies fixed-destination renewal, token reuse for media segments and rejecting other CDN/channel scopes. ProviderRelayTransportTest sends a real TCP `Range: bytes=0-` request through the production relay and checks exact decoded TS bytes, 200 status, no container Content-Range and no upstream decoded-offset Range. The sustained diagnostic also forces FreeShot renewal before decoding Main Event for 150 seconds.

Live verification on 20 October 2026: FreeShot Main Event passed the 150-second production-relay
check with a forced initial anonymous token renewal: 3,748 unique video frames over 149.92 seconds
and 7,032 unique audio frames over 150 seconds, with no timestamp gaps or relay errors. DaddyLive
Football also passed both tracks for 150 seconds after accepting whole-entity wrapped ranges.
The Main Event DaddyLive run contained a timestamp gap; these host checks do not establish the
physical TV's decoder/audio behavior. The signed test APK comes from Android run 39513033996.

PlaybackTest.aManualBackupSurvivesGuideAndFullScreenWithoutReopening plays a second copy, pins it manually, makes two full-screen/guide trips and checks the retained player, current copy/mode, fresh output and unchanged stream-open count after the intentional source change.

Final application revision `4ae9dce6e6adeb8d95b319ff46f0eb90897a3b22` passed all 122 API 28 TV
emulator tests with zero failures/skips in Android run 39513033996. The manual-backup round-trip
regression passed, alongside constant EGL timestamps, actual stopped-consumer recovery with
advancing audio and three wrapped-HLS reloads with advancing video/audio. Twelve fresh-frame
handoffs were 137, 82, 88, 63, 116, 53, 112, 63, 119, 44, 117 and 122 ms; no handoff reopened
the stream. Build/lint, signing compatibility and provider run 39513033968 also passed.

[Signed test APK ZIP](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12307505129) ·
[Emulator reports/screenshots](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12306229486).
Only documentation follows this tested application revision.

## Reviewed PR regression coverage (22 October 2026)

Run `bash android-tv-launcher/tests/run-core-tests.sh`; regular CI and publishing call this same script. The local run passed 664 checks in 13 suites, using Eclipse JDT 3.32 targeting Java 17 because this workspace has a JRE but no javac. GitHub CI still verifies javac, APK builds, lint, emulator behavior, and signing.

`VodSourceFailoverTest` covers startup exclusion, threshold boundaries, seeking/pause exclusion, provider pinning, duplicates and exhaustion. PlaybackTest adds actual-VLC fixtures for repeated-buffering replacement through a failed candidate, preserving the old stream while resolving, cancellation on pause and late results, and exhausted alternatives without loops. These tests do not establish dual-decoder support on the target TV or live Premiumize availability.

LiveControlsTest waits for a dismissed panel to leave the event queue before injecting the next physical color key; panels have no closing window animation. Guide paging retains the next direction during asynchronous loads rather than dropping it.

The guide wrap regression deliberately focuses the header before each Page Up/Page Down over four round trips. Paging is routed at the screen level; row-local handlers previously lost Page Down when asynchronous row replacement left focus on the header.

Live channel-key playback explicitly leaves immediately after Back to live restarts VLC, before
waiting for decoded frames. This covers shutdown while native video output is still opening.
The post-merge run 39821398629 crashed in EGL window initialization in this path; shutdown now
cancels view callbacks first, stops VLC with its surface retained, then detaches the surface.

Handoff evidence now excludes closing-output frames and requires the destination SurfaceView
to be shown, valid and sized. The optional PixelCopy capture never delays disabling the old
video track. The existing direct, native-resolved and manually pinned backup tests each make
two full-screen/guide round trips without reconnecting.

SidePanelTest covers Activity destruction with an open panel and rejects continuation callbacks.
LiveControlsTest and SafetyNoticeTest wait for actual window focus before injecting keys across
panel transitions; showing/idle alone does not establish input focus.

Handoff screenshots are sampled until the destination is visibly nonblank, within the existing
three-second budget including time to resume decoding. One screenshot immediately after the
first decoded frames can still precede SurfaceFlinger composition; this is not proof of a
persistently black surface. The test still fails if the visible picture misses that budget.

## Playback continuity validation (0.46 candidate)

`LiveRenewalTest` serves fixture MPEG-TS through real LibVLC. A latch blocks provider renewal
while the original video advances; tests then cover successful reopening, a failed refresh
without reopening, pause without automatic resumption, exit without a late reopen, a
synthetic video freeze repaired on ordinary IPTV without reopening, and a persistent freeze
that reaches failure rather than starting another surface repair.
`PlaybackBufferTest` checks URL-path classification (query/fragment decoys and uppercase HLS),
relay precedence, malformed paths and bounded VOD headroom. The core script now runs 703 checks.
CI must validate the full Android suite, builds/lint and signing before release. Physical-TV
memory/decoder limits and long real-provider sessions remain unverified.

`LiveRepairBudgetTest` verifies persistent freezes across hidden/paused surfaces cannot loop
through repairs, queued frames do not count as recovery, and genuine progress replenishes
the repair budget.

Activity destruction now clears its owned SidePanel explicitly, including show-and-finish
before the first window attach. The native picture-repair fixture establishes actual frame
progress before injecting a new freeze; hiding a surface is intentionally insufficient to
reset the new repair budget. Failed device runs print the full crash buffer so Java process
failures cannot disappear from a video-only filtered tail.

Native window shutdown follow-up: post-merge retry 39868257885 crashed in EGL window
creation during native-source handoff. An unreported Vout count no longer permits immediate
detachment. Every move requests track shutdown; a missing acknowledgement after 600 ms
stops the native reader before moving and restarts playback on the destination. This rare
fallback reconnects the stream; normal acknowledged moves retain it. Device fixtures cover
unreported output counts and suppressed shutdown acknowledgement. Validation is pending.

Release run 39870438797 exposed a delayed overlay callback querying an externally released
shared live player. Overlay, skip, audio-focus, event, delayed-resume and error callbacks now
apply the existing ownership check before native access; a real-player regression covers it.
