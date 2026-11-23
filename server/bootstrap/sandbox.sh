#!/usr/bin/env bash
# Codex sandbox: Codex's own Linux machine for automatic fixes. Runs on the Proxmox HOST as root,
# after host.sh has made the "moviehub" container.
#
#   curl -fsSL https://raw.githubusercontent.com/billyke2000s/moviehub/main/server/bootstrap/sandbox.sh -o /root/sandbox.sh
#   bash /root/sandbox.sh            # step 1: make and set up the machine
#   pct enter <number> ; codex login # step 2: sign Codex in to ChatGPT (once)
#   bash /root/sandbox.sh --snapshot # step 3: save the clean state every fix starts from
#
# What Codex gets: an unprivileged Debian 12 container where it is root, with the internet, Chromium
# (Playwright), Node 22, Python 3, Java, ffmpeg and Rhino, so it can open sites in a real browser and run
# the same probe the server uses.
#
# What it does NOT get: the signing key, the Forgejo token, the real repository, or the home network.
# The Proxmox firewall (enforced on the host, so root inside cannot switch it off) blocks every private
# address range, and only the moviehub container may ssh in. Before each fix the moviehub container
# rolls it back to the clean snapshot, using an API token that can do nothing except roll back, start
# and look at this one container.
set -euo pipefail
exec > >(tee -a /root/moviehub-sandbox.log) 2>&1
step() { echo; echo "==== $* ($(date +%H:%M:%S))"; }
fail() { echo "SETUP STOPPED: $*"; exit 1; }

command -v pct >/dev/null || fail "This is not a Proxmox host (no pct command)."
NAME=codex-sandbox
NODE=$(hostname)
find_ct() { pct list | awk -v n="$1" 'NR>1 && $NF==n {print $1}'; }
MH=$(find_ct moviehub)
[ -n "$MH" ] || fail "No container called 'moviehub'. Run host.sh first."
SB=$(find_ct "$NAME")

if [ "${1:-}" = "--snapshot" ]; then
    [ -n "$SB" ] || fail "No $NAME container yet. Run this script without --snapshot first."
    pct exec "$SB" -- test -s /root/.codex/auth.json || fail "Codex is not signed in. Run: pct enter $SB, then codex login"
    step "Saving the clean snapshot"
    pct listsnapshot "$SB" | grep -qw clean && pct delsnapshot "$SB" clean
    pct exec "$SB" -- rm -rf /work /root/prompt.txt
    pct snapshot "$SB" clean --description "Clean state every Codex fix starts from"
    echo "Done. Auto-fix now runs Codex in container $SB."
    exit 0
fi

[ -z "$SB" ] || fail "A container called '$NAME' already exists ($SB). Nothing changed. Remove it to start over."

step "Checking the Proxmox firewall"
# Container firewall rules only apply when the datacenter firewall is on. Turning it on blocks
# traffic to this host except the web interface (8006) and ssh from your local network.
if ! pvesh get /cluster/firewall/options --output-format json | grep -Eq '"enable" *: *1'; then
    echo "The datacenter firewall is off, so the sandbox's network limits would not apply."
    echo "Turning it on keeps the web interface (port 8006) and ssh open from your home network,"
    echo "and blocks other connections to this host. Containers without their own firewall are not affected."
    read -r -p "Turn the datacenter firewall on now? [y/N] " answer
    [ "$answer" = "y" ] || fail "Firewall left off. Turn it on (Datacenter > Firewall > Options) and run this again."
    pvesh set /cluster/firewall/options --enable 1
fi

step "Finding the moviehub container's address"
MH_IP=$(pct exec "$MH" -- hostname -I | tr ' ' '\n' | grep -m1 -E '^[0-9]+(\.[0-9]+){3}$') \
    || fail "Could not read the moviehub container's IPv4 address."
echo "moviehub container $MH at $MH_IP (reserve this address in your router so it never changes)"

step "Creating container $NAME"
SB=$(pvesh get /cluster/nextid)
TSTORE=$(pvesm status --content vztmpl --enabled 1 | awk 'NR>1 {print $1; exit}')
# The clean snapshot needs storage that can snapshot (LVM-thin, ZFS, Btrfs or Ceph, not a plain folder).
RSTORE=${SANDBOX_STORAGE:-$(pvesm status --content rootdir --enabled 1 | awk 'NR>1 && $2 ~ /^(lvmthin|zfspool|btrfs|rbd)$/ {print $1; exit}')}
[ -n "$RSTORE" ] || fail "No container storage that supports snapshots (LVM-thin, ZFS, Btrfs, Ceph). Set SANDBOX_STORAGE."
TEMPLATE=$(pveam available --section system | awk '{print $2}' | grep '^debian-12-standard_.*amd64' | sort -V | tail -1)
pveam list "$TSTORE" | grep -q "$TEMPLATE" || pveam download "$TSTORE" "$TEMPLATE"
# Codex's sign-in lives outside the snapshot (a folder on the host), so rolling back never undoes a
# refreshed sign-in. Root in an unprivileged container is uid 100000 on the host.
mkdir -p /srv/codex-sandbox-home
chown 100000:100000 /srv/codex-sandbox-home
chmod 700 /srv/codex-sandbox-home
pct create "$SB" "$TSTORE:vztmpl/$TEMPLATE" \
    --hostname "$NAME" --cores "${SANDBOX_CORES:-2}" --memory "${SANDBOX_MEMORY:-3072}" --swap 1024 \
    --cpuunits 50 --rootfs "$RSTORE:24" --unprivileged 1 --features nesting=1 --onboot 1 \
    --net0 name=eth0,bridge=vmbr0,ip=dhcp,firewall=1 --nameserver "1.1.1.1 9.9.9.9" \
    --mp0 /srv/codex-sandbox-home,mp=/root/.codex,backup=0

step "Firewall: no home network, ssh only from the moviehub container"
cat > "/etc/pve/firewall/$SB.fw" <<EOF
[OPTIONS]
enable: 1
policy_in: DROP
policy_out: ACCEPT

[RULES]
IN SSH(ACCEPT) -source $MH_IP
OUT DROP -dest 10.0.0.0/8
OUT DROP -dest 172.16.0.0/12
OUT DROP -dest 192.168.0.0/16
OUT DROP -dest 169.254.0.0/16
OUT DROP -dest 100.64.0.0/10
EOF
pct start "$SB"

step "Waiting for the sandbox's network"
SB_IP=""
for _ in $(seq 1 60); do
    SB_IP=$(pct exec "$SB" -- hostname -I 2>/dev/null | tr ' ' '\n' | grep -m1 -E '^[0-9]+(\.[0-9]+){3}$') || true
    if [ -n "$SB_IP" ] && pct exec "$SB" -- getent hosts github.com >/dev/null 2>&1; then break; fi
    sleep 2
done
[ -n "$SB_IP" ] || fail "Container $SB got no IP address (is DHCP on?)."
echo "Sandbox IP: $SB_IP (reserve this address in your router too)"

step "Checking the sandbox cannot reach the home network"
pve-firewall status | grep -q running || fail "The Proxmox firewall service is not running."
if pct exec "$SB" -- timeout 5 bash -c "echo > /dev/tcp/$MH_IP/3000" 2>/dev/null; then
    fail "The sandbox can reach the moviehub container. The firewall is not working; nothing installed."
fi
echo "Blocked, as it should be."

step "Installing Codex's tools (a few minutes)"
pct exec "$SB" -- bash -euo pipefail -c '
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q git curl ca-certificates openssh-server python3 python3-pip python3-venv \
    openjdk-17-jdk-headless ffmpeg jq ripgrep unzip
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y -q nodejs
npm install -g @openai/codex playwright@1.65.1
playwright install --with-deps chromium
pip3 install --break-system-packages requests==2.32.5 six==1.17.0
mkdir -p /srv/moviehub/tools
curl -fsSL -o /srv/moviehub/tools/rhino-1.9.1.jar https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.9.1/rhino-1.9.1.jar
echo "12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975  /srv/moviehub/tools/rhino-1.9.1.jar" | sha256sum --check
systemctl enable --now ssh
'

step "ssh key: the moviehub container may log in to the sandbox"
pct exec "$MH" -- runuser -u moviehub -- bash -c \
    'test -f /srv/moviehub/keys/sandbox_ssh || ssh-keygen -q -t ed25519 -N "" -C moviehub-auto-fix -f /srv/moviehub/keys/sandbox_ssh'
PUB=$(pct exec "$MH" -- cat /srv/moviehub/keys/sandbox_ssh.pub)
pct exec "$SB" -- bash -c "mkdir -p /root/.ssh && chmod 700 /root/.ssh && echo '$PUB' > /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys"
pct exec "$MH" -- runuser -u moviehub -- ssh -i /srv/moviehub/keys/sandbox_ssh -o BatchMode=yes \
    -o StrictHostKeyChecking=accept-new "root@$SB_IP" true || fail "The moviehub container cannot ssh to the sandbox."

step "API token that can only roll back and start this one container"
pveum role add SandboxReset -privs "VM.Audit VM.PowerMgmt VM.Snapshot.Rollback" 2>/dev/null \
    || pveum role modify SandboxReset -privs "VM.Audit VM.PowerMgmt VM.Snapshot.Rollback"
pveum user add sandbox-reset@pve --comment "MovieHub auto-fix: resets the Codex sandbox only" 2>/dev/null || true
pveum acl modify "/vms/$SB" -user sandbox-reset@pve -role SandboxReset
pveum user token remove sandbox-reset@pve autofix 2>/dev/null || true
SECRET=$(pveum user token add sandbox-reset@pve autofix --privsep 0 --output-format json | jq -r .value)
HOST_IP=$(hostname -I | tr ' ' '\n' | grep -m1 -E '^[0-9]+(\.[0-9]+){3}$')

cat <<EOF

==== Sandbox container $SB ($NAME) at $SB_IP is set up.

Next:
 1. Sign Codex in:   pct enter $SB   then   codex login   (choose "Sign in with ChatGPT"), then exit.
 2. Save the clean state:   bash /root/sandbox.sh --snapshot
 3. In Forgejo, repository Settings > Actions:
      Variables:  SANDBOX_HOST = root@$SB_IP
                  SANDBOX_CTID = $SB
                  PVE_URL      = https://$HOST_IP:8006
                  PVE_NODE     = $NODE
      Secret:     PVE_SANDBOX_TOKEN = (shown below, not saved in the log)
 4. Reserve $MH_IP (moviehub) and $SB_IP (sandbox) in your router so they never change.

Log: /root/moviehub-sandbox.log
EOF
# Straight to the screen, past the log file: this is the only time the token is shown.
echo "PVE_SANDBOX_TOKEN = sandbox-reset@pve!autofix=$SECRET" > /dev/tty
