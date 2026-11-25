# MovieHub home server

The server checks Live TV hourly and film discovery every six hours. When it opens a broken-source
issue, the repair worker can propose a code change. The server saves that change on an issue branch.
Only an independent playback comparison can open a repair review. Boxes keep using their installed
providers while a repair is pending.

New installs: someone types your Downloader code → "Enter login" → the code your phone just made →
the APK downloads. The code works once, for 5 minutes.

## Test before installing Forgejo

From a Linux checkout with Git, Node, FFmpeg and Python 3.11+, run the verification suite:

```sh
python3 -m unittest discover -s server/health -v
```

It uses a temporary Git remote and locally generated video. To test actual Forgejo permissions,
run the disposable Forgejo check. It starts a signed server and runner on loopback, submits a
harmless job to the runner and removes the test database afterwards.

```sh
mkdir -p build/server-check
bash server/bootstrap/forgejo-binary.sh forgejo 17.0.2 "$PWD/build/server-check/forgejo"
bash server/bootstrap/forgejo-binary.sh runner 13.2.2 "$PWD/build/server-check/runner"
FORGEJO_TEST_BINARY="$PWD/build/server-check/forgejo" FORGEJO_TEST_RUNNER="$PWD/build/server-check/runner" \
    python3 server/bootstrap/tests/test_forgejo_install.py
```

## Quick setup on Proxmox

Run from the Proxmox host as root. Set `rev` to the full 40-character commit you reviewed.
The host and guest scripts then come from that same revision.

```sh
curl -fL "https://raw.githubusercontent.com/billyke2000s/moviehub/$rev/server/bootstrap/host.sh" -o /root/mh.sh &&
MOVIEHUB_REVISION="$rev" bash /root/mh.sh
```

The installer creates one unprivileged Debian 12 guest with 4 cores, 1.5 GB RAM and 1 GB swap.
Set `MOVIEHUB_MEMORY` and `MOVIEHUB_SWAP` in MiB before first creation to change the limits.
The defaults cover the server services. A local model and Android emulator need their own measured memory budget.

Retry the same command after an interruption. The installer records the guest ID and a matching
marker under `/root/.moviehub-bootstrap`, reuses its credentials, and stops on a mismatched guest.
A successful installation writes `/root/moviehub-credentials.txt` inside the guest. The host
log is `/root/moviehub-bootstrap.log`. Existing older installations have a different credential layout.

## Repository and signing

Forgejo and its repository-scoped runner are installed inside the guest. Open the Forgejo address
from the credentials file on your LAN. The repair account can write candidate branches and issues.
The owner controls merges to protected `main`. The install-code page is on port 8081 and the public
download page is on 8080. Keep the Forgejo UI and port 8081 private to the LAN.

The signing key belongs to `moviehub-release`. The runner can request a release with its exact
commit ID. The publisher fetches protected main privately, rebuilds in a clean directory and
checks that revision again before installing the signed files. The gate reads the feed as a third user.
Back up `/srv/moviehub/keys/signing.pem` securely. Put the **public** key from the credentials file
in the Android trust configuration before shipping an APK that needs these updates.

## 5. Cloudflare

Use a **new domain on a separate Cloudflare account**, not linked to your other sites.

Easiest: a Cloudflare Tunnel (`cloudflared`) in the container, so no router ports open and your
home IP stays hidden: one public hostname, e.g. `tv.<domain>` → `http://localhost:8080` **[check]**.
Alternatively an AAAA record to the container's IPv6 with Cloudflare's proxy on, and only port 8080
allowed through your router's firewall.

Then:
- Downloader code: register `https://tv.<domain>/` at aftvnews.com/downloader-codes **[check]**.
- App feed address: `https://tv.<domain>/feed/` (used in step 7).

## 6. Configure the repair worker

The proposal job requires rootless Podman and a preloaded offline worker image under the
`moviehub` runner account. The image must contain `/usr/bin/python3`, Git, tar, your coding
worker and its local inference dependencies. Configure `MOVIEHUB_WORKER_IMAGE` with the full
`sha256:` image ID and `CODEX_CMD` with the command inside that image. It receives the task as
its last argument and edits the disposable checkout. The worker has no outbound network, so a
cloud CLI login or a model hosted at the server's localhost cannot serve this job.

Rootless Podman needs usable subordinate UID/GID mappings. Check `podman info` as the runner
on your actual guest. A new install supplies neither a model image nor an Android emulator.
If a local worker is unavailable the issue stays unverified. An existing `repair/issue-N` branch
containing a single candidate commit based on current main can still be checked without a model.

Run **Actions → Auto-fix broken source → Run** with the issue number. The worker must edit files.
A text-only diagnosis stays on the issue. If a candidate exists on `repair/issue-N`, running the
same issue again tests that commit. If main moved, rebase the single candidate commit before retrying.

Verification uses separate clean builds of the original revision, candidate, and original revision
again. Every recorded case must fail, play, then fail again. The review branch identifies the exact tested candidate.
Live TV uses the rebuilt provider package and FFmpeg audio/video frame counts for the same channel ID.
Generic decoder errors, network interruptions and missing measurements keep a sample unverified.
Film verification uses the existing Android LibVLC single-source audit, including runtime and
midpoint seek. The PR lists the revisions, changed files and observations. The reviewer confirms
the actual programme or film before closing the incident. Unidentified channels and incomplete
test results remain pending.

**Film verification setup.** Give the Forgejo runner access to a dedicated disposable Android TV
emulator, the Android build tools listed in DEVELOPMENT.md, Gradle, `adb`, and `python` pointing
to Python 3. Set the Forgejo repository variable `MOVIEHUB_AUDIT_EMULATOR` to its exact
`adb devices` serial (for example `emulator-5554`). The verifier removes the two MovieHub debug
packages and their data on that emulator before each build. Use a test emulator reserved for this job.
Without one, film candidates stay saved and unverified. A new server install does not provide an
emulator. `CODEX_MINUTES` controls the worker's time budget independently of verification.

## 7. Switch the boxes over (tomorrow)

1. In **GitHub** repository secrets set `MOVIEHUB_FEED_URL` = `https://tv.<domain>/feed/` and
   `MOVIEHUB_FEED_KEY` = the feed key. Set the same two as Forgejo secrets for later releases.
2. Publish the next release through GitHub as usual. That APK checks your server first and GitHub
   second, and has the signing key from step 3.
3. Copy the release's `home.apk` and `update.json` into `/srv/moviehub/feed/app/`.
4. Run **Actions → Publish scrapers** and **Actions → Publish Live TV providers** once in Forgejo,
   to put the first packages in the feed.
5. Wait about a week, until every box has updated (check the MovieHub version on each one).
   Then make the GitHub repo private or delete it. Boxes that missed the update would need a
   reinstall through the gate.

## Day to day

- Phone says **"X broken"**: nothing to do. The issue records whether a worker ran or which prerequisite is missing.
- Phone says **"playback sample passed"**: open the PR, inspect the before/after results and confirm the content before merging.
- A failed or unverified attempt records its reason on the issue. A saved branch can be tested again.
- New box: open the code page, tap **New code**, read the code out.

## Files

| File | What it is |
| --- | --- |
| `server/gate/gate.py` | Install codes, one-time APK links, keyed update feed |
| `server/health/check_sources.py` | Runs the probes, tracks incidents, sends phone alerts |
| `server/health/auto_fix.py` | Saves proposals and requests review after independent playback verification |
| `server/tools/build-scrapers.py` | Builds and signs a scraper package (refuses the wrong key) |
| `.forgejo/workflows/*.yml` | Health check (Live TV hourly, films 6-hourly), auto-fix on new issues, publish Live TV providers and film scrapers on merge |
