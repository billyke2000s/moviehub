<div align="center">

# MOVIEHUB

### Films, series and live TV in one place.

Movie and series playback refreshes temporary links, keeps each source's request headers,
ranks backups and uses fresh source discovery for next episodes. Header-protected DASH shares
the local media relay. Runtime evidence guides short-video checks and different-edition restarts.
See [TESTING.md](TESTING.md) for verification scope.

The app packages both 32-bit and 64-bit ARM libraries for TV firmware compatibility, shares bounded
one-minute TMDB catalogue replies and releases cache memory under pressure. UI, source providers,
playback engines and update identity are preserved.
Emulator builds explicitly select x86_64; see [TESTING.md](TESTING.md).

**An Android TV launcher and streaming application for films, series and live television.**

[![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/tv/)
[![License](https://img.shields.io/badge/License-MIT-8B5CF6?style=for-the-badge)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/billyke2000s/moviehub?include_prereleases&style=for-the-badge&label=Release&color=2563EB)](https://github.com/billyke2000s/moviehub/releases)
[![GitHub stars](https://img.shields.io/github/stars/billyke2000s/moviehub?style=for-the-badge&color=F59E0B)](https://github.com/billyke2000s/moviehub/stargazers)

**[Download APK](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)** · **[Releases](https://github.com/billyke2000s/moviehub/releases)** · **[Documentation](DEVELOPMENT.md)** · **[Security](SECURITY.md)**

---

_One interface for browsing, watching and live TV._

[Overview](#what-is-moviehub) · [Install](#get-moviehub) · [Build](#build-from-source) · [Project status](#project-status) · [Legal](#content-responsibility)

</div>

## What is MovieHub?

MovieHub is an Android TV launcher and streaming application that combines film and series discovery, live television, playback and installed applications in a single interface designed for TV remotes.

### Features

- **Films and series:** TMDB-powered discovery, watch history and source selection. Native free-site adapters include Movy (Boise/Miami) and Rive (including Vanguard for episodes); each playback route has its own source entry.
- **Live TV:** Public channel directories, M3U and Xtream integration, and a unified channel guide. Programme listings come from one daily combined public guide, preferring exact provider identities with country-matched XMLTV fallback where available.
- **Sources:** Optional Torrentio, MediaFusion, Comet and Premiumize integrations with automatic or manual selection.
- **Playback:** ExoPlayer (Media3) for Live TV and VLC for films and series, with stream recovery and source switching.
- **Launcher:** Remote-friendly navigation and access to installed Android TV applications.
- **Test builds:** large changes can be tried first in MovieHub Experimental, a separate copy of the app (its own package, installed alongside MovieHub) with its own [pre-release](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-experimental) feed. Normal MovieHub installs never update from it.

MovieHub is an independent application and does not provide streaming subscriptions or host content. Some provider integrations remain experimental; see [project status](#project-status).

## Get MovieHub

<div align="center">

### Downloader code: **`5881724`**

Enter this code in the **Downloader** app on an Android TV device, or download the APK directly.

**[Download home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)**

[Browse release notes and files →](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest)

</div>

1. Install the APK on an **Android 9 or newer** TV device.
2. Open MovieHub and follow the first-run setup.
3. Configure supported services or browse available public channel lists.
4. Use the in-app **Update** button when a new version is offered.

## Build from source

**Requirements:** JDK 17 · Android SDK 35 (build-tools 35 and 36) · Gradle 9.6.0 · Python 3.11 · Node 22

```bash
gradle -p android-tv-launcher assembleDebug lintDebug
```

Additional information is available in [DEVELOPMENT.md](DEVELOPMENT.md), [ARCHITECTURE.md](ARCHITECTURE.md) and [TESTING.md](TESTING.md).

## Project handbook

| Guide                                               | What you'll find                                      |
| :-------------------------------------------------- | :---------------------------------------------------- |
| [Architecture](ARCHITECTURE.md)                     | Project structure and major components                |
| [Development](DEVELOPMENT.md)                       | Local setup, coding conventions and releases          |
| [Testing](TESTING.md)                               | Test layers, commands and limitations                 |
| [Security](SECURITY.md)                             | Account storage, networking and update protections    |
| [Status](STATUS.md)                                 | Known issues and work in progress                     |
| [Changelog](CHANGELOG.md)                           | Version history                                       |
| [AI contributor rules](AGENTS.md)                   | Mandatory repository instructions                     |
| [AI working rules](docs/AI_WORKING_RULES.md)        | Scope, compatibility, evidence and release boundaries |
| [AI change checklist](docs/AI_CHANGE_CHECKLIST.md)  | Checks before editing, submitting and delivering      |
| [AI task template](docs/AI_TASK_TEMPLATE.md)        | A concrete work brief and PR description              |
| [Update publishing](android-tv-launcher/UPDATES.md) | Signed release process                                |
| [Design notes](docs/)                               | Interface and catalogue documentation                 |
| [Archive](docs/archive/)                            | Dated audits and older status notes                   |

## Project status

Current release: [MovieHub 1.0.19](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.19),
with ARM32 and ARM64 in one APK. See [STATUS.md](STATUS.md) for open problems and
[CHANGELOG.md](CHANGELOG.md) for what changed.

Automated tests use synthetic data and do not establish that every external service, live channel or update path works on physical hardware. Device-specific behaviour, especially live video handoff and provider integrations, needs real-world testing.

## Content responsibility

> [!WARNING]
> **MovieHub does not host, store or distribute films, series, channels or streams.** Public channel listings and user-configured services are supplied by independent third parties. Their availability, rights and reliability are outside this project's control.

Users are responsible for accessing only content and services they are legally entitled to use. Channel names and logos belong to their respective owners. MovieHub is not affiliated with Sky, broadcasters, streaming platforms or source providers

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
