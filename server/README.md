# MovieHub home server

The server checks Live TV hourly and film discovery every six hours. When it opens a broken-source
issue, the repair worker can propose a code change. The server saves that change on an issue branch.
Only an independent playback comparison can open a repair review. Boxes keep using their installed
providers while a repair is pending.

New installs: someone types your Downloader code → "Enter login" → the code your phone just made →
the APK downloads. The code works once, for 5 minutes.

Items marked **[check]** are commands I could not run from here. Check them against the tool's own
docs the first time.

## Quick setup (one command)

On the Proxmox host shell (as root):

```sh
curl -fsSL https://raw.githubusercontent.com/billyke2000s/moviehub/main/server/bootstrap/host.sh -o /root/mh.sh && bash /root/mh.sh
```

It does steps 1–4 and 6 below by itself: creates one new capped container (1.5 GB RAM + 1 GB swap by default; never touches others),
installs Forgejo with a private copy of the repo, the runner, the signing key, the gate and Codex,
and writes every password and key to `/root/moviehub-credentials.txt` inside the container. Log:
`/root/moviehub-bootstrap.log` on the host. Still manual: Codex sign-in, Cloudflare (step 5),
the switchover (step 7). The sections below explain each part.

## 1. Container (Proxmox)

One unprivileged Debian 12 container, capped so it can never starve Minecraft:

```sh
# On the Proxmox host. 110 = any free ID; adjust storage names to yours.
pveam update && pveam download local debian-12-standard_12.7-1_amd64.tar.zst   # [check] exact template name: pveam available
pct create 110 local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst \
  --hostname moviehub --cores 4 --memory 4096 --swap 512 \
  --rootfs local-lvm:32 --net0 name=eth0,bridge=vmbr0,ip=dhcp,ip6=auto \
  --unprivileged 1 --onboot 1
pct set 110 --cpuunits 50     # lower CPU priority than Minecraft (default 100)
pct start 110 && pct enter 110
```

Inside the container:

```sh
apt update && apt install -y git python3 python3-pip openjdk-17-jdk-headless ffmpeg curl openssl
pip install --break-system-packages requests==2.32.5 six==1.17.0
# Node 22 (Debian's is too old for the Live TV probe), from nodejs.org or NodeSource  [check]
adduser --system --group --home /srv/moviehub moviehub
mkdir -p /srv/moviehub/{feed/app,feed/scrapers,keys,health,tools}
curl -fL -o /srv/moviehub/tools/rhino-1.8.0.jar https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.8.0/rhino-1.8.0.jar
echo "e7ff37ec00b4c19ea16f42b5b3a601616d559dbb76e65bc9d094bd6bda2a925d  /srv/moviehub/tools/rhino-1.8.0.jar" | sha256sum --check
chown -R moviehub: /srv/moviehub
```

## 2. Forgejo (your own GitHub) and its runner

1. Install Forgejo from <https://forgejo.org/docs/latest/admin/installation/> (single binary +
   systemd). Keep it on your home network only: do not route it through Cloudflare.
2. In Forgejo: **+ → New migration → GitHub**, paste `https://github.com/billyke2000s/moviehub`,
   and tick "This repository will be a mirror" off. Make it **private**.
3. Repository **Settings → Actions**: enable Actions.
4. Install `forgejo-runner` (<https://forgejo.org/docs/latest/admin/actions/>) as user `moviehub`,
   registered with the label `moviehub:host`, so jobs run directly in the container and can reach
   `/srv/moviehub` **[check]**:
   ```sh
   forgejo-runner register --no-interactive --instance http://localhost:3000 \
     --token <token from Site administration → Actions → Runners> --name moviehub --labels moviehub:host
   ```
5. Repository **Settings → Actions → Variables**: `MOVIEHUB_FORGEJO_URL` = `http://localhost:3000`.
   **Secrets**: `HEALTH_TOKEN` (a token from your user settings with repository and issue write
   access) and `NTFY_URL` (step 6).
6. Phone alerts: install the **ntfy** app, subscribe to a topic with a long random name
   (e.g. `moviehub-` plus four random words). `NTFY_URL` = `https://ntfy.sh/<that topic>`.
   Anyone who guesses the name can read it, so keep it long.
7. Clone the repo for the gate: `git clone http://localhost:3000/<you>/moviehub /srv/moviehub/repo`.

## 3. Signing key (do this once, then back it up)

The boxes only install scraper packages signed with this key. **Lose it and every box needs a
reinstall. Leak it and someone could push code to every box.**

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /srv/moviehub/keys/signing.pem
chown moviehub: /srv/moviehub/keys/signing.pem && chmod 600 /srv/moviehub/keys/signing.pem
openssl pkey -in /srv/moviehub/keys/signing.pem -pubout -outform DER | base64 -w0; echo
```

Put the last line (the public key, safe to share) into
`android-tv-launcher/app/src/main/assets/providers/trust.json` as `"publicKey"` and commit it.
Copy `signing.pem` to a USB stick and keep it somewhere safe, off the server.

This same key also switches on the Live TV provider updater, which was built but had no key.

## 4. Feed key and the gate

```sh
openssl rand -hex 24      # the feed key; goes into the app build and the gate
cat > /etc/moviehub-gate.env <<EOF
FEED_DIR=/srv/moviehub/feed
FEED_KEY=<the key>
TRUST_CLOUDFLARE=1
EOF
chmod 600 /etc/moviehub-gate.env
cp /srv/moviehub/repo/server/gate/moviehub-gate.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now moviehub-gate
```

- Public page: port **8080** (Cloudflare only). Code page: port **8081**, **home network only**.
  Open `http://<container IP>:8081` on your phone and use **Add to Home screen**.
- Feed layout: `feed/app/` (home.apk + update.json), `feed/scrapers/`, `feed/providers/`,
  `feed/experimental/`, `feed/scrapers-experimental/`.

## 5. Cloudflare

Use a **new domain on a separate Cloudflare account**, not linked to your other sites.

Easiest: a Cloudflare Tunnel (`cloudflared`) in the container, so no router ports open and your
home IP stays hidden: one public hostname, e.g. `tv.<domain>` → `http://localhost:8080` **[check]**.
Alternatively an AAAA record to the container's IPv6 with Cloudflare's proxy on, and only port 8080
allowed through your router's firewall.

Then:
- Downloader code: register `https://tv.<domain>/` at aftvnews.com/downloader-codes **[check]**.
- App feed address: `https://tv.<domain>/feed/` (used in step 7).

## 6. Codex (GPT) for automatic fixes

```sh
npm install -g @openai/codex        # [check] OpenAI's install instructions
sudo -u moviehub -H codex           # first run: choose "Sign in with ChatGPT"   [check]
```

`auto-fix.yml` runs `codex exec --full-auto "<task>"` **[check]**. If your Codex version uses
different flags, set the repository variable `CODEX_CMD`. Codex needs internet access to look at
the sites; if its sandbox blocks the network, allow it in Codex's settings.

Run **Actions → Auto-fix broken source → Run** with the issue number. The worker must edit files.
A text-only diagnosis stays on the issue. If a candidate exists on `repair/issue-N`, running the
same issue again tests that commit. If main moved, rebase the single candidate commit before retrying.

Verification uses separate clean builds of the original revision, candidate, and original revision
again. A review opens when the same case from the incident fails, plays, then fails again.
Live TV uses the rebuilt provider package and FFmpeg audio/video frame counts for the same channel ID.
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

- Phone says **"X broken"**: nothing to do. Codex is already trying.
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
