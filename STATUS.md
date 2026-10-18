# Status

Last updated: 18 October 2026.

## Where things stand

- **Latest published version:** 0.15 (version code 15), published 18 October 2026, with update
  notes in the feed. Items under *Unreleased* in [CHANGELOG.md](CHANGELOG.md) are not on TVs yet.
- **Code structure:** feature classes per area, see [ARCHITECTURE.md](ARCHITECTURE.md).
  `CatalogueScreens` was split (filter sheets in `CatalogueOptions`); no file is over 700 lines.
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
   Tools 36. It is not in a published version yet; the next publish is the first built with it.

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
