# MovieHub

**An Android TV launcher with films, series, live TV and your installed apps in one interface.**

[Download](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk) · [Releases](https://github.com/billyke2000s/moviehub/releases) · [Documentation](DEVELOPMENT.md) · [Report an issue](https://github.com/billyke2000s/moviehub/issues)

![Android 9+](https://img.shields.io/badge/Android-9%2B-555?style=flat-square) ![MIT License](https://img.shields.io/badge/license-MIT-555?style=flat-square)

MovieHub is designed for use from a sofa with a TV remote. It combines a home screen, an app launcher, a film and TV catalogue, and a live television guide. It runs on Android 9 and newer. A phone/tablet layout is also available, currently focused on Live TV.

## Features

- **Home and apps:** Poster rows, favourite apps, a clock, app search, recently used apps and options to rearrange or hide apps.
- **Films and series:** Category browsing, title and actor search, cast information, trailers, similar titles, My List and Continue Watching.
- **Live TV:** Public channel directories and personal Xtream or M3U playlists, with a guide, favourites, search and reminders.
- **Playback:** VLC-based player, subtitle and audio selection, speed and picture controls, sleep timer and manual or automatic source selection.
- **Updates:** In-app release checks and installation flow.

Catalogue artwork and metadata use TMDB. Optional source discovery integrations include Torrentio, MediaFusion and Comet with Premiumize. The app does not include a streaming subscription. Playback depends on the services and sources you configure.

The live guide and fullscreen view use a shared player to avoid reopening streams during transitions. See the [technical notes](#technical-notes) for implementation details and testing limitations.

## Install

**Android TV:** Download [home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk) from the [latest Android TV release](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest), then install it on your device.

**Downloader app:** Enter code **`1031512`**.

After installation, MovieHub can offer updates inside the app. Use trusted release files and verify compatibility with your device.

## Build

Requires JDK 17, Android SDK 35 and Gradle 8.10.2.

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for the complete setup and release process.

## Documentation

| | |
| --- | --- |
| [Architecture](ARCHITECTURE.md) | Code layout and components |
| [Development](DEVELOPMENT.md) | Setup, conventions and releases |
| [Testing](TESTING.md) | Test commands and known limitations |
| [Status](STATUS.md) | Current work and known issues |
| [Changelog](CHANGELOG.md) | Changes by version |
| [Security](SECURITY.md) | Credentials, networking and updates |
| [Update publishing](android-tv-launcher/UPDATES.md) | Signed APK updates |
| [Design notes](docs/) | Interface and catalogue details |
| [Contributor instructions](AGENTS.md) | Repository editing guidance |

## Technical notes

MovieHub is still under active development. Automated tests use synthetic data; they do not establish that external services work or that every TV device handles playback and updates correctly. The following notes document implemented and experimental behaviour. They should not be read as guarantees of production reliability.

<details>
<summary>Provider integrations, player behaviour and ongoing experiments</summary>

### Native free TV

Live TV can discover regional Pluto TV channels directly, with no separate server. Settings → Free channels controls native Pluto and public lists; Xtream, personal M3U and optional hosted Masqueradarr remain separate. Streams resolve when played, with cached channel metadata and the shared guide/fullscreen player. See [provider support, test evidence and limitations](docs/NATIVE_FREE_SOURCES.md).

### Experimental standalone provider engine

Development adds opt-in FreeLiveSports using local compiled TypeScript/JavaScript and the existing
Live TV guide/player, with no home server. This is incomplete: current upstream adapters are in
an inaccessible private submodule, and signed remote updates require maintainer key setup.
See [implementation, provider status and tests](docs/STANDALONE_PROVIDER_ENGINE.md).

Experimental Runtime 2 in draft PR #83 adapts additional historical Masqueradarr provider code locally on
Android, including DaddyLive's resolver and Tubi's signing flow. This is unfinished development, not a
production release or a claim that every provider/channel plays. [Architecture, status and evidence](docs/STANDALONE_PROVIDER_ENGINE.md).

### DaddyLive playback compatibility (experimental)

The provider-engine development fix supports the current player's anonymous playlist-token bootstrap,
in-memory renewal and PNG-wrapped MPEG-TS segments through MovieHub's existing local relay. The web
player and its page advertising are not loaded. Desktop samples decoded for Main Event, Football,
Action, F1 and ABC USA on 20 October 2026; this does not establish every channel or physical-TV playback.

Provider refresh failures now show a safe reason at the start of each provider's status. Settings cards
expand to fit their descriptions, and guide labels fit shorter rows instead of clipping vertically.

Provider playback resolves a fresh stream when you select a channel. The screen shows “Finding a working stream…” during that bounded lookup, then starts the video-opening timeout after a URL is ready. Refreshing channels updates the directory; it does not pre-resolve expiring video links.

Native/provider Live TV also watches playback progress: a stopped video counter (falling back to the clock when video counters are unavailable) can recover even when VLC emits no buffering event. Paused or hidden playback is excluded.

### Live TV picture handoff

Live TV draws through VLC's standard video layout (a SurfaceView). The guide preview and full
screen share one player: moving between them changes only the screen surface, so the stream and
sound carry on and the link is not reopened (the picture may blink briefly). Device tests check
fresh, non-blank pictures in both directions; TV hardware still needs testing.

Live video progress follows VLC's displayed-picture count rather than assuming advancing audio
means video is healthy. A stopped picture gets one surface refresh before the existing bounded
source recovery. Troubleshooting retains the last safe recovery description.

Live TV Back now reveals the channel controls and a selectable Sources link before returning to the mini-player on the next Back. Sources defaults to Automatic; selecting a particular saved copy pins it until Automatic or another channel is selected. Source menus read saved channel metadata; stream resolution remains on demand.

FreeShot (UK sports) is an additional opt-in source in Settings → Provider engine. Its directory is imported without opening streams; a selected channel follows its literal player links on demand and uses MovieHub’s existing player. Anonymous playlist tokens renew in memory.

A selected live backup and its Automatic/manual mode follow the active player through guide/full-screen movement; reopening the same channel keeps that source. Choosing another channel starts in Automatic mode.

### Playback recovery

Films and episodes opened from Sources can switch among up to five cached sources. Backups resolve only when needed. A 4-second stall or two buffering starts in 60 seconds prepares a muted replacement; the current stream stays open until the replacement decodes near the current position. Seeking and user pause are excluded. Playback options → Sources offers manual selection and Automatic. Manual selection pins the playback service (currently Premiumize). Cloud-only/history playback without an in-memory source session keeps its existing single-source behavior.

Live TV keeps its separate thresholds. A still video counter over advancing audio gets an early surface repair after 2.5 seconds. Troubleshooting reports decoded/displayed VLC rates separately for the guide and full screen; these counters are not direct measurements of SurfaceView presentation.


</details>

## Content and privacy

MovieHub does not host or distribute films, series, TV channels or streams. Public channel lists are supplied by independent projects such as [Free-TV](https://github.com/Free-TV/IPTV) and [iptv-org](https://github.com/iptv-org/iptv); personal playlists and other services are provided by users. Availability and rights are outside MovieHub's control. Only access content you are entitled to watch.

MovieHub is not affiliated with Sky or any broadcaster or streaming provider. Names and logos belong to their respective owners. Rights concerns can be raised with the repository owner through GitHub.

Account details are entered on the device and stored encrypted locally. See [SECURITY.md](SECURITY.md) for further information.

## License

[MIT](LICENSE). The Live TV interface uses the Hind typeface by Indian Type Foundry under the SIL Open Font License 1.1 ([license text](android-tv-launcher/app/src/main/assets/fonts/Hind-OFL.txt)).
