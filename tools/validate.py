#!/usr/bin/env python3
import glob
import hashlib
import pathlib
import py_compile
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]

for source in glob.glob(str(ROOT / "kodi" / "**" / "*.py"), recursive=True):
    py_compile.compile(source, doraise=True)

for document in glob.glob(str(ROOT / "kodi" / "**" / "*.xml"), recursive=True):
    ET.parse(document)

index = ROOT / "kodi" / "repository" / "addons.xml"
expected = (ROOT / "kodi" / "repository" / "addons.xml.md5").read_text().strip()
actual = hashlib.md5(index.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit("kodi/repository/addons.xml.md5 does not match addons.xml")

for package in (ROOT / "kodi" / "repository").glob("*.zip"):
    with zipfile.ZipFile(package) as archive:
        bad = archive.testzip()
        if bad:
            raise SystemExit(f"Corrupt member in {package.name}: {bad}")

published_installer = ROOT / "docs" / "repository.moviehub.zip"
canonical_installer = ROOT / "kodi" / "repository" / "repository.moviehub.zip"
if published_installer.read_bytes() != canonical_installer.read_bytes():
    raise SystemExit("docs/repository.moviehub.zip is not the current Kodi repository installer")

print("Movie Hub validation passed")
