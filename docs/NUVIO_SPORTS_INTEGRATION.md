# Nuvio Live Sports integration

Status: optional hosted connection / Android test build candidate. **No real provider playback has been verified.**

For one-machine hosting alongside Masqueradarr, follow the [LAN Docker guide](../deploy/home-server/README.md).
This is a server-based option: it does not replace MovieHub's existing built-in free providers.

## Connect and use

1. Run your own Nuvio Live Sports service, or supply another instance you are authorised to
   use. The upstream is https://github.com/rajhodedara/live-sport-plugin; its Node server is
   **not embedded** in MovieHub. The field starts empty rather than silently favouring a
   public community server. For a home server, use a static LAN IP and `ADDON_URL` matching
   that reachable IP so the stream URLs are valid from the TV.
2. On the TV box open **Settings → Setup → Nuvio Sports**. Enter the **base address** such
   as `http://192.168.1.50:7000`, not `/manifest.json` and not `localhost`. Connect
   verifies the addon's manifest before storing the address in the Android Keystore-backed store.
3. For a trusted local HTTP server, turn on the existing **Allow http:// links** setting.
   Do not expose a locally hosted server to the public internet without access controls.
4. Open **Live TV → Sports**. Live matches and the 24/7 sports channel catalogue import
   into the existing guide, alongside all existing channels. A play action requests
   `/stream/tv/{id}.json` at playback time, never at import time.
5. The guide queues a targeted sports catalogue refresh at most every 15 minutes while
   Live TV is opened. Android WorkManager may defer background work.

## Constraints

- An empty, failed or interrupted catalogue update never erases the last good Nuvio rows.
  Disconnecting queues a channel refresh to remove Nuvio's source while retaining all other providers.
- User favourites and channel identity retain Nuvio's stable event ID; expiring HLS
  tokens are not stored in channel rows.
- Direct HLS and supported proxied HLS URLs can be handed to MovieHub's existing
  ExoPlayer live pipeline. Browser-only embeds, YouTube pages and unsupported DASH
  streams are not mislabelled as playable. Failed direct links are avoided when
  alternative direct links are available.
- Stream proxying, resolving provider websites and keeping links alive are handled by
  the separate upstream service. A Nuvio server outage leaves existing MovieHub
  providers unchanged; cached Nuvio channels cannot play while their server is down.
- No changes to the signed app's package ID, Android ABI support, films/series or
  existing provider configuration. No Nuvio source code was copied into MovieHub.

## Validation

`NuvioSportsTest` contains isolated API/URL parsing fixtures. A successful build
does **not** establish that the listed events are genuine live video or that a
third-party server works in the user's region. Before considering playback proven,
test on the real TV box: channel count, a live event, a 24/7 network, audio/video
frames, a failed stream with alternatives, refresh after kickoff, server outage,
and reconnection without losing other providers.
