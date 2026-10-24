# Architecture

## Series continuation and automatic play

`SeriesPlayback` prefetches next-episode metadata on the existing I/O pool and verifies real episode numbers and air dates before crossing a season boundary. It updates both the season and the history key. `EpisodeOrder` holds plain-Java ordering rules. `AutoPlayback` bounds selection to five cached sources, ranks them through `SourceRanking`, and uses `VideoFiles` to select movies or exact episodes; screen-generation and cancellation guards prevent late automatic playback. `KeyBar` registers with the active `MainActivity` screen, so hardware colours are routed before focused widgets. Screen changes clear that registration.


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
| `Timings` | In-memory timings (TMDB, sources, Premiumize, artwork, press play to picture, buffering pauses) shown on Settings → System → Troubleshooting. Names and durations only. |
| `Diagnostics`, `ErrorLog` | Logs failures the app recovers from (`adb logcat -s MovieHub`) and keeps the last 50 in memory for Troubleshooting: place, type and time only, without messages or account details. |
| `Troubleshooting`, `SpeedTest` | Shares SettingsScreens’ sidebar layout and setting cards. Settings → System → Troubleshooting: internet speed test (Cloudflare, on request), connection checks for each connected service, playback timings, recent errors. |

### Screens (feature classes)

| File | Screens |
| --- | --- |
| `HomeScreen` | Home: top navigation, featured artwork, poster shelf (`HomeShelf`), clock, Update/Settings buttons, and a MemoryClean icon when that app is installed. |
| `AppDock` | Installed apps (one entry per app: a package's TV and phone launcher entries with the same name are merged), the dock on Home (long-press Select to lift, move, remove), launching (remembers recently used), hiding, and the app menu. |
| `AllApps` | The Apps screen: search, Android settings shortcuts, recently used, and every app as a TV banner tile (three rows first, the rest straight after the first draw). |
| `CatalogueRows` | The Movies/Series start page: a hero area for the focused title above Netflix-style rows (Trending, Popular, Top rated, release lists, then genres). The focused row is pinned at the top of the rows area, rows load as they come near, and position is restored after opening a title. |
| `CatalogueScreens` | The Movies/Series screen (`CatalogueScreen`): header, hero and either the rows page or a single-category poster grid ("see all", Browse and filter results), plus the Browse and filter overlay. Back from a grid opened from the rows returns to the rows. See [docs/tmdb-categories.md](docs/tmdb-categories.md). |
| `CatalogueOptions` | The Browse and Filters sheets' contents: category and genre lists, region, years, languages, countries, streaming services and the "Refine results" form. |
| `CatalogueSearch` | Live search: a large search box whose results appear as you type, as Movies, Series and People rows; recent searches as buttons; person/company/keyword search inside the Browse sheets. Also builds the round person cards used for cast. |
| `TitleDetails` | The details page for one movie or series: full-bleed artwork, title block and action buttons (resume, sources/episodes, My List, trailer), then "More like this" posters, collection and round cast photos. |
| `SeriesScreens` | Seasons and episodes, watched-episode marks. |
| `SourcesScreen` | Finds stream sources (Torrentio → MediaFusion → Comet fallback, Premiumize cache) and lists them. |
| `LibraryScreens` | My List (watchlist), Continue Watching, saved Premiumize library. |
| `CloudScreens` | Premiumize cloud folders and transfers. |
| `LiveTvScreen` | Live TV as a classic TV guide grid: ten channels per page in satellite number order, time slots, colour-key paging, number entry, favourites, the live picture in the corner. |
| `DeviceMode`, `LayoutChooser` | TV or phone layout: the first-start question (best guess suggested), kept in preferences, changeable in Settings. |
| `PhoneLive` | Live TV in the phone layout: portrait touch channel list with tabs, search, now and next; the player gets touch buttons on its banner. |
| `GuideFrame` | The guide's fixed parts in the dark-blue classic look (as in 0.26): clock, list name and status, Options and the live picture, time slots, colour keys. |
| `KeyBar` | The colour-key guide at the bottom of the guide and its menu; each key is a D-pad button too, for remotes without coloured buttons. |
| `GuideMenu` | The numbered guide menu (1 All Channels … 0 More…) that Live TV opens first and BACK from the guide returns to; its rows share the height so all fit, and its top parts (Reminders, Search, Options) can be chosen. |
| `GuideRows` | The guide's rows: channel names and programme cells, moving along programmes and through time, SELECT to watch or set a reminder. |
| `LiveTvPanels` | The guide's side panels: options, sections and groups, search, programme information with reminders, the parental lock, colour-key set-up. |
| `GuidePicture` | The live picture in the guide's corner (the last channel watched); stops when the guide closes unless the full screen has taken the player over. |
| `LivePlayer` | The one Live TV player (LibVLC engine and media player), shared by the full screen and the guide picture so going between them moves the picture without reconnecting. Films keep their own player. |
| `LiveVideoOutput` | Where the session's one live player draws: VLC's standard `VLCVideoLayout` (SurfaceView), moved between the guide's mini picture and the full screen without reopening the stream. |
| `PlayerOptions` | LibVLC engine options, one list for films and one for Live TV, so a change to one never reaches the other. |
| `SettingsScreens` | Settings tabs, account setup (TMDB, Premiumize, Xtream, M3U), languages, toggles. |
| `ForYou` | The Home "For you" shelf: blends TMDB recommendations for up to six titles you played, saved or opened, ranked and cached on the box. |
| `UpdatePrompt` | Checks the public release feed and offers a verified update. See [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md). |

### Data and services

| File | Responsibility |
| --- | --- |
| `MediaRepository` | TMDB and Premiumize HTTP API calls; Xtream account checks. |
| `SourceRepository`, `DefaultSourceProviders` | Stream source providers and their endpoints; new film and series sources go in `DefaultSourceProviders.EXTRA` (see AGENTS.md, Adding sources). |
| `TmdbCategories` | TMDB discovery routes, genres and filters (no account state). |
| `Network` | Bounded HTTP(S) streams with manual redirect handling; bearer tokens only sent over HTTPS to the same host. |
| `Playlist` | M3U/Xtream playlist parsing and URL validation (refuses `http://` unless the user allows it). |
| `ChannelDatabase`, `ChannelStore`, `ChannelQuery` | On-device SQLite channel database, paging and search. All channels, sections and countries are read from each channel's preferred copy (`main=1`, indexed); favourites, groups and searches group the copies. |
| `ChannelSchema` | The channel database's tables, indexes and version upgrades (version 8 adds `main`; version 9 recomputes duplicate keys). |
| `ChannelIdentity`, `ChannelCategory` | Which copies are the same channel across lists and providers (country + plain name, quality and country tags dropped, +1/regions/UHD kept apart), and the guide section for a provider channel (plain Java). |
| `EpgImporter`, `EpgMatch` | The free UK XMLTV programme guide (epgshare01): streamed, matched to saved channels by a plain name key (programmes on other channels skipped unread), the next day kept in the channel database with titles, descriptions and categories. `EpgMatch` also reads XMLTV times (plain Java). |
| `SkyNumbers`, `GuideOrder`, `ChannelNumbers` | UK satellite channel numbers and sections (`SkyNumbers`, a fixed table matched by name); the whole guide's layout: UK blocks by section, regions in the 800s, radio in the 900s, other countries from 1001 (`GuideOrder`, plain Java); applied to the saved channels after every import (`ChannelNumbers`). |
| `Reminders`, `ParentalLock` | Programme reminders (kept in preferences, shown while the app is open) and the Live TV PIN lock (a salted hash of the PIN, locked channels and sections). |
| `FreeChannels` | Which free lists Live TV imports (Free-TV, then iptv-org by country, all, or off); "automatic" (the full list) only without a provider, imported when Live TV is first opened. New TV channel lists go in `FreeChannels.EXTRA` (see AGENTS.md, Adding sources). |
| `ChannelImporter`, `ChannelSync` | Background channel import (WorkManager), refreshed every 12 hours. Order: accounts, native Pluto, free lists, then FreeLiveSports and the optional providers (which can take minutes each), then the XMLTV guide. Each source commits on its own, so its channels appear as it finishes. |
| `Secrets` | Account details encrypted with an Android Keystore key; the key and read values are cached in memory. |
| `VideoFiles` | Chooses the right file in a multi-file release: the film beside extras, or the episode in a season pack (exact matches can play straight away; likely ones are only marked "Suggested episode"). Plain Java. |
| `SkipQueue` | Combines quick film skips and seek-bar moves into one jump, 750 ms after the last (plain Java). |
| `AppUpdates`, `UpdateApkProvider` | Downloads and verifies update APKs; shares the verified APK with the system installer. |
| `VlcPlaybackActivity` | Fullscreen playback: tracks, subtitles, speed, picture ratio, sleep timer, resume positions by title, so another source carries on (`ResumePoint`; opened directly at the resume point), skips combined into one jump (`SkipQueue`), Back twice to leave. Controls are flat icons (`res/drawable/ic_player_*`). |
| `PlayerOverlay` | Builds the player's on-screen controls (title and download speed, timeline, flat icon controls, Back hint). |
| `LiveZapper` | Live TV while watching: the satellite remote's keys, channel up/down, number entry, last channel, browsing other channels in the banner, the PIN lock, and backup copies with automatic switching. |
| `SkyFont` | The Live TV typeface (Hind, OFL, in `assets/fonts`), with the system font as a fallback. |
| `SafetyNotice` | The first-start "Staying safe" notice (also in Settings → System) about scam messages in streams. |
| `SkyOverlay` | The full-screen Live TV overlay in the classic satellite-box style: banner, programme information panel and pause bar. |
| `PlayerMenus` | The player's side panels: audio, subtitles, video track, speed, picture size, subtitle timing, sleep timer. |

## Where data is stored

| Store | Contents |
| --- | --- |
| SharedPreferences `ui` | UI settings and lists: accent colour, clock, `http` toggle, auto-updates, dock order, pinned apps, hidden and recently used apps, My List, watched episodes, search history, For you interest (`interest`), catalogue region/genre cache, Live TV reminders, the last channel watched (a hashed stream key), and the parental lock (a hash of the PIN, locked channels and sections). |
| SharedPreferences `accounts` (via `Secrets`) | Encrypted: TMDB key, Premiumize key, Xtream and M3U details, saved library, recent playback. |
| SharedPreferences `positions` | Playback resume positions, by title (`title:movie:<TMDB id>`, `title:tv:<TMDB id>:<season>:<episode>`) and by file id (`ResumePoint`). |
| SQLite `channels-v2.db` | Imported IPTV channels (encrypted stream URLs), groups, channel numbers, sections and countries, which copy of each channel is preferred (`main`), favourites, and the programme guide. |
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

## Native free TV

`PlutoSource` implements regional anonymous discovery, in-memory session/catalogue caching and resolve-on-play. `NativeHttp` bounds and cancels HTTPS requests. `NativeSources` connects catalogue and programme refresh to the existing SQLite store and ChannelImporter. LivePlayer resolves stable native locators asynchronously and preserves them through view handoff. Native guides and XMLTV refresh independently. Native Pluto is preferred to public-list copies, after connected accounts. See [provider scope and evidence](docs/NATIVE_FREE_SOURCES.md).

## Experimental standalone JavaScript providers

`ProviderScript` executes compiled upstream leaf functions in the isolated `ProviderService`;
`ProviderBridge` uses bounded file-descriptor IPC. `EngineSources` connects FreeLiveSports discovery,
on-play resolution and inline EPG to SQLite and the shared LivePlayer. `ProviderPackages`,
`ProviderSignature` and `ProviderUpdates` implement signed package checks, atomic slots and rollback.
Remote updates fail closed without a pinned key. See [scope and limitations](docs/STANDALONE_PROVIDER_ENGINE.md).

## Runtime 2 provider compatibility

`ProviderBroker` services bounded async HTTP continuations from isolated scripts. `ProviderCodec` supplies
pure crypto/compression primitives; `AdapterSources` integrates the historical FAST, DaddyLive, Tubi and Local Now
modules with SQLite and settings. `ProviderRelay` adds session-scoped loopback HLS header transport and
`ProviderSegments` ports upstream TS-prefix detection. See the updated [provider architecture and evidence](docs/STANDALONE_PROVIDER_ENGINE.md).

`ProviderSessions` retains encrypted ZLive HTTP/DNS event history and replays it through the original
resolver without repeating those requests, preserving token reuse, cooldowns and decoy latches across
disposable processes. Native IP/subnet primitives supply the required Node `net` subset. Signed token
expiry can renew an actively playing live stream; stopping, switching or pausing does not auto-start video.

DaddyLive's current anonymous playlist protocol is adapted by `provider-engine/tools/dlhd-session.js`
without modifying the historical upstream files. It learns literal CDN/channel declarations from player
HTML and returns session metadata after validating a tokenized manifest. `PlaylistSession` renews tokens
inside the existing `ProviderRelay`, scoped to one HTTPS authority and channel directory. The relay uses
`ProviderPngSegments` for bounded PNG-to-MPEG-TS unpacking only for this session type. Direct sources and
other providers retain their existing paths; fullscreen/mini-player share the same relay session.

`ProviderFailure` classifies fixed diagnostic summaries and HTTP status codes without copying exception
messages. `ProviderBroker` retains safe transport failure categories across replay and wraps failed script
steps with the observed HTTP/network cause. `ChannelImporter` stores the summary; `ProviderSettings`
puts it first in the description. Settings cards use minimum height plus wrap-content. Guide label sizing
fits row height while retaining horizontal ellipsis.

LivePlayer exposes its pending-resolution phase and notifies the currently attached owner when a resolved stream/relay is ready. VlcPlaybackActivity arms its normal video-startup timer at that transition, rather than spending the 15-second backup timeout on the provider walk. Guide/fullscreen handoffs replace the callback with the owner; switching cancels stale resolutions as before.

`LiveProgress` is the pure progress rule for native/provider Live TV. `LivePlayer` samples the live clock and LibVLC displayed-picture counter once per second after playback starts, only while the video view is visible and playing. It reuses LiveZapper’s eight-second stall limit, attempts the existing bounded provider retry once, then reports failure through the current owner’s backup/error callback. Changing channels, handing off views, stopping and pausing reset the baseline. The sampler uses the generation ticket so recovery cannot schedule duplicate polling loops.

PNG transport skips byte-wise unfiltering for filter-0 rows, copies RGB payload in bulk, and uses larger bounded inflate/gzip input buffers. Explicit Inflater instances are ended in finally; container validation and allocation limits are unchanged.

## Live video output

`LiveVideoOutput` draws the session's one live LibVLC player through VLC's standard
`VLCVideoLayout` (a SurfaceView), attached with `MediaPlayer.attachViews(layout, null, false,
false)` exactly as the film player does. The hardware decoder hands pictures to Android's
compositor directly, so the picture does not depend on the app's main thread redrawing.

GuidePicture and VlcPlaybackActivity supply FrameLayout hosts. Moving between them follows
VLC's own Android app: the video track is switched off, LiveVideoOutput waits for VLC's Vout event
to report no video output (at most 600 ms), then calls `detachViews()`, attaches a new layout in
the destination and switches the track back on. Changing the surface under a running video
output crashed VLC's window thread (AWindowHandler, SIGSEGV) or stopped decoding in CI. Only the screen surface changes:
the player, its media, the network connection (and relay) and the audio carry on, so the stream
is never reopened or re-resolved. The picture may blink while the new surface takes over; the
owner accepted that on 21 October 2026.

This replaced (21 October 2026) a session-long SurfaceTexture shown through TextureViews with
last-frame covers. That gave blink-free moves, but every frame waited for the main thread to
redraw: the owner saw full-screen live video lag, and stop while the sound played on, while the
same stream played smoothly in the mini picture.

Live Activity transitions use zero-animation launch options and matching close overrides (the
API 34 transition override or the legacy finish override). The stream Surface survives the
window change; the UI does not add a system fade around it.

Google's [Grafika double-decode sample](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/DoubleDecodeActivity.java)
also retains SurfaceTextures to preserve decoders across Activity recreation.

## Video freeze recovery

`LiveVideoOutput.pictures()` reads VLC's decoded-video count (`IMedia.Stats.decodedVideo`, kept
rising across source switches; the displayed-picture count barely moves with hardware decoding;
-1 until the first frame, so the check then falls back to the playback clock). LivePlayer samples
it and the playback clock once a second. After the existing eight-second live stall threshold, a
clock-moving/picture-stopped session gets one `refreshView()`: the same off-wait-on sequence on
the same surface, restarting only the video decoder and output (same player, media, relay and
audio). Swapping the surface in place raced the old surface's teardown on the emulator. An absent clock or a persistent second freeze uses
the existing bounded source retry/failure path. Paused and hidden playback are excluded.
Troubleshooting shows fixed recovery descriptions and counts, with no URLs or tokens.

LiveZapper owns the per-channel Automatic/manual source policy and two-step Back interaction. PlayerMenus lists Automatic followed by saved copies; SkyOverlay exposes a focusable Sources action. Listing copies is a local database operation; only the selected copy goes through LivePlayer resolution. Automatic failover visits the remaining saved copies once and stops.

`provider-engine/tools/freeshot.js` parses the FreeShot related-channel directory and follows only the selected UK sports channel’s two literal iframe links. ProviderBroker permits only FreeShot directory/embed and WideIPTV player paths. PlaylistSession supports the fixed WideIPTV anonymous JSON renewal endpoint, scoped to one discovered BlueTier origin/channel path; ProviderRelay posts renewal bodies and rewrites tokens without exposing them to VLC. Zero-to-end range requests fetch and decode the complete wrapped segment rather than applying decoded offsets to its PNG container.

LivePlayer retains the current source mode and attempted source keys with the active stream. LiveTvScreen matches the active stable URL’s saved SHA-256 key against the selected channel’s copies before opening full screen. LiveZapper restores the matching copy/mode/attempt history, and resets them on a new channel. These comparisons read local metadata only.

Restoring a retained source compares saved hashes rather than decrypting every backup URL; it performs no additional stream lookup.

## Reviewed playback integration (22 October 2026)

`VodSources` retains at most two short-lived sessions of up to five cached candidates, identified by random intent tokens. Manual Sources and AutoPlayback pass the selected file and remaining cached candidates. Alternative direct links resolve on demand through the existing repository; the session holds no Activity. A process restart loses alternatives and safely retains ordinary single-source history playback.

`VodSourceFailover` owns plain-Java thresholds and source exhaustion. `VodPlayback` owns asynchronous resolution, cancellation generations, a 45-second deadline per candidate, and a muted second VLC player using the film engine. The original player continues until video decodes within two seconds of its position. Replacement uses a TextureView so staging can be transparent on API 28; initial VOD and shared Live TV retain their existing SurfaceView path. Insufficient decoder resources may prevent a replacement: failure keeps the original player. Live thresholds and the shared live instance are independent.

`LivePictureRates` samples current VLC stats without retaining a frame callback. `LiveHandoffCover` best-effort captures a bounded still during a guide/full-screen move; stale PixelCopy callbacks cannot move video, and the cover expires after five seconds. Existing surface cancellation and video-repair reattachment remain intact.

## Playback continuity (0.46 candidate)

`PlaybackBuffer` owns bounded transport cache profiles. `PlayerOptions` applies the VOD/live
engine defaults; `LivePlayer.open` applies the selected direct/HLS/relay media profile.
Scheduled provider renewal uses the existing resolver and one native player, retaining the
current media/relay until a fresh link resolves. Pending lookup has a generation guard; a pause
discards its late result, and a failed refresh leaves a still-working stream running. Closing
VLC precedes closing its relay. The subsequent decoder restart can still blink. This is not
dual-decoder prepared cross-provider failover. The progress watchdog now covers ordinary IPTV
as well as provider streams, with video-track, visibility, pause and resolution exclusions.

`LiveRepairBudget` permits one surface repair until two contiguous visible observations show
video progress. Hidden/pending surfaces and old queued frames cannot replenish the budget.

Native window shutdown follow-up: post-merge retry 39868257885 crashed in EGL window
creation during native-source handoff. An unreported Vout count no longer permits immediate
detachment. Every move requests track shutdown; a missing acknowledgement after 600 ms
stops the native reader before moving and restarts playback on the destination. This rare
fallback reconnects the stream; normal acknowledged moves retain it. Device fixtures cover
unreported output counts and suppressed shutdown acknowledgement. Validation is pending.

## Prepared live backups

`LiveBackup` belongs to the shared `LivePlayer`, rather than the fullscreen Activity. It reads
saved channel-copy URLs only when recovery is needed, resolves through the existing provider
capabilities and owns at most one muted MediaPlayer/relay at a time. The candidate uses a
standard SurfaceView staged outside the visible host, then that same layout/player is moved
into place without a decoder restart. It must show increasing decoded video and, where present,
audio over at least 500 ms. Radio copies can qualify through advancing audio. On promotion,
the old native reader is stopped before its output/relay are closed; the new owner callback
updates controls and the current source without calling play again. Provider renewal transfers
with the adopted relay. Native decoder allocation failure leaves the original intact.

`LiveBuffering` owns monotonic, edge-counted live thresholds: 8 seconds or 3 starts per 60
seconds, excluding startup, pause and hidden/transitioning output. Movies/series retain their
independent 4-second/2-start rules. The progress watchdog also prepares a backup after its
bounded surface repair. Resolution (45 s) and playback (15 s) have deadlines. Each copy is
attempted once; exhausted candidates retain working original playback. Pause, manual mode,
channel change, exit and view handoff cancel and release standby native resources before
removing their surface. Handoff retains the source configuration so the guide can prepare its
own replacement. Resuming Automatic/new selection resets exhaustion. Android low-memory
signals skip candidate allocation. Separate providers cannot be synchronized from their
unrelated playback clocks: a cut can jump in programme time.


## Live TV control consistency

Live launches snapshot `LiveZapper.Session` through `LiveGuideState` into the Intent, and Activity state saves the current list/position. Every live Activity builds `SkyOverlay`; a missing static session cannot select film controls. The initial database lookup matches the launch URL and falls back to all channels if its saved list excludes that channel. Learned colour actions precede generic number/channel shortcuts.



Player-owned `SidePanel` dialogs route recognized colour keys through `LiveZapper` after closing, because dialog input does not reach Activity dispatch. Live colours complete on uncancelled release. `SkyOverlay` reserves the channel strip independently of programme content, with room to expand for larger fonts.

## Live playback recovery

`LiveStream` resolves live transports and configures VLC media; selection, lifetime and recovery remain in `LivePlayer`. `LiveNative` serializes native stop/video-disable operations per player on two bounded workers. Main-thread consumers suspend native access while that player is busy. Surface/relay/player/engine disposal follows completion, never a timeout. Generation guards drop obsolete opens. `LiveRestartBudget` allows two reconnects and replenishes only after 30 seconds of observed progress. Native Pluto invalidates its anonymous session on retry. `LiveSurfaceProbe` uses one tiny asynchronous PixelCopy at most every two seconds; structurally missing surfaces reach recovery after eight seconds. PixelCopy NO_DATA/UNKNOWN is inconclusive on hardware decoders and never triggers a reconnect; visible decoding progress replenishes surface recovery. Slow joins are logged without releasing windows underneath native readers.

Three short buffering notifications can still prepare an alternate source. A same-source reconnect requires prolonged buffering (eight seconds) or stalled video progress; ordinary HLS period notifications do not consume the reconnect budget.


## Consistent live banner and standard colours

The live overlay measures the channel banner before optional pause/information panels, so those panels cannot consume its layout height. The channel strip has an explicit height scaled for text size. Standard PROG_RED/GREEN/YELLOW/BLUE identities precede learned aliases; function-key aliases remain configurable. Player and dialog colour handlers act on the initial key-down and consume repeat/up events without reopening menus.


## One live overlay and shared colour input

Live TV does not attach PlayerOverlay film controls. PlayerOverlay creates only unattached bookkeeping widgets for shared playback state; SkyOverlay is the sole visible live controls view. Entry calls LiveZapper.showBanner; OK and picture click call LiveZapper.ok, which shows that same banner. On a TV its buttons are always boxed (`SkyOverlay.boxControls`); ◀ ▶ move between them and OK presses the highlighted one. Back focuses Sources. Unused buttons in the live player are named in a short toast (`VlcPlaybackActivity.showUnusedButton`). Colour set-up stores key codes, or scan codes for buttons Android reports as KEYCODE_UNKNOWN (`LiveTvScreen.COLOUR_SCAN_PREFS`); KeyBar and the player share `LiveTvScreen.colour(prefs, event)`. Options remains an explicit blue/Menu/selected-button action. Activity and SidePanel route colours through one session-owned ColourPress before native-player access, recognizing the first delivered press, standalone release or complete multiple event while suppressing duplicates across changing windows. Unknown translated keys can use Android TV Generic.kl’s standard colour scan identities (398–401); known non-colour keys are never overridden.

## Progressive free VOD discovery

SourcesScreen delegates discovery to FreeVodScreen and VodSearch. SourceWindow holds the
three-second initial ranking window. Eight workers query the bundled Scrubs/Free99 modules
through FreeVodProviders and Chaquopy; an optional Premiumize search runs alongside them.
The overall search budget is 120 seconds, with per-provider HTTP/processing deadlines.
Free sources retain already-resolved file links and headers in SourceRepository.Source.
VodSources.progressive appends late results without reordering existing session indices.
VodSourceFailover.appendSources preserves failed sources, pinning and buffer history.
VodPlayback uses session-owned ProviderRelay instances when host headers are required.
A new title replaces the previous search; screen-generation checks and player-session cleanup
prevent abandoned screens from starting playback. App screens remain native Java.

## VOD incoming traffic

Free HLS and media-file sources use the existing session-scoped `ProviderRelay`, including header-free URLs. Header-free DASH retains native base-URL handling and VLC statistics. It counts upstream payload reads and `StreamRate` converts active-relay deltas to Mb/s; backup prefetches have separate counters. Direct cloud playback continues using VLC statistics. `VodSearch` ranks picture quality first, then bounded transfer sample and response time; HLS manifests provide variant resolution, not a fictitious media throughput.

## Free VOD diagnosis and safe replacement

`VodSearch` records every module's sanitized outcome and permits 180 seconds for eight 18-second workers, while retaining the three-second first-selection window. `FreeVodProviders.search` returns the bridge outcome as well as progressive sources. `moviehub_compat.media` reads declared MP4/HLS/DASH duration from bounded probe bytes. Free videos with known duration below 120 seconds are rejected during probing and again by the active/prepared decoder. Unknown duration remains unknown; live/event playlist windows are not movie durations. `VodNative` reuses the existing off-main native-stop coordinator, retaining the player window and engine until shutdown completes. Free manual source choices allow cross-provider recovery; cloud pinning and buffering thresholds remain unchanged.


## Provider compatibility follow-up

The requests adapter preserves explicit GET/POST independently of the body, including
redirect method/cookie rules. The legacy client supplies default Referer/language headers.
FreeVodScreen forwards TMDB original/alternative titles; moviehub_vod applies each family's
scene title and episode mapping. Work is bounded to 30 seconds per provider and 300 seconds
for the eight-worker search; selection still begins after three seconds.

Provider-request read timeouts now honor explicit upstream values within the remaining
search budget, with an eight-second connection cap. A regression checks a 20-second read
request and clipping to a five-second remaining budget; cancellation remains bounded.


## VOD window lifetime during asynchronous shutdown

Films use TextureView output, as prepared VOD replacements already do. VodNative installs
a VodSurface listener before starting the native join. If Android detaches the Activity's
window first, the listener retains its SurfaceTexture and defers VLC's destruction callback.
After native stop returns, detachViews closes VLC's output and the retained textures are
released. An unsuccessful native join keeps the window/engine quarantined. Live TV's
retained guide/fullscreen output is unchanged.
