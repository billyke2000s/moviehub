# Status

Current state only. Keep this file to about one screen: overwrite it, do not add dated sections.
History is in [CHANGELOG.md](CHANGELOG.md); old status notes are in
[docs/history/STATUS.md](docs/history/STATUS.md).

_Last updated: 22 November 2026._

## Published

- **MovieHub 1.0.23 (version code 124)**, published 22 November 2026: the
  [numbered release](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.23) and the
  [in-app update feed](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest).
  The feed's `update.json` is authoritative; this line can lag behind it.
- **MovieHub Experimental exp-15** (`android-tv-experimental`, 24 October 2026). Since 1.0 the
  experimental app has the same features as MovieHub; only its identity differs.

## Release candidate

The combined release includes the guide focus/preview fixes, channel duplicate/name corrections,
and optional signed scraper update support. Self-hosted maintenance is inactive until the feed
and signing key are configured; the APK does not automatically fix sports player protocols.

## Not yet verified

- Automated tests use fixtures and an emulator. Real TMDB, Premiumize, Xtream and M3U accounts,
  real providers and installing/updating on the owner's TV box are only checked by hand.
- Phone layout (sideways TV pages, shipped in 1.0.20) has not been checked on a real phone. Its PR
  #203 is still open although the change is in `main`.

## Code health

- Over the 700-line limit in AGENTS.md, to be split in behaviour-free commits: `LiveZapper` (866),
  `LiveTvScreen` (820), `SettingsScreens` (739), `ChannelDatabase` (731), `LiveStage` (728),
  `ExoLive` (724), `VlcPlaybackActivity` (703).
- Many screens reach directly into `MainActivity` fields, so a change in one screen can affect
  another. Check callers before changing a shared field.

## Open pull requests

About 30 are open. Old status notes (25 October 2026) recorded #121–#140 as already contained in
the experimental line that became 1.0; they are likely superseded and should be checked and closed.
Dependabot updates (#204–#209, #212) are waiting for review.
