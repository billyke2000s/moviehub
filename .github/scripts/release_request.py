"""Decide whether .github/release-request.json asks for a new MovieHub release.

Usage: release_request.py REQUEST_JSON FEED_UPDATE_JSON
Writes action=publish|skip plus the requested values to $GITHUB_OUTPUT (or stdout).
Exits non-zero if the request is malformed or would go backwards.
"""

import json
import os
import re
import sys


def decide(request, feed):
    code = request.get("version_code")
    name = request.get("version_name")
    prs = request.get("requires_merged_prs", [])
    if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
        raise ValueError("version_code must be a positive whole number")
    if not isinstance(name, str) or not re.fullmatch(r"\d+(\.\d+)*", name):
        raise ValueError("version_name must look like 1.0.24")
    if not isinstance(prs, list) or not all(isinstance(n, int) and n > 0 for n in prs):
        raise ValueError("requires_merged_prs must be a list of pull request numbers")
    if feed.get("packageName") != "local.tv.home":
        raise ValueError("published feed is not local.tv.home")
    published = feed["versionCode"]
    if code == published:
        if name != feed.get("versionName"):
            raise ValueError(
                f"version_code {code} is already published as {feed.get('versionName')}"
            )
        return {"action": "skip", "reason": f"{name} (code {code}) is already published"}
    if code < published:
        raise ValueError(f"version_code {code} is lower than the published {published}")
    return {
        "action": "publish",
        "version_code": str(code),
        "version_name": name,
        "prs": ",".join(str(n) for n in prs),
    }


def main():
    with open(sys.argv[1]) as handle:
        request = json.load(handle)
    with open(sys.argv[2]) as handle:
        feed = json.load(handle)
    try:
        result = decide(request, feed)
    except ValueError as error:
        print(f"::error::Release request rejected: {error}")
        return 1
    lines = [f"{key}={value}" for key, value in result.items()]
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as handle:
            handle.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
