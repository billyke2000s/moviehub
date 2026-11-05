"""Checkpoint coverage must survive failures without inventing a content pass."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('checkpoint', Path(__file__).with_name('site-audit-checkpoint.py'))
checkpoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checkpoint)


class SiteCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.site = 'free99:anymovies_co'

    def write(self, media, rows, reviews=()):
        path = self.root / media / 'vod-evidence'
        path.mkdir(parents=True)
        (path.parent / 'vod-live-audit.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
        (path / 'review.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in reviews))

    def rows(self, site=None):
        site = site or self.site
        return [dict(kind='inventory', sites=[site], media='movies'),
                dict(kind='lookup', website=site, status='complete', url='https://secret.example/token'),
                dict(kind='playback', website=site, status='verified', headers={'Cookie': 'secret'}),
                dict(kind='site_finished', website=site, media='movies', testedSources=1, verified=True),
                dict(kind='finished', shard=0, shards=1)]

    def summarize(self):
        return checkpoint.summarize(self.root, self.site, 'a' * 40,
                                    'https://github.com/owner/repo/actions/runs/123')

    def test_technical_and_audio_passes_never_become_a_content_certification(self):
        self.write('movies', self.rows(), [dict(kind='playback', website=self.site,
                   verification='needs_content_review')])
        report = self.summarize()
        self.assertTrue(report['media']['movies']['complete'])
        self.assertEqual(report['media']['movies']['technicalPasses'], 1)
        self.assertEqual(report['media']['movies']['candidatesAwaitingContentReview'], 1)
        self.assertFalse(report['fullPlaybackVerified'])
        self.assertEqual(report['playbackAuditVersion'], 2)
        self.assertEqual(report['contentReview'], 'pending')
        self.assertFalse(report['media']['series']['complete'])
        self.assertNotIn('secret', json.dumps(report))

    def test_missing_final_marker_is_saved_as_incomplete(self):
        self.write('movies', self.rows()[:-1])
        self.assertFalse(self.summarize()['media']['movies']['complete'])

    def test_wrong_site_report_cannot_complete_the_selected_site(self):
        self.write('movies', self.rows('native:vidzee'))
        self.assertFalse(self.summarize()['media']['movies']['complete'])

    def test_malformed_report_does_not_destroy_the_other_media_result(self):
        self.write('movies', self.rows())
        path = self.root / 'series'
        path.mkdir()
        (path / 'vod-live-audit.jsonl').write_text('broken')
        report = self.summarize()
        self.assertTrue(report['media']['movies']['complete'])
        self.assertFalse(report['media']['series']['complete'])

    def test_site_path_injection_is_rejected(self):
        self.site = 'native:../../main'
        with self.assertRaises(ValueError):
            self.summarize()

    def test_failed_movie_check_is_preserved_before_series_runs(self):
        scripts = self.root / '.github/scripts'
        scripts.mkdir(parents=True)
        real = Path(__file__).resolve().parents[2] / '.github/scripts/audit-one-vod-site.sh'
        shutil.copyfile(real, scripts / real.name)
        (scripts / 'vod-evidence-audit.sh').write_text(
            '#!/usr/bin/env bash\n'
            'printf "{\\"media\\":\\"%s\\"}\\n" "$4" > vod-live-audit.jsonl\n'
            'mkdir -p vod-evidence\n'
            'printf "%s\\n" "$4" > vod-evidence/marker.txt\n'
            '[ "$4" = series ]\n')
        result = subprocess.run(['bash', str(scripts / real.name), self.site], cwd=self.root,
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        for media, code in [('movies', '1'), ('series', '0')]:
            target = self.root / 'site-audit' / media
            self.assertEqual(json.loads((target / 'vod-live-audit.jsonl').read_text())['media'], media)
            self.assertEqual((target / 'vod-evidence/marker.txt').read_text().strip(), media)
            self.assertEqual((target / 'exit-code.txt').read_text().strip(), code)
