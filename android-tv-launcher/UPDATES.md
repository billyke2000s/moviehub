# App updates

## Published updates

**MovieHub 1.0.25 (code 126)** was published on 22 November 2026 through the existing signed process. The [numbered release](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.25) and [in-app update feed](https://github.com/billyke2000s/moviehub/releases/tag/android-tv-latest) carry the APK (133,318,200 bytes in the numbered release); the feed's `update.json` lists SHA-256 `1a7223fda153b721cf4531a2b32d4ab708f7479fffbabb36a5f133430c090238`. Older published versions are listed on the [releases page](https://github.com/billyke2000s/moviehub/releases).

For newer versions, use the [latest release](https://github.com/billyke2000s/moviehub/releases/latest) and update feed rather than relying on a historical paragraph. The standard signed APK includes both `armeabi-v7a` and `arm64-v8a`, using the established signing identity and `local.tv.home` package.

The home screen checks the public `android-tv-latest` release in billyke2000s/moviehub when opened, at most once per six hours during an app process. The Update button beside Settings stays hidden if the release is missing, unreachable, invalid or not newer than the installed version. No account token is needed for this public feed.

Selecting Update asks before downloading, verifies the SHA-256 checksum, package name, increasing version code and matching signing certificate, then opens Android's installer. Android may first require permission to install updates from Home. No root or silent installation is used. A failed check is silent; a failed download/verification produces a retry message. APK downloads are limited to 300 MiB and use HTTPS.

The signed APK and update manifest are published in the `android-tv-latest` release. `.github/workflows/publish-android-launcher.yml` uses the following configured GitHub Actions secrets:

- APK_KEYSTORE_BASE64: base64-encoded permanent private signing keystore
- APK_STORE_PASSWORD
- APK_KEY_ALIAS
- APK_KEY_PASSWORD

Keep and back up that signing key; use it for the first release installation and all future releases. Existing debug APKs are not a verified upgrade path to this release key. The updater refuses APKs signed with a different certificate; an initial move from a debug build may require a separate installation and data migration plan.

Run Publish Android TV launcher update on the intended source branch, with a visible version name and an integer version code greater than every previous release. It builds and lints the release, verifies its signature, and publishes home.apk and update.json. Publishing is not triggered by ordinary debug builds or merges. The permanent signing secrets are configured, and the initial signed release has been published.

The release workflow runs core checks and Android database/player tests before signing. It
also requires both ARM ABIs, matching native-library coverage and stripped debug sections in
the signed APK before upload. It downloads the current updater baseline, verifies its checksum and refuses publication if the new certificate differs. Same-repository PRs also build a signed candidate and check package, increasing version code and signing continuity without publishing it. The package remains `local.tv.home`, and the existing `android-tv-latest/home.apk` and `update.json` paths and manifest fields are unchanged. Since 0.15, `update.json` may also carry an optional `notes` field: the CHANGELOG section headed with the published version name (for example `## 0.15 (date)`), shown as "What's new" in the update prompt. Older app versions ignore it.

## Publishing by changing a file

Some AI tools cannot press **Run workflow**. Instead of writing a new one-off workflow for each
version (the old `release-moviehub-<version>.yml` files, now removed), they change
`.github/release-request.json` through a PR, only when the owner has authorised publishing:

```json
{
    "version_code": 125,
    "version_name": "1.0.24",
    "requires_merged_prs": [229]
}
```

When that file changes on `main`, **Publish from release request** (`release-request.yml`)
compares it with the published `android-tv-latest/update.json`:

- Same version as the feed: does nothing, so merging an unchanged request is safe.
- Lower version code, or a reused code with a different name: fails without publishing.
- Higher version code: checks `main` has not moved and every listed PR is merged, runs the normal
  signed publisher above with that version, waits for it to pass, checks the feed now shows that
  version and checksum, then runs the numbered-release copier below.

It builds and signs nothing itself; every check of the normal publisher still applies.

## Numbered releases on the repository page

Publishing updates the `android-tv-latest` feed that TVs use; it does not change the "Latest"
release shown on the repository page. To show the current version there, run the
**Make a GitHub release of the published update** workflow (`release-android-launcher.yml`). It
copies the signed `home.apk` and `update.json` from the feed (checking the APK's SHA-256 against
the feed) into a release named `MovieHub <version>` (tag `moviehub-v<version>`), marks it as the
latest release, and renames the feed release "MovieHub <version> — updater feed". It builds and
signs nothing and does not change the feed paths.

## Experimental app

`gradle -Pexperimental …` builds **MovieHub Experimental**: package `local.tv.home.experimental`,
label "MovieHub Experimental", no home-screen role, and its own update feed
(`android-tv-experimental` pre-release). It installs next to MovieHub and keeps its own data
(channels and accounts are set up again inside it). Since 1.0 it has the same features as MovieHub;
only its identity differs, so it is for trying a risky change on the TV before it reaches MovieHub.

Run **Publish MovieHub Experimental** (`publish-experimental.yml`) on the branch to test, with an
increasing version code. It uploads `moviehub-experimental.apk` (to install by hand) and
`home.apk`/`update.json` (for the experimental app's own Update button). It never touches
`android-tv-latest`.
