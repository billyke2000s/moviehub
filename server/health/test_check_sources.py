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

    def test_playing_for_six_seconds_is_not_enough_when_soaked(self):
        results = [
            {'id': 'dies', 'catalogue': 'ok', 'channels': 5,
             'samples': [{'play': 'ok', 'soak': 'FAILED: picture stopped after 7 s of 60 s'}, {'play': 'ok'}]},
            {'id': 'bait', 'catalogue': 'ok', 'channels': 5,
             'samples': [{'play': 'ok', 'soak': 'FAILED: the picture matches a known block screen (x.jpg)'}]},
            {'id': 'keeps', 'catalogue': 'ok', 'channels': 5, 'samples': [{'play': 'ok', 'soak': 'ok'}]},
            {'id': 'unsoaked', 'catalogue': 'ok', 'channels': 5, 'samples': [{'play': 'ok'}]},
        ]
        live = c.live_results(results)
        self.assertFalse(live['live:dies']['ok'])
        self.assertIn('failed the soak test: picture stopped after 7 s', live['live:dies']['summary'])
        self.assertFalse(live['live:bait']['ok'])
        self.assertIn('block screen', live['live:bait']['summary'])
        self.assertTrue(live['live:keeps']['ok'])
        self.assertIn('soak passed', live['live:keeps']['summary'])
        self.assertTrue(live['live:unsoaked']['ok'])

    def test_soak_pictures_are_copied_into_the_evidence(self):
        import tempfile
        with tempfile.TemporaryDirectory() as work:
            frame = Path(work) / 'dlhd-1-01.jpg'
            frame.write_bytes(b'jpg')
            result = {'id': 'live:dlhd', 'records': [{'samples': [
                {'play': 'ok', 'soak': 'ok', 'soakDetail': {'frames': [str(frame), '/missing/x.jpg']}}]}]}
            self.assertEqual(c.soak_frames(result), [frame])
            folder = c.save_evidence(Path(work) / 'evidence', result, 'stamp')
            self.assertTrue((folder / 'dlhd-1-01.jpg').is_file())


class MassFailure(unittest.TestCase):
    def test_a_few_broken_sources_are_tried_one_by_one(self):
        self.assertEqual(c.mass_failure(['live:a', 'live:b', 'vod:c'], 3), '')

    def test_many_at_once_is_one_alert_and_on_hold(self):
        message = c.mass_failure(['live:a', 'live:b', 'vod:c', 'vod:d'], 3)
        self.assertIn('4 sources broke in the same run', message)
        self.assertIn('live:a, live:b, vod:c, vod:d', message)
        self.assertIn(c.HOLD, message)


def issue(number, source, labels=('broken-source',), state='open'):
    return {'number': number, 'title': c.TITLE + source, 'state': state,
            'labels': [{'name': name} for name in labels], 'body': ''}


class AutoFixQueue(unittest.TestCase):
    def test_skips_on_hold_tried_and_closed_issues(self):
        import auto_fix
        self.assertEqual(auto_fix.skip_reason(issue(1, 'live:a')), '')
        self.assertIn('on hold', auto_fix.skip_reason(issue(2, 'live:a', ('broken-source', c.HOLD))))
        self.assertIn('already tried', auto_fix.skip_reason(issue(3, 'live:a', ('broken-source', c.TRIED))))
        self.assertIn('not an open', auto_fix.skip_reason(issue(4, 'live:a', state='closed')))

    def test_next_is_live_tv_first_then_oldest(self):
        import auto_fix
        waiting = [issue(5, 'vod:x'), issue(9, 'live:late'), issue(7, 'live:early'),
                   issue(3, 'live:held', ('broken-source', c.HOLD)), issue(2, 'vod:old')]
        self.assertEqual(auto_fix.choose_next(waiting)['number'], 7)
        self.assertEqual(auto_fix.choose_next([issue(5, 'vod:x'), issue(2, 'vod:old')])['number'], 2)
        self.assertIsNone(auto_fix.choose_next([issue(1, 'live:a', ('broken-source', c.TRIED))]))


class AutoFixPrompt(unittest.TestCase):
    def test_finds_each_sources_scraper_files(self):
        import auto_fix
        self.assertEqual(auto_fix.scraper_files('live:freeshot'), ['provider-engine/tools/freeshot.js'])
        dlhd = auto_fix.scraper_files('live:dlhd')
        self.assertIn('provider-engine/upstream/modules/dlhd/resolveStream.ts', dlhd)
        self.assertIn('provider-engine/tools/dlhd-session.js', dlhd)
        self.assertEqual(auto_fix.scraper_files('vod:scrubs:watchserieshd_stream'),
                         ['android-tv-launcher/app/src/main/python/moviehub_scrubs/providers/watchserieshd_stream.py'])
        self.assertEqual(auto_fix.scraper_files('live:nonexistent'), [])

    def test_prompt_has_the_code_the_failure_and_the_test(self):
        import auto_fix
        files = auto_fix.scraper_files('live:freeshot')
        prompt = auto_fix.build_prompt('live:freeshot', 41, 'failed the soak test: picture stopped after 7 s',
                                       'issue text', files)
        code = (c.ROOT / files[0]).read_text()
        self.assertIn(code.strip().splitlines()[0], prompt)
        self.assertIn('picture stopped after 7 s', prompt)
        self.assertIn('MOVIEHUB_SOAK_SECONDS=90', prompt)
        self.assertIn('live-probe.mjs freeshot', prompt)
        self.assertIn('npm run build --prefix provider-engine', prompt)
        self.assertIn('change nothing and say so', prompt)
        film = auto_fix.build_prompt('vod:scrubs:watchserieshd_stream', 3, 'no streams', '', [])
        self.assertIn('probe-free-vod.py --provider scrubs:watchserieshd_stream', film)


class SandboxPatch(unittest.TestCase):
    def test_patch_paths_include_both_sides_of_a_rename(self):
        import sandbox
        patch = ('diff --git a/provider-engine/tools/freeshot.js b/provider-engine/tools/freeshot.js\n'
                 'index 1..2 100644\n'
                 'diff --git a/provider-engine/tools/a.js b/server/health/check_sources.py\n'
                 'rename from provider-engine/tools/a.js\n')
        self.assertEqual(sandbox.patch_paths(patch), ['provider-engine/tools/freeshot.js', 'provider-engine/tools/a.js',
                                                      'server/health/check_sources.py'])

    def test_a_renamed_file_outside_the_scrapers_is_refused(self):
        import auto_fix
        import sandbox
        patch = 'diff --git a/provider-engine/tools/a.js b/server/gate/gate.py\n'
        self.assertEqual(auto_fix.rejected(sandbox.patch_paths(patch)), ['server/gate/gate.py'])

    def test_not_configured_without_all_settings(self):
        import sandbox
        self.assertFalse(sandbox.configured({'SANDBOX_HOST': 'root@x'}))
        self.assertTrue(sandbox.configured({'SANDBOX_HOST': 'root@x', 'PVE_URL': 'https://h:8006',
                                            'PVE_TOKEN': 't', 'SANDBOX_CTID': '105'}))


class AutoFixRules(unittest.TestCase):
    def test_a_fix_may_not_touch_the_checks(self):
        import auto_fix
        self.assertEqual(auto_fix.rejected([
            'provider-engine/tools/freeshot.js',
            'android-tv-launcher/app/src/main/python/moviehub_vod.py',
            'android-tv-launcher/app/src/main/assets/providers/builtin.json',
        ]), [])
        self.assertEqual(auto_fix.rejected(['provider-engine/dist/providers.json']),
                         ['provider-engine/dist/providers.json'])
        self.assertEqual(auto_fix.rejected([
            'provider-engine/tools/live-probe.mjs',
            'provider-engine/tools/soak.mjs',
            'server/health/bait-frames/blocked.jpg',
            'server/health/check_sources.py',
        ]), ['provider-engine/tools/live-probe.mjs', 'provider-engine/tools/soak.mjs',
             'server/health/bait-frames/blocked.jpg', 'server/health/check_sources.py'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
