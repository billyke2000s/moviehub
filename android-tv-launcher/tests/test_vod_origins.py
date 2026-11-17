"""Per-entry upstream selection and committed adapter/inventory consistency."""
import importlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('sync_vod_origins',
                                            Path(__file__).with_name('sync-vod-origins.py'))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class VodOriginTests(unittest.TestCase):
    def test_source_changes_ignore_unrelated_commits_formatting_and_key_case(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            def commit(data, date):
                (repo / 'domains.json').write_text(json.dumps(data))
                subprocess.run(['git', '-C', str(repo), 'add', '.'], check=True)
                env = dict(os.environ, GIT_AUTHOR_NAME='Fixture', GIT_AUTHOR_EMAIL='fixture@example.com',
                           GIT_COMMITTER_NAME='Fixture', GIT_COMMITTER_EMAIL='fixture@example.com',
                           GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
                subprocess.run(['git', '-C', str(repo), 'commit', '-qm', 'fixture'], env=env, check=True)
                return sync.git(repo, 'rev-parse', 'HEAD')
            first = commit({'UHDMovies': 'https://first.example', 'moviesmod': 'https://mod.example'},
                           '2026-01-01T00:00:00Z')
            changed = commit({'UHDMovies': 'https://new.example', 'moviesmod': 'https://mod.example'},
                             '2026-02-01T00:00:00Z')
            commit({'moviesmod': 'https://mod.example', 'uhdmovies': 'https://new.example',
                    'unrelated': 'https://other.example'}, '2026-03-01T00:00:00Z')
            rows = sync.changes(repo, 'domains.json')
            self.assertEqual(rows['uhdmovies']['commit'], changed)
            self.assertEqual(rows['moviesmod']['commit'], first)
            self.assertEqual(rows['uhdmovies']['origin'], 'https://new.example')
            # Returning to an old URL is a new change, not the first use of that URL.
            reverted = commit({'uhdmovies': 'https://first.example'}, '2026-04-01T00:00:00Z')
            self.assertEqual(sync.changes(repo, 'domains.json')['uhdmovies']['commit'], reverted)

    def test_latest_entry_wins_independently_with_timezone_normalization(self):
        phisher = dict(repository='phisher', origin='https://one.example',
                       changed_at='2026-02-01T06:00:00+05:30')
        csx = dict(repository='csx', origin='https://two.example',
                   changed_at='2026-02-01T01:00:00Z')
        self.assertEqual(sync.select([phisher, csx]), csx)
        self.assertEqual(sync.select([dict(phisher, changed_at='2026-03-01T00:00:00Z'), csx])['repository'],
                         'phisher')

    def test_conflicting_equal_dates_require_review(self):
        rows = [dict(repository=name, origin='https://' + name + '.example',
                     changed_at='2026-02-01T00:00:00Z') for name in ('one', 'two')]
        with self.assertRaises(ValueError):
            sync.select(rows)

    def test_duplicate_upstream_aliases_are_rejected(self):
        with self.assertRaises(ValueError):
            sync.normalized({'UHDMovies': 'https://one.example', 'uhdmovies': 'https://two.example'})

    def test_non_origin_or_private_literal_updates_are_rejected(self):
        for value in ('http://site.example', 'https://user:secret@site.example',
                      'https://site.example/token', 'https://site.example/?key=secret',
                      'https://127.0.0.1', 'https://[::1]', 'https://localhost', 'https://host.local',
                      'https://site.example/#secret', 'https://site.example\n'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                sync.origin(value)
        self.assertEqual(sync.origin('https://site.example/'), 'https://site.example')

    def test_committed_origins_match_both_adapter_and_inventory(self):
        from moviehub_native import PROVIDERS
        records = {row['name']: row for row in PROVIDERS}
        manifest = json.loads((sync.ROOT / 'upstream_origins.json').read_text())
        self.assertEqual(set(manifest['providers']), set(sync.TARGETS))
        selected = {}
        for key, evidence in manifest['providers'].items():
            row = evidence['selected']
            self.assertEqual(sync.select(evidence['candidates']), row)
            selected[key] = row
            name, module, factory = sync.TARGETS[key]
            provider = getattr(importlib.import_module('moviehub_native.' + module), factory)()
            self.assertEqual(provider.base_link, row['origin'])
            self.assertEqual(records[name]['site'], sync.urlparse(row['origin']).hostname)
            self.assertEqual(records[name]['id'], 'native:' + name)
        # Reapplying committed values must be an exact no-op, including inherited classes.
        for path, text in sync.render(selected).items():
            self.assertEqual(path.read_text(), text)


if __name__ == '__main__':
    unittest.main()
