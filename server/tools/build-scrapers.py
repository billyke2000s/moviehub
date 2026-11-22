#!/usr/bin/env python3
"""Builds and signs a film/series scraper package for over-the-air delivery.

    python3 server/tools/build-scrapers.py --key /srv/moviehub/keys/signing.pem --app 124 --out build/scrapers

Writes scrapers.zip and scrapers.sig. The zip holds scrapers.properties plus the complete
android-tv-launcher/app/src/main/python folder (minus moviehub_loader.py, which always stays the
APK's own). The signature is RSA SHA-256 over the exact zip bytes, made with openssl, and is checked
against the public key pinned in android-tv-launcher/app/src/main/assets/providers/trust.json before
anything is written, so a package signed with the wrong key can never be published.

--app is the versionCode of the newest APK this package is meant for (normally the published one).
Boxes running a newer APK ignore it, because their built-in scrapers are newer.
"""
import argparse
import base64
import json
import re
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYTHON = ROOT / 'android-tv-launcher/app/src/main/python'
TRUST = ROOT / 'android-tv-launcher/app/src/main/assets/providers/trust.json'
JAVA = ROOT / 'android-tv-launcher/app/src/main/java/local/tv/home/ScraperBundle.java'
NAME = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*\.(py|json|txt)')
LOADER = 'moviehub_loader.py'
MAX_ZIP = 4 * 1024 * 1024
FIXED_TIME = (2020, 1, 1, 0, 0, 0)


def api_level():
    match = re.search(r'static final int API = (\d+);', JAVA.read_text())
    if not match:
        sys.exit('Cannot find ScraperBundle.API')
    return int(match.group(1))


def files():
    for path in sorted(PYTHON.rglob('*')):
        if not path.is_file() or '__pycache__' in path.parts:
            continue
        name = path.relative_to(PYTHON).as_posix()
        if name == LOADER:
            continue
        if not NAME.fullmatch(name) or '..' in name:
            sys.exit(f'Refusing file the app would reject: {name}')
        yield name, path.read_bytes()


def build_zip(version, api, app):
    manifest = f'version={version}\napi={api}\napp={app}\n'.encode()
    with tempfile.SpooledTemporaryFile() as buffer:
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            def add(name, data):
                info = zipfile.ZipInfo(name, FIXED_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data)
            add('scrapers.properties', manifest)
            for name, data in files():
                add('python/' + name, data)
        buffer.seek(0)
        return buffer.read()


def openssl(*args, data=None):
    result = subprocess.run(['openssl', *args], input=data, capture_output=True)
    if result.returncode != 0:
        sys.exit('openssl failed: ' + result.stderr.decode(errors='replace').strip())
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--key', required=True, help='Private signing key (PEM). Never commit it.')
    parser.add_argument('--app', required=True, type=int, help='versionCode the package targets')
    parser.add_argument('--out', required=True, help='Output folder')
    parser.add_argument('--version', type=int, default=int(time.time()),
                        help='Package version (default: current Unix time; must always increase)')
    parser.add_argument('--trust', default=str(TRUST), help=argparse.SUPPRESS)  # tests only
    args = parser.parse_args()

    pinned = json.loads(Path(args.trust).read_text()).get('publicKey', '')
    if not pinned:
        sys.exit('trust.json has no public key yet. See server/README.md, "Signing key".')
    with tempfile.TemporaryDirectory() as work:
        key_public = openssl('pkey', '-in', args.key, '-pubout', '-outform', 'DER')
        if base64.b64encode(key_public).decode() != pinned:
            sys.exit('This private key does not match the public key in trust.json. Nothing written.')
        bits = openssl('pkey', '-in', args.key, '-text', '-noout').decode()
        size = re.search(r'Private-Key: \((\d+) bit', bits)
        if not size or int(size.group(1)) < 3072:
            sys.exit('RSA key of at least 3072 bits required')

        payload = build_zip(args.version, api_level(), args.app)
        if len(payload) > MAX_ZIP:
            sys.exit(f'Package is {len(payload)} bytes; the app accepts at most {MAX_ZIP}')
        zip_path = Path(work) / 'scrapers.zip'
        zip_path.write_bytes(payload)
        signature = openssl('dgst', '-sha256', '-sign', args.key, str(zip_path))
        pub_path = Path(work) / 'public.der'
        pub_path.write_bytes(key_public)
        sig_path = Path(work) / 'scrapers.sig'
        sig_path.write_bytes(signature)
        openssl('dgst', '-sha256', '-verify', str(pub_path), '-keyform', 'DER',
                '-signature', str(sig_path), str(zip_path))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'scrapers.zip').write_bytes(payload)
    (out / 'scrapers.sig').write_bytes(signature)
    print(json.dumps({'version': args.version, 'api': api_level(), 'app': args.app,
                      'bytes': len(payload), 'out': str(out)}))


if __name__ == '__main__':
    main()
