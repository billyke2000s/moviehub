"""
tmdb.py — TMDb browsing/metadata, run LOCALLY on the device.

Every call here goes device -> TMDb directly. None of it touches your VPS. The
TMDb key is pulled from settings (cached at login from the server), so the key is
stored on the server but used here on the device.

Ported from the Electron app's TMDb usage (same endpoints, same image base).
"""

import requests
import xbmcaddon

ADDON = xbmcaddon.Addon()

API = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p/"

# Image sizes — poster grid uses w500, backdrops w1280.
POSTER = IMG + "w500"
BACKDROP = IMG + "w1280"


def _key():
    return ADDON.getSetting("tmdb_key") or ""


class TmdbError(Exception):
    pass


def _get(path, params=None):
    params = params or {}
    params["api_key"] = _key()
    if not params["api_key"]:
        raise TmdbError("No TMDb key set for this profile.")
    try:
        r = requests.get(API + path, params=params, timeout=15)
    except requests.exceptions.ConnectionError:
        raise TmdbError("Couldn't reach TMDb — check your internet connection.")
    except requests.exceptions.Timeout:
        raise TmdbError("TMDb took too long to respond. Try again shortly.")
    if r.status_code == 401:
        raise TmdbError("Your TMDb key was rejected. Check it in the add-on.")
    if r.status_code >= 400:
        raise TmdbError("TMDb error (%s)." % r.status_code)
    try:
        return r.json()
    except Exception:
        raise TmdbError("TMDb sent an unreadable response.")


def validate_key(key):
    """Used at first-login to confirm a pasted key works, before saving it."""
    try:
        r = requests.get(API + "/configuration", params={"api_key": key}, timeout=10)
        return r.status_code == 200
    except Exception:
        return False


# ── browse rows ──────────────────────────────────────────────────────────────

def trending(media_type="movie", page=1):
    return _get("/trending/%s/week" % media_type, {"page": page})

def popular(media_type="movie", page=1):
    return _get("/%s/popular" % media_type, {"page": page})

def top_rated(media_type="movie", page=1):
    return _get("/%s/top_rated" % media_type, {"page": page})

def now_playing(page=1):
    return _get("/movie/now_playing", {"page": page})

def discover(media_type="movie", sort_by="popularity.desc", page=1, **kwargs):
    params = {"sort_by": sort_by, "page": page}
    params.update(kwargs)
    return _get("/discover/%s" % media_type, params)

def genres(media_type="movie"):
    return _get("/genre/%s/list" % media_type)

def by_genre(media_type, genre_id, page=1):
    return _get("/discover/%s" % media_type,
                {"with_genres": genre_id, "page": page, "sort_by": "popularity.desc"})


# ── search ───────────────────────────────────────────────────────────────────

def search_multi(query, page=1):
    return _get("/search/multi", {"query": query, "page": page})


# ── details (needs imdb id + episodes for streaming) ─────────────────────────

def details(media_type, tmdb_id):
    # append external_ids so we get the imdb id Torrentio needs, and credits.
    return _get("/%s/%s" % (media_type, tmdb_id),
                {"append_to_response": "external_ids,credits,videos,similar"})

def season(tmdb_id, season_number):
    return _get("/tv/%s/season/%s" % (tmdb_id, season_number))


# ─────────────────────────────────────────────────────────────────────────────
# FULL ORGANISED CATEGORY CATALOG
# Every TMDb browse angle, grouped into sections for tidy menus.
# ─────────────────────────────────────────────────────────────────────────────

# The full official TMDb genre id map (movies + tv share most; both listed).
MOVIE_GENRES = [
    (28, "Action"), (12, "Adventure"), (16, "Animation"), (35, "Comedy"),
    (80, "Crime"), (99, "Documentary"), (18, "Drama"), (10751, "Family"),
    (14, "Fantasy"), (36, "History"), (27, "Horror"), (10402, "Music"),
    (9648, "Mystery"), (10749, "Romance"), (878, "Science Fiction"),
    (10770, "TV Movie"), (53, "Thriller"), (10752, "War"), (37, "Western"),
]
TV_GENRES = [
    (10759, "Action & Adventure"), (16, "Animation"), (35, "Comedy"),
    (80, "Crime"), (99, "Documentary"), (18, "Drama"), (10751, "Family"),
    (10762, "Kids"), (9648, "Mystery"), (10763, "News"), (10764, "Reality"),
    (10765, "Sci-Fi & Fantasy"), (10766, "Soap"), (10767, "Talk"),
    (10768, "War & Politics"), (37, "Western"),
]

# Sort options offered in Discover.
SORT_OPTIONS = [
    ("popularity.desc", "Most Popular"),
    ("vote_average.desc", "Highest Rated"),
    ("primary_release_date.desc", "Newest"),
    ("primary_release_date.asc", "Oldest"),
    ("revenue.desc", "Highest Grossing"),
    ("vote_count.desc", "Most Voted"),
]

# Decades for "by decade" browsing.
DECADES = [
    ("2020", "2020s"), ("2010", "2010s"), ("2000", "2000s"), ("1990", "1990s"),
    ("1980", "1980s"), ("1970", "1970s"), ("1960", "1960s"), ("1950", "1950s"),
    ("1940", "1940s"), ("1930", "1930s"),
]

# Common languages for "by language". Each maps to the flag most people
# associate with it (flagcdn 2-letter codes) — loaded by URL, no download.
LANGUAGES = [
    ("en", "English", "gb"), ("ja", "Japanese", "jp"), ("ko", "Korean", "kr"),
    ("es", "Spanish", "es"), ("fr", "French", "fr"), ("de", "German", "de"),
    ("it", "Italian", "it"), ("hi", "Hindi", "in"), ("zh", "Chinese", "cn"),
    ("ru", "Russian", "ru"), ("pt", "Portuguese", "br"), ("sv", "Swedish", "se"),
    ("da", "Danish", "dk"), ("nl", "Dutch", "nl"), ("tr", "Turkish", "tr"),
    ("th", "Thai", "th"),
]

def flag_url(country_code):
    return "https://flagcdn.com/w320/%s.png" % country_code

# Popular streaming providers. Logo path is served by TMDb (loaded by URL, no
# download). These logo paths are TMDb's stable provider logos.
PROVIDERS = [
    (8, "Netflix", "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg"),
    (9, "Amazon Prime Video", "/emthp39XA2YScoYL1p0sdbAH2WA.jpg"),
    (337, "Disney Plus", "/97yvRBw1GzX7fXprcF80er19ot.jpg"),
    (1899, "Max", "/jbe4gVSfRlbPTdESXhEKpornsfu.jpg"),
    (15, "Hulu", "/zxrVdFjIjLqkfnwyghnfywTn3Lh.jpg"),
    (350, "Apple TV Plus", "/2E03IAZsX4ZaUqM7tXlctEPMGWS.jpg"),
    (531, "Paramount Plus", "/xbhHHa1YgtpwhC8lb1NQ3ACVcLd.jpg"),
    (386, "Peacock", "/xTHltMrZPAJFLQ6qyCBjAnXSmZk.jpg"),
    (283, "Crunchyroll", "/8Gt1iClBlzTeQs8WQm8UrCoIxnQ.jpg"),
]

def provider_logo_url(logo_path):
    return "https://image.tmdb.org/t/p/w200" + logo_path


def upcoming(page=1):
    return _get("/movie/upcoming", {"page": page})

def airing_today(page=1):
    return _get("/tv/airing_today", {"page": page})

def on_the_air(page=1):
    return _get("/tv/on_the_air", {"page": page})

def trending_day(media_type="movie", page=1):
    return _get("/trending/%s/day" % media_type, {"page": page})

def by_decade(media_type, decade, page=1):
    start = "%s-01-01" % decade
    end = "%d-12-31" % (int(decade) + 9)
    if media_type == "movie":
        return _get("/discover/movie", {
            "primary_release_date.gte": start, "primary_release_date.lte": end,
            "sort_by": "popularity.desc", "page": page})
    return _get("/discover/tv", {
        "first_air_date.gte": start, "first_air_date.lte": end,
        "sort_by": "popularity.desc", "page": page})

def by_language(media_type, lang, page=1):
    return _get("/discover/%s" % media_type, {
        "with_original_language": lang, "sort_by": "popularity.desc", "page": page})

def by_provider(media_type, provider_id, page=1, region="US"):
    return _get("/discover/%s" % media_type, {
        "with_watch_providers": provider_id, "watch_region": region,
        "sort_by": "popularity.desc", "page": page})

def by_sort(media_type, sort_by, page=1):
    return _get("/discover/%s" % media_type, {"sort_by": sort_by, "page": page})
