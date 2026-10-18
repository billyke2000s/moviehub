# Architecture

MovieHub is a single native Android app (Java, no WebView, no Kotlin) in `android-tv-launcher/`.
All source lives in one package, `local.tv.home`, under
`android-tv-launcher/app/src/main/java/local/tv/home/`.

## The big picture

```
                        ┌────────────────────────────┐
  Android ─ HOME key ─▶ │ MainActivity (shell)       │── builds every screen through ──┐
                        │ lifecycle, navigation,     │                                 │
                        │ shared state, async()      │                                 ▼
                        └─────────────┬──────────────┘          ┌──────────────────────────────┐
                                      │ extends                 │ Feature classes (one per area)│
                        ┌─────────────▼──────────────┐          │ HomeScreen   AppDock          │
                        │ TvActivity (base class)    │◀─ uses ──│ CatalogueScreens  ...         │
                        │ buttons, text, rows, focus │          └──────────────┬───────────────┘
                        └────────────────────────────┘                         │ call
                                                                               ▼
          ┌──────────────────────────── data and services ──────────────────────────────┐
          │ MediaRepository (TMDB, Premiumize)   SourceRepository (Torrentio → MediaFusion │
          │ → Comet)   ChannelDatabase/Importer/Sync (IPTV)   Secrets (Keystore)  Network  │
          └──────────────────────────────────────────────────────────────────────────────┘
                                      │ play
                        ┌─────────────▼──────────────┐
                        │ VlcPlaybackActivity        │  fullscreen LibVLC player
                        └────────────────────────────┘
```

There are two activities. `MainActivity` is the launcher (it registers for the `HOME` and
`LEANBACK_LAUNCHER` intents) and draws every browsing screen. `VlcPlaybackActivity` plays one stream.

## How a screen is built

Screens are built in code, not XML layouts. Every screen follows the same pattern:

1. A feature method (for example `LibraryScreens.watchlist()`) calls `activity.screen(title, back)`
   for a scrolling page, or `activity.fixedScreen(title, back)` for a fixed viewport.
   That resets the page, closes poster feeds and sets what the Back key does.
2. It adds widgets to `activity.page` using the helpers in `TvActivity`
   (`text`, `button`, `chip`, `row`, `section`, `emptyState`, ...).
3. Network or disk work goes through `activity.async(job, done)`. `job` runs on a background pool.
   `done` runs on the UI thread **only if the user is still on the same screen**. Each screen change
   increments `activity.generation`, and late results from an old screen are dropped.

Feature classes hold a reference to the activity (`activity`) and to nothing else. They are created
once, when the activity is constructed, and keep almost no state of their own.

## Code map

### Shell and shared UI

| File | Responsibility |
| --- | --- |
| `MainActivity` | Activity lifecycle, Back handling, remote key routing, screen reset (`screen`, `fixedScreen`), background work (`async`), shared state (`page`, `prefs`, `secrets`, `media`, `generation`), starting playback (`play`). Holds one field per feature class. |
| `TvActivity` | Base class of `MainActivity`. Colours, sizes (`dp`), focus styling and the standard widgets every screen uses, including form dialogs (`field`, `form`), list rows and `fullBleedBackdrop()`. Every button uses the one standard style (`button`, `styleFocus`); there are no pill-shaped buttons. |
| `PosterCards` | Poster card views, binding TMDB items to cards, creating `PosterFeed`s, the long-press poster menu. |
| `PosterFeed` | Recycled, append-only poster grid/shelf (RecyclerView) with paging, retry and focus preservation. See [docs/continuous-posters.md](docs/continuous-posters.md). |
| `PosterTitle` | Two-line poster caption that scrolls when focused and too long. |
| `BackdropView` | Full-bleed background artwork: covers the view pinned top-right, cross-fades between images, keeps the old image while the next loads. Placed behind a screen with `TvActivity.fullBleedBackdrop()`; used on Home and details. |
| `Artwork` | Image download and caching for posters and backdrops: up to four downloads at once, decoded images in memory (an eighth of the app's memory, 12–64 MB). |
| `ArtworkDiskCache` | Artwork bytes on disk (`cache/artwork`, 80 MB, least recently used removed first), so images seen before load without downloading again. |
| `DpadRepeats` | Rate-limits held D-pad keys so a held direction cannot race through lists. |
| `SidePanel` | The app's pop-up: a rounded panel on the right with a title, message, options or ticked choices and standard buttons. Used everywhere instead of AlertDialog/PopupMenu, also by the player. |
| `ErrorMessages` | Plain-language explanation and fix (Try again / Open Settings) for a failed load, from the exception type only. |
| `Timings` | In-memory timings (TMDB, sources, Premiumize, artwork, press play to picture, buffering pauses) shown on Settings → System → Diagnostics. Names and durations only. |
| `Diagnostics` | Logs failures the app recovers from (`adb logcat -s MovieHub`), without account details. |

### Screens (feature classes)

| File | Screens |
| --- | --- |
| `HomeScreen` | Home: top navigation, featured artwork, poster shelf (`HomeShelf`), clock, Update/Settings buttons, and a MemoryClean icon when that app is installed. |
| `AppDock` | Installed apps: the dock on Home (long-press Select to lift, move, remove), launching (remembers recently used), hiding, and the app menu. |
| `AllApps` | The Apps screen: search, Android settings shortcuts, recently used, and every app as a TV banner tile. |
| `CatalogueRows` | The Movies/Series start page: a hero area for the focused title above Netflix-style rows (Trending, Popular, Top rated, release lists, then genres). The focused row is pinned at the top of the rows area, rows load as they come near, and position is restored after opening a title. |
| `CatalogueScreens` | The Movies/Series screen (`CatalogueScreen`): header, hero and either the rows page or a single-category poster grid ("see all", Browse and filter results), plus the Browse and filter overlay. Back from a grid opened from the rows returns to the rows. See [docs/tmdb-categories.md](docs/tmdb-categories.md). |
| `CatalogueOptions` | The Browse and Filters sheets' contents: category and genre lists, region, years, languages, countries, streaming services and the "Refine results" form. |
| `CatalogueSearch` | Live search: a large search box whose results appear as you type, as Movies, Series and People rows; recent searches as buttons; person/company/keyword search inside the Browse sheets. Also builds the round person cards used for cast. |
| `TitleDetails` | The details page for one movie or series: full-bleed artwork, title block and action buttons (resume, sources/episodes, My List, trailer), then "More like this" posters, collection and round cast photos. |
| `SeriesScreens` | Seasons and episodes, watched-episode marks. |
| `SourcesScreen` | Finds stream sources (Torrentio → MediaFusion → Comet fallback, Premiumize cache) and lists them. |
| `LibraryScreens` | My List (watchlist), Continue Watching, saved Premiumize library. |
| `CloudScreens` | Premiumize cloud folders and transfers. |
| `LiveTvScreen` | IPTV channel browser: groups, search, favourites, paging. |
| `SettingsScreens` | Settings tabs, account setup (TMDB, Premiumize, Xtream, M3U), languages, toggles. |
| `ForYou` | The Home "For you" shelf: blends TMDB recommendations for up to six titles you played, saved or opened, ranked and cached on the box. |
| `UpdatePrompt` | Checks the public release feed and offers a verified update. See [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md). |

### Data and services

| File | Responsibility |
| --- | --- |
| `MediaRepository` | TMDB and Premiumize HTTP API calls; Xtream account checks. |
| `SourceRepository`, `DefaultSourceProviders` | Stream source providers and their endpoints. |
| `TmdbCategories` | TMDB discovery routes, genres and filters (no account state). |
| `Network` | Bounded HTTP(S) streams with manual redirect handling; bearer tokens only sent over HTTPS to the same host. |
| `Playlist` | M3U/Xtream playlist parsing and URL validation (refuses `http://` unless the user allows it). |
| `ChannelDatabase`, `ChannelStore`, `ChannelQuery` | On-device SQLite channel database, paging and search. |
| `ChannelImporter`, `ChannelSync` | Background channel import (WorkManager), refreshed every 12 hours. |
| `Secrets` | Account details encrypted with an Android Keystore key; the key and read values are cached in memory. |
| `VideoFiles` | Chooses the right file in a multi-file release (episode matching). |
| `AppUpdates`, `UpdateApkProvider` | Downloads and verifies update APKs; shares the verified APK with the system installer. |
| `VlcPlaybackActivity` | Fullscreen playback: tracks, subtitles, speed, picture ratio, sleep timer, resume positions (opened directly at the resume point), Back twice to leave. Controls are flat icons (`res/drawable/ic_player_*`). |
| `PlayerOverlay` | Builds the player's on-screen controls (title and download speed, timeline, flat icon controls, Back hint). |
| `PlayerMenus` | The player's side panels: audio, subtitles, video track, speed, picture size, subtitle timing, sleep timer. |

## Where data is stored

| Store | Contents |
| --- | --- |
| SharedPreferences `ui` | UI settings and lists: accent colour, clock, `http` toggle, auto-updates, dock order, pinned apps, hidden and recently used apps, My List, watched episodes, search history, For you interest (`interest`), catalogue region/genre cache. |
| SharedPreferences `accounts` (via `Secrets`) | Encrypted: TMDB key, Premiumize key, Xtream and M3U details, saved library, recent playback. |
| SharedPreferences `positions` | Playback resume positions. |
| SQLite `channels-v2.db` | Imported IPTV channels (encrypted stream URLs), groups and favourites. |
| Cache `artwork/` | Downloaded public artwork (TMDB images, channel logos), at most 80 MB. No account data. |
| Cache `updates/` | The downloaded update APK, before installation. |

## Design decisions

- **One package, many files.** Splitting into sub-packages would force most members to become
  `public`. Keeping one package lets feature classes share package-private members while each file
  stays small. The file names above are the map.
- **Feature classes instead of Fragments.** The original app was one activity that swapped views.
  Moving each area into a plain class kept that behaviour exactly (no lifecycle changes) while
  making the code navigable. Fragments would be a behavioural rewrite.
- **Base class for widgets.** `TvActivity` holds the view helpers so `MainActivity` code can call
  `text(...)` and feature classes can call `activity.text(...)` with no extra indirection.
- **Recovered failures are logged, not shown.** Where the app deliberately carries on after an
  error (for example a failed artwork download), it calls `Diagnostics.recovered(...)`, which writes
  the location and exception type to logcat under the tag `MovieHub`. Exception messages are not
  logged because they can contain provider URLs with account details.
