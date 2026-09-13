#!/usr/bin/env python3
"""Build byte-for-byte reproducible Movie Hub Kodi repository artifacts."""

import argparse
import hashlib
import pathlib
import shutil
import stat
import zipfile
import xml.etree.ElementTree as ET


FIXED_TIME = (2020, 1, 1, 0, 0, 0)


def zip_bytes(archive, name, data, executable=False):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    mode = 0o755 if executable else 0o644
    info.external_attr = (stat.S_IFREG | mode) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, data)


def zip_tree(source, destination):
    root_name = source.name
    with zipfile.ZipFile(destination, "w") as archive:
        for path in sorted(p for p in source.rglob("*") if p.is_file()):
            if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
                continue
            relative = path.relative_to(source).as_posix()
            zip_bytes(
                archive,
                f"{root_name}/{relative}",
                path.read_bytes(),
                executable=bool(path.stat().st_mode & stat.S_IXUSR),
            )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--addon-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--pages-output", type=pathlib.Path)
    args = parser.parse_args()

    addon_xml_path = args.addon_dir / "addon.xml"
    plugin_xml = ET.fromstring(addon_xml_path.read_bytes())
    addon_id = plugin_xml.attrib["id"]
    version = plugin_xml.attrib["version"]
    package_name = f"{addon_id}-{version}.zip"
    base = args.url.rstrip("/") + "/kodi/repository/"

    args.output.mkdir(parents=True, exist_ok=True)
    plugin_zip = args.output / package_name
    zip_tree(args.addon_dir, plugin_zip)

    repository_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<addon id="repository.moviehub" name="Movie Hub Repository" version="1.1.0" provider-name="Movie Hub">
  <extension point="xbmc.addon.repository" name="Movie Hub updates">
    <dir>
      <info compressed="false">{base}addons.xml</info>
      <checksum>{base}addons.xml.md5</checksum>
      <datadir zip="true">{base}</datadir>
    </dir>
  </extension>
  <extension point="xbmc.addon.metadata">
    <summary lang="en_GB">Official updates for Movie Hub</summary>
    <description lang="en_GB">Installs and automatically updates Movie Hub through Kodi.</description>
    <platform>all</platform>
    <license>MIT</license>
  </extension>
</addon>'''

    repository_zip = args.output / "repository.moviehub.zip"
    with zipfile.ZipFile(repository_zip, "w") as archive:
        zip_bytes(
            archive,
            "repository.moviehub/addon.xml",
            repository_xml.encode("utf-8"),
        )

    root = ET.Element("addons")
    root.append(plugin_xml)
    root.append(ET.fromstring(repository_xml))
    index = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    (args.output / "addons.xml").write_bytes(index)
    (args.output / "addons.xml.md5").write_text(
        hashlib.md5(index).hexdigest(), encoding="ascii"
    )

    if args.pages_output:
        args.pages_output.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repository_zip, args.pages_output / repository_zip.name)


if __name__ == "__main__":
    main()
