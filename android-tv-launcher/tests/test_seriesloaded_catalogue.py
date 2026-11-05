"""Regression coverage for current SeriezLoaded catalogue selection."""
import unittest
from unittest.mock import patch
import moviehub_vod
from moviehub_compat.seriesloaded_catalogue import episode_label, movie_label
from moviehub_free99.providers import seriesloaded_ng as module

class SeriesLoadedTests(unittest.TestCase):
    def test_movie_year_and_only_observed_publisher_suffix(self):
        self.assertEqual(movie_label("Inception (2010)", "2010"), "Inception")
        self.assertEqual(movie_label("Dune: Part Two (2024) &#8211; Hollywood Movie", "2024"),
                         "Dune: Part Two")
        for label in ("Dune: Part Two (2023)", "Dune: Part Two (2024) Trailer",
                      "Dune: Part Two (2024) – Unrelated Film"):
            with self.subTest(label=label):
                self.assertIsNone(movie_label(label, "2024"))

    def test_episode_range_requires_exact_season_and_inclusive_episode(self):
        self.assertEqual(episode_label("Fixture Show Season 2 Episode 1 – 13 (Complete)", 2, 1),
                         "Fixture Show")
        self.assertEqual(episode_label("Fixture Show Season 02 Episode 01", 2, 1), "Fixture Show")
        for label in ("Fixture Show Season 2 Episode 10",
                      "Fixture Show Season 2 Episode 10 – 13 (Complete)",
                      "Fixture Show Season 3 Episode 1 – 13 (Complete)",
                      "Fixture Show Season 2 Episode 13 – 1 (Complete)"):
            with self.subTest(label=label):
                self.assertIsNone(episode_label(label, 2, 1))
        self.assertIsNone(episode_label("Fixture Show Season 2 Episode 1", "bad", 1))

    def series(self, label, buttons):
        provider = module.source()
        key = provider.tvshow("tt0903747", "1396", "", "Fixture Show", "Fixture Show",
                              [{"title": "Fixture Show"}], "2008")
        key = provider.episode(key, "tt0903747", "1396", "", "Episode", "2009-01-01", "2", "1")
        search = '<h3 class="entry-title"><a href="https://seriezloaded.tv/fixture/">%s</a></h3>' % label
        article = '<article id="post-42">%s</article>' % buttons
        calls = []
        def fetch(url, **kwargs):
            calls.append(url)
            if "/?s=" in url:
                return search
            if url == "https://seriezloaded.tv/fixture/":
                return article
            raise AssertionError("Unadvertised request")
        with patch.object(module.client, "request", side_effect=fetch), \
             patch.object(module.scrape_sources, "process",
                          side_effect=lambda hosts, url: [{"url": url}]) as process:
            result = provider.sources(key, [])
        return result, calls, process

    def test_range_reaches_episode_one_without_selecting_episode_ten(self):
        result, calls, process = self.series(
            "Fixture Show Season 2 Episode 1 – 13 (Complete)",
            '<a class="btn-ghost" href="https://media.example/ten.mp4">Episode 10</a>'
            '<a class="btn-ghost" href="https://media.example/one.mp4">Episode 1</a>')
        self.assertEqual([item["url"] for item in result], ["https://media.example/one.mp4"])
        self.assertEqual(len(calls), 2)
        process.assert_called_once()

    def test_missing_requested_episode_has_no_other_episode_fallback(self):
        result, calls, process = self.series(
            "Fixture Show Season 2 Episode 1 – 13 (Complete)",
            '<a class="btn-ghost" href="https://media.example/ten.mp4">Episode 10</a>')
        self.assertFalse(result)
        process.assert_not_called()

    def test_other_show_or_season_cannot_select_a_catalogue_post(self):
        for label in ("Fixture Show Extra Season 2 Episode 1 – 13 (Complete)",
                      "Fixture Show Season 3 Episode 1 – 13 (Complete)"):
            with self.subTest(label=label):
                result, calls, process = self.series(label, "")
                self.assertFalse(result)
                self.assertEqual(len(calls), 1)
                process.assert_not_called()

    def test_movie_publisher_suffix_reaches_only_the_matching_year(self):
        for year, expected in (("2024", True), ("2023", False)):
            with self.subTest(year=year):
                provider = module.source()
                key = provider.movie("tt15239678", "693134", "Dune: Part Two", "Dune: Part Two",
                                     [{"title": "Dune: Part Two"}], year)
                search = ('<h3 class="entry-title"><a href="https://seriezloaded.tv/film/">'
                          'Dune: Part Two (2024) – Hollywood Movie</a></h3>')
                article = ('<a class="btn-ghost" href="https://media.example/film.mp4">'
                           'Download Movie</a>')
                with patch.object(module.client, "request", side_effect=lambda url, **kwargs: search if "/?s=" in url else article), \
                     patch.object(module.scrape_sources, "process",
                                  side_effect=lambda hosts, url: [{"url": url}]) as process:
                    result = provider.sources(key, [])
                self.assertEqual(bool(result), expected)
                self.assertEqual(process.call_count, int(expected))

    def test_short_catalogue_fallback_keeps_full_title_and_year_identity(self):
        provider = module.source()
        key = provider.movie("tt15239678", "693134", "Dune: Part Two", "Dune: Part Two",
                             [{"title": "Dune: Part Two"}], "2024")
        cards = ('<h3 class="entry-title"><a href="https://seriezloaded.tv/first/">'
                 'Dune: Part One (2021)</a></h3>'
                 '<h3 class="entry-title"><a href="https://seriezloaded.tv/second/">'
                 'Dune: Part Two (2024) – Hollywood Movie</a></h3>')
        article = ('<a class="btn-ghost" href="https://media.example/second.mp4">'
                   'Download Movie</a>')
        calls = []
        def fetch(url, **kwargs):
            calls.append(url)
            if url.endswith("/?s=dune+part+two"):
                return "<h1>No results</h1>"
            if url.endswith("/?s=dune"):
                return cards
            if url == "https://seriezloaded.tv/second/":
                return article
            raise AssertionError("Unexpected title or request")
        with patch.object(module.client, "request", side_effect=fetch), \
             patch.object(module.scrape_sources, "process",
                          side_effect=lambda hosts, url: [{"url": url}]):
            result = provider.sources(key, [])
        self.assertEqual([item["url"] for item in result], ["https://media.example/second.mp4"])
        self.assertEqual(len(calls), 3)
