# Status

Last updated: 17 October 2026.

## Where things stand

- **Latest published version:** 0.10 (built from PR #19). Everything listed under *Unreleased* in
  [CHANGELOG.md](CHANGELOG.md) is on `main` but not on TVs yet.
- **Code structure:** restructured in PR #25. Sources are formatted, `MainActivity` is split into
  feature classes, and project documentation exists. See [ARCHITECTURE.md](ARCHITECTURE.md).
- **CI on `main`:** build, lint and signing checks pass. Device tests fail at random on `main` until
  PR #26 is merged; see below.

## Known issues

1. **Device tests were flaky; fixed in PR #26.** The suite went from 3/8 to 8/8 passing in parallel
   runs after fixing a real focus bug in poster rows and two test timing problems. See
   [TESTING.md](TESTING.md#device-test-reliability).
2. **Recovered errors are now logged.** Silent `catch` blocks log the location and exception type
   to logcat (tag `MovieHub`). They still do not show the user a message; consider that per screen.
3. **Not verified on real accounts or the target TV.** Automated tests use fixtures. Real TMDB,
   Premiumize, Xtream and M3U accounts, and installing and updating on the TV box, need a manual check.
4. **CI runner change.** CI actions now use Node 24. The `ubuntu-latest` runner label moves to
   Ubuntu 26 from 19 October 2026; watch the first runs after that date.

## Open pull requests and branches

- **PR #25**: readable code structure and documentation (no behaviour change).
- **PR #26**: device-test reliability, the poster focus fix, error logging, CI action updates.
- These PRs are stacked: merge #25, then #26.
- A companion Home app to survive updates (PR #27) was closed as not needed; after an update, choose
  MovieHub again as the Home app. Its code remains on branch `feature/launcher-shim`.
- **Dependabot:** #3 (`actions/checkout`, superseded by PR #26), #12 (Android Gradle plugin 9, a
  major upgrade to do separately) and #13 (LibVLC 3.7.7) remain open. Stale PRs #1, #2, #4, #5, #10
  and the superseded #22 were closed on 17 October.
- Temporary branches `ci/device-test-control` and `ci/flaky-measure` can be deleted.
- `release/moviehub-*` branches are one-off publish jobs and are intentionally not merged.

## Suggested next steps

1. Install the debug build from CI on the box and check Home, the hotbar, All Apps and playback.
2. Publish 0.11 once that check passes.
3. App drawer: search, hide apps, recently used; a row of system shortcuts (Wi-Fi, Bluetooth,
   display, sound, storage).
4. Upgrade the Android Gradle plugin (Dependabot #12) on its own branch.
