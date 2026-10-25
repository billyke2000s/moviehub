"""Summarises run-memory-probe.sh samples as GitHub annotations and a job summary table.

Usage: summarise-memory-probe.py <reports/memory dir> <test exit code>
Annotations are used because they can be read without downloading logs or artifacts.
"""

import collections
import csv
import os
import pathlib
import re
import sys

out = pathlib.Path(sys.argv[1])
result = sys.argv[2]
order = ["build", "launch", "refresh", "guide", "watch", "back-to-guide", "done"]

app_total = collections.defaultdict(int)  # (seconds, phase) -> PSS of all MovieHub processes
main_peak = {}  # phase -> peak PSS of the main process
total_peak = {}  # phase -> peak PSS of all MovieHub processes together
avail_min = {}  # phase -> lowest device MemAvailable
device_total = 0
processes = set()
with open(out / "samples.csv") as f:
    for row in csv.DictReader(f):
        phase = row["phase"]
        if row["device_total_kb"]:
            device_total = int(row["device_total_kb"])
        if row["process"] == "DEVICE":
            if row["device_available_kb"]:
                avail = int(row["device_available_kb"])
                avail_min[phase] = min(avail, avail_min.get(phase, avail))
            continue
        kb = int(row["pss_kb"])
        processes.add(row["process"])
        app_total[(row["seconds"], phase)] += kb
        if ":" not in row["process"]:
            main_peak[phase] = max(kb, main_peak.get(phase, 0))
for (_, phase), kb in app_total.items():
    total_peak[phase] = max(kb, total_peak.get(phase, 0))

probe = (out / "probe.log").read_text(errors="replace") if (out / "probe.log").exists() else ""


def found(key):
    match = re.search(key + r"=(\S+)", probe)
    return match.group(1) if match else "unknown"


kills = [l for l in (out / "kills.log").read_text(errors="replace").splitlines() if l.strip()] if (
    out / "kills.log"
).exists() else []
mb = lambda kb: f"{kb / 1024:.0f} MB"

lines = [
    f"Emulator RAM seen by Android: {mb(device_total)}",
    f"Test result: {'passed (MovieHub kept running)' if result == '0' else 'FAILED, exit ' + result}",
    f"Refresh finished: {found('refreshFinished')}; channels: {found('channels')}; picture played: {found('picturePlayed')}",
    f"MovieHub processes seen: {', '.join(sorted(processes)) or 'none'}",
    f"Kill / low-memory events for MovieHub: {len(kills)}",
]
table = ["| Phase | Main app peak | All MovieHub processes peak | Lowest free on device |", "|---|---|---|---|"]
for phase in order:
    if phase in total_peak or phase in avail_min:
        table.append(
            f"| {phase} | {mb(main_peak.get(phase, 0))} | {mb(total_peak.get(phase, 0))} | "
            f"{mb(avail_min[phase]) if phase in avail_min else '?'} |"
        )
for line in lines:
    print("::notice title=Memory probe::" + line)
for row in table[2:]:
    print("::notice title=Memory by phase::" + row)
for line in kills[:5]:
    print("::warning title=Memory probe kill::" + line[:300])
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as f:
        f.write("## MovieHub memory probe (2 GB emulator)\n\n" + "\n".join("- " + l for l in lines) + "\n\n")
        f.write("\n".join(table) + "\n")
print("\n".join(lines + table))
