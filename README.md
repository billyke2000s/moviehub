# MovieHub for Android TV

> **MovieHub does not host, store, upload or distribute any films, programmes, channels or
> streams.** It is a launcher and player: it only plays what the person using it chooses from their
> own accounts or from third-party lists and services. See [Content and copyright](#content-and-copyright).

A native Android TV launcher for Android 9 or newer. It replaces the TV's home screen and brings
together installed apps, movies and series, live TV and playback in one remote-friendly interface.
Its icon is a simple house with a play button (vector drawables in `app/src/main/res/drawable`).

## Features

- **Home**: featured artwork, poster shelf, clock, and a dock of installed apps you can rearrange
  with the remote. Android Settings and Apps are one click away.
- **Apps**: every installed app as its TV banner, search, recently used apps, Android shortcuts
  (Wi-Fi, Bluetooth, display, sound, storage, ...), and hiding apps you never use.
- **Movies and Series**: TMDB browsing by category, genre and filters; search by title, person or
  company; details pages with cast, collections, trailers and recommendations.
- **Sources and playback**: finds streams through Torrentio, falling back to MediaFusion and then
  Comet, with Premiumize cloud, cache and transfers. Plays with LibVLC, with subtitle and audio
  language preferences, track choice, speed, picture ratio, subtitle timing and a sleep timer.
- **My List and Continue Watching**: saved titles, resume positions and watched episodes.
- **Live TV**: free public channels from [iptv-org](https://github.com/iptv-org/iptv) by country, or Xtream and M3U playlists imported in the background into an on-device database, with
  no channel-count limit. Search by name or group, favourites, and automatic refresh every 12 hours.
  A failed refresh keeps the previous list.
- **Updates**: an Update button appears beside Settings only when a newer signed version is
  published. Updates are verified before Android's installer opens.

Account details are entered on the TV and stored encrypted with Android Keystore. Nothing secret is
in this repository.

## Install

Download the signed [home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)
from [Releases](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest) and install
it on the TV. Later versions arrive through the in-app Update button.

## Build

You need JDK 17, Android SDK 35 and Gradle 8.10.2.

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

Full setup, formatting and conventions are in [DEVELOPMENT.md](DEVELOPMENT.md).

## Documentation

| Document | Read it for |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the code is organised and which file does what |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Building, code style, branches and releases |
| [TESTING.md](TESTING.md) | Test layers, how to run them, and known flaky tests |
| [SECURITY.md](SECURITY.md) | How accounts, network access and updates are protected |
| [STATUS.md](STATUS.md) | Current state, known issues and next steps |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version |
| [AGENTS.md](AGENTS.md) | Rules for AI assistants editing this repository |
| [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md) | Publishing signed updates |
| [docs/](docs/) | Design notes: TV interface, catalogue categories, poster feeds |

## Limits

Automated tests use local fixtures. They do not prove that a real TMDB, Premiumize or Xtream
account works, or that the app installs and updates correctly on a specific TV box. Check those on
the device before a release.

## Content and copyright

- **No content is hosted here.** This repository and the app contain software only. No video,
  stream, channel or programme is stored in this repository, in its releases or on any server run
  for this project.
- **Where streams come from.** Everything the app plays comes from services and lists run by
  others and chosen by the user: the user's own Xtream or M3U provider, Premiumize, the public
  [Free-TV](https://github.com/Free-TV/IPTV) and [iptv-org](https://github.com/iptv-org/iptv)
  channel lists, and the third-party source finders listed under Features. This project does not
  control, check or endorse what those services and lists contain, and has no affiliation with
  them or with any broadcaster, studio or satellite provider.
- **Your responsibility.** You are responsible for making sure you have the right to watch what
  you play, and that using any provider, list or service is legal where you live. Use only
  content you are licensed to access.
- **Names and trademarks.** TV channel names, logos and programme information belong to their
  owners and are shown only to identify what is playing. The guide's look is inspired by classic
  UK satellite boxes; this project is not affiliated with or endorsed by Sky or any broadcaster.
- **Takedown requests.** If you believe something in this repository infringes your rights,
  contact the owner privately through GitHub and it will be looked at promptly.

This notice is provided for clarity. It is not legal advice.

## License

[MIT](LICENSE). The Live TV screens use the Hind typeface (Indian Type Foundry), bundled under
the SIL Open Font License 1.1: see `android-tv-launcher/app/src/main/assets/fonts/Hind-OFL.txt`.
