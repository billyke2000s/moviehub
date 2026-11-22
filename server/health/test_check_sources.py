"""Tests for the health checker's decisions. Run: python3 server/health/test_check_sources.py"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_sources as c  # noqa: E402


def line(provider, streams, status='ok'):
    return json.dumps({'provider': provider, 'status': status, 'streams': streams})


class Decisions(unittest.TestCase):
    def test_film_site_healthy_if_either_film_works(self):
        runs = ['\n'.join([line('a', 0), line('b', 0, 'timeout'), '{"providers": 2}']),
                '\n'.join([line('a', 3), line('b', 0, 'error')])]
        sites = c.vod_results(runs)
        self.assertTrue(sites['vod:a']['ok'])
        self.assertFalse(sites['vod:b']['ok'])
        self.assertIn('error', sites['vod:b']['summary'])
        self.assertIn('timeout', sites['vod:b']['summary'])

    def test_live_needs_catalogue_and_a_playing_sample(self):
        results = [
            {'id': 'good', 'catalogue': 'ok', 'channels': 5, 'samples': [{'play': 'PROVIDER?: x'}, {'play': 'ok'}]},
            {'id': 'noplay', 'catalogue': 'ok', 'channels': 5, 'samples': [{'play': 'PROVIDER?: x'}]},
            {'id': 'down', 'catalogue': 'PROVIDER: catalogue HTTP 403', 'samples': []},
            {'id': 'zlive', 'catalogue': 'ok', 'channels': 252, 'samples': []},
        ]
        live = c.live_results(results)
        self.assertTrue(live['live:good']['ok'])
        self.assertFalse(live['live:noplay']['ok'])
        self.assertFalse(live['live:down']['ok'])
        self.assertIn('403', live['live:down']['summary'])
        self.assertTrue(live['live:zlive']['ok'])

    def test_one_blip_is_ignored_two_in_a_row_open_one_issue(self):
        state = {}
        bad = {'vod:a': {'ok': False}}
        good = {'vod:a': {'ok': True}}
        self.assertEqual(c.decide(state, bad, 2, 0), ([], [], []))
        self.assertEqual(c.decide(state, good, 2, 0), ([], [], []))  # blip over, counter reset
        self.assertEqual(c.decide(state, bad, 2, 0), ([], [], []))
        self.assertEqual(c.decide(state, bad, 2, 0), (['vod:a'], [], []))
        self.assertEqual(c.decide(state, bad, 2, 0), ([], ['vod:a'], []))
        self.assertEqual(c.decide(state, good, 2, 0), ([], [], ['vod:a']))
        self.assertFalse(state['vod:a']['broken'])

    def test_live_and_film_sources_use_their_own_thresholds(self):
        state = {}
        runs = lambda key: 1 if key.startswith('live:') else 2
        bad = {'live:dlhd': {'ok': False}, 'vod:a': {'ok': False}}
        self.assertEqual(c.decide(state, bad, runs, 0), (['live:dlhd'], [], []))
        self.assertEqual(c.decide(state, bad, runs, 0), (['vod:a'], ['live:dlhd'], []))

    def test_hourly_live_run_leaves_film_state_alone(self):
        state = {'vod:a': {'fails': 1, 'broken': False, 'lastComment': 0}}
        c.decide(state, {'live:x': {'ok': True}}, 1, 0)
        self.assertEqual(state['vod:a']['fails'], 1)

    def test_live_blip_is_retried_before_counting(self):
        calls, waits = [], []
        rows = {
            (): [{'id': 'dlhd', 'catalogue': 'ok', 'channels': 3, 'samples': [{'play': 'PROVIDER?: 403'}]},
                 {'id': 'zlive', 'catalogue': 'ok', 'channels': 9, 'samples': []},
                 {'id': 'freeshot', 'catalogue': 'PROVIDER: HTTP 503', 'samples': []}],
            ('dlhd', 'freeshot'): [
                {'id': 'dlhd', 'catalogue': 'ok', 'channels': 3, 'samples': [{'play': 'ok'}]},
                {'id': 'freeshot', 'catalogue': 'PROVIDER: HTTP 503', 'samples': []}],
        }

        def run(ids=()):
            calls.append(tuple(ids))
            return rows[tuple(ids)]

        results = c.live_with_retry(run, 300, sleep=waits.append)
        self.assertEqual(calls, [(), ('dlhd', 'freeshot')])  # only the failing ones re-tested
        self.assertEqual(waits, [300])
        self.assertTrue(results['live:dlhd']['ok'])  # blip: recovered on retry
        self.assertTrue(results['live:zlive']['ok'])
        self.assertFalse(results['live:freeshot']['ok'])
        self.assertIn('failed twice', results['live:freeshot']['summary'])

    def test_no_retry_when_everything_works(self):
        calls = []
        results = c.live_with_retry(lambda ids=(): calls.append(ids) or [
            {'id': 'zlive', 'catalogue': 'ok', 'channels': 9, 'samples': []}], 300, sleep=None)
        self.assertEqual(len(calls), 1)
        self.assertTrue(results['live:zlive']['ok'])

    def test_issue_body_points_at_playbook_and_reproduction(self):
        result = {'id': 'vod:free99:levidia_ch', 'kind': 'film/series site', 'summary': 'no streams',
                  'records': [{'provider': 'free99:levidia_ch', 'streams': 0}]}
        body = c.issue_body(result, '/srv/moviehub/health/evidence/x')
        self.assertIn('FIX_A_BROKEN_SOURCE.md', body)
        self.assertIn('--provider free99:levidia_ch', body)
        live = c.issue_body({'id': 'live:dlhd', 'kind': 'Live TV provider', 'summary': 's', 'records': []}, 'f')
        self.assertIn('live-probe.mjs dlhd', live)


if __name__ == '__main__':
    unittest.main(verbosity=2)
