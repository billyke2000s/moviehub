# Testing

There are three layers of checks. CI runs all of them on every pull request that touches
`android-tv-launcher/` (workflow: `.github/workflows/android-launcher.yml`).

| Layer | Where | Needs | Runs in CI step |
| --- | --- | --- | --- |
| Core checks (45) | `android-tv-launcher/tests/` | Only a JDK | "Verify playlist, network, channel search and episode rules" |
| Build and lint | Gradle | Android SDK 35 | "Build APK and run Android lint" |
| Device tests (instrumentation) | `android-tv-launcher/app/src/androidTest/` | Android emulator | `device-tests` job |
| Signing compatibility | release build vs published APK | signing secrets (same-repo PRs only) | `signed-compatibility` job |

## Core checks (fast, run these locally)

Pure-Java rules for playlists, URLs, channel search and episode matching. They do not need Android.

```sh
mkdir -p build/core-tests
javac -d build/core-tests \
  android-tv-launcher/app/src/main/java/local/tv/home/{Playlist,Network,VideoFiles,ChannelQuery}.java \
  android-tv-launcher/tests/{PlaylistTest,NetworkTest,VideoFilesTest,ChannelQueryTest}.java
for t in PlaylistTest NetworkTest VideoFilesTest ChannelQueryTest; do
  java -Xmx64m -cp build/core-tests local.tv.home.$t
done
```

Expected output: 17 playlist, 9 network, 6 episode/file and 13 channel-query checks passed.

## Device tests

Instrumentation tests drive the real app with D-pad key events on an emulator. They cover the
encrypted channel database, imports and migrations, LibVLC playback, Home and dock navigation,
catalogue paging and focus, the streaming menus and artwork loading. Provider responses are local
fixtures, so these tests **do not** prove that a real TMDB, Premiumize or Xtream account works.

```sh
bash android-tv-launcher/tests/run-device-tests.sh   # needs a running emulator or device
```

The script runs `connectedDebugAndroidTest` and then pulls the test screenshots off the device.
CI runs it on an API 28 `tv_1080p` emulator. Note that the CI step is named "Verify real SQLite,
encryption, migrations and pagination", but it runs **every** device test, so a failure in that
step can come from any test.

Some tests save screenshots (`UiEvidence.capture`) that CI uploads as the `android-tv-device-tests`
artifact.

### Known problem: flaky device tests

As of 17 October 2026 the device tests are **not reliable**. The same source tree has passed on a
pull request and then failed on `main` minutes later (PR #24 head `4e076b0` passed; the identical
tree pushed as `84b6487` failed in the device-test step). Every completed CI run on `main` since
03:24 that morning has failed. The PR #23 notes record 10 artwork/catalogue tests failing on
unchanged `main`.

A side-by-side run on 17 October (PR #25 against unchanged `main`) failed on `main` only in
`ArtworkTest.remoteScrollingPastFirstPageAndBackLoadsEveryRecycledPoster` ("Artwork did not become
ready") and passed on PR #25. When device tests fail, CI now lists each failed test with its message
as an annotation on the run and in the job summary (`tests/report-test-failures.py`).

Until this is fixed:

- A red device-test run is not proof that a change broke something. Compare against a run of
  unchanged `main`, or re-run the job.
- A green run is real evidence, but one run is not proof of stability.
- Fixing the flakiness (likely timing waits around RecyclerView layout, artwork loading and focus)
  is the top testing priority. See [STATUS.md](STATUS.md).

## Members reached by reflection

Several device tests read private fields or call private methods by name using reflection. The
compiler cannot see these, so **renaming or moving one of these members breaks the test only at
run time**. If you change one, update the test in the same commit.

| Class | Members |
| --- | --- |
| `MainActivity` | fields `page`, `homeShelf`, `catalogueDialog`; method `screen(String, Runnable)` |
| `AppDock` | fields `homeDock`, `movingApp`; methods `saveVisibleDock()`, `addApp(...)`; nested class `AppDock$App` (3-arg constructor) |
| `HomeScreen` | method `home()`; nested `HomeShelf` with `load(String, PosterFeed.Loader)`, `show(JSONArray, String)`, field `feed` |
| `CatalogueScreens` | field `catalogue`; method `browse(String, String, String, int)`; nested `CatalogueScreen` field `content` |
| `CatalogueSearch` | `rememberSearch(String)`, `searchCatalogue(String)` |
| `LibraryScreens` | `watchlist(String, String)`, `toggleWatchlist(JSONObject)`, `isWatchlisted(JSONObject)` |
| `SeriesScreens` | `renderSeasons(JSONObject)`, `renderEpisodes(JSONArray, JSONObject, int, String, int, String)` |
| `SourcesScreen` | `renderSources(LinearLayout, List, String, SourceRepository, JSONObject, String, int, int, String, String)` |
| `SettingsScreens` | `accountScreen(String)` |
| `TitleDetails` | `renderDetails(JSONObject)` |
| `VlcPlaybackActivity` | fields `player`, `sleepAt`, `triedAlternative` |

A better long-term fix is for tests to call these package-private members directly (the tests are
in the same package), so the compiler checks them.

## What no automated test covers

- Real provider accounts (TMDB, Premiumize, Xtream, M3U) and live provider availability.
- Installation and updates on the actual target TV box.
- How the screens look on a real TV (screenshots from the emulator are a partial substitute).

Those need a manual check on the device before a release.
