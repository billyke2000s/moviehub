# Testing

There are three layers of checks. CI runs all of them on every pull request that touches
`android-tv-launcher/` (workflow: `.github/workflows/android-launcher.yml`).

| Layer | Where | Needs | Runs in CI step |
| --- | --- | --- | --- |
| Core checks (85) | `android-tv-launcher/tests/` | Only a JDK | "Verify playlist, network, channel search and episode rules" |
| Build and lint | Gradle | Android SDK 35 | "Build APK and run Android lint" |
| Device tests (instrumentation) | `android-tv-launcher/app/src/androidTest/` | Android emulator | `device-tests` job |
| Signing compatibility | release build vs published APK | signing secrets (same-repo PRs only) | `signed-compatibility` job |

## Core checks (fast, run these locally)

Pure-Java rules for playlists, URLs, channel search and episode matching. They do not need Android.

```sh
mkdir -p build/core-tests
javac -d build/core-tests \
  android-tv-launcher/app/src/main/java/local/tv/home/{Playlist,Network,VideoFiles,ChannelQuery,EpgMatch,SkyNumbers,GuideOrder}.java \
  android-tv-launcher/tests/{PlaylistTest,NetworkTest,VideoFilesTest,ChannelQueryTest,EpgMatchTest,SkyNumbersTest,GuideOrderTest}.java
for t in PlaylistTest NetworkTest VideoFilesTest ChannelQueryTest EpgMatchTest SkyNumbersTest GuideOrderTest; do
  java -Xmx64m -cp build/core-tests local.tv.home.$t
done
```

Expected output: 17 playlist, 9 network, 10 episode/file, 13 channel-query, 18 programme-guide
matching, 17 satellite numbering and 15 guide order checks passed.

## Device tests

Instrumentation tests drive the real app with D-pad key events on an emulator. They cover the
encrypted channel database, imports and migrations, LibVLC playback, Home and dock navigation,
catalogue paging and focus, the streaming menus and artwork loading, and Live TV: guide paging and
number entry, the Options button and guide menu, moving along programmes and through time, programme information, the PIN lock, reminders, channel changing and browsing in the
banner, the satellite-box overlay (banner, information panel, pause bar), pausing live TV, the
guide's live picture, and moving the live picture between the guide and the full screen without
reopening the stream (all played from a local test stream). Provider responses are local
fixtures, so these tests **do not** prove that a real TMDB, Premiumize or Xtream account works.

```sh
bash android-tv-launcher/tests/run-device-tests.sh   # needs a running emulator or device
```

The tests run through `MovieHubTestRunner`, which marks the first-start safety notice as read and
chooses the TV layout, so neither covers the screens under test; `SafetyNoticeTest` and
`PhoneModeTest` show them on purpose.

The script runs `connectedDebugAndroidTest` and then pulls the test screenshots off the device.
CI runs it on an API 28 `tv_1080p` emulator. Note that the CI step is named "Verify real SQLite,
encryption, migrations and pagination", but it runs **every** device test, so a failure in that
step can come from any test.

Some tests save screenshots (`UiEvidence.capture`) that CI uploads as the `android-tv-device-tests`
artifact.

### Device-test reliability

Until 17 October 2026 the device tests failed at random, even on unchanged code. Running the full
suite 8 times in parallel on CI found three causes:

| Cause | Kind | Fix |
| --- | --- | --- |
| Quick D-pad presses moved focus to a poster just past the edge; a layout then recycled it and focus was lost | **App bug** (users could lose the selection) | Poster rows and grids keep half a screen laid out beyond each edge, and refocus the same title if focus is still lost (`PosterFeed`) |
| A Back press sent while a dialog was closing reached no window and was dropped | Test timing | `TvNavigationTest.back()` waits for window focus before the next key |
| Controls were looked up once, immediately after a screen change | Test timing | `TvNavigationTest.select()` waits up to 5 s |

Results of 8 parallel runs of the whole suite:

| Code | Passed |
| --- | --- |
| Before the fixes | 3 / 8 |
| After the focus fix | 4 / 8 |
| After keeping off-edge posters laid out | 6 / 8 |
| After the Back fix | **8 / 8** |

Eight passing runs are good evidence, not proof. If a device test fails, read its annotation: CI
lists each failed test with its message on the run (`tests/report-test-failures.py`), and timeouts
in `ArtworkTest` and `TvNavigationTest` describe the screen state when they fail.

### Catalogue rows

`CatalogueRowsTest` drives the Movies rows page with fixture data (`CatalogueRows.testLoader`, never
set outside tests): first poster focused on entry, no captions, the hero follows focus, each focused
row is pinned at the top, only the first rows load on entry, Up reaches the header, and Back from a
grid opened from the rows returns to the same row.

### Other feature tests (October 2026)

| Test | Covers |
| --- | --- |
| `ForYouTest` | For you ranking (shared recommendations rise, seeds and played movies hidden), trending fallback, caching until signals change, old seed migration. Fixture TMDB responses. |
| `AllAppsTest` | Apps screen: Android shortcuts and Recently used shown, search box not focused on entry, search filters by name, hide and Show hidden. Uses the emulator's installed apps. |
| `SidePanelTest` | Side panel focuses and ticks the current choice and runs it after closing; `ErrorMessages` explanations never include provider text. |
| `DiagnosticsTest` | Timings record and finish once; the Diagnostics page lists them. |
| `SecretsTest` | Encrypted on disk, cached reads, replace and remove. |
| `ArtworkDiskCacheTest` | Artwork disk cache: hit, miss, least-recently-used trimming, oversized files skipped. |

## Tests call app code directly

Device tests live in the same package as the app (`local.tv.home`), so they call package-private
members directly, for example `activity.libraryScreens.toggleWatchlist(title)`. The compiler
checks every one of these calls, so renaming or moving a member breaks the test build immediately
instead of failing at run time. Do not use reflection to reach app members in tests; make the
member package-private instead.

## What no automated test covers

- Real provider accounts (TMDB, Premiumize, Xtream, M3U) and live provider availability.
- Installation and updates on the actual target TV box.
- How real apps' TV banners look on the Apps screen, and the update prompt's notes from a real feed.
- How the screens look on a real TV (screenshots from the emulator are a partial substitute).

Those need a manual check on the device before a release.
