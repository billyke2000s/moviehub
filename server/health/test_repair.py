"""Offline repair regressions. Git and the proposal subprocess are real, Forgejo and playback use fixtures."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import auto_fix as repair
import check_sources as health
import playback_evidence as ev


def vod_rows(site='library', tmdb=10331, season=-1, episode=-1, status='verified'):
    case = dict(website=site, tmdb=tmdb, season=season, episode=episode)
    return [dict(case, kind='lookup', sources=1, status='complete'),
            dict(case, kind='playback', status=status, decoded=True, decodedFrames=40,
                 decodedAudio=20, playedAudioBuffers=18, plausibleRuntime=True, midpointDecoded=True),
            dict(kind='finished', shard=0, shards=1)]


def live_rows(channel='f', **fields):
    sample = dict(name='Fixture', caseId=channel * 64, play='ok', resolve='ok (fixture)',
                  videoFrames=30, audioFrames=20)
    sample.update(fields)
    return [dict(id='demo', catalogue='ok', samples=[sample])]


class Evidence(unittest.TestCase):
    def test_exact_movie_with_decoder_counters_seek_and_still_failing_base(self):
        bad = ev.vod_cases(vod_rows(status='runtime_mismatch'), 'library')
        good = ev.vod_cases(vod_rows(), 'library')
        self.assertIn('40 video frames', ev.transition(bad, good, bad)[0])
        self.assertFalse(ev.transition(bad, good, good))
        self.assertFalse(ev.transition(bad, good, {}))

    def test_movie_name_or_episode_fallback_cannot_complete_a_different_case(self):
        bad = ev.vod_cases(vod_rows(season=2, episode=1, status='no_decoded_video'), 'library')
        for changed in (dict(season=2, episode=2), dict(tmdb=99, season=2, episode=1)):
            self.assertFalse(ev.transition(bad, ev.vod_cases(vod_rows(**changed), 'library'), bad))
        with self.assertRaises(ValueError):
            ev.vod_cases(vod_rows('fallback'), 'library')

    def test_green_status_or_http_response_without_playback_proof(self):
        for change in (dict(decodedAudio=0, playedAudioBuffers=0), dict(midpointDecoded=False),
                       dict(decodedFrames=0), dict(plausibleRuntime=False), dict(status='HTTP 200')):
            rows = vod_rows()
            rows[1].update(change)
            self.assertFalse(ev.vod_cases(rows, 'library'))
        rows = vod_rows()
        with self.assertRaises(ValueError):
            ev.vod_cases(rows[:-1], 'library')
        with self.assertRaises(ValueError):
            ev.vod_cases(rows[::2], 'library')

    def test_channel_needs_same_id_and_measured_audio_and_video(self):
        bad = ev.live_cases(live_rows(play='PROVIDER?: decode'), 'demo')
        good = ev.live_cases(live_rows(), 'demo')
        self.assertTrue(ev.transition(bad, good, bad))
        for rows in (live_rows('e'), live_rows(videoFrames=1), live_rows(audioFrames=0),
                     live_rows(caseId=''), [dict(id='demo', catalogue='ok', samples=[])]):
            self.assertFalse(ev.transition(bad, ev.live_cases(rows, 'demo'), bad))
        dup = live_rows()
        dup[0]['samples'] *= 2
        self.assertFalse(ev.live_cases(dup, 'demo'))

    def test_inconclusive_telemetry_cannot_close_an_incident_or_count_an_outage(self):
        state = {'vod:x': dict(fails=2, broken=True, lastComment=1)}
        before = state['vod:x'].copy()
        result = health.vod_results([json.dumps(dict(provider='x', streams=5))])
        self.assertEqual(health.decide(state, result, 2, 42), ([], [], []))
        self.assertEqual(state['vod:x'], before)
        self.assertIsNone(health.live_results(live_rows(videoFrames=0))['live:demo']['ok'])

    def test_failed_probe_cannot_reuse_an_old_report(self):
        with tempfile.TemporaryDirectory() as temp:
            old = Path(temp) / 'live.json'
            old.write_text(json.dumps(live_rows()))
            with patch.object(health, 'LIVE_RESULT', old), patch.dict(os.environ, RHINO_JAR=__file__), \
                 patch.object(health.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'failed')):
                self.assertEqual(health.run_live(), [])
                self.assertFalse(old.exists())

    def test_missing_retry_stays_unknown(self):
        calls = iter([live_rows(play='PROVIDER?: failed'), []])
        result = health.live_with_retry(lambda *args: next(calls), 1, sleep=lambda _: None)
        self.assertIsNone(result['live:demo']['ok'])


class Forgejo:
    repo = 'test/moviehub'

    def __init__(self):
        self.comments = []
        self.pulls = []

    def call(self, method, path, body=None):
        if path == '/issues/7':
            return dict(state='open', title='[broken source] vod:library', body='The fixture fails.',
                        comments=len(self.comments))
        if method == 'GET' and path.startswith('/issues/7/comments'):
            return self.comments
        if method == 'POST' and path == '/issues/7/comments':
            self.comments.append(body)
            return
        if method == 'GET' and path.startswith('/pulls?'):
            return [dict(head=dict(ref=p['head']), base=dict(ref=p['base'])) for p in self.pulls]
        if method == 'POST' and path == '/pulls':
            self.pulls.append(body)
            return
        raise AssertionError((method, path))


class Workflow(unittest.TestCase):
    def setUp(self):
        temp = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(temp) / 'main'
        self.root.mkdir()
        self.origin = Path(temp) / 'origin.git'
        self.file = Path(repair.VOD_FOLDER) / 'fixture.py'
        (self.root / self.file).parent.mkdir(parents=True)
        (self.root / self.file).write_text('broken')
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@localhost')
        self.git('add', '.')
        self.git('commit', '-qm', 'Initial fixture')
        self.git('init', '--bare', '-q', str(self.origin))
        self.git('remote', 'add', 'origin', str(self.origin))
        self.git('push', '-qu', 'origin', 'main')
        self.worker = Path(temp) / 'worker.py'
        self.count = Path(temp) / 'calls'
        self.forge = Forgejo()
        self.enterContext(patch.object(repair, 'ROOT', self.root))
        self.enterContext(patch.dict(os.environ, CODEX_CMD=sys.executable + ' ' + str(self.worker), NTFY_URL=''))

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True).strip()

    def worker_script(self, changes):
        self.worker.write_text('from pathlib import Path\nimport sys\n'
                               + 'with Path(%r).open("a") as out: out.write("call\\n")\n' % str(self.count)
                               + changes)

    def test_actual_branch_reused_after_inconclusive_check_and_pr_contains_measured_transition(self):
        self.worker_script('Path(%r).write_text("patched")\n' % str(self.file))
        with patch.object(ev, 'measure', side_effect=ValueError('no test emulator')):
            self.assertFalse(repair.handle(self.forge, 7))
        self.assertFalse(self.forge.pulls)
        self.assertIn('Saved candidate', self.forge.comments[-1]['body'])
        self.assertEqual((self.root / self.file).read_text(), 'broken')
        self.assertTrue(self.git('ls-remote', '--heads', 'origin', 'refs/heads/repair/issue-7'))
        seen = []

        def measure(folder, source):
            content = (folder / self.file).read_text()
            seen.append(content)
            return ev.vod_cases(vod_rows(status='verified' if content == 'patched' else 'no_decoded_video'), 'library')

        with patch.object(ev, 'measure', side_effect=measure):
            self.assertTrue(repair.handle(self.forge, 7))
            self.assertTrue(repair.handle(self.forge, 7))
        self.assertEqual(seen, ['broken', 'patched', 'broken'] * 2)
        self.assertEqual(self.count.read_text(), 'call\n')
        self.assertEqual(len(self.forge.pulls), 1)
        self.assertIn('40 video frames', self.forge.pulls[0]['body'])
        self.assertNotIn('Fixes #', self.forge.pulls[0]['body'])

    def test_diagnosis_only_persists_and_next_dispatch_receives_it(self):
        self.worker_script('print("Existing code looks correct")\n')
        self.assertFalse(repair.handle(self.forge, 7))
        self.assertIn(repair.DIAGNOSIS, self.forge.comments[-1]['body'])
        self.assertFalse(repair.handle(self.forge, 7, retry_diagnosis=False))
        self.assertEqual(self.count.read_text(), 'call\n')
        self.worker_script('assert "Existing code looks correct" in sys.argv[-1]\nprint("No patch")')
        self.assertFalse(repair.handle(self.forge, 7))
        self.assertIn('No patch', self.forge.comments[-1]['body'])
        self.assertFalse(self.git('ls-remote', '--heads', 'origin', 'refs/heads/repair/issue-7'))

    def test_verifier_edits_rejected_before_branch_or_measurement(self):
        self.worker_script('Path("evidence.py").write_text("always_pass = True")')
        with patch.object(ev, 'measure', side_effect=AssertionError('verifier must not run')):
            self.assertFalse(repair.handle(self.forge, 7))
        self.assertIn('unsupported path', self.forge.comments[-1]['body'])
        self.assertFalse(self.git('ls-remote', '--heads', 'origin', 'refs/heads/repair/issue-7'))

    def test_natural_recovery_creates_no_repair_pr(self):
        self.worker_script('Path(%r).write_text("patched")\n' % str(self.file))
        outcomes = [ev.vod_cases(vod_rows(status=status), 'library')
                    for status in ('no_decoded_video', 'verified', 'verified')]
        with patch.object(ev, 'measure', side_effect=outcomes):
            self.assertFalse(repair.handle(self.forge, 7))
        self.assertFalse(self.forge.pulls)
        self.assertIn('did not repeat', self.forge.comments[-1]['body'])

    def test_fix_of_unrelated_title_cannot_open_a_review(self):
        self.worker_script('Path(%r).write_text("patched")\n' % str(self.file))
        real_call = self.forge.call

        def only_other_title(method, path, body=None):
            response = real_call(method, path, body)
            if path == '/issues/7':
                response['body'] = '<!-- moviehub-playback-cases [[27205, -1, -1]] -->'
            return response

        with patch.object(self.forge, 'call', side_effect=only_other_title), patch.object(
                ev, 'measure', return_value=ev.vod_cases(vod_rows(status='no_decoded_video'), 'library')):
            self.assertFalse(repair.handle(self.forge, 7))
        self.assertFalse(self.forge.pulls)
        self.assertIn('no conclusive playback measurement', self.forge.comments[-1]['body'])

    def test_changed_candidate_after_playback_keeps_review_closed(self):
        self.worker_script('Path(%r).write_text("patched")\n' % str(self.file))

        def verify(*args):
            self.git('push', 'origin', ':refs/heads/repair/issue-7')
            return [str(self.file)], ['measured case']

        with patch.object(repair, 'verify', side_effect=verify):
            self.assertFalse(repair.handle(self.forge, 7))
        self.assertFalse(self.forge.pulls)
        self.assertIn('moved during verification', self.forge.comments[-1]['body'])
