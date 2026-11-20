# Connecting Masqueradarr to MovieHub

Masqueradarr is an **optional separate server**. MovieHub does not run or bundle that server, and connecting it does not guarantee a working stream.

## How to connect

1. Deploy a Masqueradarr instance you are authorised to use, following the [upstream project](https://github.com/TheBinaryNinja/masqueradarr).
2. Obtain your M3U export URL. Confirm that it returns an M3U playlist and is reachable **from the TV box**, not only from your server.
3. In MovieHub, open **Settings → Accounts → Masqueradarr** and enter the export URL. Alternatively, use **Accounts → M3U playlist** to add a normal playlist without a dedicated Masqueradarr account.
4. Connect, refresh channels and try an authorised stream in **Live TV**. Check programme information, switching, guide/fullscreen continuity and playback.
5. For a trusted local HTTP server, use the **Allow http:// links** setting under Playback → Connections. Never expose a server without appropriate access controls.

A connected Masqueradarr account also appears in **Settings → Setup** so it can be managed or disconnected. Other sources and accounts are separate.

## Important limitations

- Stream and proxy URLs may require the Masqueradarr server to stay online.
- M3U export links can contain access tokens. Keep them private; do not include them in an issue, screenshot or source-code commit.
- Successful channel import does not certify live playback. Actual streams can fail due to server errors, expiry, network restrictions and unsupported media.
- Current Live TV uses **Media3 ExoPlayer**. Older October 2026 notes that call it a LibVLC live player describe a historical implementation.

## Engineering history

The dedicated optional Masqueradarr connection was added in October 2026 using the existing channel import and encrypted account paths. Early notes proposing that it first be implemented as a generic M3U account are superseded. Source history retains those proposals. No Masqueradarr server is embedded in MovieHub.

See [native source notes](NATIVE_FREE_SOURCES.md), [security](../SECURITY.md) and [testing](../TESTING.md) for implementation details and verification limits.
