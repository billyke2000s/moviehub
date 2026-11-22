#!/usr/bin/env bash
# MovieHub server setup, part 1: runs on the Proxmox HOST as root.
#
#   curl -fsSL https://raw.githubusercontent.com/billyke2000s/moviehub/main/server/bootstrap/host.sh -o /root/mh.sh && bash /root/mh.sh
#
# (Download first, then run: piping into bash would let later commands swallow the script.)
#
# Creates ONE new unprivileged Debian 12 container (4 cores, 1.5 GB RAM + 1 GB swap by default; set
# MOVIEHUB_MEMORY / MOVIEHUB_SWAP in MB to change; CPU priority below the default
# so other containers such as Minecraft always win), then runs container.sh inside it. It never
# touches existing containers or VMs. Everything is logged to /root/moviehub-bootstrap.log.
set -euo pipefail
exec > >(tee -a /root/moviehub-bootstrap.log) 2>&1
BRANCH="${MOVIEHUB_BRANCH:-main}"
RAW="https://raw.githubusercontent.com/billyke2000s/moviehub/$BRANCH/server/bootstrap"
step() { echo; echo "==== $* ($(date +%H:%M:%S))"; }
fail() { echo "SETUP STOPPED: $*"; exit 1; }

command -v pct >/dev/null || fail "This is not a Proxmox host (no pct command)."
if pct list | awk 'NR>1 {print $NF}' | grep -qx moviehub; then
    fail "A container called 'moviehub' already exists. Nothing changed. Remove it first to start over."
fi

step "Choosing storage and container number"
CTID=$(pvesh get /cluster/nextid)
TSTORE=$(pvesm status --content vztmpl --enabled 1 | awk 'NR>1 {print $1; exit}')
RSTORE=$(pvesm status --content rootdir --enabled 1 | awk 'NR>1 {print $1; exit}')
[ -n "$TSTORE" ] || fail "No storage allows container templates."
[ -n "$RSTORE" ] || fail "No storage allows container disks."
ip link show vmbr0 >/dev/null 2>&1 || fail "Network bridge vmbr0 not found."
echo "Container $CTID, template storage $TSTORE, disk storage $RSTORE"

step "Getting the Debian 12 template"
pveam update >/dev/null
TEMPLATE=$(pveam available --section system | awk '{print $2}' | grep '^debian-12-standard_.*amd64' | sort -V | tail -1)
[ -n "$TEMPLATE" ] || fail "Debian 12 template not found."
pveam list "$TSTORE" | grep -q "$TEMPLATE" || pveam download "$TSTORE" "$TEMPLATE"

step "Creating container $CTID (moviehub)"
ROOTPW=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
pct create "$CTID" "$TSTORE:vztmpl/$TEMPLATE" \
    --hostname moviehub --cores 4 --memory "${MOVIEHUB_MEMORY:-1536}" --swap "${MOVIEHUB_SWAP:-1024}" --cpuunits 50 \
    --rootfs "$RSTORE:32" --net0 name=eth0,bridge=vmbr0,ip=dhcp,ip6=auto \
    --unprivileged 1 --features nesting=1 --onboot 1 --password "$ROOTPW"
pct start "$CTID"

step "Waiting for the container's network"
IP=""
for _ in $(seq 1 60); do
    # First IPv4 address: Forgejo's web address cannot be a bare IPv6 address.
    IP=$(pct exec "$CTID" -- hostname -I 2>/dev/null | tr ' ' '\n' | grep -m1 -E '^[0-9]+(\.[0-9]+){3}$') || true
    if [ -n "$IP" ] && pct exec "$CTID" -- getent hosts github.com >/dev/null 2>&1; then break; fi
    sleep 2
done
[ -n "$IP" ] || fail "Container $CTID got no IP address (is DHCP on?)."
echo "Container IP: $IP"

step "Running the installer inside the container"
curl -fsSL "$RAW/container.sh" -o /tmp/moviehub-container.sh
printf 'CONTAINER_IP=%s\nROOTPW=%s\nBRANCH=%s\n' "$IP" "$ROOTPW" "$BRANCH" > /tmp/moviehub-env
pct push "$CTID" /tmp/moviehub-container.sh /root/container.sh
pct push "$CTID" /tmp/moviehub-env /root/moviehub-env --perms 600
rm -f /tmp/moviehub-env
pct exec "$CTID" -- bash /root/container.sh </dev/null

echo
echo "Container: $CTID (moviehub) at $IP. Log: /root/moviehub-bootstrap.log"
