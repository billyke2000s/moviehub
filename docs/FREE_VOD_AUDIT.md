# Free VOD provider audit — 24 October 2026

The inventory exactly matches the active provider directories in Scrubs V2 5.1.42 (59)
and Free99 2.0.1 (nine): **68 modules, zero missing, zero extra**. Every recorded original-file
SHA-256 matches the downloaded upstream package. All 68 adapted constructors import locally;
the lookup-contract fixture checks the movie and series signatures and individually drives
every applicable adapter through MovieHub's query entry point with mocked network results.
Android's packaged-runtime constructor test remains part of CI.

The upstream loader walks provider modules, constructs `source()`, calls `movie(...)` or
`tvshow(...)` then `episode(...)`, and calls `sources(key, hostDict)`. MovieHub uses the same
sequence with a fresh instance per query, then calls `resolve` and free, non-popup ResolveURL
host extractors. It deliberately omits Kodi's settings/UI/database and paid/interactive hosts.
The supported host list excludes wildcard/universal resolvers, matching upstream's host-list
filter. These are compatible call contracts, not a claim of a complete Kodi runtime.

The previous 120-second overall search could leave the last modules unstarted with eight
18-second workers. The bound is now 180 seconds; first selection still occurs at three seconds
and discoveries continue as backups. Each provider records queued, searching and final outcomes.
HTTP, DNS/TLS, timeout, unsupported host, rejected media, local-library and missing-key outcomes
are shown separately from no matches. Outcomes contain no source URLs, cookies or error bodies.

Local live availability checks cannot establish provider health: this workspace's direct public
DNS lookups fail. The independent Free VOD provider audit workflow runs a public-domain film
query on GitHub's runner and saves one result per module. Neither a constructor pass nor one
film's no-match result proves a website supports a particular title or plays on the TV.

Sources: [Scrubs package](https://github.com/jewbmx/repo/tree/master/zips/plugin.video.scrubsv2),
[Free99 package](https://github.com/nebulous42069/Omega/tree/main/omega/zips/plugin.video.free99).
Archived `_duds` are not active providers. `library` uses Kodi's database; `filepursuit` requires
an API key. Their inclusion in the inventory does not make them anonymous website providers.

Independent audit [run 40094277573](https://github.com/billyke2000s/moviehub/actions/runs/40094277573)
queried all 68 modules on GitHub's runner. Both BNWMovies adapters returned three media-response
links in total. Other outcomes included HTTP 403 (12), DNS failure (6), TLS failure (7), connection
timeouts (4), read timeouts (8), no matches (14), unsupported media type (6), missing key (1),
local library (1), other HTTP errors (4) and rejected/unsupported URL or response limits (3).
This was one public-domain film, with HTTPS/public-address policy enabled, before the extended-
response compatibility correction. It does not establish availability for other films, website
health from the owner's network, sustained playback, or physical-TV success.

| Provider | Constructor | Movies | Episodes | Constraint |
| --- | --- | --- | --- | --- |
| free99:anymovies_co | Loaded | Yes | No | Website availability varies |
| free99:bnwmovies_com | Loaded | Yes | No | Website availability varies |
| free99:fzmovies_live | Loaded | Yes | Yes | Website availability varies |
| free99:levidia_ch | Loaded | Yes | Yes | Website availability varies |
| free99:my4hydra_top | Loaded | Yes | Yes | Website availability varies |
| free99:naijavault_com | Loaded | Yes | Yes | Website availability varies |
| free99:seriesloaded_ng | Loaded | Yes | Yes | Website availability varies |
| free99:stagatv_com | Loaded | Yes | Yes | Website availability varies |
| free99:tvseries_in | Loaded | Yes | Yes | Website availability varies |
| scrubs:123movies_skin | Loaded | Yes | Yes | Website availability varies |
| scrubs:123moviestv_me | Loaded | Yes | Yes | Website availability varies |
| scrubs:1movies_la | Loaded | Yes | Yes | Website availability varies |
| scrubs:9pm_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:anymovies_co | Loaded | Yes | No | Website availability varies |
| scrubs:bluray7_com | Loaded | Yes | No | Website availability varies |
| scrubs:bnwmovies_com | Loaded | Yes | No | Website availability varies |
| scrubs:bstsrs_one | Loaded | No | Yes | Website availability varies |
| scrubs:cinebox_cc | Loaded | Yes | Yes | Website availability varies |
| scrubs:couchtuner_show | Loaded | No | Yes | Website availability varies |
| scrubs:doomovies_ga | Loaded | Yes | No | Website availability varies |
| scrubs:dopebox_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:filepursuit | Loaded | Yes | Yes | API key required |
| scrubs:filmxy_pw | Loaded | Yes | No | Website availability varies |
| scrubs:flixhq_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:flixwave_me | Loaded | Yes | Yes | Website availability varies |
| scrubs:gdriveplayer_us | Loaded | Yes | Yes | Website availability varies |
| scrubs:gogoanime_bid | Loaded | No | Yes | Website availability varies |
| scrubs:gogoanime_tel | Loaded | No | Yes | Website availability varies |
| scrubs:gogoanimes_to | Loaded | No | Yes | Website availability varies |
| scrubs:goojara_club | Loaded | Yes | No | Website availability varies |
| scrubs:goojara_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:hdbest_net | Loaded | Yes | No | Website availability varies |
| scrubs:himovies_top | Loaded | Yes | Yes | Website availability varies |
| scrubs:iwaatch_com | Loaded | Yes | No | Website availability varies |
| scrubs:levidia_ch | Loaded | Yes | Yes | Website availability varies |
| scrubs:levidiamovies_club | Loaded | Yes | No | Website availability varies |
| scrubs:library | Loaded | Yes | Yes | Kodi library only |
| scrubs:m4ufree_se | Loaded | Yes | No | Website availability varies |
| scrubs:m4uhd_tv | Loaded | Yes | No | Website availability varies |
| scrubs:movie4u_live | Loaded | Yes | Yes | Website availability varies |
| scrubs:movies2watch_tv | Loaded | Yes | Yes | Website availability varies |
| scrubs:movies4fun_net | Loaded | Yes | Yes | Website availability varies |
| scrubs:moviesnipipay_me | Loaded | Yes | Yes | Website availability varies |
| scrubs:myflixer_it | Loaded | Yes | Yes | Website availability varies |
| scrubs:noxx_to | Loaded | No | Yes | Website availability varies |
| scrubs:okmov_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:pressplay_top | Loaded | Yes | Yes | Website availability varies |
| scrubs:projectfreetv_cyou | Loaded | Yes | Yes | Website availability varies |
| scrubs:projectfreetv_lol | Loaded | Yes | Yes | Website availability varies |
| scrubs:putlocker_gives | Loaded | Yes | Yes | Website availability varies |
| scrubs:rarefilmm_com | Loaded | Yes | No | Website availability varies |
| scrubs:series9movies_com | Loaded | Yes | No | Website availability varies |
| scrubs:series9mx_pro | Loaded | Yes | No | Website availability varies |
| scrubs:sflixz_watch | Loaded | Yes | Yes | Website availability varies |
| scrubs:soap2day_fan | Loaded | Yes | Yes | Website availability varies |
| scrubs:streamlord_com | Loaded | Yes | Yes | Website availability varies |
| scrubs:tinyzonetv_to | Loaded | Yes | Yes | Website availability varies |
| scrubs:tubitv_com | Loaded | Yes | Yes | Website availability varies |
| scrubs:tvids_net | Loaded | Yes | Yes | Website availability varies |
| scrubs:tvmovieflix_com | Loaded | Yes | No | Website availability varies |
| scrubs:tvseries_video | Loaded | Yes | Yes | Website availability varies |
| scrubs:upmovies_net | Loaded | Yes | Yes | Website availability varies |
| scrubs:vumoo_mx | Loaded | Yes | Yes | Website availability varies |
| scrubs:watchseries_cyou | Loaded | Yes | Yes | Website availability varies |
| scrubs:watchserieshd_stream | Loaded | Yes | Yes | Website availability varies |
| scrubs:watchtvseries_net | Loaded | Yes | Yes | Website availability varies |
| scrubs:watchug_com | Loaded | Yes | Yes | Website availability varies |
| scrubs:winnoise_com | Loaded | Yes | Yes | Website availability varies |

Additional confirmed compatibility defect: the bounded HTTP adapter returned an HTTP status integer in place of request headers in `output="extended"`. The actual WatchSeries provider then passed that integer as headers and swallowed the resulting exception. The adapter now returns the original five fields (body, request headers, response headers, cookies, final URL), without an extra cookie request. Default scrapePage Referer/protocol-relative handling and redirect cookie accumulation also match the upstream contract. A fixture drives the real WatchSeries provider through search, page and host lookup.
