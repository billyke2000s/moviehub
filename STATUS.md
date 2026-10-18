# Status

Last updated: 18 October 2026.

## Where things stand

- **Latest published version:** 0.27 (version code 27), published 18 October 2026, with update
  notes in the feed. Items under *Unreleased* in [CHANGELOG.md](CHANGELOG.md) are not on TVs yet.
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

## Decisions

- My List, watch history and watched episodes stay in SharedPreferences (history encrypted).
  They are read only on detail pages and menus and the history is capped at 50, so a database
  would add migration risk without a visible gain (reviewed 18 October 2026).

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
