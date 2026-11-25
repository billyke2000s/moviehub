# Fix a broken source

This is the procedure for any AI (Codex/GPT, Claude, others) or person fixing a source that the
health checker reported as a `[broken source]` issue. It exists because fixes used to be claimed
from reading code alone and then failed on the real site. Discovery and catalogue checks give diagnostic evidence. A repair review requires a measured
playback improvement on the original failing case.

## 1. Reproduce it first

Work on the home server, in the Forgejo checkout, so the site sees the same UK home connection as
the family's boxes. Copy the reproduce command from the issue and run it **before changing
anything**:

```sh
# Film/series site (id from the issue, e.g. free99:levidia_ch)
python3 android-tv-launcher/tests/probe-free-vod.py --provider <id> --movie "Inception" 2010 tt1375666 27205

# Live TV provider (id from the issue, e.g. dlhd)
RHINO_JAR=/srv/moviehub/tools/rhino-1.8.0.jar node provider-engine/tools/live-probe.mjs <id>
```

Record what the command observed. A returned link or a loaded channel catalogue leaves playback unverified.

## 2. Find out what actually changed

Read the evidence folder named in the issue (`probe.json`, `front-page.html`, `front-page.txt`)
and fetch the live page yourself. Decide which of these it is, and say which in the pull request:

| What happened | Typical fix |
| --- | --- |
| Domain moved | Update the site address in the scraper or `moviehub_providers.json` |
| Page layout changed | Update the selectors/regular expressions in that site's scraper |
| Player/embed changed | Update the extractor for the new player |
| Site blocks or is gone | Do not fake a fix. Say so; the owner decides whether to remove it |

Change only the files for that one source. Do not reformat other files, rename things, or "tidy up".

## 3. Prove it

The Forgejo repair verifier runs clean base, candidate and base-control checkouts for the
reported case. Live TV needs decoded audio and video on the same channel ID. Films need the
pinned Android player audit with audio, video, a plausible runtime and a midpoint seek.
A healthy control, an unavailable player or a different working sample leaves the repair unverified.
For runner setup, see [the server guide](../server/README.md#6-codex-gpt-for-automatic-fixes).

## 4. Review the change

The worker keeps a text-only diagnosis on the issue and a proposed patch on `repair/issue-N`.
Re-running the issue with a saved candidate reuses its commit. The wrapper opens a PR with both
revisions and measured observations after verification. Confirm the content identity before closing
the issue. Keep credentials and signed media URLs out of reports.

## What happens after the owner merges

- **Film/series scrapers** (`android-tv-launcher/app/src/main/python/`): `publish-scrapers.yml` on
  the server checks them, signs a package and puts it in the update feed. Boxes install it in the
  background within about 6 hours, with no app update. A package that fails to load on a box is
  thrown away automatically and the box keeps its previous scrapers.
- **Live TV providers** (`provider-engine/`): `publish-providers.yml` on the server builds, checks
  and signs the provider package and puts it in the feed. A box fetches it as soon as a stream fails
  to play (at most every 15 minutes), otherwise within 12 hours. No app update.
- **Java/app code**: needs a normal app release. If a scraper fix needs a Java change too, say so
  in the pull request; raise `ScraperBundle.API` in the same change if the Java-to-Python contract
  changed.
