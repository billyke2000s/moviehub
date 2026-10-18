# Changelog

Versions are published to TVs through the in-app updater (`android-tv-latest` release). Each
version below was built from the listed `main` commit. Earlier history is in the git log.

Note: the release shown as 0.10 below was published with version code 11 (name "0.11" in the
update feed), so the next release after it used code 12, named 0.12.

## Unreleased

On `main` but not yet published to TVs:

- Movies and Series open on a new start page in the style of the big streaming apps: a large
  backdrop with the focused title, year, rating and synopsis, above rows of posters by category
  (Trending, Popular, Top rated, release lists and every genre). Posters have no captions, the row
  being browsed always sits fully in view, rows load as you reach them, and your place is kept when
  you open a title and come back. Browse, Filters, Search and Library work as before; a category
  chosen there opens as a full grid, and Back returns to the rows.

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
