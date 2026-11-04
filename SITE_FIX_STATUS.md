# Site fix checklist

All 74 movie/series sites and the two sports sites are listed below.
Results are intentionally blank for the fresh audit. Fill each row only after checking that site.
Use **should be all good g** after the required repair and playback checks pass.

Playback method correction (4 November): older candidate checks bypassed the app’s seek
guard. Their seek/smoothness failures and backup overlays require a corrected-method retest.
New candidate receipts carry `playbackAuditVersion: 2`; lookup-only checks remain valid.

## Movies and series

| Site / provider ID | Movie result | Series result | Status | Evidence / notes |
| --- | --- | --- | --- | --- |
| anymovies.co (`free99:anymovies_co`) | 1 sources tested; 0 technical passes; 1 complete, 1 resolution_failed | 0 sources tested; 0 technical passes; 1 unsupported_host | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-anymovies_co.json) at f67c3f5 |
| bnwmovies.com (`free99:bnwmovies_com`) | 2 sources tested; 0 technical passes; 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-bnwmovies_com.json) at 3cd21dd |
| fzmovies.live (`free99:fzmovies_live`) | 8 tested; 1 technical pass with real Dune footage and non-silent audio at all positions; backup-error overlay and other failures remain | Movie-only adapter; unsupported_type | Partial verification; playback errors unresolved | [Reviewed evidence](docs/site-audits/fzmovies.json), 4 November 2026 |
| levidia.ch (`free99:levidia_ch`) | 8 sources tested; 1 technical passes; 2 complete | 8 sources tested; 1 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/free99-levidia_ch.json) at b512652 |
| naijavault.com (`free99:naijavault_com`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-naijavault_com.json) at b386a07 |
| SeriesLoaded (`free99:seriesloaded_ng`) | 1 sources tested; 0 technical passes; 1 complete, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-seriesloaded_ng.json) at 9e73074 |
| stagatv.com (`free99:stagatv_com`) | 0 sources tested; 0 technical passes; 1 no_matches, 1 http_500 | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-stagatv_com.json) at 6f0e2fd |
| tvseries.in (`free99:tvseries_in`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 ReadTimeout | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-tvseries_in.json) at 5a647c5 |
| Akwam (`native:akwam`) | Incomplete check; see evidence | Incomplete check; see evidence | Incomplete; needs follow-up | [Evidence](docs/site-audits/runs/41763869027/native-akwam.json) at 4bbfde1 |
| allmovieland (`native:allmovieland`) | 0 sources tested; 0 technical passes; 1 http_404 | Incomplete check; see evidence | Incomplete; needs follow-up | [Evidence](docs/site-audits/runs/41763869027/native-allmovieland.json) at e088281 |
| animesalt (`native:animesalt`) | | | | |
| bollyflix (`native:bollyflix`) | | | | |
| cinejoy (`native:cinejoy`) | | | | |
| dudefilms (`native:dudefilms`) | | | | |
| fibwatch (`native:fibwatch`) | | | | |
| 4KHDHub (`native:fourkhdhub`) | 7 sources tested; 0 technical passes; 2 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/native-fourkhdhub.json) at ee57d83 |
| fshare (`native:fshare`) | | | | |
| hdhub4u (`native:hdhub4u`) | | | | |
| hexa (`native:hexa`) | | | | |
| hindmoviez (`native:hindmoviez`) | | | | |
| kisskh (`native:kisskh`) | | | | |
| m4ufree.lat (`native:m4ufree_lat`) | | | | |
| MeowTV (`native:meowtv`) | | | | |
| mlsbd (`native:mlsbd`) | | | | |
| moviebox (`native:moviebox`) | | | | |
| moviesdrive (`native:moviesdrive`) | | | | |
| moviesmod (`native:moviesmod`) | | | | |
| movy (`native:movy`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/native-movy.json) at e65f8a9 |
| Multimovies (`native:multimovies`) | | | | |
| onetouchtv (`native:onetouchtv`) | | | | |
| peachify (`native:peachify`) | | | | |
| projectfreetv.sx (`native:projectfreetv_sx`) | | | | |
| rive (`native:rive`) | 18 tested, 1 technical pass; Inception footage confirmed, late audio missing and language label conflicts | 3 tested, 1 technical pass; correct Breaking Bad pilot footage; late audio capture under half a second | Partial verification; source failures remain | [Reviewed evidence](docs/site-audits/rive.json), 4 November 2026 |
| rogmovies (`native:rogmovies`) | | | | |
| Rtally (`native:rtally`) | | | | |
| skymovies (`native:skymovies`) | | | | |
| toonstream (`native:toonstream`) | | | | |
| topmovies (`native:topmovies`) | | | | |
| uhdmovies (`native:uhdmovies`) | | | | |
| vegamovies (`native:vegamovies`) | | | | |
| vidcore (`native:vidcore`) | | | | |
| videasy (`native:videasy`) | | | | |
| vidfast (`native:vidfast`) | | | | |
| vidrock (`native:vidrock`) | | | | |
| vidup (`native:vidup`) | | | | |
| Vidzee (`native:vidzee`) | | | | |
| vixsrc (`native:vixsrc`) | 2 sources tested; 0 technical passes; 2 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/native-vixsrc.json) at b512652 |
| zinkmovies (`native:zinkmovies`) | | | | |
| bluray7.com (`scrubs:bluray7_com`) | 0 sources tested; 0 technical passes; 1 no_matches, 1 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-bluray7_com.json) at 8e81bab |
| bstsrs.one (`scrubs:bstsrs_one`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 9 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-bstsrs_one.json) at 8ba8aa2 |
| doomovies.ga (`scrubs:doomovies_ga`) | 0 sources tested; 0 technical passes; 1 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-doomovies_ga.json) at d25c2be |
| gdriveplayer.us (`scrubs:gdriveplayer_us`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gdriveplayer_us.json) at 2da8e19 |
| gogoanime.tel (`scrubs:gogoanime_tel`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 ValueError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gogoanime_tel.json) at 5bf2a1b |
| gogoanimes.to (`scrubs:gogoanimes_to`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 ValueError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gogoanimes_to.json) at 8d39ad4 |
| goojara.to (`scrubs:goojara_to`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-goojara_to.json) at 2341572 |
| iwaatch.com (`scrubs:iwaatch_com`) | Inception 720p/1080p: real scenes and 148-minute runtime; technical windows pass; buffering/backup overlays and late 1080p audio unresolved | Movie-only adapter; unsupported_type | Partial verification; follow-up required | [Reviewed evidence](docs/site-audits/iwaatch.json), 4 November 2026 |
| m4uhd.tv (`scrubs:m4uhd_tv`) | 2 sources tested; 0 technical passes; 2 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-m4uhd_tv.json) at 944ee5f |
| moviesnipipay.me (`scrubs:moviesnipipay_me`) | 0 sources tested; 0 technical passes; 1 resolution_failed | 0 sources tested; 0 technical passes; 1 unsupported_host | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-moviesnipipay_me.json) at 82ea65d |
| myflixer.it (`scrubs:myflixer_it`) | 0 sources tested; 0 technical passes; 1 ValueError, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-myflixer_it.json) at fc74eec |
| noxx.to (`scrubs:noxx_to`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-noxx_to.json) at 81361c5 |
| PressPlayZ (`scrubs:pressplay_top`) | Current repaired-source check: 2 HTTP 404 outcomes; no playback candidates | Current repaired-source check: 1 HTTP 404 outcome; no playback candidates | Parser/fallback repairs saved; live lookup still blocked | [Latest retest](docs/site-audits/pressplay.json), 4 November 2026 |
| projectfreetv.cyou (`scrubs:projectfreetv_cyou`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-projectfreetv_cyou.json) at 33595bc |
| rarefilmm.com (`scrubs:rarefilmm_com`) | 1 sources tested; 0 technical passes; 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-rarefilmm_com.json) at 636a1e8 |
| series9movies.com (`scrubs:series9movies_com`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-series9movies_com.json) at 26f79eb |
| tubitv.com (`scrubs:tubitv_com`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tubitv_com.json) at b512652 |
| tvids.net (`scrubs:tvids_net`) | 0 sources tested; 0 technical passes; 2 gaierror | 0 sources tested; 0 technical passes; 1 gaierror | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tvids_net.json) at 667950f |
| tvmovieflix.com (`scrubs:tvmovieflix_com`) | 1 sources tested; 0 technical passes; 1 http_403, 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tvmovieflix_com.json) at e147736 |
| tvseries.video (`scrubs:tvseries_video`) | 0 sources tested; 0 technical passes; 2 gaierror | 0 sources tested; 0 technical passes; 1 gaierror | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tvseries_video.json) at 926cfb5 |
| upmovies.net (`scrubs:upmovies_net`) | 0 sources tested; 0 technical passes; 2 SSLError | 0 sources tested; 0 technical passes; 1 SSLError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-upmovies_net.json) at f0b0e71 |
| vumoo.mx (`scrubs:vumoo_mx`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-vumoo_mx.json) at 8c8dafc |
| watchseries.cyou (`scrubs:watchseries_cyou`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-watchseries_cyou.json) at f19ffc4 |
| watchserieshd.stream (`scrubs:watchserieshd_stream`) | 0 sources tested; 0 technical passes; 1 ValueError, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-watchserieshd_stream.json) at 0f39fa9 |
| watchtvseries.net (`scrubs:watchtvseries_net`) | 0 sources tested; 0 technical passes; 2 gaierror | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-watchtvseries_net.json) at 58812bc |
| winnoise.com (`scrubs:winnoise_com`) | 0 sources tested; 0 technical passes; 1 ValueError, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-winnoise_com.json) at 23705b5 |

## Sports

| Site | Playback result | Status | Evidence / notes |
| --- | --- | --- | --- |
| ZLive | Website F1 backup shows actual 1080p programme footage; primary manifest failed; direct media request refused | Partial verification; app chain/audio still unverified | [Current checks and separate repair](docs/site-audits/zlive.json), 4 November 2026 |
| FreeShot | Football page returned Site Unavailable; no player iframe | Blocked here; full chain unverified | [Current check and separate repair](docs/site-audits/freeshot.json), 4 November 2026 |

## Updating results

- Check the current site and MovieHub integration; repair failures and retest.
- Verify correct film or season/episode, real content, non-silent programme audio, plausible runtime, smooth playback and seeking.
- Record movies and series separately; confirm genuine unsupported types explicitly.
- Add the tested title, date, commit and evidence link when filling a result.
- Mark **should be all good g** only after the required checks pass.
- Keep unchecked results empty. Record actual failures or blockers after checking.
- Save progress to GitHub after each site's checks and repairs.

Previous evidence remains in [PR #211](https://github.com/billyke2000s/moviehub/pull/211)
and [PR #210](https://github.com/billyke2000s/moviehub/pull/210).
