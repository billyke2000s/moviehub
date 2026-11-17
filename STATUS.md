# Status

Current state only. Finished work moves to [CHANGELOG.md](CHANGELOG.md); the full history up to
1.0.19 is in [docs/archive/STATUS_HISTORY_TO_1.0.19.md](docs/archive/STATUS_HISTORY_TO_1.0.19.md).

## Current release

**MovieHub 1.0.19** (version code 119), published 29 October 2026:
[release page](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.19).
One signed APK with both `armeabi-v7a` and `arm64-v8a`; package `local.tv.home`; feed
`android-tv-latest`. SHA-256 `97ad0a88b1cdb981655fdaca34006fcc02345a04bd5a0fa2bfe399b5fbbdac6d`.
Release evidence (CI runs, checks) is in the archived history linked above.

To publish the next version, run **Release MovieHub** (`release-moviehub.yml`) by hand on `main`;
see [UPDATES.md](android-tv-launcher/UPDATES.md).

## Open problems

- **Some film/series sources fail.** Vidy playback failures and other site/host errors from the
  1.0.19 CineStream additions are unresolved; a registered source is not proof it plays.
- **Code over the 700-line limit** (AGENTS.md), each to be split in a behaviour-free commit:
  `LiveZapper` (866 lines), `LiveTvScreen` (774), `ChannelDatabase` (731), `LiveStage` (728).
- **Many stale branches** (about 250 on GitHub). Merged `feature/*`, `fix/*`, `docs/*` and
  `release/*` branches can be deleted on GitHub.

## Known limitations

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

7. **Relayed sports streams used a 4 s buffer** in the removed LibVLC live player; ExoPlayer keeps its
   default buffering (see item 10). The relay
   retries a timed-out provider once. Whether this removes the short pauses seen on the owner's
   phone (21 October 2026) is not yet confirmed on a phone or the TV box.

8. **Live TV now draws through a SurfaceView** (21 October 2026), replacing the shared
   TextureView after the owner saw full-screen lag and a still picture over playing sound. Moves
   between the mini picture and full screen keep the stream but may blink. Emulator tests only;
   not yet checked on a phone or the X905X5M box. Earlier notes below about the retained
   SurfaceTexture describe the replaced design.

9. **Phone layout covers Live TV only.** Films, series, My List and settings still open in the TV
   layout. MainActivity now handles rotation itself (configChanges), so screens are not rebuilt
   when a phone turns.

10. **Live TV stream rules ported from the LibVLC player (27 October 2026).** Buffering, dead-stream,
    reconnect-budget and provider-link-renewal rules now apply to the ExoPlayer live player
    (`LiveStallRules`). Checked by core tests and emulator fixtures only; not yet seen on a real
    stalling provider stream or the TV box. Link renewal has no device test. The LibVLC 4 s relayed
    buffer was not ported; watch for short pauses on FreeShot/DaddyLive/ZLive.

11. **Live TV quality upgrades (27 October 2026).** Quickest source first, then a better-named source
    takes over as soon as it is found, with a 60 s trial, fallback and cooldown (`LiveUpgrade`).
    Emulator fixtures only. Candidates are judged by their names only. The copy playing counts at the
    better of its name and its measured picture, so a copy whose name overstates its quality is not
    upgraded away from. Plain-address checks open one extra
    request per candidate; not checked against providers that limit connections. Only the full screen
    upgrades; the guide's corner picture does not.

12. **LibVLC live player removed (27 October 2026).** Nothing could reach it (Live TV has played on
    `LiveStage`/ExoPlayer since 1.0), so its classes, its Live TV mode in `VlcPlaybackActivity` and its
    tests were deleted; films and series still play on LibVLC. The picture click (air-mouse OK, phone
    tap) it had was ported to `LiveStage`. `LiveControlsTest` now runs on `LiveStage`. Emulator only.

13. **Continue watching shows the next episode (27 October 2026).** One row per film or series,
    nothing under three minutes, and a finished episode's series offers the next aired episode
    (`ContinueWatching`, `LibraryScreens`). The next-episode lookup uses TMDB each time the screen
    opens; it has only been checked with fixtures, not real series. History saved before this change
    still has its old records; they are grouped the same way.

14. **Thumbs up/down, New episode, Anime section (27 October 2026).** Ratings are stored on the box
    and steer For you (`TitleRatings`, `ForYou`); Continue watching marks next episodes aired in the
    last week; Home has an Anime section built from TMDB discovery (`TmdbCategories.anime`). Checked by
    core tests and emulator CI only; awaiting the owner's check on the box. [Unverified] whether the
    👍/👎 symbols draw on every box's font.

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
