#!/usr/bin/env python3
"""Public browser targets derived from MovieHub's actual enabled website registry.

VOD root URLs are *landing-page diagnostics*, not verified watch routes. Only the
known user-supplied PrimeWire watch page is clicked as a player-page fixture.
No private M3U, Xtream, Nuvio, cookies or signed media URLs enter this inventory.
"""
import argparse
import json
import re
import sys
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "android-tv-launcher/app/src/main/python"
sys.path.insert(0, str(APP))
import moviehub_vod


LIVE = [
    ("dlhd", "https://thedaddy.li/"),
    ("zlive", "https://zlive.st/"),
    ("freeshot", "https://freeshot.live/"),
    ("tubi", "https://tubitv.com/"),
    ("plex", "https://watch.plex.tv/"),
    ("roku", "https://therokuchannel.roku.com/"),
    ("stirr", "https://stirr.com/"),
    ("localnow", "https://localnow.com/"),
]


def targets(kind, shard, total):
    if kind == "live":
        return [
            dict(id=name, url=url, kind="landing", maxClicks=0)
            for index, (name, url) in enumerate(LIVE) if index % total == shard
        ]
    rows = json.loads(moviehub_vod.search_providers())
    destinations = [
        dict(id="primewire-player", url="https://primewire1.buzz/watch/the-odyssey",
             kind="player", maxClicks=4)
    ]
    seen = {"primewire1.buzz"}
    for entry in rows:
        site = entry.get("site", "").strip().lower()
        if not re.fullmatch(r"[a-z0-9.-]{4,200}", site) or site in seen:
            continue
        seen.add(site)
        name = re.sub(r"[^a-z0-9:_-]", "-", entry["id"])
        destinations.append(dict(id="vod-" + name, url="https://" + site + "/",
                                 kind="landing", maxClicks=0))
    return [row for index, row in enumerate(destinations) if index % total == shard]


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", choices=("live", "vod"), required=True)
    parser.add_argument("--shard", default="0/1")
    args = parser.parse_args()
    try:
        part, count = (int(n) for n in args.shard.split("/"))
        if not 1 <= count <= 16 or not 0 <= part < count:
            raise ValueError
    except ValueError:
        parser.error("expected --shard INDEX/COUNT")
    print(json.dumps(targets(args.kind, part, count), separators=(",", ":")))
