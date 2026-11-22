"""Chooses which copy of the film/series scrapers runs: a downloaded package or the APK's own.

FreeVodProviders calls activate() with the folder of a verified, signed package (or "" for the APK's
copy). This file is never replaced by a package (ScraperBundle refuses it), so a broken package can
always be backed out of.
"""
import importlib
import os
import sys

# Top-level names a package may provide. Anything else (requests, six, the standard library) always
# comes from the APK.
PACKAGES = frozenset({
    'moviehub_vod', 'moviehub_identity', 'moviehub_compat', 'moviehub_free99',
    'moviehub_native', 'moviehub_scrubs', 'resolveurl', 'simplejson',
})

_overlays = set()


def _purge():
    for name in list(sys.modules):
        if name.split('.')[0] in PACKAGES:
            del sys.modules[name]
    importlib.invalidate_caches()


def _remove_overlays():
    for path in list(_overlays):
        while path in sys.path:
            sys.path.remove(path)
    _overlays.clear()


def _inside(module, folder):
    location = os.path.realpath(getattr(module, '__file__', '') or '')
    return location.startswith(os.path.realpath(folder) + os.sep)


def activate(path):
    """Returns 'overlay' when the package at path is in use, otherwise 'builtin' or 'builtin: <why>'.

    Modules already handed to a running search keep working; new calls get the newly chosen copy.
    """
    _remove_overlays()
    _purge()
    if not path:
        importlib.import_module('moviehub_vod')
        return 'builtin'
    sys.path.insert(0, path)
    _overlays.add(path)
    try:
        vod = importlib.import_module('moviehub_vod')
        if not _inside(vod, path):
            raise ImportError('package folder was not used')
        # Smoke test: the website list must build without touching the network.
        vod.search_providers()
        return 'overlay'
    except Exception as error:  # Any failure means: back to the APK's own scrapers.
        _remove_overlays()
        _purge()
        importlib.import_module('moviehub_vod')
        return 'builtin: %s: %s' % (type(error).__name__, str(error)[:200])
