# Changelog

Versions are published to TVs through the in-app updater (`android-tv-latest` release). Each
version below was built from the listed `main` commit. Earlier history is in the git log.

Note: the release shown as 0.10 below was published with version code 11 (name "0.11" in the
update feed), so the next release after it used code 12, named 0.12.

## Unreleased

Nothing yet.

## 0.16 (18 Oct 2026)

- Player: the title now sits at the top; at the bottom a full-width slim timeline with the time on
  the right, play controls on the left and Video, Audio, Subtitles and More on the right.
- Built with Android Gradle plugin 9.4.1 and Gradle 9.6.0 (no visible change).

## 0.15 (18 Oct 2026)

- For you on Home blends recommendations from what you played, saved and opened, instead of
  changing with every title you look at.
- New Apps screen: search, Android shortcuts (Wi-Fi, Bluetooth, display, sound, storage, ...),
  recently used apps, TV banners, and hiding apps you never use (hold Select on an app).
- MovieHub has its own icon and banner, and is named "MovieHub" instead of "Home".
- Every pop-up is now a panel on the right in MovieHub's style instead of Android's grey dialogs.
- Redesigned Sources (quality, HDR, codec and size at a glance), Series (season pills with the
  episodes on the same page), Continue watching, Live TV, cloud and the player controls.
- Search: one large box with results appearing as you type, in Movies, Series and People rows.
- Movies/Series: Back jumps to the top bar from anywhere; Back again leaves.
- Clearer messages when something fails to load, with Try again or Open Settings.
- Updates show what's new before installing.
- Faster artwork: images you have seen are kept on the box and load instantly.
- Home: the featured title's button is back to the "View details" style, sized to its label.

## 0.14 (18 Oct 2026)

Published from `main` after PRs #32 and #33:

- Redesigned title details page: full-screen artwork with the type and genres, a large title,
  facts, synopsis (shown once, not twice) and pill buttons on the left, the main action in white;
  then a "More like this" poster row, the collection and the cast as round photos.
- The Movies/Series title area keeps the same height for every title, so the poster rows no
  longer move when a synopsis is shorter or longer; the title sits higher to make room.
- Home: the featured title's action became a slim "More info" pill (changed back in 0.15).

## 0.13 (18 Oct 2026)

- Settings: Appearance, Playback and System now use the same cards as Accounts (title, short
  description, current value on the right, one action button) instead of plain switches and
  full-width buttons. The redundant "Return home" button is gone; Back does the same.
- Movies and Series open on a new start page in the style of the big streaming apps: a large
  backdrop with the focused title, year, rating and synopsis, above rows of posters by category
  (Trending, Popular, Top rated, release lists and every genre). Posters have no captions, the row
  being browsed always sits fully in view, rows load as you reach them, and your place is kept when
  you open a title and come back. Browse, Filters, Search and Library work as before; a category
  chosen there opens as a full grid, and Back returns to the rows.
- Home: the featured title's artwork now fills the whole screen behind the page, pinned top-right
  with soft fades on the left and bottom, instead of sitting in a zoomed-in box. Artwork
  cross-fades as you move between posters, and no placeholder block flashes while it loads.
- Home: a MemoryClean shortcut beside Settings, shown as the app's own icon with no box (only
  when an app named exactly "MemoryClean" is installed). The clock moves left to make room.
- Focused list rows (for example stream sources) no longer grow into the rows around them or past
  the screen edge; small items still grow slightly, and focused items always draw on top.

## 0.12 (18 Oct 2026)

Published from `main` after PRs #25 and #26:

- Full portrait posters and redesigned movie/series browsing with filter overlays (PR #20).
- The catalogue no longer jumps back to the top when the first page arrives; long category lists
  moved into a Browse overlay (PR #21, plus direct follow-up commits on initial poster focus).
- Rearrange the Home hotbar with the remote; app actions and Android system controls in All Apps (PR #23).
- Redesigned streaming menus: catalogue previews, cinematic details, trailers, cast, collections,
  search history, combined filters, library sorting, viewing progress, watched episodes, channel
  groups and logos, source quality filters, cloud breadcrumbs and player options (PR #24).
- Fixed: quick D-pad presses along a poster row or grid could lose the selection.
- Failures the app recovers from are logged to logcat (tag `MovieHub`) without account details.
- Device tests are reliable again (8/8 parallel runs) and call app code directly; CI actions use Node 24.
- Code restructure: Java sources reformatted for readability, `MainActivity` split into feature
  classes, project documentation added, and failed device tests listed as CI annotations. No
  intended behaviour change (PR #25).

## 0.10 (17 Oct 2026)

Built from PR #19: keeps scrolling artwork loaded and simplifies catalogue navigation (grouped
catalogues open on daily trending).

## 0.9 (17 Oct 2026)

Built from PR #18: keeps TV focus stable while movie and series feeds load continuously; end-of-list
message and scrolling captions.

## 0.8 (17 Oct 2026)

Built from PR #17: complete TMDB category browsing in Movies and Series (all movie and TV genres,
trending, popular, top rated and release/airing lists).

## 0.7 (17 Oct 2026)

Built from PR #16: TV home fits the viewport; catalogue and account navigation made visible.

## 0.6 (16 Oct 2026)

Built from PR #15: MovieHub TV browsing, scalable IPTV and fullscreen playback, with signing
continuity checks.
