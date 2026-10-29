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
established native adapters, ResolveURL and existing HTTP behavior remain unchanged for existing callers. A new optional session argument is used
only by CineStream adapters to retain cookies during a lookup; all old calls keep the same fetch arguments. The relay additionally scopes the new
Vidfast X-CSRF-Token header to its original media authority, like Cookie/Authorization.
Existing implementations are assumed working as instructed; this work does not audit or
replace their parsers. Root and backend comparison is separate from name matching: ProjectFreeTV's
new `.sx` `/data/browse`/`data/watch` protocol differs from existing `.cyou` HTML `/vembed`.
Levidia's `www.levidia.ch` is the existing `levidia.ch` website, and 4KHDHub's root is already
present. Neither gets a duplicate adapter. A different backend label is retained even when
it returns the same CDN URL, using existing source/header identity rules.

## Additional protocols (21 enabled adapters, 3 pending)

Actual Android audits on the new VaPlayer/Hexa HLS routes aborted inside LibVLC:
`FakeESOut.cpp:535: applyTimestampContinuity: assertion ts >= VLC_TICK_INVALID failed`.
The [redacted audit](https://github.com/billyke2000s/moviehub/actions/runs/40822400650)
records the native crash. These runs used incomplete translations and do not establish that the upstream sites are broken.
VaPlayer, Hexa and Vidcore were missing M3u8Helper variant expansion. Their adapters now expand
playable quality links before retaining the original master. External audio groups stay attached
to the master. HLS bytes and timestamps are not rewritten to hide decoder errors. The later per-backend validation is recorded below; the old crash evidence is historical,
not a verdict on every corrected adapter.
Normal discovery now enables 21 new adapters, bringing it to 59 websites / 62 adapters.
VaPlayer remains pending after repeat native timestamp assertions; PrimeSrc and Asiaflix remain
pending because their upstream cfGet browser-challenge helper has no equivalent here. HTTP
refusals do not establish that a whole site is broken. Hexa exposes only Alpha in normal discovery:
Alpha passed movie and episode decode/audio/runtime/seek checks; Delta did not, and Echo crashed.
Other Hexa backends remain auditable with `cinestreamAudit=true` but are not exposed normally.
All 24 protocols remain available through `search_providers(include_retired=True)` for explicit
audits. Enabling a translation is not a claim of live availability for every title. The existing
player and established source implementations are preserved.


| Website / adapter | Retained playback backends / capabilities |
| --- | --- |
| VaPlayer | Every returned `stream_urls` entry, original nextgencloudfabric Referer; movies/episodes |
| PrimeSrc | Every advertised server key/name; existing ResolveURL handles supported terminal hosts |
| Fshare | `sources` and flattened `alternatives`; movies only |
| Videasy / Vidy | Vegas, Reno, Oakland, Seattle, Phoenix, Atlanta, Portland, Austin, Dallas, Tampa, Orlando, Munich, Berlin, Paris, Delhi, Cancun; Boise/Miami already belong to existing Movy |
| Meowtv | dcloud, doppler, acme (advertised languages), tik, ipcloud, hindi, english, movie-only turkce; single-use guest tickets |
| Vidup | Every advertised server; enc-vidup/dec-vidup data protocol |
| Vidcore | Every advertised server; enc-vidcore/dec-vidcore data protocol |
| VidFastPro | Every advertised server; enc-vidfast/dec-vidfast data protocol |
| Cinejoy | Advertised server list; HLS playlists and each explicit file quality |
| Hexa | Alpha enabled; Delta/Echo/Foxtrot retained for explicit audits; public token/data protocol |
| OneTouchTV | Exact title/year/season search and requested episode, each returned source |
| Vidrock | Every advertised server, locally authenticated AES-GCM data decoding |
| Peachify | multi, hr, holly, air, moviebox; original per-source headers and proxy URL data |
| Vidzee | Servers 0, 1, 2, 4, 5, 6, 7; local CBC data decoding and advertised headers |
| MovieBox | Anonymous issued token; download, play, DASH and language identities remain separate; VIP locks respected |
| AllMovieLand | Advertised player root, exact season/episode folders, every language/server |
| ProjectFreeTV `.sx` | Exact identity match and requested episode; supported hosted terminals |
| Akwam | IMDb-verified films; series deferred because upstream does not establish the season |
| Asiaflix | Exact title and explicit episode from `ng-state`; season 1 only |
| Multimovies | Current search catalogue and watchConfig server names; exact episode checked; legacy Doo fallback |
| Toonstream | Exact movie/episode slug, both iframe levels; supported hosted terminals |
| AnimeSalt | Current .cx episode frames and explicit multilingual player data; legacy CDN fallback |
| Rtally | Explicit direct resources and published lulustream/strmup/filemoon/vidhide/streamwish IDs; movies only |
| KissKH | Exact title/season match, exact numbered episode, Video and ThirdParty separately |

Inventory: 59 enabled website groups / 62 enabled adapters; 3 pending candidates and 96 bundled constructors including
excluded original websites. No previously excluded site is re-enabled. New factory names only
apply to new modules; existing modules still instantiate their original `source` class.
New website groups follow the established order; pending groups are explicit-audit only.
The three pending groups do not enter normal discovery or take its worker slots. The existing overall search deadline
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

Current local validation: **107 Python tests**, covering all translated adapter classes, and
**34 core suites / 1,167 checks**. Android/live evidence is recorded per commit below.

### Initial implementation evidence (historical)

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
fatal timestamp assertion above; at that checkpoint no new candidate was playback-verified. Hexa also returned
an adapter TypeError in one episode lookup. The later corrections and selective enablement
are described below; this older run does not establish their result. Exact-head fixture CI and the baseline Rive live-audit comparison
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
Local checks: 107 Python tests; 34 core suites / 1,167 checks. Final Android and live results must
be read from this correction's CI, not inherited from the earlier green build.

Correction run [40826640669](https://github.com/billyke2000s/moviehub/actions/runs/40826640669)
at `c16889e` verified one Hexa alpha Breaking Bad S1E1 link: 3479 seconds, 1280×720,
109 decoded video frames, 225 decoded audio frames, 112 played audio buffers and midpoint decode.
Hexa delta decoded but reported 5690 seconds against the expected 58 minutes. Later Hexa and both
VaPlayer audits still hit the native timestamp assertion. Hexa movie lookups produced timeout/
TypeError responses; a green completed report with no links is not playback success.
Further diagnostics record the starting backend before playback and trace all candidates'
protocol failures/manifest structure without publishing URLs, headers, keys or response bodies.

The follow-up per-backend audit [40827310088](https://github.com/billyke2000s/moviehub/actions/runs/40827310088)
at `f7a1e5d` also verified Hexa Alpha for Inception (8888 seconds, decoded video/audio, midpoint
seek) and repeated the episode success. Starting records identify Echo as the native crash in
both Hexa audits. VaPlayer still crashes on its first offered rendition. These results support
the selective backend gates, not a universal claim about website health.

All new adapters now use the upstream default browser profile even on implicit-header calls,
retain one isolated HTTP cookie session per lookup, and pass only domain/path-matching media
cookies into playback. Cookies do not persist across providers/lookups. The established client
uses its unchanged default session handling; its optional new session argument is unused by old
providers. New fixtures cover every translated adapter class, cookies, media cookie scoping and
normal/audit backend separation. Local Python validation: 107 tests passed.

TMDB/title-only routes no longer require an IMDb ID they never use. VaPlayer, PrimeSrc, Fshare,
AllMovieLand and IMDb-verified Akwam still require it. Missing catalogue identity is rejected;
provider-supplied match/episode evidence and existing validation remain in place.

## Final application revision a9974b6

Local validation: 107 Python tests (33 CineStream regression methods) and 34 Java core suites /
1,167 checks passed. The original source implementations and ResolveURL have no diff. The
legacy client's only change is an optional session argument with identical default fetch calls.

[CineStream run 40856772425](https://github.com/billyke2000s/moviehub/actions/runs/40856772425)
verified both normal Hexa Alpha cases through managed LibVLC playback:

| Title | Duration | Decoded video | Decoded audio | Played audio buffers | Midpoint |
| --- | --- | --- | --- | --- | --- |
| Inception | 8888 seconds | 1920×800, 115 frames | 209 | 104 | decoded |
| Breaking Bad S1E1 | 3479 seconds | 1280×720, 121 frames | 602 | 120 | decoded |

Both explicit pending VaPlayer diagnostics still failed in LibVLC timestamp continuity. The
protocol trace visited 24 movie and 21 series adapters; the sampled titles yielded media only
from VaPlayer/Hexa across the runs. Other results include HTTP refusals, unavailable API routes,
decoder-service 400s, connection/DNS timeouts and no matches. These are recorded lookup results,
not successful playback and not proof of permanent website failure.

The existing-source audit at this head passed Movy episodes and 7/8 movie links. The Boise 4K
movie lacked confirmed audio in that sample; Rive returned no selected Vanguard links. Neither
provider implementation was modified. A separate comparison uses unchanged main with the same
managed audit harness. Its result and the final Android suite outcome are recorded on the PR.

All six Android CI jobs passed for application revision `a9974b6` in
[40856772393](https://github.com/billyke2000s/moviehub/actions/runs/40856772393). Inspected
reports: 208 ordinary cases passed, plus one unchanged opt-in audit assumption (rendered as a
failure by the HTML report, accepted by Gradle); 27 unique experimental cases and ten focused
VOD cases passed without skips. The real native track-format regression passed in 2.185 seconds.
Build/lint, signing continuity, dual-ARM APK inspection, Python and provider checks passed.

The unchanged-main comparison [40857723719](https://github.com/billyke2000s/moviehub/actions/runs/40857723719)
used the same managed audit harness on `fab1f64` production code and verified all eight Movy
movie links, including Boise 4K audio. Thus the one feature-run audio-unverified sample remains
an unresolved individual audit result; neither it nor the baseline alone proves a production
regression. Existing source/player logic is preserved. No other failures were hidden or gates
weakened. This final documentation records already-tested application code; no APK is published.


## Direct website and current-client investigation — follow-up, 29 October

The earlier all-adapter film/episode scrape did **not** establish website health. This follow-up
requested the actual roots, inspected browser pages where available, read their current public
frontend assets without executing downloaded scripts, and compared the maintained protocol samples
at [EncDecEndpoints f260cc8](https://github.com/smy778/EncDecEndpoints/tree/f260cc8cfa968a113e53010e7f4c5c3f14c9b592/samples).
An HTTP 200 alone is not working playback; an API root returning 404 is not a closed website.
The original 24 roots produced 17 HTTP 200 responses, two API-root 404s, one 403, three 5xx
responses and one timeout. The 200s include an empty API root and an old Videasy placeholder.

| Candidate | Direct observations | Method finding / remaining limit |
| --- | --- | --- |
| VaPlayer | API root 200, empty body | Earlier native timestamp crash remains unresolved; pending |
| PrimeSrc | Homepage/embed 200; list_servers 200 with real file/language metadata | list_servers has no playable keys; embed still uses /api/v1/s. Optional server token was missing from link request; now retained. Browser-protected extraction remains pending |
| FshareTV | Homepage and Forrest Gump detail 200; watch route gives explicit Cloudflare challenge | Root is up; watch access is blocked here. No challenge bypass attempted |
| Videasy / Vidy | Old sources-with-title 404; current vidy.st frontend and wecollege API respond | Updated to /sources and existing local Movy decoder. Sixteen additional backends; Boise/Miami excluded as duplicates. Inception Vegas decoded locally to a source and its HLS playlist returned 200; Reno source media returned 500. These are not native playback claims |
| MeowTV | Homepage/browser film detail 200; guest ticket endpoint 200 | Frontend requires a fresh single-use x-stream-ticket per request, with one ticket-error refresh. Old adapter had none. Direct ipcloud/acme samples with fresh tickets returned 502; downstream decoder validation remains blocked |
| Vidup | Homepage and current Game of Thrones S1E1 embed 200, bootstrap field present | Old adapter omitted stage=1 bootstrap POST and stage=2 rotated-token exchange; corrected against current published sample, fixture validated only |
| Vidcore | Homepage and current TV embed 200, bootstrap field present | Same missing bootstrap exchange; corrected and no site CSRF forwarded in decoder headers. Live decoder validation blocked |
| Vidfast | Homepage/browser docs and current TV embed 200, bootstrap field present | Same missing bootstrap/rotated-token exchange; fixture validated. A different movie embed had returned 403; that does not establish whole-site failure |
| OneTouchTV | API root 404; public onetouchtv.xyz homepage 200 | API-root status was not evidence of closure; encrypted catalogue validation blocked |
| Cinejoy | .to redirects to .pk; current API /servers 200 | Replaced stale api.shegu.st with current api.wing.st. Advertised servers: Nebula, secret, Lisbon, Solara, Athens. Encrypted downstream validation blocked |
| Hexa | Homepage 200 | Previous Alpha movie/episode native playback evidence remains valid; no new external-decoder probe run |
| ProjectFreeTV .sx | Direct timeout; browser returned 522 | Access/origin availability unresolved; no claim of permanent closure |
| Akwam | Root 200 with domain-directory links | Root response does not prove current /search or film loading. Current catalogue chain remains unverified |
| Asiaflix | Homepage 200 with actual drama card | Not a dead website; current API/challenge integration remains pending |
| Vidrock | Root 503 | Unavailable from this environment; no evidence of permanent closure |
| Peachify | Root 523, origin unreachable | Origin unavailable in this sample; no parser diagnosis possible |
| Vidzee | Homepage 200; current embed frontend present, old /api/server 404 | Current client imports a WASM decoder; old CBC endpoint translation is stale. Replacement protocol remains unresolved; no remote script execution or fingerprint spoofing attempted |
| MovieBox | API root 404; moviebox.com responds 200 with a Site Unavailable page | API root is not the viewer route; previous search success and media refusal do not prove closure. Link delivery remains unverified |
| AllMovieLand | .one redirects to .art, 403 | Access limited; catalogue/stream methods remain unverified |
| Multimovies | Homepage and Attack on Titan detail/search 200 | Site replaced WordPress with /api/search, explicit season/episode paths and literal watchConfig. Added current parsing with episode equality checks. Advertised iframe hosts still require supported terminal resolution |
| Toonstream | Homepage 200 | Catalogue exists; two-level terminal resolution remains unverified |
| AnimeSalt | .ac redirects .cx; Naruto Shippuden detail/S1E1 200 | Updated root and parsed actual video frames / multilingual player JSON; current hosts differ from old as-cdn*.top. Hosted terminal playback remains unverified |
| Rtally | Homepage 200 | Current detail/host decoding still unverified; root success is not playback |
| KissKH | Old .nl 503; maintained .do homepage, Lovely Runner search and episode details 200 | Updated root using maintained sample and live API evidence; encrypted stream-key validation blocked |

No established site implementation, player loading/parsing/resolution, menu or selection behavior
was changed by this follow-up. Existing Movy code is reused without edits. The native decoder audit
now selects Vidy Vegas/Phoenix explicitly, keeping the normal production backend set intact.

Unattended Python audits now reject enc-dec.app requests **before** network transmission. Automatic
approval review rejected a live probe because it would send provider-issued tokens and encrypted
responses to that external service. This restriction also applies to CI: the new playback matrix
uses locally decoded Vidy only. Fixture tests of the handshake are not live decoder validation.
Explicit user approval is required before this blocked third-party validation can be attempted.
The application's existing external-decoder dependency is disclosed in SECURITY.md; no production
site or feature is silently removed by this diagnostic restriction.

Follow-up local validation: 115 Python tests passed, including single-use guest tickets, one retry,
Acme language variants, optional PrimeSrc token, exact current Multimovies episodes, current AnimeSalt
hosts, backend deduplication, token-origin rejection and the unattended audit guard. The 34 Java core
suites / 1,167 checks passed. Android CI and the new Vidy native playback audit are pending for the
follow-up commit; historical CI results above apply to the earlier code revision only.
