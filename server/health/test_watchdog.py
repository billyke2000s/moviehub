"""Tests for the server watchdog. Run: python3 server/health/test_watchdog.py"""
import datetime
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import watchdog as w  # noqa: E402


class Watchdog(unittest.TestCase):
    def setUp(self):
        self.state = tempfile.NamedTemporaryFile(delete=False)
        self.addCleanup(os.unlink, self.state.name)
        self.now = time.time()

    def found(self, age_hours=0.5, up=True, disk=40, codex=False):
        os.utime(self.state.name, (self.now - age_hours * 3600,) * 2)
        return w.problems(self.now, self.state.name, 3, lambda name: up, disk, codex)

    def test_all_good(self):
        self.assertEqual(self.found(), {})

    def test_each_problem_has_a_plain_fix(self):
        found = self.found(age_hours=5, up=False, disk=95, codex=True)
        self.assertIn('5 hours', found['health'])
        self.assertIn('systemctl restart forgejo-runner', found['service:forgejo-runner'])
        self.assertIn('95%', found['disk'])
        self.assertIn('codex login', found['codex'])

    def test_missing_state_file_counts_as_not_running(self):
        found = w.problems(self.now, '/nonexistent/state.json', 3, lambda n: True, 10, False)
        self.assertIn('health', found)

    def test_alert_once_repeat_after_12_hours_then_fixed(self):
        found = {'disk': 'Disk is 95% full.'}
        send, memory = w.decide({}, found, 1000, False)
        self.assertEqual(len(send), 1)
        send, memory = w.decide(memory, found, 1000 + 3600, False)
        self.assertEqual(send, [])  # no spam
        send, memory = w.decide(memory, found, 1000 + w.REPEAT_SECONDS, False)
        self.assertEqual(len(send), 1)  # reminder
        send, memory = w.decide(memory, {}, 1000 + w.REPEAT_SECONDS + 60, False)
        self.assertEqual(send, [('MovieHub server: fixed', 'disk is OK again.')])
        self.assertEqual(memory, {})

    def test_weekly_message(self):
        send, memory = w.decide({}, {}, 5000, True)
        self.assertEqual(send, [('MovieHub weekly check', 'All good: checks running, services up, disk fine.')])
        self.assertNotIn('MovieHub server: fixed', [t for t, _ in w.decide(memory, {}, 6000, False)[0]])
        self.assertEqual(memory['_weekly'], 5000)

    def test_weekly_due_only_monday_9am_once(self):
        monday9 = datetime.datetime(2026, 11, 23, 9, 30).timestamp()
        self.assertTrue(w.weekly_due(monday9, {}))
        self.assertFalse(w.weekly_due(monday9, {'_weekly': monday9 - 3600}))
        self.assertFalse(w.weekly_due(monday9 + 2 * 3600, {}))


if __name__ == '__main__':
    unittest.main(verbosity=2)
