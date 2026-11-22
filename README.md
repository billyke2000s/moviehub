# MovieHub

**Films, series and live TV, together on your Android TV.**

MovieHub brings browsing, playback, live-channel guides and your installed TV apps into one remote-friendly interface. You can connect your own services, choose a stream when several are available, and pick up where you left off.

MovieHub is an independent app. **It does not host films, series or live channels, and it is not a streaming subscription.** Content comes from public listings and services you choose to use. Availability varies.

[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/tv/)
[![MIT license](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/billyke2000s/moviehub?style=flat-square&label=Latest%20version)](https://github.com/billyke2000s/moviehub/releases/latest)

**[Download MovieHub (APK)](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)** · [See the latest release](https://github.com/billyke2000s/moviehub/releases/latest) · [Report a problem](https://github.com/billyke2000s/moviehub/issues)

## Install MovieHub

MovieHub is made for **Android TV devices running Android 9 or newer**. Its standard APK includes support for both 32-bit and 64-bit ARM Android firmware.

1. Download **home.apk** using the button above, or open the **Downloader** app on your TV device and enter code **5881724**.
2. Open the downloaded file and allow installation from that source if Android asks.
3. Launch MovieHub and follow the on-screen setup.
4. Add the services you want to use. You can start with public Live TV lists, or connect your own film, series and TV services.

If you already have MovieHub installed, use its **Update** button when a newer signed version is available. You normally don't need to uninstall it first.

## What can you do?

| | |
| --- | --- |
| **Find something to watch** | Browse films and series by title, genre and other filters. Artwork and descriptions come from TMDB. |
| **Watch live TV** | Browse channels in a TV-style guide, including public channel lists and any playlists or providers you connect. |
| **Pick a source** | Use automatic playback or choose an available source yourself. If a stream fails, the app can try alternatives when they exist. |
| **Keep your place** | Save favourites and use My list and Continue watching. |
| **Open your other apps** | Get to installed Android TV apps without leaving the launcher. |

The live TV guide and film/series player use different playback engines. Not every provider, channel, subtitle or stream format works on every device.

## Set up your services

Go to **Settings** to choose what MovieHub can access:

- **Accounts:** connect supported services such as TMDB, Xtream and your own M3U playlist. Where offered, you can enter details on the TV or use your phone.
- **Setup → Free channels:** select public Live TV services and channel lists.
- **Setup → Premiumize:** connect an optional Premiumize account and manage its cloud options.
- **Setup → Nuvio Sports:** on version **1.0.21 or later**, connect an independent Nuvio Live Sports server if you use one. It is not bundled with MovieHub, and live-stream availability is not guaranteed.
- **Setup → Parental lock:** choose which sections need a PIN.

**TMDB provides film and series information, not streaming access.** You may need your own TMDB API key to use the film and series catalogue. Other services have their own requirements.

## Using MovieHub

**Films and series:** open a title, select Play or Sources, and choose a stream if more than one is found. Use the Browse controls to narrow down what you're looking for.

**Live TV:** choose a channel in the guide. Use the on-screen buttons at the bottom (press BACK to reach them); remotes with real colour keys can press them directly. CH+ and CH- move one channel at a time. You can open sources, audio and subtitle options from the player.

**Help:** go to **Settings → System → Help** for common problems such as missing internet or low playback quality. Technical information lives under **Settings → Setup → Engineer view**.

### Updates

When a newer signed release is available, MovieHub shows **Update** near Settings. It checks the download before handing installation to Android. If an update is not offered, check the [releases page](https://github.com/billyke2000s/moviehub/releases/latest); development work in an open pull request is **not** an app update.

There is also a separate [MovieHub Experimental](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-experimental) app for testing changes. It installs alongside the main app and keeps separate data. Use the ordinary download above unless you specifically want a test version.

## Questions and problems

**A channel or film won't play.** Streams come from different providers and can expire, be unavailable in your region or use an unsupported format. Try another source if MovieHub finds one. A listed provider does not guarantee a playable stream.

**I can't see films or series.** Check your TMDB setup and internet connection. MovieHub uses TMDB for catalogue information.

**I can't install the APK.** Check Android version, available storage and whether your device has allowed installation from the app you used to download it. Use the standard APK, not an emulator-only build.

**I found a bug.** [Open a GitHub issue](https://github.com/billyke2000s/moviehub/issues) with the MovieHub version, Android device and steps to reproduce it. Please remove passwords, playlist URLs containing tokens and other personal details.

## For developers

MovieHub is open source. Build instructions, implementation notes and verification history are kept separately from this user guide.

| Document | Contents |
| --- | --- |
| [Development](DEVELOPMENT.md) | Local build, code conventions and contributing |
| [Architecture](ARCHITECTURE.md) | How the Android app and services are organised |
| [Testing](TESTING.md) | Tests and what they do (and do not) prove |
| [Updates](android-tv-launcher/UPDATES.md) | Signed publishing and the updater feed |
| [Status](STATUS.md) | Current release, known limits and open work (one page) |
| [Changelog](CHANGELOG.md) | Changes by version |
| [Security](SECURITY.md) | Credential handling and vulnerability reporting |
| [Contributor instructions](AGENTS.md) | Rules for making changes to this repository (the one rulebook for AI tools) |

## Content and privacy

MovieHub does not host, store or distribute movies, episodes, channels or streams. Public lists and connected services are supplied by third parties; you are responsible for accessing only content you are entitled to use. MovieHub is not affiliated with broadcasters, streaming brands or source providers.

Connected account details are kept on the device using the app's protected storage. See [Security](SECURITY.md) for implementation details and how to report a vulnerability privately.

## Licence and credits

MovieHub is released under the [MIT License](LICENSE). Film and series information comes from [The Movie Database (TMDB)](https://www.themoviedb.org/); MovieHub is not endorsed or certified by TMDB. The Live TV interface uses the Hind typeface under the [SIL Open Font License](android-tv-launcher/app/src/main/assets/fonts/Hind-OFL.txt).

---

**[Download MovieHub](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)** · [Releases](https://github.com/billyke2000s/moviehub/releases) · [Issues](https://github.com/billyke2000s/moviehub/issues)
