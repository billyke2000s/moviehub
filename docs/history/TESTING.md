# TESTING.md history

Old sections moved out of [TESTING.md](../../TESTING.md) on 22 November 2026, unchanged. They describe
the state on their own dates and may no longer be true. Use the main file for current information.

## Published release evidence (20 November 2026)

The [MovieHub 1.0.21 signed publisher](https://github.com/billyke2000s/moviehub/actions/runs/44648355873) passed the core rules, emulator device tests, signed build/manifest generation, ABI inspection, certificate checks and asset upload. The [one-off release workflow](https://github.com/billyke2000s/moviehub/actions/runs/44648333364) passed, and the [numbered release](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.21) was published. Both listed APK assets have digest `8953e5d3de794e1eacc6d0262e4704acd29c26ef90bb8c6ce75a75110c9f17f7` and size 133,276,236 bytes. This confirms GitHub's reported release parity, **not** independent media playback on a real TV box.

Earlier 1.0.20 [signed publisher](https://github.com/billyke2000s/moviehub/actions/runs/44231414573) and [numbered-release copier](https://github.com/billyke2000s/moviehub/actions/runs/44232764334) also passed. For later versions use the [release list](https://github.com/billyke2000s/moviehub/releases/latest).

The following sections preserve dated test and development records, not current service guarantees. Device tests and fixtures do not establish live playback on every TV.

## Historical 1.0.20 integration candidate — 18 November 2026

Combined core rules pass locally with Eclipse JDT targeting Java 17; 144 Python provider
regressions pass. Phone/menu and sports-policy test lists are both retained. Final Android
CI, actual dual-ARM APK inspection and signed updater compatibility are required before
merging and publication. This candidate does not certify the unfinished movie-source audits.

## Published 1.0.19 validation — 29 October 2026

Source: `8759c88e0e2e72227c06bc1751ae1836ef241c53`. Signed publisher
[40958274305](https://github.com/billyke2000s/moviehub/actions/runs/40958274305) passed core/device
checks, release build/lint, actual dual-ARM inspection and certificate continuity before upload.
Copier [40960019599](https://github.com/billyke2000s/moviehub/actions/runs/40960019599) passed.
Both public APKs were downloaded and have SHA-256 `97ad0a88b1cdb981655fdaca34006fcc02345a04bd5a0fa2bfe399b5fbbdac6d`.
Independent Android `aapt2` and `apksig` checks verified package/version 119 / 1.0.19, signature
validity and signing identity matching 1.0.18. Actual native ELF classes/machine types, both Python
payloads and absent native debug sections were also checked. APK size: 132,801,072 bytes.
Post-merge [main CI 40958057391](https://github.com/billyke2000s/moviehub/actions/runs/40958057391)
passed its build/lint, normal/experimental suites, provider probe and focused recovery. The initial
normal-device attempt failed its emulator connection before running tests; its unchanged-code
targeted retry passed. No gate or test was removed or weakened.
The existing 23 full native-source passes and unresolved live failures remain separate evidence
below and in the [source report](docs/CINESTREAM_ADDITIONS_2026_10_29.md).


CineStream additions, published in 1.0.19: 74 websites / 77 enabled adapters, with all fifteen added catalogue
routes retained. Current redirect fixes decode escaped URL literals and support the observed
DriveSeed public cloud/file-button exchange on its issuing origin. New host-button source names
say Stream instead of Download. LinksMod navigation is excluded from file traversal so it cannot
consume the mirror budget; the existing player and source-list layout are unchanged.
Local validation: 144 Python tests and 34 core suites / 1,167 checks pass. At `d85b832`, all six
Android CI jobs passed and the fifteen-site audit verified 21 individual sources across four sites.
Application revision `925af7c` passed all six Android jobs in run 40913881846. Final live checks
include 23 verified sources (eleven movies and twelve episodes); the episode retry passed after an EGL crash.
Vidy playback failures and other site/host errors remain unresolved; registration is not playback proof.
See [dated evidence and limits](docs/CINESTREAM_ADDITIONS_2026_10_29.md).


Earlier checkpoint: current-site inspection corrected the new adapters. Application revision
47b22de passed all six Android jobs after an experimental retry (the first attempt raised
DeadSystemException). Its native Vidy samples still failed and remain draft-PR blockers.
The final KissKH decimal episode-number correction passed the 116-test Python suite and a
public-ID-only API check; Android CI for that small final correction is pending. Unattended
audits continue to block external decoder uploads of site tokens/encrypted responses.
See the [direct-site findings](docs/CINESTREAM_ADDITIONS_2026_10_29.md#direct-website-and-current-client-investigation--follow-up-29-october).

## Published 1.0.18 validation — 29 October 2026

[Main CI 40808318926](https://github.com/billyke2000s/moviehub/actions/runs/40808318926) passed
build/lint, both TV emulator suites, the live probe and the focused VOD job. The focused reports
confirmed 3 session tests, 5 playback tests and two individual recovery regressions, with none skipped.
[Signed publisher 40808326711](https://github.com/billyke2000s/moviehub/actions/runs/40808326711)
passed core/device tests, release build/lint, dual-ARM inspection, signature compatibility and upload.
The downloaded public APK matched the updater checksum; ELF headers confirm 12 ARM32 and
12 ARM64 libraries, both Python ABI payloads, no native debug sections and unchanged native
compression. Recorded validation covers builds, emulators and the published APK.
Older dated validation notes below are historical checkpoints.

## Live picture handoff evidence (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

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

## Early video freeze and reload investigation (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

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

Run `bash android-tv-launcher/tests/run-core-tests.sh`; regular CI and publishing call this same script. A local run on 25 October 2026 (OpenJDK 21 `javac`) passed 903 checks in 26 suites; the suite list is in the script. GitHub CI still verifies javac, APK builds, lint, emulator behavior, and signing.

`VodSourceFailoverTest` covers startup exclusion, threshold boundaries, seeking/pause exclusion, provider pinning, duplicates, exhaustion and the frozen-clock rule (12 s still clock while playing, once per freeze, never while buffering, paused, seeking or before a new source plays). PlaybackTest adds actual-VLC fixtures for repeated-buffering replacement through a failed candidate, preserving the old stream while resolving, cancellation on pause and late results, and exhausted alternatives without loops. These tests do not establish dual-decoder support on the target TV or live Premiumize availability.

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

## Playback continuity validation (0.46 candidate) (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

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

Independent release validation 39871238896 passed 146/146 tests. PR validation passed
145/146, observing audio track -1 during picture recovery. Forced native-stop recovery now
restores the selected audio track after rediscovery. Repair evidence waits for selected
audio and playing state, then checks new audio-output progress rather than old-session stats.

## Prepared live backup validation (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

`LiveBufferingTest` covers exact live thresholds, duplicate buffering notifications, window
expiry, startup/pause exclusion, buffering completion and one trigger per recovery window.
The shared core script runs 733 checks.

`LiveBackupTest` uses actual LibVLC and local MPEG-TS: old frames during preparation, one muted
candidate, promotion of the same already-running decoder/transport, audio volume restoration,
failed candidates followed by a working copy, exhaustion without loops, bounded startup,
pause/manual/channel/exit cancellation with deliberately late resolver completion, and a real
fullscreen/guide transition followed by preparation and promotion in the mini-player. Actual
screenshots must contain nonblank video after promotion and later full-screen reuse. No new
provider sources or account credentials are used. Local core validation passed; Android
build/lint, full emulator and signing validation are pending on the PR. Concurrent hardware
decoder support, actual TV memory limits and live-feed timing require physical-device checks.

## Live playback recovery (LibVLC live player; removed 27 October 2026)

The tests below were deleted with the LibVLC live player (see "LibVLC live tests removed"). This section is kept as history.

LiveRecoveryTest serves deterministic 320×180 and 640×360 H.264/AAC MPEG-TS segments behind an HLS playlist with timestamp discontinuities. It checks actual video/audio progression across format changes, bounded same-channel Pluto retries, controls during a deliberately blocked native stop, and recovery after hiding the active SurfaceView. Pure-Java checks cover retry exhaustion/replenishment and presentation timeouts without treating valid static/black buffers as failures. Physical ARM64/TV-box decoding and comparative Media3 testing remain outstanding; no claim that these fixtures reproduce every Pluto broadcast.

The alternating-resolution HLS fixture contains explicit timestamp discontinuities. It requires advancing decoded video, decoded audio and played audio buffers across at least eleven seconds, unchanged source/player identity and actual visible pixels. LibVLC's live demux clock alone is not proof of stalled output. A separate test verifies three brief buffering notifications do not reconnect an advancing selected channel when there is no alternate source; the existing third-buffer test still verifies prepared-backup promotion.

0.49 validation checkpoint: source f2e37666b297cb225a458ef9df9101fe0b01d87f passed CI run 39985039245: all 166 TV emulator tests, zero failures/skips, build/lint and signing/updater compatibility. Provider CI also passed. Local core checks: 753. HLS transition evidence: 320 decoded video frames, 547 played audio buffers, one resolver call. Screenshots confirm the moving fixture output and complete channel header. This is fixture/emulator evidence, not a physical TV or live Pluto test.

## Free VOD provider candidate

SourceWindowTest covers the exact three-second boundary, best available result, no-result
waiting, late results, duplicates, cancellation and appending alternatives without resetting
VOD stall/failed-source state. test_free_vod.py imports the 68 Kodi constructors and two native constructors and runs
the real MP4Hydra provider against offline search/page/playlist/media fixtures with two CDN
servers. It also covers header preservation, HTML error rejection, cancellation and private/
plain-HTTP target rejection. Run `python android-tv-launcher/tests/test_free_vod.py` with
requests and six installed. These checks do not prove live playback. Python 3.11 is required
for Android builds; Chaquopy adds native runtime ABIs to the APK. Full Android CI and device
validation must pass before treating this as a release candidate.

FreeVodProvidersTest imports all 70 constructors from the packaged Android Python runtime
and checks Java-to-Python cancellation. The offline suite also rejects executable decoder
expressions. A separate CI probe reports public provider availability without logging URLs.

## LibVLC live tests removed (27 October 2026)

The LibVLC live player was removed (see ARCHITECTURE.md, "ExoPlayer Live TV"), and with it the device
tests that only exercised it: `LiveBackupTest`, `LiveRecoveryTest`, `LiveRenewalTest`,
`LiveHandoffEvidence`, the Live TV tests in `PlaybackTest` (including the seven already skipped with
`@Ignore`), and the core `LivePresentationTest`, `LiveRepairBudgetTest` and `PlaybackBufferTest`.
`PlaybackTest` keeps every film and series test. `LiveControlsTest` was ported rather than removed:
the banner, colour keys and source rules it checks are shared with Live TV, so it now opens the
channel full screen in `LiveStage` (with `ExoLive.testResolver` holding the link lookup) instead of
the removed separate player screen. Its `recreatedPlayerKeepsLiveControlsWithoutStaticGuideState`
was dropped: Live TV no longer has a separate player screen to recreate. `entryOkClickAndBackUseTheSameFullBanner`
now also covers the picture click ported to `LiveStage`. Provider link renewal on ExoPlayer still has
no device test.
