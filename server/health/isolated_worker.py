"""Run a preloaded offline worker and import its untrusted Git patch as data."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import uuid

PODMAN = '/usr/bin/podman'
MAX_PATCH = 2 * 1024 * 1024


def proposal(root, base, prompt):
    image = os.environ.get('MOVIEHUB_WORKER_IMAGE', '')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise ValueError('configure a preloaded worker image by its full sha256 image ID')
    if os.getuid() == 0:
        raise ValueError('the proposal runner requires rootless Podman')
    env = {k: os.environ[k] for k in ('HOME', 'USER', 'LOGNAME', 'XDG_RUNTIME_DIR', 'DBUS_SESSION_BUS_ADDRESS')
           if k in os.environ}
    env['PATH'] = '/usr/local/bin:/usr/bin:/bin'
    def engine(*args, **kwargs):
        return subprocess.run([PODMAN, '--remote=false', *args], cwd='/', env=env, **kwargs)
    rootless = engine('info', '--format', '{{.Host.Security.Rootless}}', capture_output=True,
                      text=True, check=True, timeout=20)
    if rootless.stdout.strip() != 'true':
        raise ValueError('rootless container isolation is unavailable')
    command = shlex.split(os.environ.get('CODEX_CMD', 'codex exec --full-auto'))
    if not command:
        raise ValueError('empty worker command')
    minutes = int(os.environ.get('CODEX_MINUTES', '30'))
    if not 1 <= minutes <= 120:
        raise ValueError('worker time budget must be 1 to 120 minutes')
    with tempfile.TemporaryDirectory(prefix='mh-input-') as temp:
        archive = Path(temp) / 'source.tar'
        subprocess.run(['git', 'archive', '--format=tar', '-o', str(archive), base],
                       cwd=root, check=True, timeout=60)
        helper = Path(__file__).with_name('worker_entry.py').resolve()
        name = 'moviehub-proposal-' + uuid.uuid4().hex
        args = ['run', '--name', name, '--rm', '--pull=never', '--network=none', '--read-only',
                '--image-volume=ignore', '--http-proxy=false', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--userns=keep-id', '--pid=private', '--ipc=private', '--pids-limit=256',
                '--log-driver=none', '--tmpfs=/tmp:rw,nosuid,nodev,size=256m,mode=1777',
                '--env=HOME=/tmp/home', '--workdir=/tmp', '--entrypoint=/usr/bin/python3',
                '--volume', str(archive) + ':/source.tar:ro', '--volume', str(helper) + ':/worker_entry.py:ro',
                '-i', image.removeprefix('sha256:'), '-I', '/worker_entry.py', *command]
        # Results are read from the disposable container's bounded tmpfs. Its stdout/stderr is
        # never inherited by Forgejo and no worker file is opened as host Git configuration.
        try:
            with tempfile.TemporaryFile() as output:
                # prlimit applies to the Podman client writing the host file, including a noisy image.
                result = subprocess.run(['/usr/bin/prlimit', '--fsize='+str(2 * MAX_PATCH), '--',
                                         PODMAN, '--remote=false', *args], cwd='/', env=env,
                                        input=prompt.encode(), stdout=output, stderr=subprocess.DEVNULL,
                                        timeout=60 * minutes, check=False)
                if result.returncode:
                    raise ValueError('isolated worker failed; no completed proposal')
                output.seek(0)
                data = json.loads(output.read(2 * MAX_PATCH + 1))
                patch = data['patch'].encode()
                if len(patch) > MAX_PATCH:
                    raise ValueError('worker patch exceeds the review limit')
                return patch, str(data['diagnosis'])[:2000]
        finally:
            engine('rm', '--force', '--ignore', name, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=30, check=False)
