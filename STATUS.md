# Status

## Continuous series and automatic playback

New settings keep manual source selection as the default. Next-episode metadata is prefetched while watching; season transitions use catalogue episodes and air dates. Missing-pack episodes follow the selected manual/automatic mode. Guide colour shortcuts are handled independently of focused widgets, with Back revealing accessible controls. Physical TV, remote and real-account validation remain outstanding.


Last updated: 20 October 2026.

## Where things stand

- **Latest in-app update:** the public [updater manifest](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/update.json)
  is authoritative for `versionName` and `versionCode`. Compare it with the numbered GitHub release;
  merging code does not itself publish an update. The baseline verified before this work was 0.38.
- **Code structure:** feature classes per area, see [ARCHITECTURE.md](ARCHITECTURE.md).
  `CatalogueScreens` was split (filter sheets in `CatalogueOptions`) and the Live TV guide's
  panels are in `LiveTvPanels`. `LiveTvScreen` is about 710 lines; split it further (for example
  colour keys and number entry) before it grows.
- **CI on `main` before this integration:** APK build passed; the latest device run had 132 passes and one failure (Green opens Sources). The latest publish attempt failed. See the integration PR for subsequent validation.

## Known issues

1. **Not verified on real accounts or the target TV.** Automated tests use fixtures. Real TMDB,
   Premiumize, Xtream and M3U accounts, real app banners, and installing and updating on the TV box
   need a manual check.
2. **Recovered errors are logged, screen loads explained.** Failed screen loads show a plain
   explanation (`ErrorMessages`); other recovered failures only log the location and exception
   type to logcat (tag `MovieHub`).
3. **CI runner change.** The `ubuntu-latest` runner label moves to Ubuntu 26 from 19 October 2026;
   watch the first runs after that date.
4. **Android Gradle plugin 9.4.1** (replacing Dependabot #12) builds with Gradle 9.6.0 and Build
   Tools 36. First published in 0.16.

5. **Live TV satellite features are only tested on fixtures.** Channel numbers come from a fixed
   table of UK satellite numbers (June 2026) matched by name, so some channels may get none or a
   wrong one. The guide layout was checked by running the real Free-TV and iptv-org lists
   (18 October 2026, about 12,500 channels) through `GuideOrder` on a computer, not on the box.
   A channel's country comes from its guide id, a provider prefix or a country-named group; free
   channels with none of these are filed as international. Pausing live TV relies on LibVLC: whether a real stream resumes where it paused or
   jumps to live depends on the stream, and rewinding works only on streams that allow seeking.
   Reminders show only while MovieHub is open. Automatic backup switching has not been seen on a
   real failing stream. Moving the live picture between the full screen and the guide (one shared
   LibVLC player, views detached and attached) is tested on the emulator with a local stream,
   not on the X905X5M box.

6. **Provider channels still arrive slowly.** Pluto and the free lists now import before the
   optional providers, but the providers still run one after another (their script steps share one
   lock with playback, so running them in parallel would delay starting a channel), and the
   programme guide is fetched only after all of them. Not measured on the X905X5M box.

6. **Relayed sports streams use a 4 s buffer** (`LivePlayer.RELAYED_CACHING_MS`) and the relay
   retries a timed-out provider once. Whether this removes the short pauses seen on the owner's
   phone (21 October 2026) is not yet confirmed on a phone or the TV box.

6. **Live TV now draws through a SurfaceView** (21 October 2026), replacing the shared
   TextureView after the owner saw full-screen lag and a still picture over playing sound. Moves
   between the mini picture and full screen keep the stream but may blink. Emulator tests only;
   not yet checked on a phone or the X905X5M box. Earlier notes below about the retained
   SurfaceTexture describe the replaced design.

7. **Phone layout covers Live TV only.** Films, series, My List and settings still open in the TV
   layout. MainActivity now handles rotation itself (configChanges), so screens are not rebuilt
   when a phone turns.

## Decisions

- Version 7 → 8 channel upgrades must add `main` before renumbering the preferred copies.
  The repair preserves the existing database and encryption key; clearing app data is unnecessary.
  A device regression fixture now omits the column, unlike the older version-number-only test.

- My List, watch history and watched episodes stay in SharedPreferences (history encrypted).
  They are read only on detail pages and menus and the history is capped at 50, so a database
  would add migration risk without a visible gain (reviewed 18 October 2026).
- Film skips are combined into one jump 750 ms after the last press, as Kodi's default seek delay
  does. LibVLC's `--input-fast-seek` (keyframe seeking, which Kodi also uses) was considered and
  left out: VLC 3 seeks to a keyframe at or before the target, so in files with far-apart keyframes
  a +10 s skip could land behind where you were (the device-test video has keyframes only at 0 s
  and 16.7 s). Kodi avoids that by seeking forward to the next keyframe. Revisit only with a test
  on real files (18 October 2026).
- The film buffer stays at 1.5 s (`--network-caching=1500`, shared with Live TV). A larger buffer
  delays the start and every jump, which were the slow parts reported (18 October 2026).

- Big guide lists (all channels, sections, countries) read each channel's preferred copy
  (`main=1`, database version 8) through an index instead of grouping every copy. Favourites,
  provider groups and searches still group, because they can match a backup copy only (each copy
  has its own key, group and name). Checked equal to the grouped queries in SQLite (19 October
  2026).

## Open pull requests and branches

- **Dependabot:** #3 (`actions/checkout`) is superseded by checkout@v5 in CI and can be closed.
  #13 (LibVLC 3.7.7) fails the APK build and lint step, both in Dependabot's run and when retried
  on the 0.15 branch; the CI log was not readable from the assistant's environment, so the cause is not
  known yet. LibVLC stays on 3.7.0. #12 (AGP 9): see above.
- Branches that are merged or no longer needed can be deleted on GitHub (the assistant's proxy
  cannot delete branches): `ci/device-test-control`, `ci/flaky-measure`, `refactor/readable-structure`,
  `fix/flaky-device-tests`, `feature/launcher-shim`, and every `feature/*` or `fix/*` branch whose
  pull request is merged.
- `release/moviehub-*` branches are one-off publish jobs and are intentionally not merged.

## Suggested next steps

1. Check 0.15 on the box: For you, the Apps screen and banners, side panels, Sources, Series and
   the player controls.
2. Optional: a TMDB account sign-in so My List syncs to a TMDB watchlist and TMDB's personal
   recommendations can be mixed into For you.
3. Check Live TV on the box with a large channel list (the full free list): guide pages, channel
   up/down and typing numbers should feel quicker. Diagnostics has no guide timing yet.

## Native free-TV work (19 October 2026)

PR #81 adds native regional Pluto discovery, resolve-on-play, source cancellation, guide data and Settings → Free channels controls on top of #80’s optional hosted connection. A real GB catalogue returned 187 channels; one sample decoded successfully on desktop. Other upstream providers are not advertised as native. Validation and release status are linked in the PR and GitHub release; physical TV checks remain outstanding. See [the full evidence record](docs/NATIVE_FREE_SOURCES.md).

## Standalone engine development

Experimental local JS engine and FreeLiveSports wiring are implemented on a development branch.
This is incomplete, opt-in and not a production release. The current Masqueradarr server submodule
returns 404; current adapter parity and automatic inheritance are blocked by source access.
Signing-key setup and physical ARM64 playback checks remain outstanding. Full implementation,
provider matrix and evidence: [standalone provider engine](docs/STANDALONE_PROVIDER_ENGINE.md).

## Extended Android provider port

Draft PR #83 now adapts fifteen additional historical upstream providers through Runtime 2, including
DaddyLive's multi-player resolver and Tubi's signing/bootstrap. Discovery has succeeded for several FAST
platforms; DaddyLive discovery alone does not establish playback. Remaining unported dulo login
and HDHomeRun transport are documented. Nothing is merged or released by this work.

The standalone-engine code at `4ca05fdf89eebc659e7ba8f3e0c66cdd04b72abf` passed build/lint, signing
compatibility, provider verification and the complete TV emulator suite. A debug APK is available in
[CI artifacts](https://github.com/billyke2000s/moviehub/actions/runs/39341576650). Ten added providers
have one decoded desktop media sample each; this does not prove physical TV playback or all channels.
The PR remains draft, and automatic remote provider updates remain unconfigured.

## Provider channels as backups (20 October 2026)

Provider-engine channels now join the existing Live TV guide and backup switching instead of
standing alone: copies of one channel across free lists, accounts and providers share a duplicate
key (`ChannelIdentity`, database version 9), provider channels go into the shared sections
(`ChannelCategory`), and a failed source moves to the next copy once each. Enabled providers no
longer depend on the free-list setting. Checked with core checks, the real Free-TV/iptv-org lists
on a computer, and fixture device tests in CI. **Not yet seen on the TV box or on a real failing
provider stream**; whether a given provider's Sky Sports copy actually plays depends on that provider.

Flaky device tests seen 20 October 2026: run 39357280476 (attempt 1) failed
`EpgImporterTest.matchesChannelsAcrossNamingStylesAndKeepsOnlyCurrentProgrammes` (a parsed XMLTV time
130 s off) and `LiveGuideTest.pagesWrapRoundLikeTheSatelliteGuide` (timed out on "first page again").
The commit changed neither area, both passed on the two previous commits, and attempt 2 passed
unchanged. Cause not found; watch for a repeat.

## Live provider probe (20 October 2026, GitHub runner, United States)

`provider-engine/tools/live-probe.mjs` (workflow "Live provider probe") runs each provider with the
app's own interpreter, allow-lists and limits, then decodes samples with ffmpeg. Run 39361949935,
after raising the limits:

| Provider | Catalogue | Sample playback | Fault |
| --- | --- | --- | --- |
| STIRR, TCL, Xumo, LG, Whale, Vizio, Roku, Tubi, FreeLiveSports, Local Now | works | plays | — |
| Samsung TV Plus (GB) | 252 channels | some play; some GB streams fail from a US runner | probably region |
| Plex | 692 channels | stream 404 | provider |
| DaddyLive | 899 channels (45 Sky Sports) | original probe: 403; missing token step identified below | incomplete integration |
| DistroTV, Vidaa | host does not resolve (ENOTFOUND) | — | provider |
| ZLive | catalogue 404 | — | provider |

Before the fixes, Samsung (GitHub redirect not allowed), Tubi and Plex (over the 3/4 MB limits)
failed inside MovieHub. Not checked on the TV box: catalogue steps there are slower (Plex's slowest
step took 2.9 s on the runner; the box limit is now 10 s), and UK region results may differ.

## DaddyLive investigation correction (20 October 2026)

The original "outside players refused" conclusion was too broad. The actual CDN response was `token
required`: the current player bootstraps an anonymous 120-second playlist token, which the historical
resolver omitted. Adding that ordinary request returned HTTP 200 HLS. Media then needed PNG container
unpacking; direct FFmpeg initially failed. After unpacking, Main Event, Football, Action, F1 and ABC USA
samples each decoded successfully (FFmpeg exit 0). The original walk also exhausted its 20-second budget
before later players, so it was not evidence about every player.

The development fix adds the current token protocol, scoped in-memory renewal and bounded PNG transport
to the existing native player/relay. Local core checks, new PNG fixtures and relay/session fixtures have
passed; live checks and Android CI are recorded with the change. Physical TV, long sessions and all-channel
availability remain unverified. No release or in-app update has been published by this fix.

## Box report: hidden provider failure (20 October 2026)

The user's TV reports no successful DaddyLive refresh, before the playback resolver is reached. Its
failure description is clipped because Settings cards had a fixed 78dp height; the guide also clips
large text in short rows. This follow-up fixes card sizing, places failures before catalogue metadata,
and retains safe HTTP/network/runtime failure categories rather than only the outer exception class.
Guide labels now fit row height. This is a readability and diagnosis fix: the actual cause of the
reported box's DaddyLive catalogue failure is still unknown until another refresh records its category.

## Main Event playback report (20 October 2026)

The latest TV screenshot shows DaddyLive discovery succeeding (899 channels), so the previous catalogue-failure report is no longer current. Playback is a separate path: selecting its stored channel identity runs the player-chain resolver and hands a transient session to the native relay. A fresh Main Event (38) walk returned valid HLS/session metadata through Player 1 in about 28 seconds in this workspace, using the bundled module in Node VM and curl transport. The actual JVM step hit its processing deadline in this slower environment; this Node observation is not Android interpreter evidence.

A confirmed app mismatch started the 15-second video-startup timeout before provider resolution, even though the broker allows a longer bounded lookup. The fix starts that timer when LivePlayer has the URL/relay, retaining immediate resolution-error failover and cancellation. Physical TV playback remains to be tested; a successful directory refresh alone never proves playback.

The same fresh Main Event result returned HTTP 200 media HLS and a 6,803,517-byte PNG segment. Production ProviderPngSegments unpacked it on Java 17; FFmpeg decoded four seconds of video with exit 0. This verifies a desktop sample and the native unpacker, not physical TV playback or a complete native-network relay session.

## Sustained playback report (20 October 2026)

The user confirmed Main Event starts on the physical TV, then reported repeated freezes and a frame stuck for several minutes without a buffering spinner. This verifies startup only, not sustained playback or token renewal. The exact TV-side cause remains unknown. Confirmed code gaps: only VLC buffering/error events could initiate recovery, and PNG processing unnecessarily walked every byte of filter-0 rows. The latest sample used filter 0 on 3,815 of 4,477 rows.

The fix adds native/provider progress monitoring (clock plus displayed-picture counter) with the existing eight-second threshold, one provider retry then normal backup/error handling; paused/hidden playback is excluded. PNG bulk copying and larger inflate buffers improve desktop decoding with identical output. This does not prove that provider-side frozen content, timestamp discontinuities, hardware decoding or network delivery is fixed on the box. Token renewal already has bounded fixture coverage; this report alone does not identify a renewal failure.

## Live guide/full-screen handoff repair

The previous shared-player handoff still detached LibVLC's native video output. LiveVideoOutput
now retains its SurfaceTexture/Surface across both presentations, with a temporary last-frame
cover until fresh video arrives. Stronger emulator checks verify rendered output and repeated
round trips, rather than only the playback clock. Revision `89f90e285b7944b1be93f40b9cde40dfef7df873` passed
formatting, all 293 core checks, APK/debug/release builds and lint, signing compatibility,
provider checks and the complete API 28 TV emulator suite. Eight measured handoffs produced
fresh destination frames in 47–104 ms, preserving the output and avoiding stream restarts.
Physical-TV frame pacing and seamlessness are not yet verified. Local execution was unavailable;
checks ran in CI.

- [Android validation](https://github.com/billyke2000s/moviehub/actions/runs/39492778810)
- [Provider validation](https://github.com/billyke2000s/moviehub/actions/runs/39492778815)
- [Test APK ZIP](https://github.com/billyke2000s/moviehub/actions/runs/39492778810/artifacts/12296945928)

The earlier repeated-trip test reused an ActivityMonitor after waitForMonitorWithTimeout
removed it. Both first trips passed with fresh frames; the second trips could not detect their
Activity. The test now registers a new monitor per trip, and the final full suite passed.

## Live playback, controls and FreeShot (20 October 2026)

The user confirmed instant mini-player/full-screen movement on the physical TV, then reported
video freezing within about five seconds while audio continued, audio missing after a reload,
and playback recovering on a move back to the mini-player. Physical-TV sustained playback and
the precise cause of its audio symptom remain unverified.

The candidate counts actual TextureView updates even when presentation timestamps repeat and
tries one consumer-only refresh for stopped rendered video while the clock advances. It keeps
the decoder, network session and audio output. Persistent stalls use bounded source recovery.
The relay also accepts `Range: bytes=0-` for wrapped segments by fetching/decoding the complete
entity instead of applying decoded offsets to its PNG container.

First Back reveals the live controls and Sources; the next returns to the retained mini-player.
Sources defaults to Automatic. A manual choice pins that source; restoring Automatic tries
unvisited copies once, including earlier copies, then stops. The menu reads saved database rows;
only a selected source or needed failover is resolved.

FreeShot (UK sports) is available as an opt-in source in Settings → Provider engine. It imports
the public related-channel directory in one request, follows only the chosen channel's literal
embed/player links, normalizes relative/whitespace-containing iframe addresses, and uses the
native player. Fetched page scripts and adverts never execute. Anonymous tokens renew at the
fixed WideIPTV endpoint within one discovered BlueTier origin and channel prefix. Other website
countries/categories are not imported.

Verified live host evidence: FreeShot Main Event decoded video (149.92 seconds, 3,748 unique
frames) and audio (150 seconds, 7,032 unique frames) through the actual resolver and production
relay, including forced token renewal, with no timestamp gaps. DaddyLive Football decoded both
tracks for 150 seconds without timestamp gaps after the wrapped-range fix. DaddyLive Main Event
showed a roughly 10-second timestamp gap in the host check; this is not proof of the TV's cause.
The desktop diagnostic explicitly preserves Origin to match Android; otherwise Java silently
omits that header.

Local validation: 540 core checks, compiler tests, 21 engine checks, 45 actual-Rhino multi-provider
checks, 17 PNG checks and four real-TCP wrapped-range checks passed. The FreeShot relative-URL and
channel-identity fixtures also passed after normalization. Android run 39513033996 passed all 122
TV emulator tests (zero failures/skips), debug/release builds and lint, instrumentation compilation,
and permanent-signing compatibility. This includes a stopped real texture consumer with continuing
audio, three wrapped-HLS reloads with both tracks advancing, constant EGL timestamps, Back/Sources,
and two guide/full-screen round trips after manually selecting a backup. Player/output identity,
current copy/mode, source-open counts and fresh nonblank video remained correct. Twelve fresh-frame
handoffs measured 44–137 ms on this emulator; this is not a hardware latency guarantee.

[Signed test build](https://github.com/billyke2000s/moviehub/actions/runs/39513033996/artifacts/12307505129)
(source `4ae9dce6e6adeb8d95b319ff46f0eb90897a3b22`). Extract and install `moviehub-test.apk`.
No production update has been merged or published.

## Reviewed pending work (22 October 2026)

The integration branch adapts #82 (VOD recovery), #88 (early still-picture repair), #89 (current SurfaceView diagnostics), and the remaining #90 still-picture cover while preserving subsequent surface fixes. #84, #85 and #87 already belong to main; #92's publishing checks are superseded by a shared core-test script. #91 is a duplicate combined test branch.

#77's empty video registry and conflicting free-channel gate are not imported: DefaultSourceProviders.EXTRA already registers shared movie/series manifests, and ProviderSettings already owns provider opt-in and per-provider settings. Its third-party warning is added to the existing SafetyNotice without silently disabling existing channel preferences. No new provider adapters are claimed. WorkManager is updated to 2.12.0 for current CI validation.

Local core validation: 664 checks passed (Eclipse JDT/Java 17). Android build, lint, emulator and signing validation must be read from this integration PR before merging. Physical TV playback and installation remain unverified.

## Live shutdown follow-up (22 October 2026)

The integration PR passed its full emulator suite, but post-merge run 39821398629 crashed in
native EGL initialization during the channel-key playback test. Live shutdown now cancels
surface callbacks without destroying the native window, stops VLC, then detaches the view.
Release validation is pending on this follow-up; no real-device result is claimed.

## Handoff capture follow-up (22 October 2026)

Post-merge run 39824605890 repeated the native EGL crash during a guide/full-screen handoff.
The attempted 0.45 publish ran all 136 tests with one failure: the manual-backup handoff
reported fresh pictures before its surface had a size. Nothing was uploaded. PixelCopy no
longer delays closing the old video output; destination evidence excludes old decoded frames
and waits for a valid, sized surface. Follow-up device validation is pending.

## Panel lifecycle follow-up (22 October 2026)

The second 0.45 release validation passed playback handoffs but failed two key-event tests
during dialog focus transitions and a teardown that retained a detached dialog. The release
upload was skipped. Panels now clear their reference on window detach and suppress follow-up
actions for a finishing/destroyed Activity. Input tests wait for actual focus; a new device
regression closes an Activity with a panel open. CI validation is pending.

Post-merge run 39827167743 passed 135/136 tests with one screenshot captured during handoff
before destination composition. Handoff evidence now observes actual pixels within the same
three-second total budget, including decoding and composition; validation is pending.

## Published 0.45 and next playback candidate

0.45 was published after PR #102 passed full emulator, build/lint, provider and signing checks;
the independent publish run 39828740034 and post-merge run 39829699262 also passed. The public
numbered APK and updater APK have matching SHA-256 digests. Earlier pending-validation notes
above describe intermediate attempts.

The next candidate improves scheduled link renewal without a second decoder, covers ordinary
IPTV with the existing freeze watchdog and separates bounded buffering profiles. Local core
validation passes 703 checks. Android validation is pending. Cross-provider failover still
restarts playback; preparing a second live decoder is deferred until physical-TV memory and
decoder concurrency can be measured. VOD already has bounded prepared replacements. Cache
profiles are fixed by transport, not dynamically adapted from measured network performance.

Surface recovery is also bounded to one repair until observed video progress resumes. A
repair that hides its surface can no longer reset the watchdog into repeated early repairs.

Release validation 39834499208 passed 142/143 tests, with a panel still retained when its
Activity was destroyed before window attachment. Explicit owner cleanup now covers that path.
PR validation also stopped during the native picture-repair fixture; that fixture now
establishes real frame progress before a second synthetic freeze, and full crash-buffer
diagnostics are preserved for subsequent validation. Main's newer README is retained.

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

## Prepared live backups (23 October 2026)

0.46 was published with matching numbered/updater assets after 703 core checks and 146 Android
tests passed; its post-merge build/device run also passed. Earlier pending notes are history.

The next change adds bounded prepared Live TV failover to the shared session. It preserves
the live 8-second/3-buffer thresholds and the current stream until a single muted candidate
decodes video and available audio. Failed candidates are released and tried once each, with
working original playback retained after exhaustion. Fullscreen and guide ownership, manual
pinning, cancellation, native surface lifetime and provider relay renewal are covered by new
fixtures. A low-memory signal skips additional allocation. Different provider delays mean
programme-time alignment is not promised. Local core checks pass (733); Android CI is pending.
No physical-TV or real-provider concurrent-decoder test is claimed.


## Live TV control consistency

The owner reports colour keys still failing and inconsistent live-player controls. Confirmed code paths allowed live launches without the static guide session to use film controls, learned number/channel codes to be consumed before colour handling, and a fixed-width banner row to overflow. The candidate always builds live controls, persists per-player guide context, matches initial metadata to the launch channel, prioritizes learned colours and bounds banner content. Core checks pass (733); Android CI is pending. Physical remote behaviour and the precise appearance seen on the owner’s TV remain unverified.



## Live header and menu-owned remote keys

The supplied photographs both show SkyOverlay; losing the channel strip is distinct from falling back to film controls. The strip now reserves at least 64 dp while allowing larger text to expand it. Player side panels now forward colour shortcuts after dismissal, since their Dialog window otherwise consumes keys before the Activity sees them. Colour actions complete on release to avoid passing a trailing release into a replacement window. Added fixture checks cover all four colours across open panels and the entire header after number entry, programme updates and Back focus. All 733 core checks pass locally; Android CI and physical remote confirmation are pending. The exact cause of the photographed strip collapse has not been reproduced.

## Live playback recovery

The 0.49 recovery source passed all 166 Android TV emulator tests (no skips), 753 core assertions, Android build/lint, provider-engine checks and updater/signing compatibility (CI run 39985039245). It adds bounded native Pluto/direct reconnects, refreshed anonymous Pluto sessions, off-main native shutdown with retained window lifetime, surface validity checks and optional buffer diagnostics, and explicit Repair picture/Reconnect channel actions. Live 8-second/3-start thresholds and VOD rules remain unchanged. HLS transitions produced 320 decoded video frames and 547 played audio buffers without reconnecting. A native driver that never returns cannot safely be killed in-process; its resources remain quarantined. Physical TV/live Pluto causes remain unconfirmed; comparative Media3 testing has not been done.

Three short buffering notifications can still prepare an alternate source. A same-source reconnect requires prolonged buffering (eight seconds) or stalled video progress; ordinary HLS period notifications do not consume the reconnect budget.


## Consistent live banner and standard colours

The owner supplied new photographs after 0.49: a full banner on some channels and a collapsed strip on others, with colour shortcuts still failing. The next candidate restores first-down colour handling, prevents saved aliases from overriding standard colour identities, reserves explicit scaled header height and measures the banner before optional panels. The constrained-layout case and input edge cases have new regressions. 753 core checks pass locally; Android CI is pending. The exact physical-TV failure remains unconfirmed; no claim that the owner must inspect remote codes or configure standard buttons.


0.50 validation: all 169 Android tests passed without skips in CI run 40006802520, along with builds/lint/signing and provider checks. Inspected normal and constrained banner screenshots; header and all four controls are visible. Core validation: 753 checks. Physical-TV behaviour still requires confirmation.


## One live overlay and shared colour input

The owner confirms the problem persists on the current release. A concrete UI mismatch remained: OK opened Options when the banner was already visible, and TV picture clicks only revealed pause state. The candidate routes entry/OK/click/Back through the same banner and removes the attached film-controls tree from live sessions. Visible video receives initial focus. Colour dispatch is centralized before native access and shared with player Dialogs; first-received repeats, standalone releases and standard scan identities are covered without requiring remote setup or code capture. Core validation: 775 passing checks. Android validation is recorded on the pull request/release. The owner’s exact hardware-input failure is not reproduced and must not be declared conclusively resolved.

## Free VOD integration candidate (23 October 2026)

Bundled all 59 Scrubs V2 and nine Free99 non-archived provider modules and ResolveURL host
extractors. Added free discovery without a Premiumize gate, the three-second best-available
selection window and late-source appending to existing VOD recovery. Website responses are
probed before selection; reported quality is not a measured decoder resolution. Local Java
core checks and Python offline fixtures pass. Android build/emulator validation is pending
on this branch. No production release or real-provider/physical-TV success is claimed.
The Free99 metadata licence field is blank; its Scrubs ancestry and inherited GPL notices
are recorded. Kodi-library and API-key sources are explicitly not represented as working
anonymous website providers. The follow-up removes website-fed eval/exec, bounds direct urllib
requests, adds an external CI availability probe and verifies packaged Python imports on Android.

## VOD incoming-speed correction

The candidate counts incoming upstream relay bytes for free HLS/media-file sources, shows an explicit waiting state instead of blank statistics, and strengthens ready-link ranking. It preserves the three-second selection window and existing failover thresholds. Local fixtures and CI validate code paths; physical-TV validation is outstanding. A startup sample does not guarantee future host performance.

## Free VOD source audit and failure recovery candidate (24 October 2026)

The owner reports mostly empty providers, short error clips, absent speed and crashes on switching services. All 68 module constructors and original package hashes were checked: 59 Scrubs and nine Free99, no missing active files. Confirmed code gaps: discarded provider outcomes, a global deadline shorter than the worst-case worker schedule, manual free-provider pinning, no short-duration rejection and main-thread native shutdown. The candidate addresses these gaps and shows selected source, Mb/s and cumulative incoming bytes. The three-second first choice and existing buffering thresholds remain. See docs/FREE_VOD_AUDIT.md for the per-module inventory. Local live DNS is unavailable; provider availability must be recorded from the independent GitHub audit. Android/emulator validation is pending. No physical-TV crash diagnosis or real-title availability is claimed.

Additional confirmed compatibility defect: the bounded HTTP adapter returned an HTTP status integer in place of request headers in `output="extended"`. The actual WatchSeries provider then passed that integer as headers and swallowed the resulting exception. The adapter now returns the original five fields (body, request headers, response headers, cookies, final URL), without an extra cookie request. Default scrapePage Referer/protocol-relative handling and redirect cookie accumulation also match the upstream contract. A fixture drives the real WatchSeries provider through search, page and host lookup.


## Provider request/title compatibility correction (24 October 2026)

Reproduced and fixed bodyless POST becoming GET in the requests adapter, affecting the
actual Scrubs Levidia search. Added legacy default Referer/language headers, upstream
movie/episode title preparation and original/alternative TMDB aliases. Providers now have
30 seconds each and 300 seconds of background search headroom; the three-second initial
choice and buffering thresholds remain. Seventeen Python tests and 814 core assertions pass
locally. Android CI and real-provider outcomes remain to be checked on the final commit.
No Guardians playback or physical-TV success is claimed.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.


## EGL crash during film-to-Live-TV transition

Provider revision 202ecee passed Android build/lint/signing, core/Python/provider checks,
but emulator run 40100218911 crashed after 121 passes while the native-channel test closed
its initial film Activity. The prior asynchronous VOD join retained the Java view/engine
but could not prevent Android from destroying its SurfaceView buffer queue. Films now
use retainable TextureViews; VodSurface owns detached textures until native stop and
detachViews finish. A blocked-stop/Activity-destruction regression verifies this lifetime.
Full new Android validation is pending; no physical-device fix is claimed.


## One OK overlay and learnable unnamed colour buttons (24 October 2026)

The owner wants OK to always show the most detailed overlay, and reports that the coloured buttons of a universal/air-mouse remote do nothing (colour set-up never used). Confirmed code paths that showed different overlays: OK after BACK opened Sources when no button was focused; OK while browsing the current channel closed the banner; ◀ opened the reduced browsing layout; an air-mouse click showed the banner without information. These now show the same full banner (owner's photo confirmed banner only, no separate information panel). The live player also names unused buttons in a toast, to identify what the owner's MX3-style air-mouse remote sends; the owner reports its colour buttons work in other apps, and the colour mapping itself is unchanged since 22 October. Confirmed colour gap: buttons arriving as KEYCODE_UNKNOWN (other than the standard 398–401 scan codes) could not be learned, because set-up stored only the key code (0). Set-up now stores scan codes and shows what was received. Whether this remote sends its colour buttons to Android at all is unknown; running "Set up coloured buttons" (Live TV guide → Options) will show it. Core checks pass locally; Android build/device tests run in CI only (no SDK in the workspace). Not tested on the owner's TV or remote.

## One banner look (24 October 2026, after 0.56)

Owner photographs on 0.56 showed the banner with unboxed buttons (OK/channel change), with boxed buttons (after BACK, the look the owner wants) and once with the channel header strip missing. The banner now always boxes its buttons and ◀ ▶ move between them; the browsing layout is removed. The missing header strip is still unexplained: the strip has a fixed reserved height, and no app code hides it or draws over it. Its trigger on the owner's TV is unknown.

## Experimental ExoPlayer Live TV (24 October 2026)

At the owner's request, Live TV on Media3 ExoPlayer is being built as a separate MovieHub
Experimental app, keeping the normal app on LibVLC. First version: same guide and banner, ExoPlayer
shared between the guide picture and the full screen. Audio track choice only (no subtitle
rendering yet); no prepared standby backup. Built in CI only; not yet run on the owner's TV.

exp-2 candidate: one-screen Live TV (`LiveStage`) replaces the separate ExoPlayer full-screen
Activity. Same guide and banner code. Not yet compiled at the time of writing; not run on a TV.


## Experimental release follow-up (25 October 2026)

The published exp-3 predates the latest OK/banner changes. This candidate cancels stale engine
and screen retries, cancels abandoned resolution, and releases the static Media3 player/view
on stop. A dedicated experimental emulator suite now covers actual Media3 playback, picture
continuity, 404 retry cancellation, lifecycle and HLS tracks. Results must be checked on the
final CI commit before publishing exp-4. No physical-TV or real-provider success is claimed.
Frozen-picture detection remains diagnostic, preserving the experimental comparison behaviour.
