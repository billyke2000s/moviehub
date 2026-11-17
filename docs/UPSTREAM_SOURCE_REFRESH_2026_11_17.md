# Per-source upstream refresh — 17 November 2026

The requested policy is to choose the newest change to each source independently. A newer
repository commit, release build or settings update does not make all of its resolvers newer.
MovieHub adapts provider code into its existing Python runtime; CloudStream `.cs3` packages
are not interchangeable with those Python adapters.

## Implemented: catalogue addresses

The refresh compares complete histories of `phisher98/TVVVV:domains.json` and
`SaurabhKaperwan/Utils:urls.json`. It dates the latest actual value change of each individual
key, ignoring formatting, key case and unrelated entries. Reverting to a previous value is
counted as a new change. Timestamp comparisons normalize timezones. Conflicting values with
identical change times stop the update for review. No MovieHub edit is used as an upstream date.

Nineteen existing native providers occur in those files. Ten catalogue addresses changed:
Bollyflix, MoviesMod, MoviesDrive, UHDMovies, DudeFilms, VegaMovies, RogMovies, HindMoviez,
HDHub4u and ZinkMovies. The M4ufree registry label is aligned with its existing adapter origin.
Provider IDs and the 74-site / 77-enabled-adapter inventory are retained. Sources outside
these two upstream address files are unchanged, not represented as audited resolver updates.

Selection evidence and both candidates are pinned in
[`upstream_origins.json`](../android-tv-launcher/app/src/main/python/moviehub_native/upstream_origins.json).
The shipped adapters contain fixed reviewed addresses: the app does not fetch or execute
GitHub configuration or code at runtime. The existing origin, header, episode, cancellation,
media and source-identity checks remain in effect.

| Source      | Selected upstream     | Individual address changed | Change commit  |
| ----------- | --------------------- | -------------------------- | -------------- |
| 4khdhub     | SaurabhKaperwan/Utils | 2026-06-06 06:49 UTC       | `d5787fd9d125` |
| bollyflix   | SaurabhKaperwan/Utils | 2026-11-16 04:20 UTC       | `c05cf728af56` |
| dudefilms   | SaurabhKaperwan/Utils | 2026-11-14 04:32 UTC       | `f89871fa2ef9` |
| fibwatch    | SaurabhKaperwan/Utils | 2026-07-20 05:43 UTC       | `7daa73ccdca1` |
| hdhub4u     | SaurabhKaperwan/Utils | 2026-11-14 04:32 UTC       | `f89871fa2ef9` |
| hindmoviez  | phisher98/TVVVV       | 2026-11-14 02:58 UTC       | `5f53180068c9` |
| m4ufree     | SaurabhKaperwan/Utils | 2026-10-15 02:47 UTC       | `ca3196fe2846` |
| mlsbd       | SaurabhKaperwan/Utils | 2026-06-27 15:22 UTC       | `1aa89f1076d8` |
| moviesdrive | SaurabhKaperwan/Utils | 2026-11-12 05:22 UTC       | `330290cf061e` |
| moviesmod   | SaurabhKaperwan/Utils | 2026-10-31 03:41 UTC       | `6a058424bd21` |
| multimovies | SaurabhKaperwan/Utils | 2026-10-16 02:43 UTC       | `2d1fd4a36651` |
| rogmovies   | SaurabhKaperwan/Utils | 2026-11-16 04:20 UTC       | `c05cf728af56` |
| rtally      | SaurabhKaperwan/Utils | 2026-09-24 02:42 UTC       | `fd211994d279` |
| skymovies   | SaurabhKaperwan/Utils | 2026-10-30 03:57 UTC       | `2089c4b7cb1c` |
| toonstream  | phisher98/TVVVV       | 2026-11-07 13:42 UTC       | `5c07d1a15a30` |
| topmovies   | SaurabhKaperwan/Utils | 2026-09-16 02:52 UTC       | `7bf3a1e8559f` |
| uhdmovies   | phisher98/TVVVV       | 2026-11-17 00:07 UTC       | `fe937b03a06c` |
| vegamovies  | phisher98/TVVVV       | 2026-11-02 23:24 UTC       | `99cf4ae6b3e1` |
| zinkmovies  | phisher98/TVVVV       | 2026-11-03 23:06 UTC       | `6f981579ba69` |

## Reproduce the comparison

Use clean, complete clones of both upstream repositories. Shallow histories are rejected
because they cannot date an individual value reliably. Inspect the clones and updated diff
before committing; the maintenance command does not fetch or publish anything.

```sh
python android-tv-launcher/tests/sync-vod-origins.py \
  --phisher /path/to/TVVVV --csx /path/to/Utils
# To apply the comparison, repeat with --write.
```

## Remaining resolver-code work

This is the address refresh portion of the requested source-by-source replacement, not a
completed port of every newest resolver. The original CSX source is available; its latest
CineStream code change is 8 September 2026. Phisher's current master contains documentation,
while the builds branch publishes compiled extensions. A package timestamp cannot establish
when each enclosed resolver function last changed.

Inspection of StreamPlay 693 (28 October) and 711 (16 November) confirms that many resolver
methods are unchanged despite the newer package date. Compiled reference-number changes also
produce binary differences without protocol changes. MovieBox has an additional mobile
play-info route in 711, which needs a complete protocol translation and validation before it
can replace the current adapter. This change does not silently substitute an older parser,
remove existing site-specific repairs, add a partial mobile signing flow, or claim that the
latest package fixes all sources. A complete per-resolver replacement remains outstanding.

## Validation

- 150 Python regression tests passed (144 existing plus six upstream-selection/consistency tests).
- All 34 Java core suites passed through Eclipse JDT targeting Java 17.
- A second comparison against the same pinned clones produces no changed files.
- Android build, lint, emulator and signing checks run on the pull request; their final results
  must be inspected separately.
- No live movie/episode decoding was established by the address comparison or fixtures.
  A current upstream address alone is not proof of provider availability or playback.

No merge or APK publication is part of this change.
