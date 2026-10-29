# CineStream sources and format display — 29 October 2026

## Resumed checkpoint and current corrections

Recovered branch `feat/cinestream-sources-metadata`, PR #195, at `d85b832`.
[Android run 40908951143](https://github.com/billyke2000s/moviehub/actions/runs/40908951143)
passed all six jobs: APK/build/lint/dual-ARM inspection, signed compatibility, normal device tests,
experimental device tests, focused VOD recovery and the public provider probe.

[Native audit 40908951001](https://github.com/billyke2000s/moviehub/actions/runs/40908951001)
verified 21 sources from the fifteen additions: three MoviesDrive Inception, two HDHub4u Inception,
four SkyMovies Inception and twelve HindMoviez Breaking Bad S1E1. Each passed decoded video,
audio, expected runtime and midpoint-seek checks. Five more sources decoded audio/video but had
no expected runtime/midpoint verification (one Bollyflix, four DudeFilms); one MoviesDrive source
failed video decoding. SkyMovies advertised one result as 1080p although the decoded picture was
1280×536. Relay socket/I/O errors remain recorded; these checks do not establish full-title reliability.

The same run's Vidy movie samples returned no decoded video, and the Vidy episode group crashed
in LibVLC's adaptive timestamp-continuity assertion. A green individual audit job can contain
zero sources or unsuccessful playback outcomes; it is not proof of site health. Separate existing
FMHY audits at this head failed route requirements for Rive series and Movy series (two decoded
4K episode sources had no verified audio). Those established providers have not been changed here.

UHDMovies research identified an ordinary 1,425-byte redirect page after its existing forms/cookie
steps. Its `window.location.replace` destination is a JSON string with escaped slashes. Treating
that string literally requested the wrong address and returned 404. The new-only redirect reader
now decodes quoted literals as data. Research then reached the actual DriveSeed file page and its
public Resume Cloud button. Current file/cloud pages advertise same-page POST fields `action`,
`key`, `action_token` and an `x-token` hostname header. Handling is limited to present public
cloud/original/direct buttons and the exact issuing page. Issued values stay out of media headers,
reports and repository files; downloaded scripts are never executed. The sampled 2160p cloud
exchange returned `Unknown error`, so this is not a successful UHDMovies playback claim.

Host-button labels such as Download File / Download FSL Server are copied website instructions,
not a new app download feature. The new source-label path changes that word to Stream while
preserving the server/file-size wording, actual media URL, headers and metadata. The same built-in
player opens the source. Existing provider implementations, UI, source selection and ARM packaging
remain unchanged.

Bollyflix public-page research also exposed LinksMod's Home, signup/login, FAQ and policy links
on the same host as actual wrappers. The generic host matcher followed them and repeatedly spent
its forty-page budget on site navigation. An exact LinksMod-only navigation-label filter now
preserves that budget for advertised media mirrors. Its fixture requires the real wrapper/file path
to succeed without issuing any navigation request. This does not establish that every downstream
host or file is available.

Local checks for these corrections: 144 Python tests; 34 core suites / 1,167 checks; diff checks.
New fixtures cover escaped redirect traversal to a file host, same-page cloud POST/header isolation,
login-only/unrelated-host rejection and Stream labeling with retained file metadata. Direct app
transport in this workspace failed DNS before reaching UHDMovies. The public-page research used
the environment's research transport and does not establish guarded Android extraction or playback.
Final-revision Android and live audit results are pending; earlier evidence below is historical.


## Remaining fifteen chains — implementation follow-up

Current follow-up adds the remaining fifteen catalogue adapters and bounded redirect/file-host
protocols without modifying established sites or replacing the player. All fifteen stay registered;
ordinary redirects are not grounds for removal. Eleven current roots exposed film catalogues;
Vega exposed its gateway, TopMovies returned 503, and Zink/M4U returned browser challenges.
M4U .lat contributes TV only to avoid duplicating existing movie adapters. Normal discovery now
contains 74 websites / 77 adapters (39 added adapter protocols, three separately pending).
Local validation: 141 Python tests and 34 core suites / 1,167 checks pass. At revision 89d2d80,
Inception extraction yielded two HDHub4u and four SkyMovies sources; Breaking Bad S1E1 yielded
twelve HindMoviez sources. The existing native player verified three SkyMovies Inception sources
and all twelve HindMoviez episode sources (video/audio, runtime and midpoint seek), recording
relay socket errors. Other live checks and
validation of subsequent parser corrections remain pending; accessible HTML is not playback.
The preceding revision 7a38417 passed all six Android jobs in run 40869288216; its separate Vidy
series audit failed and is not evidence of success for this follow-up.

| Site | Current public observation | Adapter / ordinary chain support |
| --- | --- | --- |
| Bollyflix | Catalogue and file-link page 200 | Quality sections, LinksMod, GDFlix and existing GoFile resolver |
| TopMovies | Root/search 503 | Catalogue/season selection and shared file-host chains; live parser unconfirmed |
| MoviesMod | Catalogue and download wrapper 200 | Quality sections, current lp-land/lp-s1 POST chain |
| MoviesDrive | Old root redirects to new1.moviesdrive.cyou; catalogue 200 | IMDb JSON search, mdrive, HubCloud |
| UHDMovies | Catalogue and landing form 200 | Title/year lookup, current multi-step same-origin forms |
| DudeFilms | Catalogue and dflinks wrapper 200 | Quality/episode sections, advertised independent mirrors |
| VegaMovies | Gateway 200; advertised catalogue redirects back; host config Cloudflare 403 | Literal advertised gateway followed once; unresolved gateway reported, adapter retained |
| RogMovies | Catalogue and nexdrive 200 | IMDb JSON lookup, FastDL literal reurl, VCloud |
| HindMoviez | Catalogue, wrapper and signing endpoint 200; file page 200 | Current same-origin hindshare_sign AJAX; no copied static signing key |
| HDHub4u | Catalogue and HubDrive file page 200 | Public search, HubDrive/HubCloud, bounded wrapper data; excludes existing 4KHDHub link |
| ZinkMovies | Cloudflare 403 | Archive token/worker protocol implemented; no browser-challenge bypass |
| Fibwatch | Catalogue/detail 200 | Resolution AJAX, explicit episode identity, downstream literal media/host traversal |
| Mlsbd | Catalogue/detail 200 | Download sections and explicit episodes only; no combined-pack fallback |
| SkyMovies | Old root redirects to new4.skymovieshd.clinic; catalogue 200 | Current L/Let download rows and advertised downstream hosts |
| M4UFree .lat | Cloudflare 403 | Missing TV CSRF/AJAX protocol; existing movie implementations unchanged |

Fresh research fetched actual observed detail links and first download hops, rather than treating
an intermediate page as a stream. Research used the available environment transport; it does not
prove the application's guarded transport or native decoder succeeds. The audit workflow tests
these routes separately with the application's existing transport and LibVLC player. All external
decoder-upload restrictions remain in force. No ordinary redirect or temporary HTTP error is
classified as permanent closure. Signed links, cookies and response bodies are not committed.

At revision `89d2d80`, run [40905392565](https://github.com/billyke2000s/moviehub/actions/runs/40905392565)
completed the Inception lookup on fourteen movie-enabled additions and Breaking Bad S1E1 on all
fifteen TV additions. Six movie sources from two sites and twelve episode sources from HindMoviez
passed media validation. This is not a count of all films available on those sites. The existing
player then verified three SkyMovies Inception sources at 1280×536, 8,887 seconds, with decoded
video/audio and midpoint decoding. Relay socket errors were also recorded. Zink/Fibwatch/Vega
returned browser challenges in that environment; TopMovies failed DNS. All twelve HindMoviez S1E1 sources also passed native video/audio/runtime/midpoint checks:
3,486 seconds, English audio, four 720×406 and eight 1280×722 samples. Each recorded relay socket
errors as well. Bollyflix decoded one Spider-Man source at 1728×720 with audio, but its expected
runtime was unknown and midpoint was not checked: runtime_unverified, excluded from the verified
count of fifteen. The HDHub4u movie group was still running at this checkpoint. The Vidy movie audit failed before tests
because SDK installation could not read its downloaded NDK ZIP; no playback result was produced.

Inspection of MoviesDrive's actual Inception page subsequently identified its download content
outside the introductory entry-content block. The correction also rejects quality-search links.
UHD's final `lp_go` literal link and HubCloud's extensionless FSL file buttons were added from
current public pages. MLSBD now searches title/year and selects current Epi-NN download dividers without crossing into
a following episode. An intervening guarded Inception check at 4f2a6bd returned five MoviesDrive sources; the final
c8c42cc check returned two, with other hosts refusing/timing out. MLSBD movie sections now allow
the observed Download Links subheading, and plain mirror lists reuse existing exact host patterns.
Ordinary landing forms no longer carry AJAX-only headers. Stage-only diagnostics record recognized
form IDs and literal-final-link presence without response bodies or tokens. The final 1,082-byte UHD page was subsequently identified as Session expired. Its preceding
page issues the redirect payload through a literal sc(name,value,minutes) cookie call. The new
resolver now sends that cookie only to the exact issuing origin; the value is never exported to
media headers or logged. The fix has an explicit isolation/header-injection regression. Final
checks for this correction remain pending.

The completed 89d2d80 native movie group adds one HDHub4u Inception pass (1280×536,
8,888 seconds, English audio, midpoint decode) and one failed HDHub4u source. That checkpoint's
verified total is sixteen: twelve HindMoviez episodes, three SkyMovies movies and one HDHub4u
movie. Six additional sources decoded video/audio but had no verified runtime/midpoint (five
HindMoviez and one Bollyflix Spider-Man); three attempts had no decoded video. These counts are
for that checkpoint and the named test titles, not all available site content.

The sections below retain earlier revision evidence; their original counts and unimplemented
lists describe that earlier checkpoint, superseded by this follow-up where explicitly stated.


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

## Initial blockers and unimplemented routes (historical baseline)

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
| M4ufree `.lat` | M4u CSRF/AJAX family overlaps existing M4u routes. A public catalogue is readable through web retrieval, but independent playback-backend identity is unresolved. It is not added, and existing implementations/exclusions stay intact. |

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

Follow-up local validation: 116 Python tests passed, including single-use guest tickets, one retry,
Acme language variants, optional PrimeSrc token, exact current Multimovies episodes, current AnimeSalt
hosts, backend deduplication, token-origin rejection and the unattended audit guard. The 34 Java core
suites / 1,167 checks passed. Android CI and the new Vidy native playback audit are pending for the
follow-up commit; historical CI results above apply to the earlier code revision only.


The initial native Vidy run at 28338fe did not run instrumentation: adb split the source label
"Vidy Vegas" at its remote shell boundary. Revision e232f6f quotes that boundary correctly; a
mock-adb check verified the exact remote argument and unchanged runner. Its movie audit then ran
but returned no decoded video (relay IOException); its episode crashed inside LibVLC's adaptive
timestamp continuity. Neither is a playback pass. The follow-up offers advertised HLS renditions
before the master, preserving Vidy's complete Origin/Referer/UA headers and leaving external audio
attached to its master. It reuses one seed per lookup, as CineStream does, instead of requesting a
new seed for every backend. This reduces unnecessary requests; prior traces included HTTP 429s.
Native validation of this last adapter correction is pending. Existing player and Movy code remain
unchanged. The earlier direct-site findings still apply.

Protocol runs at e232f6f yielded nine media-checked Vidy links (four film / five episode), across
Reno, Seattle, Phoenix, Atlanta and Dallas. These are media checks, not native decode passes.
Parsing the actual fetched AnimeSalt Naruto S1E1 HTML yielded seven hosts/language variants;
parsing the actual Multimovies Attack on Titan player configuration yielded seven named backends.
Those cached-page checks are parsing evidence, not live terminal resolution or playback.

The fifteen remaining unimplemented candidate roots were also read from the current maintainer
URL map and subjected to public-address DNS preflight. All fifteen hit this workspace's DNS
restriction before a homepage request; no conclusion about their live health is possible here.
No alternative resolver or private-address allowance was introduced.


## Follow-up outcomes at application revision 47b22de

Local checks: 116 Python tests and 34 core suites / 1,167 checks passed. The remote-shell argument
regression check also passed. Established source implementations remain unchanged.

[CineStream native audit 40866160539](https://github.com/billyke2000s/moviehub/actions/runs/40866160539)
did not establish an additional playback pass. The Inception lookup returned four Phoenix links;
each failed with zero decoded frames/audio and a relay IOException. The Breaking Bad S1E1 lookup
returned three links; its first Phoenix selection crashed in LibVLC's adaptive timestamp continuity,
leaving only the starting record and no finished marker. HLS rendition expansion therefore did not
resolve these samples. A successful audit job/report is not counted as successful playback.
No additional native-playback success is claimed beyond the earlier two Hexa Alpha checks.
These candidate failures remain draft-PR blockers; the existing player was not replaced or modified
to hide them.

A separate direct first-segment check of Phoenix/Inception obtained a 200 master with three
variants, a 200 rendition with 1,776 segments, and a 200 first segment containing a TS sync byte.
The segment's Content-Type was text/html despite binary media bytes. This is transport evidence,
not a decoded-frame check or a proven cause of the native failure; the existing relay already
sniffs TS. No player or MIME-validation behavior was changed on this hypothesis.

[Android run 40866160526](https://github.com/billyke2000s/moviehub/actions/runs/40866160526) passed
build/lint/dual-ARM inspection, signing compatibility, the public availability probe and focused
VOD recovery. The experimental emulator job failed after 12 passing cases with
android.os.DeadSystemException. This failure is retained; no claim is made that it is unrelated.
The regular device suite was still running at this checkpoint. A retry of the failed job cannot
start while the same workflow run is running (GitHub rejected that request); no test gate was
weakened. Full Android validation is not claimed complete.

Workspace DNS preflight could not reach the fifteen other candidate roots. Separate web retrieval
could nevertheless read catalogue pages for **eight**: Bollyflix, MoviesMod, UHDMovies, DudeFilms,
RogMovies, HindMovie, HDHub4u and M4UFree. The other seven returned fetch errors, access refusals or
redirect-access limits through that retrieval service. The public pages prove that several of
these catalogues are available; they do not prove file-host playback. The corresponding download
and player chains remain unimplemented, as listed above. No inaccessible result is a permanent
closure finding.


The regular device suite subsequently passed: its HTML report contains 209 entries, with 208
passing plus the unchanged Explicit live audit only assumption (rendered as one failure by the
HTML generator, accepted by Gradle). The native track-format regression passed in 2.485 seconds.
The experimental job is being retried on the same application revision after its recorded
DeadSystemException; the first attempt is not erased or reclassified.

FFprobe recognized the separately fetched Phoenix/Inception first segment as H.264 video
(640×266) plus AAC audio, starting at zero. This is independent media-inspection evidence,
not successful playback in MovieHub and not proof of the full stream's timestamps.

The automatically triggered existing-source diagnostics at this revision verified all eight
Movy movie links and seven of eight episode links; the last Miami episode link had unverified
audio. Rive returned two links but none selected by that audit's Vanguard filter. Those are
recorded diagnostic results, not grounds for replacing the established implementations.
No established scraper, native source, ResolveURL code or player engine was changed to make
these audits green. The earlier unchanged-main comparison remains recorded above.


## Final follow-up checkpoint

Application revision **47b22de** passed all six Android jobs in run 40866160526 after retrying
the experimental job. Its retry report contains 27 unique passing cases (7 startup, 20 Exo
lifecycle), with no failures/skips. The initial DeadSystemException remains recorded above.
Regular native track metadata passed; source/native playback failures are separately retained
and are not hidden by the green application build/test workflow.

The real KissKH detail response exposes episode numbers as JSON decimals, such as 1.0.
String equality against "1" incorrectly discarded the requested episode. The final adapter
correction accepts only the requested integer with an optional all-zero fractional part;
a 3.5 special does not match episode 3. The existing fixture now uses actual decimal-shaped
numbers and passed in the full 116-test Python runner. Android CI for this last correction
is pending; the six-job success above applies to revision 47b22de.

A materially safer KissKH helper check sent **only its already-public numeric episode ID**
to enc-kisskh. No site-issued token, cookie, authorization header, encrypted server response
or request body was uploaded. The helper and provider episode API both returned 200, and
the latter advertised two playback entries. A direct Video sample timed out; ThirdParty
returned a 200 HTML embed, requiring terminal resolution. Neither is claimed as native
playback. The opaque-token/response decoder probes for the other adapters remain blocked
by automatic approval review pending explicit approval. The unattended audit guard was not
relaxed to run this isolated public-ID-only check.

The draft remains incomplete: ten new adapters received current-method corrections, sixteen
nonduplicate Vidy backends are represented, fifteen further website chains remain unimplemented,
and the native Vidy failures need resolution. Existing providers/player behavior was preserved.
No merge or publication occurred.
