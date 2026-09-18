#!/usr/bin/env python3
import glob
import hashlib
import json
import pathlib
import py_compile
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
ADDON = ROOT / "kodi" / "plugin.video.moviehub"
REPOSITORY = ROOT / "kodi" / "repository"

for source in glob.glob(str(ADDON / "**" / "*.py"), recursive=True):
    py_compile.compile(source, doraise=True)

for document in glob.glob(str(ADDON / "**" / "*.xml"), recursive=True):
    ET.parse(document)

for document in (
    ROOT / "package.json",
    ROOT / "cloudflare" / "package.json",
    ROOT / "cloudflare" / "wrangler.jsonc",
):
    text = document.read_text(encoding="utf-8")
    if document.suffix == ".jsonc":
        text = "\n".join(line.split("//", 1)[0] for line in text.splitlines())
    json.loads(text)

index = REPOSITORY / "addons.xml"
expected = (REPOSITORY / "addons.xml.md5").read_text().strip()
actual = hashlib.md5(index.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit("kodi/repository/addons.xml.md5 does not match addons.xml")

for package in REPOSITORY.glob("**/*.zip"):
    with zipfile.ZipFile(package) as archive:
        bad = archive.testzip()
        if bad:
            raise SystemExit(f"Corrupt member in {package.name}: {bad}")

addon_xml = ET.parse(ADDON / "addon.xml").getroot()
addon_id = addon_xml.attrib["id"]
version = addon_xml.attrib["version"]
plugin_name = f"{addon_id}-{version}.zip"

# Kodi requires zip="true" datadir packages at
# <datadir>/<addon-id>/<addon-id>-<version>.zip
# https://kodi.wiki/view/Add-on_repositories
plugin_packages = sorted(p.name for p in (REPOSITORY / addon_id).glob(f"{addon_id}-*.zip"))
if plugin_packages != [plugin_name]:
    raise SystemExit(
        f"Expected only kodi/repository/{addon_id}/{plugin_name}; found {plugin_packages}"
    )

with tempfile.TemporaryDirectory() as directory:
    temporary = pathlib.Path(directory)
    generated_repo = temporary / "repository"
    generated_docs = temporary / "docs"
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "tools" / "build_repository.py"),
            "--url",
            "https://raw.githubusercontent.com/billyke2000s/moviehub/main",
            "--addon-dir",
            str(ADDON),
            "--output",
            str(generated_repo),
            "--pages-output",
            str(generated_docs),
        ],
        check=True,
    )
    for name in ("repository.moviehub.zip", "addons.xml", "addons.xml.md5"):
        if (generated_repo / name).read_bytes() != (REPOSITORY / name).read_bytes():
            raise SystemExit(f"{name} was not generated reproducibly")
    if (generated_repo / addon_id / plugin_name).read_bytes() != (
        REPOSITORY / addon_id / plugin_name
    ).read_bytes():
        raise SystemExit(f"{plugin_name} was not generated reproducibly")
    if (generated_docs / "repository.moviehub.zip").read_bytes() != (
        ROOT / "docs" / "repository.moviehub.zip"
    ).read_bytes():
        raise SystemExit("docs/repository.moviehub.zip is stale")

subprocess.run(
    ["npm", "test", "--prefix", str(ROOT / "cloudflare")],
    check=True,
)

print("Movie Hub validation passed")
