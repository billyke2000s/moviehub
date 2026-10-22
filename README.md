<div align="center">

# MOVIEHUB

### Films, series and live TV in one place.

**An Android TV launcher and streaming application for films, series and live television.**

[![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/tv/)
[![License](https://img.shields.io/badge/License-MIT-8B5CF6?style=for-the-badge)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/billyke2000s/moviehub?include_prereleases&style=for-the-badge&label=Release&color=2563EB)](https://github.com/billyke2000s/moviehub/releases)
[![GitHub stars](https://img.shields.io/github/stars/billyke2000s/moviehub?style=for-the-badge&color=F59E0B)](https://github.com/billyke2000s/moviehub/stargazers)

**[Download APK](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)** · **[Releases](https://github.com/billyke2000s/moviehub/releases)** · **[Documentation](DEVELOPMENT.md)** · **[Security](SECURITY.md)**

---

*One interface for browsing, watching and live TV.*

[Overview](#what-is-moviehub) · [Install](#get-moviehub) · [Build](#build-from-source) · [Project status](#project-status) · [Legal](#content-responsibility)

</div>

## What is MovieHub?

MovieHub is an Android TV launcher and streaming application that combines film and series discovery, live television, playback and installed applications in a single interface designed for TV remotes.

### Features

- **Films and series:** TMDB-powered discovery, watch history and source selection.
- **Live TV:** Public channel directories, M3U and Xtream integration, and a unified channel guide.
- **Sources:** Optional Torrentio, MediaFusion, Comet and Premiumize integrations with automatic or manual selection.
- **Playback:** VLC-based playback with stream recovery and source switching.
- **Launcher:** Remote-friendly navigation and access to installed Android TV applications.

MovieHub is an independent application and does not provide streaming subscriptions or host content. Some provider integrations remain experimental; see [project status](#project-status).

## Get MovieHub

<div align="center">

### Downloader code: **`1031512`**

Enter this code in the **Downloader** app on an Android TV device, or download the APK directly.

**[Download home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)**

[Browse release notes and files →](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest)

</div>

1. Install the APK on an **Android 9 or newer** TV device.
2. Open MovieHub and follow the first-run setup.
3. Configure supported services or browse available public channel lists.
4. Use the in-app **Update** button when a new version is offered.

## Build from source

**Requirements:** JDK 17 · Android SDK 35 · Gradle 8.10.2

```bash
gradle -p android-tv-launcher assembleDebug lintDebug
```

Additional information is available in [DEVELOPMENT.md](DEVELOPMENT.md), [ARCHITECTURE.md](ARCHITECTURE.md) and [TESTING.md](TESTING.md).

## Project handbook

| Guide | What you'll find |
| :--- | :--- |
| [Architecture](ARCHITECTURE.md) | Project structure and major components |
| [Development](DEVELOPMENT.md) | Local setup, coding conventions and releases |
| [Testing](TESTING.md) | Test layers, commands and limitations |
| [Security](SECURITY.md) | Account storage, networking and update protections |
| [Status](STATUS.md) | Known issues and work in progress |
| [Changelog](CHANGELOG.md) | Version history |
| [AI contributor rules](AGENTS.md) | Repository editing instructions |
| [Update publishing](android-tv-launcher/UPDATES.md) | Signed release process |
| [Design notes](docs/) | Interface and catalogue documentation |

## Project status

Automated tests use synthetic data and do not establish that every external service, live channel or update path works on physical hardware. Device-specific behaviour, especially live video handoff and provider integrations, needs real-world testing. Experimental work and its evidence are described in the technical notes below.

<details>
<summary><strong>Expand technical notes, experiments and playback behaviour</strong></summary>

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

## Content responsibility

> [!WARNING]
> **MovieHub does not host, store or distribute films, series, channels or streams.** Public channel listings and user-configured services are supplied by independent third parties. Their availability, rights and reliability are outside this project's control.

Users are responsible for accessing only content and services they are legally entitled to use. Channel names and logos belong to their respective owners. MovieHub is not affiliated with Sky, broadcasters, streaming platforms or source providers. For rights concerns, contact the repository owner through GitHub.

## Privacy & security

Account credentials are entered on-device and stored encrypted locally, as described in [SECURITY.md](SECURITY.md). Review the security documentation and report sensitive vulnerabilities privately rather than publishing credentials or exploits in an issue.

## License & credits

MovieHub is distributed under the [MIT License](LICENSE). The Live TV interface includes the **Hind** typeface by Indian Type Foundry under SIL Open Font License 1.1; see [font license](android-tv-launcher/app/src/main/assets/fonts/Hind-OFL.txt).

<div align="center">

---

### Made for the living room.

**[Back to top ↑](#moviehub)** · **[Download](https://github.com/billyke2000s/moviehub/releases)** · **[Report an issue](https://github.com/billyke2000s/moviehub/issues)**

<sub>MovieHub is an independent open-source project. No third-party services are endorsed by or affiliated with this repository.</sub>

</div>
