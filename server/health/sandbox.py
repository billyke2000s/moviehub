"""Runs Codex on its own Linux machine (the sandbox container) instead of on the MovieHub server.

The sandbox is an unprivileged Proxmox container made by server/bootstrap/sandbox.sh. Codex has full
control of it (root, internet, a real browser), but it holds no signing key, no Forgejo token and no
copy of the real repository, and the Proxmox firewall stops it reaching anything on the home network.

One job:
  1. reset  - roll the sandbox back to its clean snapshot (Proxmox API token that may only do that)
  2. send   - copy the repository (files only, no .git, no secrets) to /work in the sandbox
  3. run    - run Codex there with the prompt, with a time limit
  4. take   - bring back only the change as a patch; the caller decides which files it may keep

Settings (environment variables, set by auto-fix.yml from Forgejo secrets/variables):
  SANDBOX_HOST     ssh target, e.g. root@192.168.1.60
  SANDBOX_KEY      ssh key for it (default /srv/moviehub/keys/sandbox_ssh)
  PVE_URL          e.g. https://192.168.1.10:8006
  PVE_NODE         default pve
  PVE_TOKEN        API token "user@realm!name=secret", allowed only to roll back and start the sandbox
  SANDBOX_CTID     the sandbox container number
  SANDBOX_SNAPSHOT default clean
"""
import json
import os
import shlex
import ssl
import subprocess
import time
import urllib.parse
import urllib.request


class SandboxError(Exception):
    pass


def configured(env=os.environ):
    return all(env.get(k) for k in ('SANDBOX_HOST', 'PVE_URL', 'PVE_TOKEN', 'SANDBOX_CTID'))


class Sandbox:
    def __init__(self, env=os.environ):
        self.host = env['SANDBOX_HOST']
        self.key = env.get('SANDBOX_KEY', '/srv/moviehub/keys/sandbox_ssh')
        self.pve = env['PVE_URL'].rstrip('/')
        self.node = env.get('PVE_NODE', 'pve')
        self.token = env['PVE_TOKEN']
        self.ctid = env['SANDBOX_CTID']
        self.snapshot = env.get('SANDBOX_SNAPSHOT', 'clean')
        # Proxmox uses its own certificate on the home network; the token only allows a rollback.
        self.tls = ssl._create_unverified_context()

    # --------------------------------------------------------------------------------- Proxmox

    def _api(self, method, path, data=None):
        body = urllib.parse.urlencode(data).encode() if data else None
        request = urllib.request.Request(self.pve + '/api2/json' + path, data=body, method=method,
                                         headers={'Authorization': 'PVEAPIToken=' + self.token})
        with urllib.request.urlopen(request, timeout=30, context=self.tls) as response:
            return json.loads(response.read().decode())['data']

    def reset(self, timeout=300):
        """Rolls the sandbox back to its clean snapshot and starts it, then waits for ssh."""
        base = '/nodes/%s/lxc/%s' % (self.node, self.ctid)
        task = self._api('POST', '%s/snapshot/%s/rollback' % (base, self.snapshot), {'start': 1})
        deadline = time.time() + timeout
        while time.time() < deadline:
            status = self._api('GET', '/nodes/%s/tasks/%s/status' % (self.node, urllib.parse.quote(task)))
            if status.get('status') == 'stopped':
                if status.get('exitstatus') != 'OK':
                    raise SandboxError('rollback failed: %s' % status.get('exitstatus'))
                break
            time.sleep(3)
        else:
            raise SandboxError('rollback did not finish in %d s' % timeout)
        if self._api('GET', base + '/status/current').get('status') != 'running':
            self._api('POST', base + '/status/start')
        while time.time() < deadline:
            if self.ssh('true', timeout=20, check=False).returncode == 0:
                return
            time.sleep(3)
        raise SandboxError('sandbox did not answer ssh after the rollback')

    # --------------------------------------------------------------------------------- ssh

    def ssh(self, command, data=None, timeout=120, check=True):
        done = subprocess.run(
            ['ssh', '-i', self.key, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new',
             '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=30', self.host, command],
            input=data, capture_output=True, timeout=timeout)
        if check and done.returncode != 0:
            raise SandboxError('sandbox command failed (%s): %s'
                               % (done.returncode, done.stderr.decode(errors='replace')[-400:]))
        return done

    def send(self, root):
        """Copies the committed repository files (no .git history, nothing ignored) to /work."""
        archive = subprocess.run(['git', 'archive', '--format=tar', 'HEAD'], cwd=root, capture_output=True,
                                 check=True).stdout
        self.ssh('rm -rf /work && mkdir -p /work && tar -x -C /work && cd /work && git init -q && '
                 'git add -A && git -c user.name=base -c user.email=base@sandbox commit -qm base',
                 data=archive, timeout=300)

    def run(self, command, prompt, minutes):
        """Runs `command` (a list, e.g. codex exec --full-auto) in /work with the prompt as its last
        argument. Returns Codex's output (last part); a time-out is reported, not raised."""
        self.ssh('cat > /root/prompt.txt', data=prompt.encode(), timeout=60)
        quoted = ' '.join(shlex.quote(part) for part in command)
        remote = 'cd /work && timeout %dm %s "$(cat /root/prompt.txt)" 2>&1' % (minutes, quoted)
        try:
            done = self.ssh(remote, timeout=minutes * 60 + 120, check=False)
        except subprocess.TimeoutExpired:
            return 'Codex ran out of time.'
        text = done.stdout.decode(errors='replace')
        if done.returncode == 124:
            text += '\nCodex ran out of time.'
        return text[-2500:]

    def take(self):
        """The sandbox's changes to the repository as a binary git patch ('' when nothing changed)."""
        done = self.ssh('cd /work && git add -A && git diff --cached --binary', timeout=120)
        return done.stdout.decode(errors='replace')


def patch_paths(patch):
    """Every path a git patch touches (both sides of renames and copies)."""
    paths = []
    for line in patch.splitlines():
        if line.startswith('diff --git '):
            rest = line[len('diff --git '):]
            # "a/<path> b/<path>"; paths with spaces are quoted by git, which we refuse below.
            if '"' in rest or ' b/' not in rest:
                paths.append(rest)
                continue
            left, right = rest.split(' b/', 1)
            for path in (left[2:] if left.startswith('a/') else left, right):
                if path not in paths:
                    paths.append(path)
    return paths
