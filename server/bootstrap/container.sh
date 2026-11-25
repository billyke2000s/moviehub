#!/usr/bin/env bash
# MovieHub server setup, part 2: runs INSIDE the new container as root (host.sh starts it).
# Installs tools, Forgejo (private git + workflows), its runner, the signing key, the gate and the
# health checker settings. Passwords and keys go to /root/moviehub-credentials.txt (root only).
set -euo pipefail
if [[ -f /root/moviehub-env ]]; then
    . /root/moviehub-env
elif [[ -f /root/.moviehub-install ]]; then
    . /root/.moviehub-install
else
    echo 'Missing installer inputs' >&2; exit 1
fi
export DEBIAN_FRONTEND=noninteractive
export HOME=/root   # pct exec starts without HOME; git needs it
step() { echo; echo "==== [container] $* ($(date +%H:%M:%S))"; }
fail() { echo "SETUP STOPPED: $*"; exit 1; }
rand() { openssl rand -hex "${1:-24}"; }
CREDS=/root/moviehub-credentials.txt
umask 077

# Create the install identity once. A retry keeps the admin password, feed and notification keys.
identity=/root/.moviehub-install
if [[ ! -f "$identity" ]]; then
    [[ ! -e /root/moviehub-credentials.txt && ! -e /etc/forgejo/app.ini ]] || fail "This existing installation has a different credential layout; its credentials were left intact."
    ADMINPW=$(rand 24)
    BOTPW=$(rand 24)
    NTFY_TOPIC="moviehub-$(rand 12)"
    FEED_KEY=$(rand 24)
    tmp=$(mktemp /root/.moviehub-install.XXXXXXXX)
    declare -p CONTAINER_IP ROOTPW BRANCH INSTALL_REV ADMINPW BOTPW NTFY_TOPIC FEED_KEY > "$tmp"
    mv -fT "$tmp" "$identity"
else
    . "$identity"
fi

step "Installing packages"
apt-get update -q
apt-get install -y -q git python3 python3-pip openjdk-17-jdk-headless ffmpeg curl openssl ca-certificates jq sqlite3 gnupg sudo podman uidmap slirp4netns fuse-overlayfs
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o /root/.moviehub-node-key
    fingerprint=$(gpg --batch --with-colons --show-keys /root/.moviehub-node-key |
        awk -F: '$1=="pub"{primary=1; next} $1=="fpr" && primary {print $10; primary=0}')
    [[ "$fingerprint" == 6F71F525282841EEDAF851B42F59B5F99B1BE0B4 ]] || fail "Unexpected NodeSource key."
    install -m 0644 /root/.moviehub-node-key /usr/share/keyrings/moviehub-nodesource.asc
    echo 'deb [arch=amd64 signed-by=/usr/share/keyrings/moviehub-nodesource.asc] https://deb.nodesource.com/node_22.x nodistro main' > /etc/apt/sources.list.d/moviehub-node.list
    chmod 644 /etc/apt/sources.list.d/moviehub-node.list
    apt-get update -q
    apt-get install -y -q nodejs
fi
(umask 022; pip install -q --break-system-packages requests==2.32.5 six==1.17.0)

step "Folders and the JavaScript interpreter for the Live TV probe"
id moviehub >/dev/null 2>&1 || adduser --disabled-password --gecos "" --home /var/lib/moviehub-checks --shell /bin/bash moviehub
id moviehub-release >/dev/null 2>&1 || adduser --system --group --home /var/lib/moviehub-release moviehub-release
id moviehub-gate >/dev/null 2>&1 || adduser --system --group --no-create-home moviehub-gate
install -d -m 0700 -o moviehub -g moviehub /var/lib/moviehub-checks /srv/moviehub/health /srv/moviehub/runner
install -d -m 0755 -o root -g root /srv/moviehub /srv/moviehub/tools
install -d -m 0700 -o moviehub-release -g moviehub-release /var/lib/moviehub-release /srv/moviehub/keys
install -d -m 0755 -o moviehub-release -g moviehub-release /srv/moviehub/feed{,/app,/experimental,/providers,/scrapers,/scrapers-experimental}
if [[ ! -d /srv/moviehub/repo/.git ]]; then
    staged=$(mktemp -d /srv/moviehub/.checkout.XXXXXXXX)
    git clone -q --no-checkout https://github.com/billyke2000s/moviehub.git "$staged"
    mv -T "$staged" /srv/moviehub/repo
fi
[[ "$(stat -c %u /srv/moviehub/repo)" == 0 ]] || fail "Gate checkout must be owned by root."
git -C /srv/moviehub/repo checkout -q --detach "${INSTALL_REV:?missing installer revision}"
# The gate service reads its trusted script. The worker has no write access.
chmod -R a+rX /srv/moviehub/repo
curl -fsSL -o /srv/moviehub/tools/rhino-1.8.0.jar https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.8.0/rhino-1.8.0.jar
echo "e7ff37ec00b4c19ea16f42b5b3a601616d559dbb76e65bc9d094bd6bda2a925d  /srv/moviehub/tools/rhino-1.8.0.jar" | sha256sum --check

step "Signing key (stays on this server; back it up to a USB stick)"
if [ ! -f /srv/moviehub/keys/signing.pem ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /srv/moviehub/keys/signing.pem 2>/dev/null
fi
PUBKEY=$(openssl pkey -in /srv/moviehub/keys/signing.pem -pubout -outform DER | base64 -w0)
chown moviehub-release:moviehub-release /srv/moviehub/keys/signing.pem
chmod 600 /srv/moviehub/keys/signing.pem
chmod 644 /srv/moviehub/tools/rhino-1.8.0.jar

step "Forgejo"
id git >/dev/null 2>&1 || adduser --system --group --home /home/git --shell /bin/bash git
bash /root/forgejo-binary.sh forgejo 17.0.2 /usr/local/bin/forgejo
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
if ! $FJ admin user list | awk '{print $2}' | grep -qx "$ADMIN"; then
    $FJ admin user create --admin --username "$ADMIN" --password "$ADMINPW" \
        --email moviehub@localhost --must-change-password=false
fi
API=http://127.0.0.1:3000/api/v1
auth=(-u "$ADMIN:$ADMINPW")

step "Copying the MovieHub repository from GitHub into Forgejo (private)"
if ! curl -fs "${auth[@]}" "$API/repos/$ADMIN/moviehub" >/dev/null; then
    curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -X POST "$API/repos/migrate" -d "{
        \"clone_addr\": \"https://github.com/billyke2000s/moviehub\", \"repo_name\": \"moviehub\",
        \"repo_owner\": \"$ADMIN\", \"private\": true, \"mirror\": false, \"service\": \"git\"}" >/dev/null
fi

step "Protected main and repository-scoped credentials"
# Finish the import before protecting main. No authenticated URL is logged or saved in Git.
TOKEN_HEADER="Authorization: Basic $(printf '%s:%s' "$ADMIN" "$ADMINPW" | base64 -w0)"
# A completed bootstrap can be retried after the owner has merged later work.
if [[ ! -e /root/.moviehub-imported ]]; then
    FJ_BOOT_AUTH="$TOKEN_HEADER" git -C /srv/moviehub/repo --config-env=http.extraHeader=FJ_BOOT_AUTH \
        push -q http://127.0.0.1:3000/$ADMIN/moviehub.git HEAD:refs/heads/main
    touch /root/.moviehub-imported
fi
unset TOKEN_HEADER
NTFY_URL="https://ntfy.sh/$NTFY_TOPIC"
FJ_ADMIN_PASSWORD="$ADMINPW" FJ_BOT_PASSWORD="$BOTPW" FJ_NTFY_URL="$NTFY_URL" \
    python3 /srv/moviehub/repo/server/bootstrap/configure-repair.py
runuser -u moviehub-release -- env HOME=/var/lib/moviehub-release git -C /var/lib/moviehub-release config --global credential.helper store
runuser -u moviehub-release -- env HOME=/var/lib/moviehub-release git -C /var/lib/moviehub-release config --global credential.useHttpPath true
bash /srv/moviehub/repo/server/bootstrap/install-publisher.sh

step "Repository-scoped Forgejo runner"
bash /root/forgejo-binary.sh runner 13.2.2 /usr/local/bin/forgejo-runner
RUNNER_HOME=/srv/moviehub/runner
mkdir -p "$RUNNER_HOME"; chown moviehub:moviehub "$RUNNER_HOME"
if [ ! -f "$RUNNER_HOME/.runner" ]; then
    SECRET=$(rand 20)
    $FJCLI actions register --config /etc/forgejo/app.ini --name moviehub --scope "$ADMIN/moviehub" \
        --labels moviehub:host --secret "$SECRET"
    runuser -u moviehub -- bash -c 'cd "$1" && exec /usr/local/bin/forgejo-runner create-runner-file --instance http://127.0.0.1:3000 --secret "$2" --name moviehub' -- "$RUNNER_HOME" "$SECRET"
fi
if [[ ! -s "$RUNNER_HOME/config.yml" ]]; then
    install -m 0600 -o moviehub -g moviehub /srv/moviehub/repo/server/bootstrap/runner.yml "$RUNNER_HOME/config.yml"
fi
cat > /etc/systemd/system/forgejo-runner.service <<EOF
[Unit]
Description=Forgejo runner (MovieHub)
After=forgejo.service
Requires=forgejo.service
[Service]
User=moviehub
WorkingDirectory=$RUNNER_HOME
Environment=HOME=/var/lib/moviehub-checks XDG_RUNTIME_DIR=/run/moviehub-checks
RuntimeDirectory=moviehub-checks
RuntimeDirectoryMode=0700
Delegate=yes
ExecStart=/usr/local/bin/forgejo-runner daemon --config $RUNNER_HOME/config.yml
Restart=always
[Install]
WantedBy=multi-user.target
EOF

step "Gate (install codes + update feed)"
cat > /etc/moviehub-gate.env <<EOF
FEED_DIR=/srv/moviehub/feed
FEED_KEY=$FEED_KEY
EOF
chmod 600 /etc/moviehub-gate.env
cp /srv/moviehub/repo/server/gate/moviehub-gate.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable forgejo-runner moviehub-gate
systemctl restart forgejo-runner moviehub-gate

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

Still to configure: offline worker image, dedicated test emulator for films, Cloudflare, GitHub secrets
MOVIEHUB_FEED_URL / MOVIEHUB_FEED_KEY for the switchover release.
EOF
chmod 600 "$CREDS"
rm -f /root/moviehub-env

step "Checks"
for s in forgejo forgejo-runner moviehub-gate; do systemctl is-active --quiet "$s" || fail "Service $s is not active"; printf '%-16s %s\n' "$s" "$(systemctl is-active $s)"; done
curl -fs -o /dev/null -w 'gate public page: HTTP %{http_code}\n' http://127.0.0.1:8080/ || fail "Gate public page did not answer."
curl -fs -o /dev/null -w 'gate code page:   HTTP %{http_code}\n' http://127.0.0.1:8081/ || fail "Gate code page did not answer."
echo
echo "PUBLIC_SIGNING_KEY=$PUBKEY"
echo "MOVIEHUB SETUP FINISHED. Forgejo: http://$CONTAINER_IP:3000  Credentials: $CREDS"
