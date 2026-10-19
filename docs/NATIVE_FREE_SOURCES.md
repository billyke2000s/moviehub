# Native free-source integration (Android TV)

## Decision
MovieHub should not start or bundle the Masqueradarr Docker/Node/MongoDB/Rust stack on an Android TV device. Masqueradarr is a self-hosted IPTV aggregator, not a standalone list of links. Instead, adapt **suitable, permitted, client-side source resolution logic** into MovieHub behind a small native source interface. Do not copy providers' credentials or bypass authentication/DRM/access controls.

## Desired experience
1. Opening Live TV loads cached free channels immediately; refresh catalogues in the background with rate limiting and timeouts.
2. Free sources are configured under Settings → Free channels, separately from Xtream and personal M3U.
3. A channel can have multiple source candidates. Resolve URLs just before playback, not when building the guide; handle expiry, errors and a bounded retry/fallback sequence.
4. Pass playable HLS/TS streams to the existing Live TV player and preserve its mini-player handoff without reconnecting.
5. Persist stable channel IDs, EPG mapping, source provenance and user choices. Never persist ephemeral signed URLs.
6. Provide clear per-source diagnostics and a manual refresh; do not silently spin on failed streams.

## Initial implementation sequence
- Audit current FreeChannels, ChannelSync, Playlist, ChannelDatabase, LiveZapper and playback interfaces; document their contracts before editing.
- Start with legitimate public FAST providers with documented/accessible streams and permitted client playback; verify each provider's terms and regional availability. Prefer direct Android playback over a local proxy.
- Add an adapter contract: catalogue fetch, normalized channel metadata, optional EPG, resolve-on-play and source-specific request headers. Each adapter must have tests using local fixtures.
- Add bounded resolution and fallback on a worker thread, with cancellation when switching channels; no work on UI thread.
- Integrate free-source toggles into existing Free channels popup. Keep existing iptv-org and the separately configured Masqueradarr M3U option operational during migration.
- Test startup, navigation, DB migrations, guide/mini-player handoff, HLS/TS playback and offline/error cases on Android emulator; merge only with green CI.

## Explicit non-goals
- Do not imply Masqueradarr server adapters work without porting them.
- Do not add unauthenticated access to paid/private services, embedded secrets, or circumvention of access controls.
- Do not modify the separate MovieHub FreeLinks repository or publish an APK without explicit authorization.

Upstream architecture: https://github.com/TheBinaryNinja/masqueradarr
