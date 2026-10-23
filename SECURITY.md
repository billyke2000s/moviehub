# Security

## Automatic source selection

Automatic play uses only already-cached sources and never queues a paid/account transfer automatically. Resolved links pass the existing network URL policy before launch. Selection tries at most five candidates, and screen-generation/cancellation guards prevent an abandoned request from starting playback. Episode catalogue prefetches use the existing authenticated TMDB client; no credentials or provider links are logged.


MovieHub stores provider accounts and installs its own updates, so these rules matter.

## Accounts and secrets

- No credentials, API keys, signing keys or firmware images are committed to this repository.
  Account details are entered on the TV.
- Account details (TMDB, Premiumize, Xtream, M3U), the saved library and recent playback are
  encrypted with AES-GCM using a device-bound Android Keystore key (`Secrets`).
  Once read, values are kept decrypted in the app's memory until it closes (not on disk), so
  every request does not have to go through the Keystore.
- IPTV stream addresses are stored encrypted in the channel database.
- `android:allowBackup="false"`, so app data is not copied into Android backups.
- Diagnostic logging (`Diagnostics`, logcat tag `MovieHub`) records only where a failure happened
  and the exception type. Exception messages are never logged, because they can contain provider
  URLs with usernames and passwords. The same place and type (with the time) are kept in memory
  for Troubleshooting's "Recent errors" (`ErrorLog`, last 50, cleared when the app closes).
- Troubleshooting's connection checks show each service by name only (stream-source providers by
  host name, since a provider's address can carry its settings), never addresses or keys.

## Network

- The manifest allows cleartext traffic because many IPTV providers only offer `http://`.
- The app's own network layer (`Playlist.networkUrl`) still refuses `http://` addresses unless the
  user switches on the HTTP setting. It is off by default.
- Bearer tokens are only sent over HTTPS, and only to the host the request started on (`Network`).
  Redirects to another host or to HTTP drop the token.
- Downloads are size-bounded.
- The speed test in Troubleshooting downloads test data from `speed.cloudflare.com` (at most
  50 MB or 10 seconds). It runs only when the viewer presses "Run speed test" and sends nothing
  about the viewer or their accounts. The "Internet" connection check asks the same host for
  nothing.

## App updates

- Updates come from the public `android-tv-latest` GitHub release. No token is sent to it.
- Before installing, the app verifies the SHA-256 checksum, the package name, that the version code
  increases, and that the signing certificate matches the installed app. It then hands the file to
  Android's installer, which asks the user. There is no silent install and no root.
- The permanent signing key is stored only in GitHub Actions secrets. Losing it means existing
  installs can never update again, so keep an offline backup. See
  [android-tv-launcher/UPDATES.md](android-tv-launcher/UPDATES.md).

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Metadata, streams, provider APIs, updates |
| `REQUEST_INSTALL_PACKAGES` | Installing verified app updates |
| `REQUEST_DELETE_PACKAGES` | Uninstalling apps from All Apps (Android asks the user) |

## Scam messages in streams

Free Live TV channels come from public lists we don't control, so a stream could one day show a
fake "your device has a virus, call this number" message. The first time MovieHub opens, it shows
a short "Staying safe" notice (also in Settings → System): MovieHub will never ask you to leave the
app while you're watching, so any message in a stream telling you to visit a site, ring a number,
install something or pay isn't from us. Ignore it and watch something else. Device tests mark the notice as read through their test
runner (`MovieHubTestRunner`); `SafetyNoticeTest` checks it shows.

## Reporting a problem

Do not put credentials or private provider URLs in a public issue. Contact the repository owner
privately through GitHub.

## Native free-TV sessions

Pluto uses an anonymous session from the actual network region, without embedded accounts or geographic overrides. Tokens and signed playback URLs remain in memory. SQLite stores stable encrypted locators. Provider API endpoints are restricted to HTTPS Pluto subdomains; redirects do not carry bearer tokens to another authority. API responses and retries are bounded, and channel switches disconnect stale requests. Ads, HLS key retrieval and provider access checks are preserved. No upstream private adapter code or licensing assumptions are imported.

## Experimental provider code

Provider transforms run under an isolated Android UID without app accounts/files or network, with
Java access denied, bounded input/output, stack/deadline limits and a process watchdog. Only compiled
APK code or RSA-authenticated packages can be selected; no unsigned GitHub source is executed.
The remote trust anchor is intentionally unconfigured. Rollback preserves a monotonic network
version floor. See [security boundaries and setup](docs/STANDALONE_PROVIDER_ENGINE.md).

## Runtime 2 provider capabilities

Upstream async code executes without network privilege in the isolated service. The Android HTTP broker
checks per-provider host capabilities, redirects, private DNS targets, methods, headers and request budgets.
DaddyLive has an explicit bounded public-HTTPS-GET capability for rotating players; it receives no app
credentials. HMAC/decompression are bounded pure-data callbacks. The optional HLS relay exposes only random
session grants on loopback, with bounded workers/targets and stop-time cleanup. Credential headers stay on
the initial master authority. DNS checks are not DNS pinning, and full JS/runtime security review remains
outstanding. Signed update trust is still unconfigured. See the provider-engine document for exact limits.

ZLive's original IP blocklists, refusal cooldowns and decoy detection execute unchanged. Its replay ledger
is encrypted, module/domain scoped, bounded to 128 contact events/three hours and never reissues historical
HTTP/DNS work. Missing/corrupt history fails closed. A full ledger prevents new contacts while still allowing
cached targets; incompatible module/domain state pauses new contacts until old history expires. User channel
cancellation records a per-channel cancelled contact rather than imposing a global refusal cooldown.

## Anonymous playlist sessions

DaddyLive tokens are obtained from the player-advertised anonymous HTTPS endpoint; page JavaScript is
never executed. Session data lives only in the operation/player's memory. Renewal endpoints must match
the stream authority, exact `/api/playlist-token` path and the channel slug; tokens apply only to that
channel's playlists, never segments or another host. Renewal bodies are capped at 16 KiB, lifetime at
10 minutes, and a rejected token gets at most one renewal retry per relay request. Existing public-DNS,
redirect, header and cancellation checks apply. Tokens are not persisted or published.

PNG segment decoding checks chunk bounds and CRCs, 8-bit RGB/RGBA layout and row filters before extracting
a recognized payload. Encoded data is capped at 16 MiB, dimensions at 8192 per axis and inflated/decoded
data at 24 MiB; a valid five-packet TS sync is required. No Android bitmap allocation, downloaded code,
DRM processing or browser is involved. Physical-box memory/CPU and long-running playback remain untested.

Provider refresh diagnostics persist only fixed error categories or HTTP status numbers. Exception
messages, URLs, response bodies and tokens are not rendered in Settings. Sanitized isolated-service
exception type chains can identify a processing deadline; arbitrary transport error strings are rejected.

Separating link resolution from video startup does not extend interpreter, broker, HTTP or request-count limits. It removes the competing video-startup timer only while the bounded native lookup is pending. Cancellation/generation checks still discard results after a channel switch or stop; the callback belongs to the attached view owner.

Silent-stall recovery applies to native/provider playback after actual playback starts, while the view is visible and the player is playing. It uses aggregate clock/frame counters only; no addresses or session values are logged. It reuses the existing one-provider-retry bound and cancellation/generation checks. PNG decoding keeps checksum/layout/inflation/TS bounds and explicitly releases its native inflater after every segment.

## Live video presentation

The retained live output and handoff bitmap stay in process memory. The incoming cover is
removed on the next fresh frame; the outgoing cover is discarded with its old presentation host.
Session stop releases the active output and cover. Handoffs do not save pictures or provider URLs to disk and
do not add permissions, network endpoints or changes to stream authorization.

The video-only consumer refresh keeps the existing in-memory relay session and does not make a
new provider request itself. Troubleshooting stores fixed labels and counts only. The sustained
diagnostic uploads numerical audio/video results, never provider URLs, tokens, pages or media.
Its host-JVM adapter stubs are test-only and are not packaged in the APK.

The Sources menu uses already imported channel rows and does not crawl provider pages or probe backups. Manual mode suppresses cross-source failover; Automatic retains the existing finite retry order. FreeShot is opt-in; validation evidence and its restricted renewal capability are described below.

FreeShot executes no fetched scripts or adverts. Its catalogue and resolver have narrow audited HTTPS GET paths; media must be on a discovered BlueTier subdomain and the selected channel prefix. Only PlaylistSession may POST an anonymous current-token/channel body to exactly `https://wideiptv.top/api/refresh_token.php`, with no query or fragment. Tokens stay in memory; other origins and channel prefixes never receive them. No generic broker POST capability was added. PNG nonzero/partial ranges remain rejected; whole-entity ranges are decoded as complete entities without forwarding container offsets.

Retained source choices and attempt keys stay in memory and are cleared on stop/new channel. Matching the guide selection to an active saved copy does not resolve or contact other providers, and exposes no URLs in the UI or logs.

## In-memory VOD recovery sessions

VOD alternatives are bounded to five cached sources, resolved on demand, and passed via a random in-process session token rather than lists of direct URLs in intents or preferences. Every resolved URL is validated against the existing HTTP setting. No new external executables or provider scripts are introduced. Logs and labels exclude resolved URLs. Cancellation generations discard late results; stopping playback releases the staged player and shuts down its resolver. Existing encrypted history stores the primary playback URL as before.

## Prepared live playback

A standby uses the same saved-channel URL validation, HTTP opt-in, provider allow-lists and
scoped relay transport as the active source. It resolves one saved copy at a time, with bounded
resolution/playback lifetimes and one candidate decoder/relay. Cancellation generations reject
late results and close their relays; native readers stop before their surfaces or relays close.
Diagnostics retain fixed descriptions only, without resolved links, tokens or account details.
No background discovery or new provider capability is introduced.
