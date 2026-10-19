# Status

Last updated: 19 October 2026.

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
