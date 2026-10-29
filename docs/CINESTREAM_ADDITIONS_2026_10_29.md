# CineStream sources and format display — 29 October 2026

## Reference and preservation boundary

Inspected **CineStream 487**, `SaurabhKaperwan/CSX` commit
[`13903aa`](https://github.com/SaurabhKaperwan/CSX/tree/13903aa/CineStream), the Megix/CSX
extension distributed through Mega's repository discovery. This is not the unrelated Stremio
project `dexter21767/cinestream`. Upstream's README marks maintenance as paused.
The current URL-map snapshot came from
[`SaurabhKaperwan/Utils/urls.json`](https://github.com/SaurabhKaperwan/Utils/blob/main/urls.json).
Only public, non-account roots were retained. No remotely downloaded code or dynamic config
executes in MovieHub. These translations carry upstream's GPLv3-or-later terms.

MovieHub baseline: `fab1f64`. Existing Scrubs/Free99 files, original inventory, the four
established native adapters, ResolveURL and existing HTTP behavior remain unchanged. The relay additionally scopes the new
Vidfast X-CSRF-Token header to its original media authority, like Cookie/Authorization.
Existing implementations are assumed working as instructed; this work does not audit or
replace their parsers. Root and backend comparison is separate from name matching: ProjectFreeTV's
new `.sx` `/data/browse`/`data/watch` protocol differs from existing `.cyou` HTML `/vembed`.
Levidia's `www.levidia.ch` is the existing `levidia.ch` website, and 4KHDHub's root is already
present. Neither gets a duplicate adapter. A different backend label is retained even when
it returns the same CDN URL, using existing source/header identity rules.

## Candidate protocols (24 adapters; disabled in normal discovery)

Actual Android audits on the new VaPlayer/Hexa HLS routes aborted inside LibVLC:
`FakeESOut.cpp:535: applyTimestampContinuity: assertion ts >= VLC_TICK_INVALID failed`.
The [redacted audit](https://github.com/billyke2000s/moviehub/actions/runs/40822400650)
records the native crash. These runs used incomplete translations and do not establish that the upstream sites are broken.
VaPlayer, Hexa and Vidcore were missing M3u8Helper variant expansion. Their adapters now expand
playable quality links before retaining the original master. External audio groups stay attached
to the master. HLS bytes and timestamps are not rewritten to hide decoder errors. New validation
is pending; the old crash evidence is historical, not a verdict on these corrected adapters.
All 24 unverified candidates have `pending=True`; default discovery stays on the original
38 websites / 41 adapters. `search_providers(include_retired=True)` retains candidates for
explicit audits without exposing them in the owner's source picker. The existing player and established source implementations are preserved.


| Website / adapter | Retained playback backends / capabilities |
| --- | --- |
| VaPlayer | Every returned `stream_urls` entry, original nextgencloudfabric Referer; movies/episodes |
| PrimeSrc | Every advertised server key/name; existing ResolveURL handles supported terminal hosts |
| Fshare | `sources` and flattened `alternatives`; movies only |
| Videasy | myflixerzupcloud, downloader2, m4uhd, hdmovie, cdn, superflix, lamovie, jett, tejo, neon2, ym |
| Meowtv | ipcloud, dcloud, tik, turkce, hindiv3 |
| Vidup | Every advertised server; enc-vidup/dec-vidup data protocol |
| Vidcore | Every advertised server; enc-vidcore/dec-vidcore data protocol |
| VidFastPro | Every advertised server; enc-vidfast/dec-vidfast data protocol |
| Cinejoy | Advertised server list; HLS playlists and each explicit file quality |
| Hexa | Every advertised server; public token/data protocol |
| OneTouchTV | Exact title/year/season search and requested episode, each returned source |
| Vidrock | Every advertised server, locally authenticated AES-GCM data decoding |
| Peachify | multi, hr, holly, air, moviebox; original per-source headers and proxy URL data |
| Vidzee | Servers 0, 1, 2, 4, 5, 6, 7; local CBC data decoding and advertised headers |
| MovieBox | Anonymous issued token; download, play, DASH and language identities remain separate; VIP locks respected |
| AllMovieLand | Advertised player root, exact season/episode folders, every language/server |
| ProjectFreeTV `.sx` | Exact identity match and requested episode; supported hosted terminals |
| Akwam | IMDb-verified films; series deferred because upstream does not establish the season |
| Asiaflix | Exact title and explicit episode from `ng-state`; season 1 only |
| Multimovies | Exact movie/episode slug, Doo AJAX player options excluding trailers |
| Toonstream | Exact movie/episode slug, both iframe levels; supported hosted terminals |
| AnimeSalt | Movie/episode slug and explicit CDN player protocol |
| Rtally | Explicit direct resources and published lulustream/strmup/filemoon/vidhide/streamwish IDs; movies only |
| KissKH | Exact title/season match, exact numbered episode, Video and ThirdParty separately |

Inventory: 38 enabled website groups / 41 enabled adapters; 24 pending candidates and 96 bundled constructors including
excluded original websites. No previously excluded site is re-enabled. New factory names only
apply to new modules; existing modules still instantiate their original `source` class.
Pending website groups are available after the unchanged established order for explicit audits.
They do not enter normal discovery or take its worker slots. The existing overall search deadline
and worker count remain unchanged; queued sites can still be cancelled at that deadline.
New routes use current bounded transport, media checks, cancellation, refresh and identity gates.
Upstream's hard-coded 720p/1080p/4K defaults are deliberately not copied. A missing quality is Auto.
Exact-source refresh and provider-report status remain available through existing mechanisms.

## Remaining blockers and unimplemented routes

This is a partial source expansion, not a claim that all CineStream websites were imported.

| Candidates | Blocker / remaining work |
| --- | --- |
| ShowBox/FebBox, GramCinema | Require a user-supplied token in CineStream settings. No account flow or token is copied. |
| Castle, MovieBlast | BuildConfig keys/token/API values are absent from the public source; no credentials inferred. |
| Movies4u | Upstream URL map currently points to `https://www.google.com`; no usable catalogue root supplied. |
| Bollyflix, TopMovies, Moviesmod, MoviesDrive, UHDMovies, Dudefilms, VegaMovies, RogMovies | Need independently implemented/validated fastdlserver, linksmod, Driveleech/Driveseed, VCloud, GDFlix or broader HubCloud terminal chains. MovieHub's established 4KHDHub-local resolver is left untouched. These new website adapters are not implemented. |
| HindMoviez | HShare signing/redirect and custom link-terminal chain are not implemented. |
| HDHub4u, Zinkmovies | Custom redirect/archive/player chains in CineStream's helper/extractor files are not implemented. |
| Fibwatch | Custom packed stream resolver; upstream can fall back to the first episode and labels combined content. Exact episode/duration evidence and a validated terminal are still needed. |
| Mlsbd | Series path has no episode argument and labels combined content; film host chain remains unsupported. |
| SkyMovies | Custom downstream download hosts; upstream combined-episode fallback cannot satisfy exact-episode selection. |
| M4ufree `.lat` | M4u CSRF/AJAX family overlaps existing M4u routes. DNS restrictions prevent establishing whether this is an independent current site/backend or an alias. It is not added, and the existing implementation/exclusions stay intact. |

Anime-only registry entries, subtitle services and torrent providers are not new movie/TV
website adapters. Existing Torrentio stays unchanged. Akwam series, Asiaflix seasons beyond 1
and the established 4KHDHub's additional TV/download routes are not silently enabled.
Terminal support is an actual implementation gap; neither a new display label nor an iframe
is evidence that a stream works. No inaccessible candidate is described as permanently dead.

## Two metadata paths

CineStream's `getSimplifiedTitle` matches explicit release-name tokens for codecs, audio,
bit depth, HDR, language and size. Its adapters also use provider fields. These are reports,
not a universal file-header/decoder probe; some adapters hard-code quality. MovieHub now:

- Parses explicit audio/video/bit-depth/HDR tokens for display and prefixes them **Reported**.
  It does not copy emoji labels, infer audio from quality or classify HDRip as HDR.
- Reads HLS `CODECS` from the bytes already fetched by the existing bounded probe, labelled
  **Manifest codecs**. Multiple advertised codec families do not identify the selected tracks.
- Reads a complete-file size only from a valid Content-Range total for a non-playlist response.
  A 64 KiB sample's Content-Length and manifest size are not movie sizes. No additional requests
  or changes to source ranking, quality verification, headers or request handling are introduced.
- Preserves existing source rows/options and appends metadata in the existing labels. Quality
  correction during active playback retains the format suffix.
- Separately reads LibVLC `IMedia.Track` objects from the **active player's** retained media.
  Matches native type + ID, never menu index or the current track for every option. Appends
  known audio/video codec names to each original TrackDescription name, using the numeric
  FourCC first and native codec descriptions as fallback. Unknown codecs or
  unavailable metadata leave the original name intact. Off, subtitles, headings, item IDs and
  selection callbacks remain unchanged. Retained media is released in `finally`.

No universal codec/audio/bitrate availability is claimed. There is no new metadata network
request, source grouping, decoder menu, player replacement, automatic switch or release change.

## Validation

- Local core runner: **34 suites, 1,167 checks**, including 19 new format-display cases.
  This workspace has a JRE but no javac; Eclipse ECJ 3.42.0 compiled with Java 17 target, then
  the unchanged runner executed each suite on Java 17. Android CI uses the normal JDK compiler.
- Local Python runner: **90 tests passed**, including 16 CineStream protocol/identity/metadata
  tests (subcases additionally cover movie/episode paths and three token-server providers).
  Fixtures block accidental live HTTP. These prove protocol handling, not provider playback.
- Fixture media inspected with ffprobe: existing playback.mp4 is H.264 + AAC. Added Android
  regression reads real LibVLC track labels and verifies suffixes, Off/subtitle preservation,
  and unchanged audio/video selections. Android compilation/lint/device results and the HDR-label correction are tracked on the PR.
- Initial direct movie probes for 14 new adapters all failed local DNS (`gaierror`) before
  usable provider responses; no live playback, duration, midpoint seek or decoded audio was
  established. Remaining new adapters share the same network limitation; no local success claimed.
- Formatting and `git diff --check` run before submission. Existing provider and native-site
  regressions remain part of the Python run. No APK was published.

The desktop runner probe at `7bdca2d` returned seven valid media responses across VaPlayer
and Hexa for the sampled film. Other candidates returned refusals, unavailable endpoints,
timeouts or no match. These were not decoder checks. The subsequent native audit exposed the
fatal timestamp assertion above; no new candidate is marked playback-verified. Hexa also returned
an adapter TypeError in one episode lookup. Enabling candidates or changing the decoder requires
further compatible-source work. Exact-head fixture CI and the baseline Rive live-audit comparison
are recorded on the PR. Nothing was merged or published.

## Loading-path correction after owner review

The first ports omitted shared helpers and overgeneralized the three token-server protocols.
The correction traces CineStream 487 call sites through CloudStream's `M3u8Helper2`
(`recloudstream/cloudstream` commit `5024ace`, `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/M3u8Helper.kt`).
VaPlayer/Hexa/Vidcore now fetch and expand master variants, resolving relative links against the
response URL and reporting only manifest heights. Trick-play entries are not offered as video.
Media playlists retain their original URL. External AUDIO groups keep their master association.

Vidfast uses its explicit Linux Chrome 138 profile, trailing-slash embed route and playback
headers. Vidup preserves status checks and Referer-only playback; Vidcore uses HLS expansion.
Fshare, Peachify and MovieBox retain their distinct upstream browser/request profiles. Vidzee
merges supplied headers case-insensitively. No hard-coded upstream 1080p claim is promoted to
verified metadata. Two explicitly allowed new media headers are X-CSRF-Token and
X-Requested-With; the relay scopes the former to the media authority.

The live audit now opens each returned source through the normal managed VOD path with its real
source entry/headers and expected runtime; it still pins that source to avoid crediting a fallback.
Explicit provider arguments can audit pending candidates without enabling them globally. Empty
instrumentation arguments are omitted rather than shifted by adb. Offline regressions cover HLS
variants, redirects, external audio, error documents, token protocol differences and exact episodes.
Local checks: 94 Python tests; 34 core suites / 1,167 checks. Final Android and live results must
be read from this correction's CI, not inherited from the earlier green build.
