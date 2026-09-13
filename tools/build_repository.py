#!/usr/bin/env python3
"""Build a Kodi repository index and installer for one private Worker."""

import argparse
import hashlib
import pathlib
import shutil
import tempfile
import zipfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--addon", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    base = args.url.rstrip("/") + "/kodi/repository/"
    args.output.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(args.addon) as package:
        plugin_xml = ET.fromstring(package.read("plugin.video.moviehub/addon.xml"))

    repository_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<addon id="repository.moviehub" name="Movie Hub Repository" version="1.0.0" provider-name="Movie Hub">
  <extension point="xbmc.addon.repository" name="Movie Hub updates">
    <dir>
      <info compressed="false">{base}addons.xml</info>
      <checksum>{base}addons.xml.md5</checksum>
      <datadir zip="true">{base}</datadir>
    </dir>
  </extension>
  <extension point="xbmc.addon.metadata">
    <summary lang="en_GB">Private updates for Movie Hub</summary>
    <description lang="en_GB">Keeps this private Movie Hub installation current through Kodi.</description>
    <platform>all</platform>
    <license>MIT</license>
  </extension>
</addon>'''

    repo_dir = pathlib.Path(tempfile.mkdtemp()) / "repository.moviehub"
    repo_dir.mkdir()
    (repo_dir / "addon.xml").write_text(repository_xml, encoding="utf-8")
    repo_zip = args.output / "repository.moviehub.zip"
    with zipfile.ZipFile(repo_zip, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(repo_dir / "addon.xml", "repository.moviehub/addon.xml")

    root = ET.Element("addons")
    root.append(plugin_xml)
    root.append(ET.fromstring(repository_xml))
    index = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    (args.output / "addons.xml").write_bytes(index)
    (args.output / "addons.xml.md5").write_text(hashlib.md5(index).hexdigest(), encoding="ascii")
    destination = args.output / args.addon.name
    if args.addon.resolve() != destination.resolve():
        shutil.copy2(args.addon, destination)


if __name__ == "__main__":
    main()
