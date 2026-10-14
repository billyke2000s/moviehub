# Android TV launcher

Native launcher for Android TV and Android box devices running Android 9 or newer, with no custom logo or wordmark.

Live TV supports Xtream and M3U playlists. Movies and Series provide TMDB browsing and recommendations, automatic Torrentio → MediaFusion → Comet fallback, Premiumize cloud/cache/transfers, and LibVLC playback. The launcher includes an installed-app dock, remote navigation, Android Settings access, subtitle/audio language preferences, and a conditional app-update button.

## Build and download

Download the signed [home.apk](https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/home.apk) from [Releases](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest).

The **Build Android TV launcher APK** workflow provides separate debug builds and runs 30 core checks, Android compilation/lint and signature verification. Use the signed release APK as the baseline for in-app updates.

For a local build, use JDK 17, Android SDK 35 and Gradle 8.10.2:

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

## Updates

The **Publish Android TV launcher update** workflow publishes signed APKs and update metadata. It uses the permanent signing key stored in GitHub Actions secrets; see [update setup](android-tv-launcher/UPDATES.md). The Update button beside Settings appears only for a newer published version. Publishing source to main does not publish an APK release.

## Status

The APK builds successfully. Playback, remote navigation, on-screen keyboard and update installation require testing on each device. Compatibility with every Android box has not been verified.

Account details are entered on the device and stored using Android Keystore. No credentials or firmware images are included in this repository.
