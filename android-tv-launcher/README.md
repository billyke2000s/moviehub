# Native Android TV launcher

This module adds a native Android TV HOME application alongside the existing Movie Hub Kodi add-on and private server. Existing repository components are retained.

The launcher has Live TV, Movies, Series, TMDB browsing and recommendations, Premiumize accounts/cloud/cache/transfer controls, Torrentio sources with MediaFusion/Comet fallback endpoints, an installed-app dock, Android Settings access, and internal LibVLC playback. It uses the user-provided visual reference without a custom product wordmark or logo.

GitHub Actions builds a debug-signed APK and verifies its signature. Debug signing is for review/testing, not a controlled firmware release key. Account keys are entered on the device and encrypted through Android Keystore; none are bundled or sent to the build service.

Build locally with JDK 17, Android SDK 35 and Gradle 8.10.2: `gradle -p android-tv-launcher assembleDebug lintDebug`.

See REUSE.md for the source logic ported from the existing Kodi add-on. Private-server/profile/Trakt sync is retained in the existing repository but is not connected to this native module.

Runtime playback, provider responses, visual fidelity and device compatibility require testing. Successful compilation does not establish that the firmware is flash-ready. No firmware image or source firmware is uploaded by this workflow.
