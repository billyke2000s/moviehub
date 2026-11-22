# Status

Current state only. Keep this file to about one screen: overwrite it, do not add dated sections.
History is in [CHANGELOG.md](CHANGELOG.md); old status notes are in
[docs/history/STATUS.md](docs/history/STATUS.md).

_Last updated: 22 November 2026._

## Published

- **MovieHub 1.0.25 (version code 126)**, published 22 November 2026: the
  [numbered release](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.25) and the
  [in-app update feed](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest).
  The feed's `update.json` is authoritative; this line can lag behind it.
- **MovieHub Experimental exp-15** (`android-tv-experimental`, 24 October 2026). Since 1.0 the
  experimental app has the same features as MovieHub; only its identity differs.

## Included and remaining work

This release adds the verified ZLive PNG media-header correction and retains the guide focus/preview
fixes, channel duplicate/name corrections and optional signed scraper update support. A captured F1
backup decoded audio/video; a fresh primary F1 probe still returned HTTP 404 and FreeShot's public page
returned HTTP 403. DaddyLive remains unresolved; this release does not establish that every sports
source plays. Self-hosted maintenance is inactive until the feed
and signing key are configured; the APK does not automatically fix sports player protocols.

Signed publisher [45031535188](https://github.com/billyke2000s/moviehub/actions/runs/45031535188)
and numbered-release copier [45032659975](https://github.com/billyke2000s/moviehub/actions/runs/45032659975)
passed. Both public APKs were independently downloaded: 133,318,200 bytes, SHA-256
`1a7223fda153b721cf4531a2b32d4ab708f7479fffbabb36a5f133430c090238`.
Signature verification matches 1.0.24, and actual native libraries cover ARM32 and ARM64.
The unchanged UHDMovies scraper returned no link for its smoke-test film twice; this is unresolved.

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
