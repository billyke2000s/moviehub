# Current provider re-audit — 29 October 2026

Status: in progress. This is not a claim that the added providers work.

The owner's 1.0.19 test reports most new sites failing. Earlier 23-link passes covered only four
sites and did not validate the full addition. The original native matrix selected only sixteen
of the 39 added adapters, and its summary allowed zero-source/no-decoded-video groups to finish
green. That coverage and success criterion were insufficient.

## Current public-page observations

All 39 added adapter roots were requested freshly through the available research transport.
Thirty-one returned HTTP 200. This proves neither catalogue validity nor stream extraction or
playback. An API root returning 404 is not evidence that its API paths are broken. Redirected
catalogue hosts are retained. None of the HTTP errors below establishes permanent closure.

| Adapter | Root observation | Final host | Current playback verification |
| --- | --- | --- | --- |
| vaplayer | 200 | streamdata.vaplayer.ru | Pending |
| primesrc | 200 | primesrc.me | Pending |
| fshare | 200 | fsharetv.cc | Pending |
| videasy | 200 | www.vidy.st | Pending |
| meowtv | 200 | meowtv.ru | Pending |
| vidup | 200 | vidup.to | Pending |
| vidcore | 200 | vidcore.io | Pending |
| vidfast | 200 | vidfast.vc | Pending |
| onetouchtv | 404 | api3.devcorp.me | Pending |
| cinejoy | 200 | cinejoy.pk | Pending |
| hexa | 200 | hexa.su | Pending |
| projectfreetv_sx | TimeoutError | not reached | Pending |
| akwam | 200 | akwam.it | Pending |
| asiaflix | 200 | asiaflix.net | Pending |
| vidrock | 503 | vidrock.ru | Pending |
| peachify | 523 | peachify.top | Pending |
| vidzee | 200 | player.vidzee.wtf | Pending |
| moviebox | 404 | h5-api.aoneroom.com | Pending |
| allmovieland | 200 | allmovieland.art | Pending |
| multimovies | 200 | m1.multimovies.courses | Pending |
| toonstream | 200 | toonstream.us | Pending |
| animesalt | 200 | animesalt.cx | Pending |
| rtally | 200 | rtally.lol | Pending |
| kisskh | 200 | kisskh.do | Pending |
| bollyflix | 200 | bollyflix.church | Pending |
| topmovies | 503 | moviesleech.club | Pending |
| moviesmod | 200 | moviesmod.meme | Pending |
| moviesdrive | 200 | new1.moviesdrive.cyou | Pending |
| uhdmovies | 200 | uhdmovies.beauty | Pending |
| dudefilms | 200 | dudefilms.surf | Pending |
| vegamovies | 200 | 1vegamovies.shop | Pending |
| rogmovies | 200 | rogmovies.im | Pending |
| hindmoviez | 200 | hindmovie.tax | Pending |
| hdhub4u | 200 | new10.hdhub4u.cl | Pending |
| zinkmovies | 403 | new6.zinkmovies.mobi | Pending |
| fibwatch | 200 | fibwatch.art | Pending |
| mlsbd | 200 | mlsbd.co | Pending |
| skymovies | 200 | new4.skymovieshd.clinic | Pending |
| m4ufree_lat | 500 | ww5.m4ufree.lat | Pending |

## Concrete protocol correction

AllMovieLand's hard-coded `allmovieland.link/player.js?v=60%20128` returned 403. Its current
catalogue alias `allmovieland.one/player.js` redirected to `allmovieland.art/player.js`, returning
200 and the expected literal `AwsIndStreamDomain` declaration. The new adapter follows that
catalogue script route and derives the catalogue Referer from the final script URL instead of
using the retired host/version. Original season/episode matching and CSRF origin boundaries
remain intact. A direct public research request to the declared player's Inception page returned
503, so this correction is not a successful live-playback claim. The offline regression verifies
redirected Referer retention, exact episode selection and exclusion of the catalogue CSRF token
from media headers.

## Validation gap and revised audit

The expanded native matrix selects all 39 added adapters, omitting only media modes the registry
explicitly does not support. Every lookup site gets a separate outcome. Zero verified sources,
missing reports and unsuccessful individual playback outcomes now fail the audit. Fixed audit
titles can be absent from niche catalogues: such outcomes remain unverified, not proof of a
broken site. Site-appropriate current catalogue samples and current client/request inspection
remain necessary before declaring those methods correct.

Unattended external-decoder uploads remain prohibited. The native audit now explicitly carries
a per-query decoder-block flag into its isolated adapter scope and rejects enc-dec.app requests
before networking; scope is restored afterward. Production queries without that flag retain their
existing behavior. Blocked decoder-dependent adapters are unverified, not successful.

The workspace's unchanged guarded movie trace returned 37 DNS failures and one existing external
API block for its 38 movie-enabled adapters. Those DNS failures are workspace transport failures,
not proof that 37 sites are down. Research transport and guarded/native playback are separate
results; no URL-validation or credential-origin restriction was removed.

Local checks: 145 Python fixtures passed; core validation is recorded in the PR. Final Android
CI and expanded native audit results are pending. The existing player and established provider
implementations are unchanged. No new release has been authorised or published for this work.
