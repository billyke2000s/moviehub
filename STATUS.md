# Status

Last updated: 18 October 2026.

## Where things stand

- **Latest published version:** 0.13 (version code 13), published 18 October 2026. Items under
  *Unreleased* in [CHANGELOG.md](CHANGELOG.md) are not on TVs yet.
- **Code structure:** restructured in PR #25. Sources are formatted, `MainActivity` is split into
  feature classes, and project documentation exists. See [ARCHITECTURE.md](ARCHITECTURE.md).
- **CI on `main`:** build, lint, signing and device tests pass.

## Known issues

1. **Device tests were flaky; fixed in PR #26 (merged).** The suite went from 3/8 to 8/8 passing in parallel
   runs after fixing a real focus bug in poster rows and two test timing problems. See
   [TESTING.md](TESTING.md#device-test-reliability).
2. **Recovered errors are now logged.** Silent `catch` blocks log the location and exception type
   to logcat (tag `MovieHub`). They still do not show the user a message; consider that per screen.
3. **Not verified on real accounts or the target TV.** Automated tests use fixtures. Real TMDB,
   Premiumize, Xtream and M3U accounts, and installing and updating on the TV box, need a manual check.
4. **CI runner change.** CI actions now use Node 24. The `ubuntu-latest` runner label moves to
   Ubuntu 26 from 19 October 2026; watch the first runs after that date.

## Open pull requests and branches

- PRs #25 and #26 are merged. A companion Home app (PR #27) was closed as not needed; its code is on
  branch `feature/launcher-shim`.
- **Dependabot:** #3 (`actions/checkout`, superseded), #12 (Android Gradle plugin 9, a major upgrade
  to do separately) and #13 (LibVLC 3.7.7, needs a fresh test run and a check on the TV) are open.
- Temporary or merged branches that can be deleted: `ci/device-test-control`, `ci/flaky-measure`,
  `refactor/readable-structure`, `fix/flaky-device-tests`.
- `release/moviehub-*` branches are one-off publish jobs and are intentionally not merged.

## Suggested next steps

1. Check 0.12 on the box: Home, the hotbar, All Apps and playback.
2. Try the new Movies/Series rows page and publish it as 0.13 once it looks right.
3. App drawer: search, hide apps, recently used; a row of system shortcuts (Wi-Fi, Bluetooth,
   display, sound, storage).
4. Upgrade the Android Gradle plugin (Dependabot #12) on its own branch.
