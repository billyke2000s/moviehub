# Android TV launcher

Native Android TV home screen for the Tanix TX5 Plus S905X5M, with no custom logo or wordmark.

Live TV supports Xtream and M3U playlists. Movies and Series provide TMDB browsing and recommendations, source aggregation, Premiumize cloud/cache/transfers, and LibVLC playback. The launcher includes an installed-app dock, remote navigation, Android Settings access, subtitle/audio language preferences, and a conditional app-update button.

## Build and download

Use the **Build Android TV launcher APK** workflow for a debug APK. It runs 30 core checks, Android compilation and lint, and APK signature verification. Download the APK artifact from the completed workflow run.

For a local build, use JDK 17, Android SDK 35 and Gradle 8.10.2:

```sh
gradle -p android-tv-launcher assembleDebug lintDebug
```

## Updates

The **Publish Android TV launcher update** workflow publishes signed APKs and update metadata. Configure the permanent signing key first; see [update setup](android-tv-launcher/UPDATES.md). The Update button beside Settings appears only for a newer published version. Publishing source to main does not publish an APK release.

## Status

The APK builds successfully. Tanix runtime behavior, playback, on-screen keyboard and update installation require device testing. No firmware image has been integrated or flashed. Build success does not establish flash-ready firmware.

Account details are entered on the device and stored using Android Keystore. No credentials or firmware images are included in this repository.
