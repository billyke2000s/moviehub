"""Prepare parallel decoder-package downloads using APT's signed SHA256 metadata."""
import re
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

MIRROR_PREFIX = 'mirror+file:/etc/apt/apt-mirrors.txt/'


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
        runner_mirror = url.startswith(MIRROR_PREFIX) and not target.query
        if (not runner_mirror and (target.scheme not in ('http', 'https') or not target.hostname) or
                target.username or target.password or target.fragment):
            raise ValueError('Unexpected package URL')
        if (not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+:%_~-]*\.deb', name) or
                '/' in unquote(name) or int(size) <= 0):
            raise ValueError('Unsafe package filename or size')
        result.append((url, name))
    return result


def download_urls(url, mirror_list=None):
    if not url.startswith(MIRROR_PREFIX):
        return [url]
    if mirror_list is None:
        mirror_list = Path('/etc/apt/apt-mirrors.txt').read_text()
    suffix = url[len(MIRROR_PREFIX):]
    urls = []
    for line in mirror_list.splitlines():
        fields = line.split('#', 1)[0].split()
        if not fields:
            continue
        mirror = urlsplit(fields[0])
        if (mirror.scheme in ('http', 'https') and mirror.hostname and
                not mirror.username and not mirror.password and not mirror.query and not mirror.fragment):
            urls.append(fields[0].rstrip('/') + '/' + suffix)
    if not urls:
        raise ValueError('No HTTP package mirror in the fixed runner list')
    return urls


def download_plan(requests, metadata, mirror_list=None):
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
        lines.extend(('\t'.join(download_urls(url, mirror_list)),
                      '  out=' + name, '  checksum=sha-256=' + matches.pop()))
    return '\n'.join(lines) + ('\n' if lines else '')


if __name__ == '__main__':
    requests = requests_from(Path(sys.argv[1]).read_text())
    names = sorted({name.split('_', 1)[0] for _, name in requests})
    metadata = subprocess.check_output(['apt-cache', 'show', *names], text=True) if names else ''
    Path(sys.argv[2]).write_text(download_plan(requests, metadata))
    print('Prepared %d package downloads with signed SHA256 checksums' % len(requests))
