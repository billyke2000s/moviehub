# README technical notes (archived 17 November 2026)

These notes were removed from the top-level README to keep it short. They describe behaviour up to
MovieHub 1.0.19. Current code structure is in [ARCHITECTURE.md](../../ARCHITECTURE.md).

## Release summary that sat above the README title

CineStream additions, published in 1.0.19: 74 websites / 77 enabled adapters, with all fifteen added catalogue
routes retained. Current redirect fixes decode escaped URL literals and support the observed
DriveSeed public cloud/file-button exchange on its issuing origin. New host-button source names
say Stream instead of Download. LinksMod navigation is excluded from file traversal so it cannot
consume the mirror budget; the existing player and source-list layout are unchanged.
Local validation: 144 Python tests and 34 core suites / 1,167 checks pass. At `d85b832`, all six
Android CI jobs passed and the fifteen-site audit verified 21 individual sources across four sites.
Application revision `925af7c` passed all six Android jobs in run 40913881846. Final live checks
include 23 verified sources (eleven movies and twelve episodes); the episode retry passed after an EGL crash.
Vidy playback failures and other site/host errors remain unresolved; registration is not playback proof.
See [dated evidence and limits](CINESTREAM_ADDITIONS_2026_10_29.md).


CineStream adds 36 enabled adapter protocols; three further adapters and failing Hexa backends
remain pending. Source/player format display preserves existing site implementations. New adapter follow-up
tracks current site protocols and excludes Vidy’s duplicate Movy backends. See the [inventory, blockers and validation](CINESTREAM_ADDITIONS_2026_10_29.md).

## Project status

Current release: [MovieHub 1.0.19](https://github.com/billyke2000s/moviehub/releases/tag/moviehub-v1.0.19),
with ARM32 and ARM64 in one APK. The CineStream source/format additions, optimisations and VOD
recovery changes are published;
see [current status and release evidence](../../STATUS.md).

Automated tests use synthetic data and do not establish that every external service, live channel or update path works on physical hardware. Device-specific behaviour, especially live video handoff and provider integrations, needs real-world testing. Experimental work and its evidence are described in the technical notes below.

<details>
<summary><strong>Expand technical notes, experiments and playback behaviour</strong></summary>

### Native free TV

Live TV can discover regional Pluto TV channels directly, with no separate server. Settings → Free channels controls native Pluto and public lists; Xtream, personal M3U and optional hosted Masqueradarr remain separate. Streams resolve when played, with cached channel metadata and the shared guide/fullscreen player. See [provider support, test evidence and limitations](../NATIVE_FREE_SOURCES.md).

### Experimental standalone provider engine

Development adds opt-in FreeLiveSports using local compiled TypeScript/JavaScript and the existing
Live TV guide/player, with no home server. This is incomplete: current upstream adapters are in
an inaccessible private submodule, and signed remote updates require maintainer key setup.
See [implementation, provider status and tests](../STANDALONE_PROVIDER_ENGINE.md).

Experimental Runtime 2 (PR #83, merged 22 October 2026) adapts additional historical Masqueradarr provider code locally on
Android, including DaddyLive's resolver and Tubi's signing flow. This is unfinished development, not a
production release or a claim that every provider/channel plays. [Architecture, status and evidence](../STANDALONE_PROVIDER_ENGINE.md).

### DaddyLive playback compatibility (experimental)

The provider-engine development fix supports the current player's anonymous playlist-token bootstrap,
in-memory renewal and PNG-wrapped MPEG-TS segments through MovieHub's existing local relay. The web
player and its page advertising are not loaded. Desktop samples decoded for Main Event, Football,
Action, F1 and ABC USA on 20 October 2026; this does not establish every channel or physical-TV playback.

Provider refresh failures now show a safe reason at the start of each provider's status. Settings cards
expand to fit their descriptions, and guide labels fit shorter rows instead of clipping vertically.

Provider playback resolves a fresh stream when you select a channel. The screen shows “Finding a working stream…” during that bounded lookup, then starts the video-opening timeout after a URL is ready. Refreshing channels updates the directory; it does not pre-resolve expiring video links.

Native/provider Live TV also watches playback progress: a stopped video counter (falling back to the clock when video counters are unavailable) can recover even when VLC emits no buffering event. Paused or hidden playback is excluded.

### Live TV picture handoff

Live TV draws through VLC's standard video layout (a SurfaceView). The guide preview and full
screen share one player: normal moves change only the screen surface, retaining the stream and
sound (the picture may blink briefly). If native video shutdown is not acknowledged within
600 ms, recovery stops and restarts the stream before releasing its old surface, then restores
the selected audio track. Device tests check fresh, non-blank pictures in both directions;
TV hardware still needs testing.

Live video progress follows VLC's decoded-picture count rather than assuming advancing audio
means video is healthy. A stopped picture gets one surface refresh before the existing bounded
source recovery. Troubleshooting retains the last safe recovery description.

Live TV Back now reveals the channel controls and a selectable Sources link before returning to the mini-player on the next Back. Sources defaults to Automatic; selecting a particular saved copy pins it until Automatic or another channel is selected. Source menus read saved channel metadata; stream resolution remains on demand.

FreeShot (UK sports) is an additional opt-in source in Settings → Free channels. Its directory is imported without opening streams; a selected channel follows its literal player links on demand and uses MovieHub’s existing player. Anonymous playlist tokens renew in memory.

A selected live backup and its Automatic/manual mode follow the active player through guide/full-screen movement; reopening the same channel keeps that source. Choosing another channel starts in Automatic mode.

### Playback recovery

Films and episodes opened from Sources can switch among up to five cached sources. Backups resolve only when needed. A 4-second stall or two buffering starts in 60 seconds prepares a muted replacement; the current stream stays open until the replacement decodes near the current position. Seeking and user pause are excluded. Playback options → Sources offers manual selection and Automatic. Manual selection pins the playback service (currently Premiumize). Cloud-only/history playback without an in-memory source session keeps its existing single-source behavior.

Live TV keeps its separate thresholds. A still video counter over advancing audio gets an early surface repair after 2.5 seconds. Ordinary IPTV and provider streams share this watchdog; one repair is allowed until observed video progress resumes. Troubleshooting reports decoded/displayed VLC rates separately for the guide and full screen; these counters are not direct measurements of SurfaceView presentation.

Scheduled provider-link renewal keeps the current stream running while a fresh link resolves; pause or exit discards late results, and failed refresh leaves working playback alone. In Automatic mode, an 8-second stall or three buffering starts within a minute prepares one muted live backup while the current stream remains open. The replacement is promoted only after video and available audio advance. Failed candidates are tried once each; exhausted backups leave working playback alone. Pause, manual selection, channel changes and exit cancel preparation. The same session handles full screen and the guide preview. Different live feeds can have different delays, so a source cut may jump in programme time. Transport cache profiles use 1.5 s for direct live, 2.5 s for HLS, 4 s for relayed live and 2.5 s for VOD; extra VOD headroom can increase startup/seek delay.

</details>

## Sections that sat below the README footer

## Live TV control consistency

Live TV uses the same channel banner on initial playback and restored sessions. Red opens audio/subtitles, green opens sources, yellow shows programme information and blue opens options. Back reveals controls for arrow/OK selection on remotes without colour buttons.

## Live playback recovery

Live TV retries an established Pluto/direct connection within a bounded recovery budget, preserving the selected source. Options includes Repair picture and Reconnect channel. Background native shutdown keeps playback controls available while a decoder closes. Recovery cannot guarantee availability of an external channel or correct operation of every TV decoder.

### Free movie and series sources

The source system bundles Scrubs V2 and Free99 provider modules with headless
host resolution inside the APK. Premiumize is optional. Automatic play defaults on (existing
explicit preferences remain): after three seconds, it selects the highest reported quality
available within the configured quality preference. If none is ready, search continues until
a playable response arrives or the bounded search ends. Later discoveries join the player’s
backup list. The existing two-stalls-in-60-seconds / four-second-stall VOD policy is unchanged.
Provider availability varies; inclusion does not establish successful live playback.
See [provider provenance and licensing](../third-party/vod/NOTICE.md).

## Free-stream playback information

Free movie and series controls show incoming traffic from the selected source. Automatic selection uses the configured resolution ceiling, manifest resolution where available, camera-recording penalties, and startup transfer evidence among equal-quality results. The selection window remains about three seconds; late results remain backups.

Free movie searches include **Provider search details** with separate outcomes for timeouts, connection failures, unsupported hosts and no matching links. The player shows the selected source and incoming traffic, uses expected title runtimes to reject substantially truncated videos (unknown runtimes retain the two-minute guard), and can try another free service after a failed manual choice. [Provider inventory and audit](../FREE_VOD_AUDIT.md).

Free movie/episode searches use bundled provider title normalization and TMDB original/alternative
titles, preserve session request methods and cookies, and continue collecting backups after the
three-second initial selection. Remote availability varies; provider details distinguish network,
HTTP, host-resolution and no-match outcomes.

### Live TV playback validation

Since 1.0 Live TV plays through Media3 ExoPlayer, which was tried first in MovieHub Experimental
(up to exp-15). `ExoLiveTest` covers shared guide/fullscreen video, channel retries,
audio/subtitles and player cleanup on the emulator. Physical-TV/provider behaviour still needs
verification; recorded picture freezes do not trigger automatic repair.

### Faster experimental DaddyLive startup

Experimental DaddyLive lookups use one disposable isolated process per channel lookup, reuse
compiled provider code with fresh script globals, and briefly cache public mirror pages.
Tokens, player embeds and media remain fresh. Failed players advance rather than being
selected repeatedly. Provider/network response time can still affect startup.

### Experimental combined programme guide

The experimental integration downloads only [the finished compressed guide](https://raw.githubusercontent.com/billyke2000s/Moviehub-epg/main/epg.xml.gz) for built-in listings. GitHub fetches and merges the public sources at **04:37 UTC daily**, with a manual trigger. [Coverage, unmatched channels and source failures](https://github.com/billyke2000s/Moviehub-epg/blob/main/coverage.md) are measured against public catalogues, not a viewer's private account. Guide identifiers include aliases and are not unique broadcast services. Scheduling may be delayed and public-repository schedules stop after 60 days without activity; see the generator README.

Downloads use conditional requests and a six-hour check interval inside the existing background channel refresh, with cached data on failure. Channel changes query local SQLite. Private accounts, URLs and favourites stay on the device. The current integration has no separately configured private XMLTV URL feature; it does not publish private account data.

ZLive exposes each distinct advertised, supported public resolver route with its server label. Browser-only protected choices cannot be promised to work in an external player. ZLive serves a looping "refresh the site" decoy unless its API requests carry browser fetch headers; with them, MovieHub Experimental exp-15 played ZLive channels on the owner's TV (25 October 2026). Other ZLive channels and long sessions are not individually verified.

### Experimental provider repairs

MovieHub searches distinct movie/series websites, retaining alternate Kodi scrapers as fallbacks without counting the same website twice. Each actual playback provider has a separate row labelled with its discovery website; multiple mirrors are not collapsed into one website row. Resume refreshes movie/episode metadata and searches for a current link before restoring progress. ZLive uses its current public ticket-and-POST player API and the source labels actually advertised in its catalogue. Provider availability depends on the website, host and location; bundled module counts do not establish playback availability.

### Movie and series discovery recovery

Levidia is restored after verifying its exact scraper address and search. The other 18 provisional restorations are disabled pending positive verification; eleven confirmed retired or repurposed domains also stay disabled. Ordinary discovery contains 36 websites and 39 adapters, including two separately maintained native websites. Scraper metadata lookups stop after response headers, redirects discard an obsolete Host header, and Levidia tolerates an empty initial cookie jar and yearless search entries. Availability is reported per search; this does not promise that every remote website or host plays.

## Site-by-site VOD recovery (26 October 2026)

Free film/series results list each actual playback provider separately, with its discovery website retained. See [the site-by-site audit](FREE_VOD_SITE_AUDIT_2026_10_26.md) for current website, search and player findings.

## Native movie source expansion (26 October 2026)

MovieHub-native movie adapters can use a real TMDB identity directly. These are maintained separately from the bundled Kodi adapters; each downstream media host remains an individual source. Provider availability and 4K results vary by film and network. Source labels are advertised quality unless the media manifest supplies a resolution.

## FMHY provider additions

Rive adds separately listed movie and episode resources through the existing native-provider bridge. Only audited Primevids/dcloud and Citadel routes are enabled; this does not advertise its unverified 4K backend. See [the new-source audit](FMHY_ADDITIONS_2026_10_26.md) for tested titles, measured quality, audio, duration and outstanding checks.
