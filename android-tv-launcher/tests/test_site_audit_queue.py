"""Resume coverage and playback certification are independent requirements."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit_queue', Path(__file__).with_name('site-audit-queue.py'))
queue = importlib.util.module_from_spec(spec)
spec.loader.exec_module(queue)


class SiteAuditQueueTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.sites = ['free99:levidia_ch', 'native:vixsrc', 'native:rive']

    def save(self, run, site, complete=True, **extra):
        path = self.root / str(run)
        path.mkdir(exist_ok=True)
        report = dict(site=site, sourceCommit='a' * 40,
                      evidenceRun='https://github.com/owner/repo/actions/runs/' + str(run),
                      media={kind: dict(complete=complete) for kind in ('movies', 'series')},
                      fullPlaybackVerified=False)
        report.update(extra)
        (path / (site.replace(':', '-') + '.json')).write_text(json.dumps(report))
        return report

    def test_resume_keeps_completed_failures_and_retries_missing_or_incomplete_checks(self):
        self.save(10, self.sites[0])
        self.save(10, self.sites[1], complete=False)
        reports = queue.latest_reports(self.root, self.sites)
        self.assertEqual(queue.pending_sites(self.sites, reports), self.sites[1:])
        with self.assertRaisesRegex(ValueError, 'Incomplete saved site coverage'):
            queue.verify(self.sites, reports)

    def test_newer_incomplete_receipt_cannot_hide_behind_an_old_complete_receipt(self):
        self.save(9, self.sites[0])
        self.save(10, self.sites[0], complete=False)
        self.assertEqual(queue.pending_sites(self.sites, queue.latest_reports(self.root, self.sites)), self.sites)

    def test_combined_run_coverage_still_cannot_certify_failed_playback(self):
        for run, site in enumerate(self.sites, 10):
            self.save(run, site)
        reports = queue.latest_reports(self.root, self.sites)
        self.assertEqual(queue.pending_sites(self.sites, reports), [])
        with self.assertRaisesRegex(ValueError, 'playback repairs/content review'):
            queue.verify(self.sites, reports)
        self.assertEqual(queue.pending_sites(self.sites, reports, fresh=True), self.sites)

    def test_invalid_receipt_identity_fails_closed(self):
        self.save(10, self.sites[0], evidenceRun='https://github.com/owner/repo/actions/runs/11')
        with self.assertRaisesRegex(ValueError, 'run identity'):
            queue.latest_reports(self.root, self.sites)

    def test_truthy_strings_do_not_complete_a_check(self):
        self.assertFalse(queue.complete(dict(media={kind: dict(complete='false')
                                                   for kind in ('movies', 'series')})))

    def test_old_raw_seek_samples_are_rechecked_without_repeating_lookup_only_checks(self):
        playback = {kind: dict(complete=True, candidatesTested=1) for kind in ('movies', 'series')}
        self.save(10, self.sites[0], media=playback)
        self.save(10, self.sites[1])
        self.save(10, self.sites[2], media=playback, playbackAuditVersion=2)
        reports = queue.latest_reports(self.root, self.sites)
        self.assertEqual(queue.pending_sites(self.sites, reports), [self.sites[0]])
        with self.assertRaisesRegex(ValueError, 'Incomplete saved site coverage'):
            queue.verify(self.sites, reports)

    def test_unrecognized_playback_method_cannot_satisfy_current_coverage(self):
        playback = {kind: dict(complete=True, candidatesTested=1) for kind in ('movies', 'series')}
        self.save(10, self.sites[0], media=playback, playbackAuditVersion=999)
        reports = queue.latest_reports(self.root, self.sites)
        self.assertEqual(queue.pending_sites(self.sites, reports), self.sites)

    def test_unknown_site_or_corrupt_receipt_is_not_silently_skipped(self):
        self.save(10, 'native:unknown')
        with self.assertRaisesRegex(ValueError, 'enabled inventory'):
            queue.latest_reports(self.root, self.sites)


if __name__ == '__main__':
    unittest.main()
