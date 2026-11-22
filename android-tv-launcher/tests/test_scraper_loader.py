"""moviehub_loader: a downloaded scraper package is used, and a broken one falls back to the APK's.

Run: python android-tv-launcher/tests/test_scraper_loader.py   (needs requests and six)
No network: moviehub_vod's import and search_providers() only read bundled files.
"""
import importlib
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

BUILTIN = Path(__file__).resolve().parents[1] / 'app/src/main/python'
sys.path.append(str(BUILTIN))  # The APK's copy, after everything else, as Chaquopy does.
import moviehub_loader as loader  # noqa: E402


def package(folder, change=None):
    """A copy of the python folder as a package would unpack it (without the loader)."""
    target = Path(folder) / 'python'
    shutil.copytree(BUILTIN, target, ignore=shutil.ignore_patterns('__pycache__', 'moviehub_loader.py'))
    if change:
        change(target)
    return str(target)


class LoaderTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.work)
        self.addCleanup(loader.activate, '')

    def vod(self):
        return importlib.import_module('moviehub_vod')

    def test_builtin_when_no_package(self):
        self.assertEqual(loader.activate(''), 'builtin')
        self.assertTrue(os.path.realpath(self.vod().__file__).startswith(str(BUILTIN.resolve())))

    def test_package_replaces_builtin_including_subpackages(self):
        def mark(python):
            with open(python / 'moviehub_vod.py', 'a') as f:
                f.write('\nPACKAGE_MARK = "new"\n')
            with open(python / 'moviehub_native' / 'primewire.py', 'a') as f:
                f.write('\nPACKAGE_MARK = "new"\n')
        path = package(self.work, mark)
        self.assertEqual(loader.activate(path), 'overlay')
        self.assertEqual(self.vod().PACKAGE_MARK, 'new')
        primewire = importlib.import_module('moviehub_native.primewire')
        self.assertEqual(primewire.PACKAGE_MARK, 'new')
        self.assertTrue(os.path.realpath(primewire.__file__).startswith(os.path.realpath(path)))

    def test_broken_package_falls_back_to_builtin(self):
        def corrupt(python):
            (python / 'moviehub_vod.py').write_text('def broken(:\n')
        result = loader.activate(package(self.work, corrupt))
        self.assertTrue(result.startswith('builtin: SyntaxError'), result)
        vod = self.vod()
        self.assertFalse(hasattr(vod, 'PACKAGE_MARK'))
        self.assertTrue(os.path.realpath(vod.__file__).startswith(str(BUILTIN.resolve())))
        self.assertTrue(vod.search_providers())

    def test_package_whose_website_list_fails_falls_back(self):
        def bad_list(python):
            (python / 'moviehub_providers.json').write_text('[{"broken": true}]')
        result = loader.activate(package(self.work, bad_list))
        self.assertTrue(result.startswith('builtin:'), result)
        self.assertTrue(self.vod().search_providers())

    def test_switching_back_to_builtin(self):
        self.assertEqual(loader.activate(package(self.work)), 'overlay')
        self.assertEqual(loader.activate(''), 'builtin')
        self.assertTrue(os.path.realpath(self.vod().__file__).startswith(str(BUILTIN.resolve())))
        self.assertNotIn(os.path.join(self.work, 'python'), sys.path)

    def test_missing_folder_falls_back(self):
        result = loader.activate(os.path.join(self.work, 'nothing-here'))
        self.assertTrue(result.startswith('builtin:'), result)


if __name__ == '__main__':
    unittest.main(verbosity=2)
