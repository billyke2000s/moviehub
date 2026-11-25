"""Real rootless container test. Supply the immutable ID of a test image with Git, tar and Python 3."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'health'))
import isolated_worker


class WorkerContainer(unittest.TestCase):
    def test_only_patch_leaves_the_offline_container(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)/'repo'
            root.mkdir()
            canary = Path(temp)/'signing-canary'
            canary.write_text('private fixture')
            (root/'source.py').write_text('before\n')
            for args in (['init','-q'], ['add','source.py'],
                         ['-c','user.name=QA','-c','user.email=qa@example.org','commit','-qm','Fixture']):
                subprocess.run(['git',*args],cwd=root,check=True)
            code = ('from pathlib import Path; import os; '
                    'assert not Path(%r).exists(); assert "FORGEJO_TOKEN" not in os.environ; '
                    'assert set(os.listdir("/sys/class/net")) == {"lo"}; '
                    'assert not Path("/run/podman/podman.sock").exists(); '
                    'Path("source.py").write_text("after\\n"); print("fixture diagnosis")' % str(canary))
            with patch.dict(os.environ, CODEX_CMD=shlex.join(['/usr/bin/python3','-c',code]),
                            FORGEJO_TOKEN='fixture-only'):
                original_run = subprocess.run
                def diagnose(args, **kwargs):
                    if args[0] == '/usr/bin/prlimit':
                        # Only the harmless, generated test worker runs in this job.
                        kwargs['stderr'] = subprocess.PIPE
                    result = original_run(args, **kwargs)
                    if result.returncode and result.stderr:
                        print(result.stderr.decode(errors='replace')[-4096:], file=sys.stderr)
                    return result
                with patch.object(subprocess, 'run', side_effect=diagnose):
                    data, diagnosis=isolated_worker.proposal(root,'HEAD','fixture')
            self.assertIn(b'+after', data)
            self.assertIn('fixture diagnosis', diagnosis)
            self.assertEqual((root/'source.py').read_text(), 'before\n')
            self.assertEqual(canary.read_text(), 'private fixture')


if __name__=='__main__':
    unittest.main(verbosity=2)
