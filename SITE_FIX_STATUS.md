# Site fix checklist

All **74 VOD sites** are listed below, with separate movie and series lookup results,
plus the two sports repairs. Keep every row when updating progress.

**“Should be all good g”** is the final status after a repair and the required playback
checks pass. No site has complete verification recorded yet. Saved fixes and partial
evidence are recorded explicitly.

## Movies and series — all 74 sites

These are the saved lookup outcomes from [audit run 41484094657](https://github.com/billyke2000s/moviehub/actions/runs/41484094657),
as recorded in [PR #211](https://github.com/billyke2000s/moviehub/pull/211) at code revision
`36d8d9a`. This checklist expansion does not represent a new playback run.

- `complete` means links were extracted; it is not a playback pass.
- `no_matches` applies only to the selected audit title.
- `unsupported_type` describes the current adapter capability and needs confirmation against the site.
- `PermissionError` can reflect an intentionally disabled desktop decoder; check Android before judging the site.
- HTTP, timeout, DNS, SSL, parsing and rejected-media results remain unresolved until investigated.

| Site / provider ID | Movie lookup | Series lookup | Fix / playback status | Next check |
| --- | --- | --- | --- | --- |
| anymovies.co (`free99:anymovies_co`) | complete (1 links) | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| bnwmovies.com (`free99:bnwmovies_com`) | complete (2 links) | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| fzmovies.live (`free99:fzmovies_live`) | complete (4 links) | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| levidia.ch (`free99:levidia_ch`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| naijavault.com (`free99:naijavault_com`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| SeriesLoaded (`free99:seriesloaded_ng`) | complete (1 links) | no_matches | Partial visual evidence; playback verification incomplete | Earlier Android frames matched Inception. Finish audio and smoothness checks; retest series with a catalogue title. |
| stagatv.com (`free99:stagatv_com`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| tvseries.in (`free99:tvseries_in`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| Akwam (`native:akwam`) | complete (1 links) | ReadTimeout | Episode-selection and download-hop fixes saved; playback pending | Resolve series timeout; complete playback checks for both types. |
| allmovieland (`native:allmovieland`) | http_404 | http_404 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| animesalt (`native:animesalt`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| bollyflix (`native:bollyflix`) | ConnectTimeout | ConnectTimeout | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| cinejoy (`native:cinejoy`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| dudefilms (`native:dudefilms`) | no_matches | ReadTimeout | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| fibwatch (`native:fibwatch`) | ValueError | ValueError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| 4KHDHub (`native:fourkhdhub`) | complete (2 links) | complete (1 links) | Episode-selection fix saved; playback pending | Complete content/audio/runtime/smoothness/seek checks. |
| fshare (`native:fshare`) | http_403 | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| hdhub4u (`native:hdhub4u`) | complete (1 links) | http_404 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| hexa (`native:hexa`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| hindmoviez (`native:hindmoviez`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| kisskh (`native:kisskh`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| m4ufree.lat (`native:m4ufree_lat`) | unsupported_type | ValueError | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| MeowTV (`native:meowtv`) | http_400 | http_400 | Partial visual evidence; playback verification incomplete | Earlier Android frames matched Breaking Bad pilot. Investigate desktop HTTP 400; finish audio and smoothness checks. |
| mlsbd (`native:mlsbd`) | http_403 | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| moviebox (`native:moviebox`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| moviesdrive (`native:moviesdrive`) | complete (2 links) | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| moviesmod (`native:moviesmod`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| movy (`native:movy`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| Multimovies (`native:multimovies`) | rejected_media | rejected_media | Dubbed-title and host-handling fixes saved; still failing | Resolve rejected media; complete playback checks for both types. |
| onetouchtv (`native:onetouchtv`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| peachify (`native:peachify`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| projectfreetv.sx (`native:projectfreetv_sx`) | ReadTimeout | ReadTimeout | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| rive (`native:rive`) | complete (7 links) | complete (3 links) | No site-specific fix recorded; playback unverified | Complete MovieHub playback checks for supported types. |
| rogmovies (`native:rogmovies`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| Rtally (`native:rtally`) | rejected_media | SSLError | Episode-selection fix saved; still failing | Resolve rejected movie media and series SSL failure; complete playback checks. |
| skymovies (`native:skymovies`) | complete (4 links) | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| toonstream (`native:toonstream`) | ConnectTimeout | ConnectionError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| topmovies (`native:topmovies`) | gaierror | gaierror | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| uhdmovies (`native:uhdmovies`) | http_404 | http_404 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| vegamovies (`native:vegamovies`) | ValueError | ValueError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| vidcore (`native:vidcore`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| videasy (`native:videasy`) | JSONDecodeError | JSONDecodeError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| vidfast (`native:vidfast`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| vidrock (`native:vidrock`) | ConnectionError | ConnectionError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| vidup (`native:vidup`) | PermissionError | PermissionError | No site-specific fix recorded; playback unverified | Check Android decoder path; desktop decoder restriction is not a site failure. Complete MovieHub playback checks for supported types. |
| Vidzee (`native:vidzee`) | complete (2 links) | complete (4 links) | Source-protocol fix saved; Android playback stalled | Real content reviewed; separate movie samples had non-silent dialogue. Fix Android stall and complete playback evidence. |
| vixsrc (`native:vixsrc`) | complete (1 links) | complete (1 links) | No site-specific fix recorded; playback unverified | Complete MovieHub playback checks for supported types. |
| zinkmovies (`native:zinkmovies`) | ValueError | ValueError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| bluray7.com (`scrubs:bluray7_com`) | http_403 | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| bstsrs.one (`scrubs:bstsrs_one`) | unsupported_type | http_403 | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| doomovies.ga (`scrubs:doomovies_ga`) | http_403 | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| gdriveplayer.us (`scrubs:gdriveplayer_us`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| gogoanime.tel (`scrubs:gogoanime_tel`) | unsupported_type | ValueError | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| gogoanimes.to (`scrubs:gogoanimes_to`) | unsupported_type | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| goojara.to (`scrubs:goojara_to`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| iwaatch.com (`scrubs:iwaatch_com`) | complete (2 links) | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| m4uhd.tv (`scrubs:m4uhd_tv`) | complete (1 links) | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| moviesnipipay.me (`scrubs:moviesnipipay_me`) | resolution_failed | unsupported_host | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| myflixer.it (`scrubs:myflixer_it`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| noxx.to (`scrubs:noxx_to`) | unsupported_type | http_403 | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| PressPlayZ (`scrubs:pressplay_top`) | http_404 | http_404 | Investigated; repair not implemented | Replace retired player endpoint using current site flow; validate and complete playback checks. |
| projectfreetv.cyou (`scrubs:projectfreetv_cyou`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| rarefilmm.com (`scrubs:rarefilmm_com`) | complete (1 links) | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Complete MovieHub playback checks for supported types. |
| series9movies.com (`scrubs:series9movies_com`) | http_403 | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| tubitv.com (`scrubs:tubitv_com`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| tvids.net (`scrubs:tvids_net`) | gaierror | gaierror | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| tvmovieflix.com (`scrubs:tvmovieflix_com`) | http_403 | unsupported_type | No site-specific fix recorded; playback unverified | Confirm and record genuine type support. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| tvseries.video (`scrubs:tvseries_video`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| upmovies.net (`scrubs:upmovies_net`) | SSLError | SSLError | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| vumoo.mx (`scrubs:vumoo_mx`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |
| watchseries.cyou (`scrubs:watchseries_cyou`) | http_403 | http_403 | No site-specific fix recorded; playback unverified | Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| watchserieshd.stream (`scrubs:watchserieshd_stream`) | ValueError | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| watchtvseries.net (`scrubs:watchtvseries_net`) | gaierror | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Investigate recorded failure and repair where possible. Complete MovieHub playback checks for supported types. |
| winnoise.com (`scrubs:winnoise_com`) | no_matches | no_matches | No site-specific fix recorded; playback unverified | Retest using confirmed catalogue titles. Complete MovieHub playback checks for supported types. |

## Sports — separate PR #210

These fixes are on `fix/zlive-freeshot-current-players`, not this audit branch.
Neither PR has been merged or released.

| Site | Current status | Remaining verification |
| --- | --- | --- |
| ZLive | Resolver-key and short-placeholder fixes saved; verification pending | Real F1 picture and audio decoded from a sample; complete MovieHub live-relay playback check remains blocked by DNS. |
| FreeShot | Current-CDN and renewal compatibility fix saved; verification pending | Underlying WideIPTV sample showed real sports with audio; verify the complete FreeShot-to-MovieHub playback chain. |

## How to update this file

- Once a repaired site passes the checks below, change its status to **should be all good g**.
- Record the commit, evidence/run link, tested title and media type, and test date beside that status.
- Check exact film or season/episode; inspect beginning, middle and late footage for wrong or promotional-only content.
- Verify non-silent programme audio, plausible runtime, smooth playback and seeking through MovieHub's Android/LibVLC path.
- Record movies and series separately. Clearly identify genuinely unsupported media types.
- A lookup result, HTTP success, build pass or audio-track flag alone is not a playback pass.
- Sample-based checks do not establish an uninterrupted full-title watch or physical speaker output.
- Reopen a site's status if a subsequent check fails. Preserve the evidence and reason.

All 74 VOD sites have recorded protocol lookup attempts, not complete playback passes.
The full lookup table and outstanding audit details are in
[PR #211](https://github.com/billyke2000s/moviehub/pull/211).
Sports evidence and limitations are in
[PR #210](https://github.com/billyke2000s/moviehub/pull/210).
