#!/usr/bin/env bash
# Run in a disposable namespace with a fresh /root tmpfs and the repository mounted at /repo.
set -euo pipefail
[[ "$(findmnt -n -o FSTYPE --mountpoint /root)" == tmpfs ]]
[[ ! -e /root/.moviehub-bootstrap && ! -e /root/moviehub-bootstrap.log ]]
testdir=$(mktemp -d)
export QA_DIR=$testdir PATH="$testdir:$PATH"
cat > "$testdir/mock" <<'MOCK'
#!/bin/bash
set -euo pipefail
case ${0##*/} in
pvesh) echo 102 ;;
pvesm) printf 'Name Type\nlocal dir\n' ;;
pveam) [[ $1 != available ]] || echo 'system debian-12-standard_fixture_amd64.tar.zst' ;;
ip) [[ $* == 'link show vmbr0' ]] ;;
curl)
    [[ $* == *"$EXPECTED_REV/server/bootstrap/"* ]]
    [[ ${@: -2:1} == -o ]]
    echo '# fixture' > "${@: -1}" ;;
pct)
    case "$1" in
        list) echo 'VMID Status Name' ;;
        config) cat "$QA_DIR/config" ;;
        create)
            [[ $2 == 102 && ! -e "$QA_DIR/config" ]]
            while [[ $1 != --description ]]; do shift; done
            echo "description: $2" > "$QA_DIR/config"
            echo create >> "$QA_DIR/events" ;;
        status) echo 'status: running' ;;
        push) [[ $2 == 102 && -s $3 ]] ;;
        exec)
            [[ $2 == 102 && $3 == -- ]]
            case "$4" in
                hostname) echo '192.0.2.10 2001:db8::1' ;;
                getent) [[ $* == *' hosts github.com' ]] ;;
                bash) echo setup >> "$QA_DIR/events"; [[ -f "$QA_DIR/retry" ]] ;;
                *) exit 2 ;;
            esac ;;
        *) exit 2 ;;
    esac ;;
*) exit 2 ;;
esac
MOCK
chmod +x "$testdir/mock"
for cmd in pct pvesh pvesm pveam curl ip; do ln -s mock "$testdir/$cmd"; done
export EXPECTED_REV=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
host=/repo/server/bootstrap/host.sh
state=/root/.moviehub-bootstrap
export MOVIEHUB_REVISION=$EXPECTED_REV
if bash "$host"; then exit 1; fi
[[ -f "$state/created" && ! -e "$state/complete" ]]
cp "$state/identity" "$testdir/identity"
cp "$state/guest.env" "$testdir/guest.env"
# Simulate a different guest appearing at the old ID before resuming.
mv "$testdir/config" "$testdir/owned"
echo 'description: unrelated guest' > "$testdir/config"
touch "$testdir/retry"
if bash "$host"; then exit 1; fi
rm "$testdir/config"
if bash "$host"; then exit 1; fi
mv "$testdir/owned" "$testdir/config"
# The saved revision and credentials must win even if the caller now sets another revision.
export MOVIEHUB_REVISION=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
bash "$host"
bash "$host"
cmp "$state/identity" "$testdir/identity"
cmp "$state/guest.env" "$testdir/guest.env"
[[ -f "$state/complete" ]]
[[ "$(cat "$testdir/events")" == $'create\nsetup\nsetup' ]]
echo 'Passed interrupted install, mismatched guest, missing guest, retry and completed rerun.'
