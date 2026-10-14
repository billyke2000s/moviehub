# App updates

The home screen checks the public `android-tv-latest` release in billyke2000s/moviehub when opened, at most once per six hours during an app process. The Update button beside Settings stays hidden if the release is missing, unreachable, invalid or not newer than the installed version. No account token is needed for this public feed.

Selecting Update asks before downloading, verifies the SHA-256 checksum, package name, increasing version code and matching signing certificate, then opens Android's installer. Android may first require permission to install updates from Home. No root or silent installation is used. A failed check is silent; a failed download/verification produces a retry message. APK downloads are limited to 300 MiB and use HTTPS.

The repository currently has no published APK release. The included publication workflow goes in `.github/workflows/publish-android-launcher.yml`. It requires these GitHub Actions secrets, configured directly in GitHub rather than sent through chat:

- APK_KEYSTORE_BASE64: base64-encoded permanent private signing keystore
- APK_STORE_PASSWORD
- APK_KEY_ALIAS
- APK_KEY_PASSWORD

Keep and back up that signing key; use it for the first release installation and all future releases. Existing debug APKs are not a verified upgrade path to this release key. The updater refuses APKs signed with a different certificate; an initial move from a debug build may require a separate installation and data migration plan.

Run Publish Android TV launcher update on the intended source branch, with a visible version name and an integer version code greater than every previous release (above 2). It builds and lints the release, verifies its signature, and publishes home.apk and update.json. Publishing is not triggered by ordinary debug builds or merges. No release or signing secrets were created by this implementation.

The HTML preview hides the button normally. Append `?updateDemo=1` to its URL to see the illustrative update-available state. It does not perform a live release check or install anything.

Offline compilation cannot establish successful installation, unknown-source settings availability, or update behavior on the Tanix box. Those require device testing.
