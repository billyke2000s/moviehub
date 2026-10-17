# Security

MovieHub stores provider accounts and installs its own updates, so these rules matter.

## Accounts and secrets

- No credentials, API keys, signing keys or firmware images are committed to this repository.
  Account details are entered on the TV.
- Account details (TMDB, Premiumize, Xtream, M3U), the saved library and recent playback are
  encrypted with AES-GCM using a device-bound Android Keystore key (`Secrets`).
- IPTV stream addresses are stored encrypted in the channel database.
- `android:allowBackup="false"`, so app data is not copied into Android backups.
- Diagnostic logging (`Diagnostics`, logcat tag `MovieHub`) records only where a failure happened
  and the exception type. Exception messages are never logged, because they can contain provider
  URLs with usernames and passwords.

## Network

- The manifest allows cleartext traffic because many IPTV providers only offer `http://`.
- The app's own network layer (`Playlist.networkUrl`) still refuses `http://` addresses unless the
  user switches on the HTTP setting. It is off by default.
- Bearer tokens are only sent over HTTPS, and only to the host the request started on (`Network`).
  Redirects to another host or to HTTP drop the token.
- Downloads are size-bounded.

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

## Reporting a problem

Do not put credentials or private provider URLs in a public issue. Contact the repository owner
privately through GitHub.
