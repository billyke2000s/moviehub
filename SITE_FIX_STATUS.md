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
| anymovies.co (`free99:anymovies_co`) | Corrected-seek check: real Inception scenes and audio signal; about 15 displayed fps, below the smoothness requirement | unsupported_host; no episode source played | Partial verification; frame-rate cause unresolved | [Current review](docs/site-audits/anymovies.json), playback method 2 |
| bnwmovies.com (`free99:bnwmovies_com`) | 2 sources tested; 0 technical passes; 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/free99-bnwmovies_com.json) at 966d6ad |
| fzmovies.live (`free99:fzmovies_live`) | Corrected-seek check: 8 tested, 0 technical passes; real Inception/Dune frames; Dune audio at all positions; remaining FPS/late-audio/decoder failures | Movie-only adapter; unsupported_type | Partial verification; playback failures remain | [Current review](docs/site-audits/fzmovies.json), playback method 2 |
| levidia.ch (`free99:levidia_ch`) | 7 sources tested; 0 technical passes; 2 complete | 9 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/free99-levidia_ch.json) at 9a92abd |
| naijavault.com (`free99:naijavault_com`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-naijavault_com.json) at b386a07 |
| SeriesLoaded (`free99:seriesloaded_ng`) | 1 sources tested; 0 technical passes; 1 complete, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/free99-seriesloaded_ng.json) at 28412c4 |
| stagatv.com (`free99:stagatv_com`) | 0 sources tested; 0 technical passes; 1 no_matches, 1 http_500 | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-stagatv_com.json) at 6f0e2fd |
| tvseries.in (`free99:tvseries_in`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 ReadTimeout | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-tvseries_in.json) at 5a647c5 |
| Akwam (`native:akwam`) | 2 sources tested; 0 technical passes; 2 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-akwam.json) at 29815b9 |
| allmovieland (`native:allmovieland`) | 0 sources tested; 0 technical passes; 1 http_404 | 0 sources tested; 0 technical passes; 1 http_404 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-allmovieland.json) at b17d4b5 |
| animesalt (`native:animesalt`) | 3 sources tested; 2 technical passes; 1 complete | 3 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-animesalt.json) at f389b4d |
| bollyflix (`native:bollyflix`) | 0 sources tested; 0 technical passes; 1 ConnectTimeout | 0 sources tested; 0 technical passes; 1 ConnectTimeout | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-bollyflix.json) at 8fa7d7a |
| cinejoy (`native:cinejoy`) | 2 sources tested; 1 technical passes; 2 complete | 1 sources tested; 1 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-cinejoy.json) at b86f8fe |
| dudefilms (`native:dudefilms`) | 1 sources tested; 0 technical passes; 1 no_matches, 1 complete | 0 sources tested; 0 technical passes; 1 ReadTimeout | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-dudefilms.json) at c8ed973 |
| fibwatch (`native:fibwatch`) | 0 sources tested; 0 technical passes; 2 ValueError | 0 sources tested; 0 technical passes; 1 ValueError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-fibwatch.json) at 9368691 |
| 4KHDHub (`native:fourkhdhub`) | 5 sources tested; 0 technical passes; 2 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-fourkhdhub.json) at 33b6cec |
| fshare (`native:fshare`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-fshare.json) at dc1c152 |
| hdhub4u (`native:hdhub4u`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_404 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-hdhub4u.json) at fa777d7 |
| hexa (`native:hexa`) | 1 sources tested; 1 technical passes; 1 complete, 1 http_403 | 0 sources tested; 0 technical passes; 1 ReadTimeout | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-hexa.json) at fa777d7 |
| hindmoviez (`native:hindmoviez`) | 0 sources tested; 0 technical passes; 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-hindmoviez.json) at f1e8b59 |
| kisskh (`native:kisskh`) | 1 sources tested; 0 technical passes; 1 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-kisskh.json) at acf19f9 |
| m4ufree.lat (`native:m4ufree_lat`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-m4ufree_lat.json) at 85f455c |
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
| rive (`native:rive`) | 18 sources tested; 0 technical passes; 2 complete | 3 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-rive.json) at 2e136f0 |
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
| vixsrc (`native:vixsrc`) | 2 sources tested; 0 technical passes; 2 complete | 1 sources tested; 0 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/native-vixsrc.json) at 4de6b23 |
| zinkmovies (`native:zinkmovies`) | | | | |
| bluray7.com (`scrubs:bluray7_com`) | 0 sources tested; 0 technical passes; 1 no_matches, 1 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-bluray7_com.json) at 8e81bab |
| bstsrs.one (`scrubs:bstsrs_one`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 9 sources tested; 1 technical passes; 1 complete | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/scrubs-bstsrs_one.json) at 242db90 |
| doomovies.ga (`scrubs:doomovies_ga`) | 0 sources tested; 0 technical passes; 1 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-doomovies_ga.json) at d25c2be |
| gdriveplayer.us (`scrubs:gdriveplayer_us`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gdriveplayer_us.json) at 2da8e19 |
| gogoanime.tel (`scrubs:gogoanime_tel`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 ValueError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gogoanime_tel.json) at 5bf2a1b |
| gogoanimes.to (`scrubs:gogoanimes_to`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 ValueError | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-gogoanimes_to.json) at 8d39ad4 |
| goojara.to (`scrubs:goojara_to`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-goojara_to.json) at 2341572 |
| iwaatch.com (`scrubs:iwaatch_com`) | 2 sources tested; 0 technical passes; 1 complete, 1 no_matches | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/scrubs-iwaatch_com.json) at 8e1009a |
| m4uhd.tv (`scrubs:m4uhd_tv`) | 2 sources tested; 0 technical passes; 2 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/scrubs-m4uhd_tv.json) at d8c51de |
| moviesnipipay.me (`scrubs:moviesnipipay_me`) | 0 sources tested; 0 technical passes; 1 resolution_failed | 0 sources tested; 0 technical passes; 1 unsupported_host | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-moviesnipipay_me.json) at 82ea65d |
| myflixer.it (`scrubs:myflixer_it`) | 0 sources tested; 0 technical passes; 1 ValueError, 1 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-myflixer_it.json) at fc74eec |
| noxx.to (`scrubs:noxx_to`) | 0 sources tested; 0 technical passes; 1 unsupported_type | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-noxx_to.json) at 81361c5 |
| PressPlayZ (`scrubs:pressplay_top`) | Current repaired-source check: 2 HTTP 404 outcomes; no playback candidates | Current repaired-source check: 1 HTTP 404 outcome; no playback candidates | Parser/fallback repairs saved; live lookup still blocked | [Latest retest](docs/site-audits/pressplay.json), 4 November 2026 |
| projectfreetv.cyou (`scrubs:projectfreetv_cyou`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 http_403 | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-projectfreetv_cyou.json) at 33595bc |
| rarefilmm.com (`scrubs:rarefilmm_com`) | 1 sources tested; 1 technical passes; 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/scrubs-rarefilmm_com.json) at 7d27c3a |
| series9movies.com (`scrubs:series9movies_com`) | 0 sources tested; 0 technical passes; 2 http_403 | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/scrubs-series9movies_com.json) at 26f79eb |
| tubitv.com (`scrubs:tubitv_com`) | 0 sources tested; 0 technical passes; 2 no_matches | 0 sources tested; 0 technical passes; 1 no_matches | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tubitv_com.json) at b512652 |
| tvids.net (`scrubs:tvids_net`) | 0 sources tested; 0 technical passes; 2 gaierror | 0 sources tested; 0 technical passes; 1 gaierror | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41763869027/scrubs-tvids_net.json) at 667950f |
| tvmovieflix.com (`scrubs:tvmovieflix_com`) | 1 sources tested; 0 technical passes; 1 http_403, 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41766998519/scrubs-tvmovieflix_com.json) at 14257de |
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
