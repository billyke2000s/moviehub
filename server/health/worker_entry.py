"""Read-only container entry point. Its entire output is treated as an untrusted proposal."""
import json
from pathlib import Path
import subprocess
import sys


def main():
    work = Path('/tmp/source')
    work.mkdir()
    Path('/tmp/home').mkdir(exist_ok=True)
    subprocess.run(['tar', '-xf', '/source.tar', '-C', str(work)], check=True)
    def git(*args, stdout=subprocess.DEVNULL):
        return subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=work,
                              check=True, stdout=stdout).stdout
    git('init', '-q')
    git('add', '-A')
    tree = git('write-tree', stdout=subprocess.PIPE).decode().strip()
    prompt = sys.stdin.read(16000)
    with open('/tmp/worker-output', 'w+b') as log:
        subprocess.run([*sys.argv[1:], prompt], cwd=work, stdout=log, stderr=log, check=True)
        log.seek(max(0, log.tell() - 2000))
        diagnosis = log.read().decode(errors='replace')
    git('add', '-A')
    patch = git('diff', '--cached', '--binary', '--no-renames', tree, stdout=subprocess.PIPE)
    if len(patch) > 2 * 1024 * 1024:
        raise SystemExit('patch too large')
    print(json.dumps({'patch': patch.decode(), 'diagnosis': diagnosis}))


if __name__ == '__main__':
    main()
