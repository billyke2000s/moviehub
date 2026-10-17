# MovieHub for Android TV

Native launcher for Android TV and Android box devices running Android 9 or newer, with no custom logo or wordmark.

Live TV supports Xtream and M3U playlists. Channel lists are streamed into an on-device SQLite database in the background (no fixed channel limit), searchable by name or group, and refreshed automatically every 12 hours; a failed refresh keeps the previous list. Movies and Series provide TMDB browsing and recommendations, automatic Torrentio → MediaFusion → Comet fallback, Premiumize cloud/cache/transfers, and LibVLC playback. The launcher includes an installed-app dock, remote navigation, Android Settings access, subtitle/audio language preferences, and a conditional app-update button.

## Build and download

Download the signed [home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk) from [Releases](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest).

The **Build Android TV launcher APK** workflow runs 45 core checks (including a 250,000-channel streaming parse), debug/release compilation and lint, Android instrumentation tests for the encrypted database and native player, and signing compatibility checks against the current release. It provides a separate debug APK. Use the signed release APK as the baseline for in-app updates.

For a local build, use JDK 17, Android SDK 35 and Gradle 8.10.2:

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

## Updates

The **Publish Android TV launcher update** workflow publishes signed APKs and update metadata. It uses the permanent signing key stored in GitHub Actions secrets; see [update setup](android-tv-launcher/UPDATES.md). The Update button beside Settings appears only for a newer published version. Publishing source to main does not publish an APK release.

## Status

Build, lint, signed-update compatibility and Android device verification run on pull requests. Deterministic Android tests cover SQLite pagination, favourites beyond 900 entries, failed-import rollback, non-destructive schema upgrades, encrypted stream URLs and actual LibVLC playback/seek/media keys. Provider fixtures exercise Xtream login and TS/HLS URL negotiation and M3U imports; fixtures do not establish live provider availability. Real Xtream/Premiumize accounts and target-TV installation remain release verification requirements.

Account details are entered on the device and stored using Android Keystore. No credentials or firmware images are included in this repository.
