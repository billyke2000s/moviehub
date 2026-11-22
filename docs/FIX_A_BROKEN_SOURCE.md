# Fix a broken source

This is the procedure for any AI (Codex/GPT, Claude, others) or person fixing a source that the
health checker reported as a `[broken source]` issue. It exists because fixes used to be claimed
from reading code alone and then failed on the real site. **A fix counts only when the live
command below finds working streams again on the server.**

## 1. Reproduce it first

Work on the home server, in the Forgejo checkout, so the site sees the same UK home connection as
the family's boxes. Copy the reproduce command from the issue and run it **before changing
anything**:

```sh
# Film/series site (id from the issue, e.g. free99:levidia_ch)
python3 android-tv-launcher/tests/probe-free-vod.py --provider <id> --movie "Inception" 2010 tt1375666 27205

# Live TV provider (id from the issue, e.g. dlhd)
RHINO_JAR=/srv/moviehub/tools/rhino-1.9.1.jar node provider-engine/tools/live-probe.mjs <id>
```

If it now works, the site had a temporary outage: comment that on the issue and stop.

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

Run the same reproduce command again. It must report `streams` greater than 0 (film sites) or a
sample with `play: ok` (Live TV). Then run the offline checks:

```sh
python3 android-tv-launcher/tests/test_free_vod.py
python3 android-tv-launcher/tests/test_scraper_loader.py
```

If you cannot get streams back, open no pull request. Comment on the issue with what you found.

## 4. Open the pull request

Title: `Fix <source id>: <what changed>`. In the body, paste the command you ran and the actual
output line showing streams (not a description of it), and the issue number (`Fixes #N`).

Never commit keys, tokens, account details or playback URLs with tokens in them.

## What happens after the owner merges

- **Film/series scrapers** (`android-tv-launcher/app/src/main/python/`): `publish-scrapers.yml` on
  the server checks them, signs a package and puts it in the update feed. Boxes install it in the
  background within about 6 hours, with no app update. A package that fails to load on a box is
  thrown away automatically and the box keeps its previous scrapers.
- **Live TV providers** (`provider-engine/`): ship in the signed provider package, or the next app
  release.
- **Java/app code**: needs a normal app release. If a scraper fix needs a Java change too, say so
  in the pull request; raise `ScraperBundle.API` in the same change if the Java-to-Python contract
  changed.
