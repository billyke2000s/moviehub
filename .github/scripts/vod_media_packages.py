"""Prepare parallel decoder-package downloads using APT's signed SHA256 metadata."""
import re
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


def requests_from(text):
    result = []
    for line in text.splitlines():
        if not line.startswith("'"):
            continue
        fields = shlex.split(line)
        if len(fields) < 3:
            raise ValueError('Incomplete APT download record')
        url, name, size = fields[:3]
        target = urlsplit(url)
        if (target.scheme not in ('http', 'https') or not target.hostname or
                target.username or target.password or target.fragment):
            raise ValueError('Unexpected package URL')
        if (not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+:%_~-]*\.deb', name) or
                '/' in unquote(name) or int(size) <= 0):
            raise ValueError('Unsafe package filename or size')
        result.append((url, name))
    return result


def download_plan(requests, metadata):
    hashes = {}
    for paragraph in metadata.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in paragraph.splitlines()
                      if line and not line[0].isspace() and ': ' in line)
        filename, digest = fields.get('Filename'), fields.get('SHA256', '')
        if filename and re.fullmatch(r'[a-fA-F0-9]{64}', digest):
            hashes.setdefault(unquote(filename), set()).add(digest.lower())
    lines = []
    for url, name in requests:
        path = unquote(urlsplit(url).path)
        matches = set().union(*(values for filename, values in hashes.items()
                                if path.endswith('/' + filename)))
        if len(matches) != 1:
            raise ValueError('Missing or ambiguous signed SHA256 package metadata')
        lines.extend((url, '  out=' + name, '  checksum=sha-256=' + matches.pop()))
    return '\n'.join(lines) + ('\n' if lines else '')


if __name__ == '__main__':
    requests = requests_from(Path(sys.argv[1]).read_text())
    names = sorted({name.split('_', 1)[0] for _, name in requests})
    metadata = subprocess.check_output(['apt-cache', 'show', *names], text=True) if names else ''
    Path(sys.argv[2]).write_text(download_plan(requests, metadata))
    print('Prepared %d package downloads with signed SHA256 checksums' % len(requests))
