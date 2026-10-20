# Status

Last updated: 20 October 2026.

## Where things stand

- **Latest in-app update:** the public [updater manifest](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/update.json)
  is authoritative for `versionName` and `versionCode`. Compare it with the numbered GitHub release;
  merging code does not itself publish an update. The baseline verified before this work was 0.38.
- **Code structure:** feature classes per area, see [ARCHITECTURE.md](ARCHITECTURE.md).
  `CatalogueScreens` was split (filter sheets in `CatalogueOptions`) and the Live TV guide's
  panels are in `LiveTvPanels`. `LiveTvScreen` is about 710 lines; split it further (for example
  colour keys and number entry) before it grows.
- **CI on `main`:** build, lint, signing and device tests pass.

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

6. **Phone layout covers Live TV only.** Films, series, My List and settings still open in the TV
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

## Early video/audio freeze follow-up (20 October 2026)

The user confirmed instant guide/full-screen movement on the physical TV. Sky video then froze
within about five seconds while audio continued; a reload restored video but lost audio, while
moving back to the mini-player restored playback. This strongly implicates the presentation or
reload path but does not establish a particular device/decoder cause.

Confirmed code gaps: timestamp equality discarded valid TextureView callbacks and could retain
a still cover; the freeze monitor used LibVLC picture stats/clock instead of the actual consumer
counter. The candidate removes timestamp gating, monitors rendered buffers, and tries one
consumer-only refresh before existing bounded source recovery, preserving audio and network
sessions. New regression tests exercise constant EGL timestamps, actual consumer notification
loss, and wrapped-HLS reload audio/video progress. Validation is pending.

The first host-JVM sustained diagnostic resolved Main Event and Football but decoded zero
frames because the relay reported IOException. That is an unsuccessful playback probe, not
provider-failure proof; safe error classification is being added. Physical-TV sustained playback
and the reload-audio symptom remain unverified.
