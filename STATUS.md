# Status

Last updated: 17 October 2026.

## Where things stand

- **Latest published version:** 0.10 (built from PR #19). Everything listed under *Unreleased* in
  [CHANGELOG.md](CHANGELOG.md) is on `main` but not on TVs yet.
- **Code structure:** restructured in PR #25. Sources are formatted, `MainActivity` is split into
  feature classes, and project documentation exists. See [ARCHITECTURE.md](ARCHITECTURE.md).
- **CI on `main`:** build, lint and signing checks pass. The device-test job fails; see below.

## Known issues

1. **Device tests fail or are flaky.** The same source tree passed on PR #24 and failed on `main`
   minutes later, and every completed `main` run since the morning of 17 October has failed.
   On 17 October PR #25 and unchanged `main` were run side by side (runs 39067415466 and
   39067416543). PR #25 passed every device test; `main` failed one,
   `ArtworkTest.remoteScrollingPastFirstPageAndBackLoadsEveryRecycledPoster` ("Artwork did not
   become ready"). An earlier PR #25 run also failed before failures were being reported, so the
   test that failed then is unknown. One run each is not enough to call any test stable; artwork
   loading timing is the first suspect. Failed tests are now listed as annotations on each run.
2. **Errors are often swallowed.** About 38 `catch (Exception ...)` blocks, 8 of them empty, hide
   failures from the user and from logs. Some are intentional (optional features), but failures on a
   real TV can be silent. Review them one area at a time, and log or show a message where useful.
3. **Not verified on real accounts or the target TV.** Automated tests use fixtures. Real TMDB,
   Premiumize, Xtream and M3U accounts, and installing and updating on the TV box, need a manual check.
4. **Some tests use reflection** to reach private members by name. A rename can break them without
   a compile error. See [TESTING.md](TESTING.md#members-reached-by-reflection).
5. **CI maintenance.** GitHub warns that the actions in use target the deprecated Node 20 runtime
   (`actions/checkout@v4`, `setup-java@v4` and others). The `ubuntu-latest` runner label moves to
   Ubuntu 26 from 19 October 2026; watch the first runs after that date.

## Open pull requests and branches

- **PR #22** "Align TV navigation device tests with Browse panel": the tests on `main` already
  use the Browse panel, so this looks superseded by later work. Review it and close it if so.
- **Dependabot PRs:**
  - Still relevant: #3 (`actions/checkout` 4 → 7), #13 (LibVLC 3.7.0 → 3.7.7) and #12 (Android
    Gradle plugin 8.7.3 → 9.4.1, a major upgrade that needs care and a full device-test run).
  - Stale: #1, #2, #4, #5 and #10 update actions or a `cloudflare/` folder that this repository no
    longer uses. They can be closed.
- `release/moviehub-*` branches are one-off publish jobs and are intentionally not merged.
- Several older feature branches are fully merged and can be deleted.

## Suggested next steps

1. Make the device tests reliable, then require them to pass before merging.
2. Manually check the unreleased changes on the TV, then publish the next version (0.11).
3. Reduce silent `catch` blocks, starting with playback and account setup.
4. Replace test reflection with direct package-private calls.
