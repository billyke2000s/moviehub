# Security

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
