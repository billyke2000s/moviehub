"""Reject contradictory provider evidence before resolving a host. No invented filename proof."""
import re

def matches(item, wanted):
    for field in ('imdb', 'tmdb'):
        if item.get(field) and wanted.get(field) and str(item[field]) != str(wanted[field]):
            return False
    season, episode = wanted.get('season', -1), wanted.get('episode', -1)
    if season < 0: return True
    for field, value in (('season', season), ('episode', episode)):
        if field in item and str(item[field]) != str(value): return False
    # Only actual provider filenames are evidence. The requested title is never substituted here.
    filename = str(item.get('filename') or item.get('file') or '')
    named = re.search(r'(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?!\d)', filename)
    if named and (int(named[1]), int(named[2])) != (season, episode): return False
    return True
