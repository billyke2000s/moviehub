# MovieHub for Android TV

> ## ⚠️ MovieHub doesn't host anything
>
> **MovieHub is just an app. It doesn't store or share any videos, channels or streams.** The free
> channels come from two public lists run by other people,
> [Free-TV](https://github.com/Free-TV/IPTV) and [iptv-org](https://github.com/iptv-org/iptv), and
> they play from other people's servers. Anything else comes from accounts you add yourself. **We
> don't control or check any of it. What you watch is up to you.**

MovieHub is a home screen for Android TV boxes (Android 9 or newer). It puts your apps, films,
series and live TV in one place, and it's built to be used with a remote.

## What it does

- **Home:** big artwork, a row of posters, a clock and your favourite apps. Move the apps around
  with the remote. Android settings are one click away.
- **Apps:** all your installed apps, with search and recently used apps. Hide the ones you never use.
- **Films and series:** browse by category or genre, search by title or actor, and see cast,
  trailers and similar titles.
- **Playing things:** finds sources through Torrentio, MediaFusion or Comet, with Premiumize. Pick
  subtitles, audio, speed and picture size, and set a sleep timer.
- **My List and Continue Watching:** save titles and carry on where you left off.
- **Live TV:** free channels, or your own Xtream or M3U playlist. A TV guide, favourites, search and
  reminders. The channel list refreshes itself every 12 hours, and keeps the old list if that fails.
- **Phones and tablets:** on first open it asks whether you're using a TV or a phone. The phone
  layout covers Live TV for now.
- **Updates:** an Update button shows up next to Settings when there's a new version. The app
  checks the update is genuine before installing it.

Your account details are typed in on the TV and stored encrypted on the device. None of them are
in this repository.

## Install

> [!TIP]
> ### 📺 Install with Downloader
> **Downloader code: `1031512`**
>
> Open the **Downloader** app on your Android TV device, enter **1031512** in its address/code field, and follow the download prompts.
>
> **Direct APK:** [Download MovieHub (home.apk)](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)

Download [home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk)
from [Releases](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest) and install
it on your TV box. After that, new versions come through the Update button in the app.

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

The automated tests use made-up data. They don't prove that a real TMDB, Premiumize or Xtream
account works, or that the app installs and updates properly on every TV box. Check those on a real
box before a release.

## Content and copyright

- **Nothing is hosted here.** There are no videos, streams or channels in this repository, its
  releases or on any server for this project.
- **The free channels are other people's links.** If you haven't added a TV provider, Live TV loads
  the public Free-TV and iptv-org lists from their own websites. The streams stay on other people's
  servers. We don't pick, check or control them. If a stream has a problem, report it to those
  lists.
- **Everything else is added by you.** Xtream or M3U providers, Premiumize and the source finders
  are services run by other people, and you choose whether to use them.
- **It's your responsibility.** Only watch what you're allowed to, and only use services that are
  legal where you live.
- **We're not linked to any broadcaster.** Channel names and logos belong to their owners and are
  only shown so you know what's on. The guide looks like an old UK satellite box, but this project
  has nothing to do with Sky or any broadcaster.
- **Takedowns.** If you think something here breaks your rights, contact the owner through GitHub
  and it will be taken down quickly.

This is here to be clear, not legal advice.

## License

[MIT](LICENSE). The Live TV screens use the Hind typeface (Indian Type Foundry), bundled under
the SIL Open Font License 1.1: see `android-tv-launcher/app/src/main/assets/fonts/Hind-OFL.txt`.

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

The guide preview and full screen retain one decoder Surface for the live session. A retained
last frame covers the view transfer until fresh video arrives. Device tests check fresh rendered
frames in both directions; continuity on individual TV hardware still needs testing.

Live video progress now follows TextureView buffer updates rather than assuming advancing audio
means video is healthy. A stopped visible video consumer gets one view-only refresh before the
existing bounded source recovery. Troubleshooting retains the last safe recovery description.

Live TV Back now reveals the channel controls and a selectable Sources link before returning to the mini-player on the next Back. Sources defaults to Automatic; selecting a particular saved copy pins it until Automatic or another channel is selected. Source menus read saved channel metadata; stream resolution remains on demand.
