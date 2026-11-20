# MovieHub Android application

This directory contains the native Android app. To **download, install and use MovieHub**, start with the [main README](../README.md).

## What runs here

- Java-based launcher with Home, Movies, Series, My list, Continue watching, Live TV and installed apps.
- **Media3 ExoPlayer** for Live TV in the guide and fullscreen; **LibVLC** for films and episodes.
- Settings → Setup for free-channel services, optional Nuvio Sports, saved links, parental controls and engineer tools; Settings → System → Help for everyday troubleshooting.
- Standard signed package `local.tv.home`, with **ARM32 and ARM64** libraries in one APK. Experimental builds use a separate package and app data.

These are source-code capabilities, not guarantees that every external service works. See [latest releases](https://github.com/billyke2000s/moviehub/releases/latest) for published features.

## Contributing

See [Development](../DEVELOPMENT.md), [Architecture](../ARCHITECTURE.md), [Testing](../TESTING.md), [signed updates](UPDATES.md) and [contributor rules](../AGENTS.md). Do not publish an APK as a side effect of an ordinary documentation change.
