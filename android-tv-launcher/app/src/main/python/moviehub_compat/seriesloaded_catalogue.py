"""Exact catalogue identities for SeriezLoaded's movie and episode cards."""
import re
from html import unescape

_MOVIE = re.compile(
    r"^(.+?)\s*\((\d{4})\)(?:\s*[-–—]\s*Hollywood Movie)?\s*$", re.I)
_EPISODE = re.compile(
    r"^(.+?)\s+Season\s+(\d+)\s+Episodes?\s+(\d+)"
    r"(?:\s*[-–—]\s*(\d+))?(?:\s*\(Complete(?:d)?\))?\s*$", re.I)

def movie_label(label, year):
    """Remove only the observed publisher suffix after an exact stated year."""
    match = _MOVIE.fullmatch(unescape(label).strip())
    if match and match[2] == str(year):
        return match[1].strip()
    return None

def episode_label(label, season, episode):
    """Return the show name only for its exact season and advertised episode/span."""
    match = _EPISODE.fullmatch(unescape(label).strip())
    if not match:
        return None
    try:
        requested_season, requested_episode = int(season), int(episode)
    except (TypeError, ValueError):
        return None
    first, last = int(match[3]), int(match[4] or match[3])
    if (requested_season > 0 and requested_episode > 0 and
            int(match[2]) == requested_season and
            0 < first <= requested_episode <= last):
        return match[1].strip()
    return None
