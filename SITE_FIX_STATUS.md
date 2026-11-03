# Site fix checklist

This is the owner's quick per-site checklist. Update it after each repair and retest,
and commit progress to GitHub so another session can resume it.

**“Should be all good g” is reserved for a repaired site whose required playback
checks have passed. No site has that complete verification recorded yet.**
A saved code fix alone does not earn that status.

## Movies and series — PR #211

| Site | Current status | Remaining verification |
| --- | --- | --- |
| 4KHDHub | Episode-selection fix saved; verification pending | Links extracted for both types; confirm correct content, audio, runtime, smoothness and seeking in MovieHub. |
| Akwam | Episode-selection and download-hop fixes saved; verification pending | Movie link extracted; series lookup timed out. Retest both types and complete playback checks. |
| Rtally | Episode-selection fix saved; still failing | Resolve rejected movie media and series SSL failure, then complete playback checks. |
| Multimovies | Dubbed-title and advertised-host fixes saved; still failing | Resolve rejected media for both types, then complete playback checks. |
| Vidzee | Source-protocol fix saved; playback verification incomplete | Real content reviewed and separate movie samples contained non-silent dialogue; Android movie playback stalled. Repair and retest smoothness and complete audio/content evidence. |

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
