#!/usr/bin/env bash
# Fresh Proxmox install. Re-running resumes only a guest created by this installer.
set -euo pipefail
umask 077
command -v pct >/dev/null || { echo 'Run this on the Proxmox host.' >&2; exit 1; }
exec > >(tee -a /root/moviehub-bootstrap.log) 2>&1
state=/root/.moviehub-bootstrap
install -d -m 0700 "$state"
exec 9>"$state/lock"
flock -n 9 || { echo 'Another MovieHub installation is running.' >&2; exit 1; }
fail() { echo "SETUP STOPPED: $*" >&2; exit 1; }

if [[ ! -f "$state/identity" ]]; then
    BRANCH=${MOVIEHUB_BRANCH:-main}
    [[ "$BRANCH" =~ ^[A-Za-z0-9][A-Za-z0-9_./-]*$ ]] || fail 'Invalid git branch.'
    INSTALL_REV=${MOVIEHUB_REVISION:-}
    if [[ -z "$INSTALL_REV" ]]; then
        INSTALL_REV=$(curl -fsSL "https://api.github.com/repos/billyke2000s/moviehub/commits/$BRANCH" |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')
    fi
    [[ "$INSTALL_REV" =~ ^[0-9a-f]{40}$ ]] || fail 'Expected the full installer commit.'
    names=$(pct list)
    if awk 'NR>1 && $NF=="moviehub" {found=1} END {exit !found}' <<< "$names"; then
        fail 'An existing MovieHub guest has no matching installer record. Nothing was changed.'
    fi
    CTID=$(pvesh get /cluster/nextid)
    [[ "$CTID" =~ ^[1-9][0-9]+$ ]] || fail 'Invalid guest ID.'
    marker=moviehub-installer-$(openssl rand -hex 16)
    ROOTPW=$(openssl rand -hex 18)
    tmp=$(mktemp "$state/identity.XXXXXXXX")
    declare -p CTID BRANCH INSTALL_REV marker ROOTPW > "$tmp"
    mv -T "$tmp" "$state/identity"
else
    . "$state/identity"
fi

if config=$(pct config "$CTID" 2>/dev/null); then
    grep -Fxq "description: $marker" <<< "$config" || fail "CT $CTID does not match this installation."
else
    [[ ! -f "$state/created" ]] || fail "Previously created CT $CTID is missing. No replacement was created."
    tstore=$(pvesm status --content vztmpl --enabled 1 | awk 'NR==2 {print $1}')
    rstore=$(pvesm status --content rootdir --enabled 1 | awk 'NR==2 {print $1}')
    [[ -n "$tstore" && -n "$rstore" ]] || fail 'Template and root disk storage are required.'
    ip link show vmbr0 >/dev/null || fail 'Network bridge vmbr0 is missing.'
    pveam update >/dev/null
    template=$(pveam available --section system | awk '$2 ~ /^debian-12-standard_.*amd64/ {print $2}' | sort -V | tail -1)
    [[ -n "$template" ]] || fail 'Debian 12 template not found.'
    cached=$(pveam list "$tstore")
    grep -Fq "$template" <<< "$cached" || pveam download "$tstore" "$template"
    pct create "$CTID" "$tstore:vztmpl/$template" --hostname moviehub --description "$marker" \
        --cores 4 --memory "${MOVIEHUB_MEMORY:-1536}" --swap "${MOVIEHUB_SWAP:-1024}" --cpuunits 50 \
        --rootfs "$rstore:32" --net0 name=eth0,bridge=vmbr0,ip=dhcp,ip6=auto \
        --unprivileged 1 --features nesting=1 --onboot 1 --password "$ROOTPW"
fi
touch "$state/created"
if [[ -f "$state/complete" ]]; then
    echo "Installation previously completed on CT $CTID. See /root/moviehub-credentials.txt inside the guest."
    exit 0
fi
[[ "$(pct status "$CTID")" == "status: running" ]] || pct start "$CTID"
ready=0
for _ in $(seq 1 60); do
    CONTAINER_IP=$(pct exec "$CTID" -- hostname -I 2>/dev/null | tr ' ' '\n' | awk '/^[0-9]+(\.[0-9]+){3}$/ && !seen++ {print}') || true
    if [[ -n "$CONTAINER_IP" ]] && pct exec "$CTID" -- getent hosts github.com >/dev/null 2>&1; then ready=1; break; fi
    sleep 2
done
((ready)) || fail "CT $CTID did not get IPv4 and DNS. Retry the same command after restoring connectivity."
raw="https://raw.githubusercontent.com/billyke2000s/moviehub/$INSTALL_REV/server/bootstrap"
for script in container.sh forgejo-binary.sh; do
    curl -fsSL --retry 2 "$raw/$script" -o "$state/$script"
    pct push "$CTID" "$state/$script" "/root/$script" --perms 700
done
declare -p CONTAINER_IP ROOTPW BRANCH INSTALL_REV > "$state/guest.env"
pct push "$CTID" "$state/guest.env" /root/moviehub-env --perms 600
pct exec "$CTID" -- bash /root/container.sh </dev/null
touch "$state/complete"
echo "MovieHub installed on CT $CTID at $CONTAINER_IP."
