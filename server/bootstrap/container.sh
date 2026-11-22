#!/usr/bin/env bash
# MovieHub server setup, part 2: runs INSIDE the new container as root (host.sh starts it).
# Installs tools, Forgejo (private git + workflows), its runner, the signing key, the gate and the
# health checker settings. Passwords and keys go to /root/moviehub-credentials.txt (root only).
set -euo pipefail
. /root/moviehub-env
export DEBIAN_FRONTEND=noninteractive
export HOME=/root   # pct exec starts without HOME; git needs it
step() { echo; echo "==== [container] $* ($(date +%H:%M:%S))"; }
fail() { echo "SETUP STOPPED: $*"; exit 1; }
rand() { openssl rand -hex "${1:-24}"; }
CREDS=/root/moviehub-credentials.txt
umask 022

step "Installing packages"
apt-get update -q
apt-get install -y -q git python3 python3-pip openjdk-17-jdk-headless ffmpeg curl openssl ca-certificates jq sqlite3 gnupg
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -q nodejs
fi
pip install -q --break-system-packages requests==2.32.5 six==1.17.0

step "Folders and the JavaScript interpreter for the Live TV probe"
id moviehub >/dev/null 2>&1 || adduser --system --group --home /srv/moviehub --shell /bin/bash moviehub
mkdir -p /srv/moviehub/{feed/app,feed/experimental,feed/providers,feed/scrapers,feed/scrapers-experimental,keys,health,tools}
curl -fsSL -o /srv/moviehub/tools/rhino-1.9.1.jar https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.9.1/rhino-1.9.1.jar
echo "12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975  /srv/moviehub/tools/rhino-1.9.1.jar" | sha256sum --check

step "Signing key (stays on this server; back it up to a USB stick)"
if [ ! -f /srv/moviehub/keys/signing.pem ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /srv/moviehub/keys/signing.pem 2>/dev/null
fi
PUBKEY=$(openssl pkey -in /srv/moviehub/keys/signing.pem -pubout -outform DER | base64 -w0)
chmod 700 /srv/moviehub/keys; chmod 600 /srv/moviehub/keys/signing.pem

step "Forgejo"
id git >/dev/null 2>&1 || adduser --system --group --home /home/git --shell /bin/bash git
FJ_VER=$(curl -fsSL https://codeberg.org/api/v1/repos/forgejo/forgejo/releases/latest | jq -r .tag_name | sed 's/^v//')
[ -n "$FJ_VER" ] && [ "$FJ_VER" != null ] || fail "Could not find the latest Forgejo version."
# Download beside the old file and swap it in, so a re-run works while Forgejo is running.
curl -fsSL -o /usr/local/bin/forgejo.new "https://codeberg.org/forgejo/forgejo/releases/download/v$FJ_VER/forgejo-$FJ_VER-linux-amd64"
chmod 755 /usr/local/bin/forgejo.new && mv -f /usr/local/bin/forgejo.new /usr/local/bin/forgejo
mkdir -p /var/lib/forgejo /etc/forgejo
chown git:git /var/lib/forgejo
chmod 750 /var/lib/forgejo
chown root:git /etc/forgejo; chmod 770 /etc/forgejo
if [ ! -f /etc/forgejo/app.ini ]; then
    cat > /etc/forgejo/app.ini <<EOF
APP_NAME = MovieHub
RUN_USER = git
RUN_MODE = prod
WORK_PATH = /var/lib/forgejo

[database]
DB_TYPE = sqlite3
PATH = /var/lib/forgejo/data/forgejo.db

[server]
HTTP_PORT = 3000
DOMAIN = $CONTAINER_IP
ROOT_URL = http://$CONTAINER_IP:3000/
DISABLE_SSH = true
LFS_START_SERVER = false
LFS_JWT_SECRET = $(/usr/local/bin/forgejo generate secret LFS_JWT_SECRET)

[oauth2]
JWT_SECRET = $(/usr/local/bin/forgejo generate secret JWT_SECRET)

[security]
INSTALL_LOCK = true
SECRET_KEY = $(rand 32)
INTERNAL_TOKEN = $(/usr/local/bin/forgejo generate secret INTERNAL_TOKEN)

[service]
DISABLE_REGISTRATION = true
REQUIRE_SIGNIN_VIEW = true

[repository]
DEFAULT_PRIVATE = private

[actions]
ENABLED = true
EOF
fi
# Forgejo may add generated settings to its own config on first start, so it must be able to write it.
chown root:git /etc/forgejo/app.ini; chmod 660 /etc/forgejo/app.ini
cat > /etc/systemd/system/forgejo.service <<'EOF'
[Unit]
Description=Forgejo (MovieHub)
After=network-online.target
Wants=network-online.target
[Service]
User=git
Group=git
WorkingDirectory=/var/lib/forgejo
Environment=USER=git HOME=/home/git
ExecStart=/usr/local/bin/forgejo web --config /etc/forgejo/app.ini
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable forgejo
systemctl reset-failed forgejo 2>/dev/null || true
systemctl restart forgejo
for _ in $(seq 1 60); do curl -fs http://127.0.0.1:3000/api/healthz >/dev/null && break; sleep 2; done
curl -fs http://127.0.0.1:3000/api/healthz >/dev/null || fail "Forgejo did not start (journalctl -u forgejo)."

FJ="runuser -u git -- /usr/local/bin/forgejo --config /etc/forgejo/app.ini --work-path /var/lib/forgejo"
FJCLI="runuser -u git -- env FORGEJO_WORK_DIR=/var/lib/forgejo /usr/local/bin/forgejo forgejo-cli"
ADMIN=billy
ADMINPW=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
if ! $FJ admin user list | awk '{print $2}' | grep -qx "$ADMIN"; then
    $FJ admin user create --admin --username "$ADMIN" --password "$ADMINPW" \
        --email moviehub@localhost --must-change-password=false
else
    $FJ admin user change-password --username "$ADMIN" --password "$ADMINPW" --must-change-password=false
fi
API=http://127.0.0.1:3000/api/v1
auth=(-u "$ADMIN:$ADMINPW")

step "Copying the MovieHub repository from GitHub into Forgejo (private)"
if ! curl -fs "${auth[@]}" "$API/repos/$ADMIN/moviehub" >/dev/null; then
    curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X POST "$API/repos/migrate" -d "{
        \"clone_addr\": \"https://github.com/billyke2000s/moviehub\", \"repo_name\": \"moviehub\",
        \"repo_owner\": \"$ADMIN\", \"private\": true, \"mirror\": false, \"service\": \"git\"}" >/dev/null
fi

step "Token, secrets and settings for the workflows"
TOKEN=$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X POST "$API/users/$ADMIN/tokens" \
    -d "{\"name\": \"health-$(date +%s)\", \"scopes\": [\"write:repository\", \"write:issue\"]}" | jq -r .sha1)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || fail "Could not create the workflow token."
# Keep the existing alert topic on a re-run (your phone is subscribed to it).
NTFY_TOPIC=$(sed -n 's|^NTFY_URL=https://ntfy.sh/||p' /etc/moviehub-watchdog.env 2>/dev/null || true)
[ -n "$NTFY_TOPIC" ] || NTFY_TOPIC="moviehub-$(rand 12)"
NTFY_URL="https://ntfy.sh/$NTFY_TOPIC"
put_secret() { curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X PUT \
    "$API/repos/$ADMIN/moviehub/actions/secrets/$1" -d "{\"data\": \"$2\"}" >/dev/null \
    || echo "WARNING: could not set secret $1 (set it in repository Settings > Actions > Secrets)"; }
put_var() { curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X POST \
    "$API/repos/$ADMIN/moviehub/actions/variables/$1" -d "{\"value\": \"$2\"}" >/dev/null \
    || echo "WARNING: could not set variable $1 (set it in repository Settings > Actions > Variables)"; }
put_secret HEALTH_TOKEN "$TOKEN"
put_secret NTFY_URL "$NTFY_URL"
put_var MOVIEHUB_FORGEJO_URL "http://127.0.0.1:3000"

step "Forgejo runner (runs the workflows directly in this container as user moviehub)"
RUN_VER=$(curl -fsSL https://code.forgejo.org/api/v1/repos/forgejo/runner/releases/latest | jq -r .tag_name | sed 's/^v//')
[ -n "$RUN_VER" ] && [ "$RUN_VER" != null ] || fail "Could not find the latest runner version."
curl -fsSL -o /usr/local/bin/forgejo-runner.new \
    "https://code.forgejo.org/forgejo/runner/releases/download/v$RUN_VER/forgejo-runner-$RUN_VER-linux-amd64"
chmod 755 /usr/local/bin/forgejo-runner.new && mv -f /usr/local/bin/forgejo-runner.new /usr/local/bin/forgejo-runner
RUNNER_HOME=/srv/moviehub/runner
mkdir -p "$RUNNER_HOME"; chown moviehub:moviehub "$RUNNER_HOME"
if [ ! -f "$RUNNER_HOME/.runner" ]; then
    SECRET=$(rand 20)
    # Two documented ways to register; try the offline one first, then a registration token.
    if $FJCLI actions register --config /etc/forgejo/app.ini --name moviehub --labels moviehub:host --secret "$SECRET" >/dev/null 2>&1 \
        && runuser -u moviehub -- bash -c "cd $RUNNER_HOME && /usr/local/bin/forgejo-runner create-runner-file \
            --instance http://127.0.0.1:3000 --secret $SECRET --name moviehub" >/dev/null; then
        echo "Runner registered (offline secret)."
    else
        RTOKEN=$($FJ actions generate-runner-token 2>/dev/null | tail -1)
        [ -n "$RTOKEN" ] || fail "Could not register the workflow runner."
        runuser -u moviehub -- bash -c "cd $RUNNER_HOME && /usr/local/bin/forgejo-runner register --no-interactive \
            --instance http://127.0.0.1:3000 --token $RTOKEN --name moviehub --labels moviehub:host" >/dev/null
        echo "Runner registered (token)."
    fi
    runuser -u moviehub -- bash -c "cd $RUNNER_HOME && /usr/local/bin/forgejo-runner generate-config > config.yml"
fi
cat > /etc/systemd/system/forgejo-runner.service <<EOF
[Unit]
Description=Forgejo runner (MovieHub)
After=forgejo.service
Requires=forgejo.service
[Service]
User=moviehub
WorkingDirectory=$RUNNER_HOME
ExecStart=/usr/local/bin/forgejo-runner daemon --config $RUNNER_HOME/config.yml
Restart=always
[Install]
WantedBy=multi-user.target
EOF

step "Gate (install codes + update feed)"
# Keep the existing feed key on a re-run (it is built into the app).
FEED_KEY=$(sed -n 's/^FEED_KEY=//p' /etc/moviehub-gate.env 2>/dev/null || true)
[ -n "$FEED_KEY" ] || FEED_KEY=$(rand 24)
[ -d /srv/moviehub/repo/.git ] || git clone -q https://github.com/billyke2000s/moviehub /srv/moviehub/repo
git config --global --add safe.directory /srv/moviehub/repo
git -C /srv/moviehub/repo checkout -q "$BRANCH" 2>/dev/null || true
git -C /srv/moviehub/repo pull -q --ff-only || echo "WARNING: could not update /srv/moviehub/repo"
# Bring Forgejo's copy up to date with GitHub (fast-forward only; never overwrites newer work).
git -C /srv/moviehub/repo push -q "http://$ADMIN:$ADMINPW@127.0.0.1:3000/$ADMIN/moviehub.git" "HEAD:refs/heads/main" \
    || echo "WARNING: could not update Forgejo's copy (it has newer commits than GitHub?)"
cat > /etc/moviehub-gate.env <<EOF
FEED_DIR=/srv/moviehub/feed
FEED_KEY=$FEED_KEY
EOF
chmod 600 /etc/moviehub-gate.env
cp /srv/moviehub/repo/server/gate/moviehub-gate.service /etc/systemd/system/
chown -R moviehub:moviehub /srv/moviehub
chown root:root /srv/moviehub/keys
chown moviehub:moviehub /srv/moviehub/keys/signing.pem
chmod 711 /srv/moviehub/keys
systemctl daemon-reload
systemctl enable forgejo-runner moviehub-gate
systemctl restart forgejo-runner moviehub-gate

step "Watchdog (pings your phone if the server itself needs you)"
printf 'NTFY_URL=%s\n' "$NTFY_URL" > /etc/moviehub-watchdog.env
chmod 600 /etc/moviehub-watchdog.env
cat > /etc/systemd/system/moviehub-watchdog.service <<'UNIT'
[Unit]
Description=MovieHub watchdog
[Service]
Type=oneshot
User=moviehub
EnvironmentFile=/etc/moviehub-watchdog.env
ExecStart=/usr/bin/python3 /srv/moviehub/repo/server/health/watchdog.py
UNIT
cat > /etc/systemd/system/moviehub-watchdog.timer <<'UNIT'
[Unit]
Description=Run the MovieHub watchdog every 30 minutes
[Timer]
OnBootSec=10min
OnUnitActiveSec=30min
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now moviehub-watchdog.timer

step "Codex (GPT) command line"
npm install -g @openai/codex >/dev/null 2>&1 || echo "WARNING: Codex install failed; install later with: npm install -g @openai/codex"

step "Writing $CREDS"
cat > "$CREDS" <<EOF
MovieHub server credentials. Readable by root only. Change the passwords whenever you like.

Container root password : $ROOTPW
Forgejo                 : http://$CONTAINER_IP:3000   user $ADMIN   password $ADMINPW
Code page (phone)       : http://$CONTAINER_IP:8081   (home network only; Add to Home screen)
Public gate             : http://$CONTAINER_IP:8080   (put Cloudflare in front of this)
Feed key (app build)    : $FEED_KEY
Phone alerts (ntfy app) : subscribe to topic  $NTFY_TOPIC  on ntfy.sh
Signing public key      : $PUBKEY
Signing PRIVATE key     : /srv/moviehub/keys/signing.pem   <- copy to a USB stick, keep it safe

Still to do yourself: Codex login (runuser -u moviehub -- codex), Cloudflare, GitHub secrets
MOVIEHUB_FEED_URL / MOVIEHUB_FEED_KEY for the switchover release.
EOF
chmod 600 "$CREDS"
rm -f /root/moviehub-env

step "Checks"
for s in forgejo forgejo-runner moviehub-gate moviehub-watchdog.timer; do printf '%-16s %s\n' "$s" "$(systemctl is-active $s)"; done
curl -fs -o /dev/null -w 'gate public page: HTTP %{http_code}\n' http://127.0.0.1:8080/ || echo "gate public page: NOT ANSWERING"
curl -fs -o /dev/null -w 'gate code page:   HTTP %{http_code}\n' http://127.0.0.1:8081/ || echo "gate code page: NOT ANSWERING"
echo
echo "PUBLIC_SIGNING_KEY=$PUBKEY"
echo "MOVIEHUB SETUP FINISHED. Forgejo: http://$CONTAINER_IP:3000  Credentials: $CREDS"
