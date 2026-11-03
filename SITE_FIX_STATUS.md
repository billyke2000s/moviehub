# Site fix checklist

All 74 movie/series sites and the two sports sites are listed below.
Results are intentionally blank for the fresh audit. Fill each row only after checking that site.
Use **should be all good g** after the required repair and playback checks pass.

## Movies and series

| Site / provider ID | Movie result | Series result | Status | Evidence / notes |
| --- | --- | --- | --- | --- |
| anymovies.co (`free99:anymovies_co`) | 1 sources tested; 0 technical passes; 1 complete, 1 resolution_failed | 0 sources tested; 0 technical passes; 1 unsupported_host | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-anymovies_co.json) at f67c3f5 |
| bnwmovies.com (`free99:bnwmovies_com`) | 2 sources tested; 0 technical passes; 1 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-bnwmovies_com.json) at 3cd21dd |
| fzmovies.live (`free99:fzmovies_live`) | 8 sources tested; 1 technical passes; 2 complete | 0 sources tested; 0 technical passes; 1 unsupported_type | Checked; repair/content review pending | [Evidence](docs/site-audits/runs/41726423062/free99-fzmovies_live.json) at 57881d6 |
| levidia.ch (`free99:levidia_ch`) | Incomplete check; see evidence | Incomplete check; see evidence | Incomplete; needs follow-up | [Evidence](docs/site-audits/runs/41726423062/free99-levidia_ch.json) at 32ec197 |
| naijavault.com (`free99:naijavault_com`) | | | | |
| SeriesLoaded (`free99:seriesloaded_ng`) | | | | |
| stagatv.com (`free99:stagatv_com`) | | | | |
| tvseries.in (`free99:tvseries_in`) | | | | |
| Akwam (`native:akwam`) | | | | |
| allmovieland (`native:allmovieland`) | | | | |
| animesalt (`native:animesalt`) | | | | |
| bollyflix (`native:bollyflix`) | | | | |
| cinejoy (`native:cinejoy`) | | | | |
| dudefilms (`native:dudefilms`) | | | | |
| fibwatch (`native:fibwatch`) | | | | |
| 4KHDHub (`native:fourkhdhub`) | | | | |
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
| movy (`native:movy`) | | | | |
| Multimovies (`native:multimovies`) | | | | |
| onetouchtv (`native:onetouchtv`) | | | | |
| peachify (`native:peachify`) | | | | |
| projectfreetv.sx (`native:projectfreetv_sx`) | | | | |
| rive (`native:rive`) | | | | |
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
| vixsrc (`native:vixsrc`) | | | | |
| zinkmovies (`native:zinkmovies`) | | | | |
| bluray7.com (`scrubs:bluray7_com`) | | | | |
| bstsrs.one (`scrubs:bstsrs_one`) | | | | |
| doomovies.ga (`scrubs:doomovies_ga`) | | | | |
| gdriveplayer.us (`scrubs:gdriveplayer_us`) | | | | |
| gogoanime.tel (`scrubs:gogoanime_tel`) | | | | |
| gogoanimes.to (`scrubs:gogoanimes_to`) | | | | |
| goojara.to (`scrubs:goojara_to`) | | | | |
| iwaatch.com (`scrubs:iwaatch_com`) | | | | |
| m4uhd.tv (`scrubs:m4uhd_tv`) | | | | |
| moviesnipipay.me (`scrubs:moviesnipipay_me`) | | | | |
| myflixer.it (`scrubs:myflixer_it`) | | | | |
| noxx.to (`scrubs:noxx_to`) | | | | |
| PressPlayZ (`scrubs:pressplay_top`) | | | | |
| projectfreetv.cyou (`scrubs:projectfreetv_cyou`) | | | | |
| rarefilmm.com (`scrubs:rarefilmm_com`) | | | | |
| series9movies.com (`scrubs:series9movies_com`) | | | | |
| tubitv.com (`scrubs:tubitv_com`) | | | | |
| tvids.net (`scrubs:tvids_net`) | | | | |
| tvmovieflix.com (`scrubs:tvmovieflix_com`) | | | | |
| tvseries.video (`scrubs:tvseries_video`) | | | | |
| upmovies.net (`scrubs:upmovies_net`) | | | | |
| vumoo.mx (`scrubs:vumoo_mx`) | | | | |
| watchseries.cyou (`scrubs:watchseries_cyou`) | | | | |
| watchserieshd.stream (`scrubs:watchserieshd_stream`) | | | | |
| watchtvseries.net (`scrubs:watchtvseries_net`) | | | | |
| winnoise.com (`scrubs:winnoise_com`) | | | | |

## Sports

| Site | Playback result | Status | Evidence / notes |
| --- | --- | --- | --- |
| ZLive | | | |
| FreeShot | | | |

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
