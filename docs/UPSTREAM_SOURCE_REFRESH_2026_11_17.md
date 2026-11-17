# Complete-inventory follow-up — 17 November 2026

The earlier address-only candidate did not fulfil the request. This follow-up covers the
entire existing inventory in the comparison report and adds independent movie/series probes
for all **111 adapters**, including pending, excluded, local-library and API-key entries.
A missing capability is recorded explicitly rather than silently skipping an adapter.

Implemented resolver updates:

- MovieBox: Phisher 711's anonymous mobile request signing, visitor-token bootstrap,
  subject search, play-info/v2 and play-info fallback. Tokens remain within one lookup and
  API origin; media URLs never receive the visitor token. Existing web flow remains a fallback.
- Vidzee: current named servers and exact movie/episode paths on core.vidzee.wtf, dynamic
  Acme languages and the API's supported plain-JSON representation. Server failures are
  isolated; explicit playback headers and the older CBC-compatible fallback are retained.
- Videasy: Phisher 711's separate `api.speedracelight.com` service, current named servers,
  seed, exact-episode `sources-with-title` requests and existing decoder API; retain the Vidy
  path as a fallback, rather than presenting the older Vidy service as current Videasy.
- Rive: fetch the currently advertised provider directory, instead of assuming the retired
  Vanguard name still works. Directory names are validated, duplicate backends are removed,
  and clear file/DASH formats use the existing transport and media checks. Extraction reserves
  time for validation so a late slow backend cannot discard earlier collected links. New
  directory names remain audit-only until packaged playback passes; normal discovery retains
  the previously tested Primevids/Citadel routes when still advertised.
- Fshare: Phisher's IMDb catalogue lookup and advertised watch-page links, current .co origin,
  broader player-token syntax and the current default trailer identifier.
- VaPlayer: send TMDB IDs to the current API, retain exact season/episode numbers and
  stop requiring an IMDb identifier for a TMDB-based lookup. Its pending playback flag remains.
- Peachify: current .pro/none.eat-peach hosts, Firefox request profile and current AES-GCM
  format key. An independent Node crypto vector catches the old one-byte key mismatch.
- Multimovies: current `/search?q=` poster cards and `server-chip` buttons, with title/year,
  same-origin and exact-episode guards; recognize explicit translated-edition title suffixes
  and follow literal advertised Modiplay server links; retain API/watchConfig/Doo fallbacks.
- Cinejoy and AllMovieLand: current upstream origins, keeping existing protocol/transport guards.
- ResolveURL: eleven current free-host modules (ten replacements plus PeeStream), including
  Byse, Vidzy, StreamWish, Streamix, Mixdrop, FireStream, VOE and Abyss. The account-based
  Torbox resolver and existing runtime hardening are retained. This is a selective refresh,
  not a claim that every local ResolveURL file equals a complete pristine upstream release.
- Catalogue origins: rerun the per-entry semantic-history comparison against both current
  upstream snapshots, including today's SkyMovies, UHDMovies and ZinkMovies changes.

The machine-readable [whole-inventory comparison](UPSTREAM_RESOLVER_COMPARISON_2026_11_17.json)
records every adapter and all 237 bundled host modules, candidate implementations, hashes,
selection/retention reasons and unresolved cases. All 59 Scrubs originals match the currently
published official 5.1.42 package. The official Free99 repository no longer publishes its add-on;
its nine last-known modules remain recorded instead of being claimed current or working.

The independent audit uses `moviehub_vod.query` for each adapter directly. It does not use
website grouping or another adapter's successful fallback as proof. All 111 entries are
partitioned exactly once across eight shards, for both movie and exact-episode queries.
Each adapter has its own timeout starting when it runs. An audit job's success proves report
coverage, not that its remote providers produced working streams. The current workspace cannot
resolve provider DNS, so live results must come from the GitHub runners; local DNS failures are
not evidence that these providers are closed.

Local checks: **169 Python tests** and all **34 Java core suites** pass. The earlier Android
build/lint job stopped on pre-existing Java formatting differences; the two files are formatted
in a separate formatting-only commit. Prior device and signing checks passed, but this follow-up
requires new CI results. No merge or APK publication is performed.

## First full live audit

[Run 44103491723](https://github.com/billyke2000s/moviehub/actions/runs/44103491723),
application commit `e774f3c`, produced exactly 111 unique adapter records for films and
111 for series. Fifteen adapters returned media-validated film sources; seven returned
series sources. Vidzee returned two film and four episode links through its new protocol.
These are extraction/media-response observations, not decoded audio/video proof. The
[complete redacted results](ALL_ADAPTER_AUDIT_2026_11_17.json) retain every failure and
unsupported capability, including pending and excluded adapters.

This run exposed MovieBox's nested mobile search response (`data.results[].subjects`),
which was corrected after the snapshot, together with a bounded visitor-token refresh.
The corrected revision and the newly added packaged playback checks need their own results.

## Follow-up runner results

[Run 44108734444](https://github.com/billyke2000s/moviehub/actions/runs/44108734444),
commit `d2f39d7`, again emitted all 111 film and 111 series records. Fourteen film adapters
and five series adapters returned media-validated links for the sampled titles. Multimovies
now extracts six film players; those hosts remained unsupported in that snapshot. Its
translated series title handling and Modiplay wrapper were updated afterwards. Rive hit
the extraction deadline; the validation-time reservation was added afterwards.

Direct requests with the current mobile signing format to all four MovieBox token-bootstrap
hosts returned HTTP 403 with the explicit message `Service not available in current region`.
This is recorded as an availability failure, not repaired by the protocol port. Peachify also
returned HTTP 403 in runner tests. No region bypass or weakened media checks are introduced.

## Packaged playback failure and release gate

[Series job 134128169324](https://github.com/billyke2000s/moviehub/actions/runs/44108734340/job/134128169324)
verified seven sampled links: Vidzee Dcloud/Hindi, Cinejoy Nebula/Warsaw and Rive
Primevids/Citadel variants. Four other links failed to decode. Starting Rive Pulse then
crashed LibVLC in `FakeESOut::applyTimestampContinuity`, so the run did not finish. The
[redacted records](PACKAGED_RESOLVER_AUDIT_2026_11_17.json) preserve the exact outcomes.
New Rive backends are therefore available to explicit audits only; they are not newly
enabled for ordinary playback. This gate does not relabel their failures as successes.

## Limits that remain

This still must not be described as a successful replacement of every newest resolver or a
fix for every remote service. Several current Phisher paths depend on its Android browser/WASM
helpers; function dates cannot be inferred from a compiled package timestamp. Movy, Hexa and other route failures are not relabelled as repaired; Rive's newly discovered directory needs fresh packaged playback evidence. The report explicitly marks
retained or unresolved implementations, and live audits report zero-source/failing entries.

The changes above are implemented and reviewable; the owner's full "every source works" goal
is not established. The prior address-only scope and reasoning are preserved below as history.

---

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

## Earlier resolver-code observations (before this follow-up)

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
