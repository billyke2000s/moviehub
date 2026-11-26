"""Start a real disposable Forgejo on loopback and exercise the permissions used by the installer."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import getpass

spec = importlib.util.spec_from_file_location('repair_setup', Path(__file__).resolve().parents[1]/'configure-repair.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class FreshForgejo(unittest.TestCase):
    def test_private_repo_automation_and_protected_main(self):
        binary = os.environ['FORGEJO_TEST_BINARY']
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            check_publisher = os.environ.get('MOVIEHUB_TEST_PUBLISHER') == '1'
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 3000 if check_publisher else 0))
                port = sock.getsockname()[1]
            url = f'http://127.0.0.1:{port}'
            conf = base/'app.ini'
            conf.write_text(f"""RUN_USER = {getpass.getuser()}
RUN_MODE = prod
WORK_PATH = {base}
[server]
ROOT_URL = {url}/
HTTP_ADDR = 127.0.0.1
HTTP_PORT = {port}
OFFLINE_MODE = true
DISABLE_SSH = true
[database]
DB_TYPE = sqlite3
PATH = {base}/db
[security]
INSTALL_LOCK = true
[service]
DISABLE_REGISTRATION = true
[actions]
ENABLED = true
ENDLESS_TASK_TIMEOUT = 8h
[log]
LEVEL = Warn
""")
            env = {**os.environ, 'HOME': temp, 'NO_PROXY': '127.0.0.1,localhost', 'no_proxy': '127.0.0.1,localhost',
                   'GIT_CONFIG_NOSYSTEM':'1', 'GIT_TERMINAL_PROMPT':'0'}
            cli = [binary, '--work-path', temp, '--config', str(conf)]
            def local(args, **kw):
                return subprocess.run(args, cwd=base, env=env, capture_output=True, timeout=60, **kw)
            local(cli + ['migrate'], check=True)
            owner_pw, bot_pw = secrets.token_hex(20), secrets.token_hex(20)
            local(cli+['admin','user','create','--username','billy','--email','owner@example.org',
                       '--password',owner_pw,'--admin','--must-change-password=false'], check=True)
            process = subprocess.Popen(cli+['web'], env=env, cwd=temp, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                for _ in range(150):
                    try:
                        with urllib.request.urlopen(url+'/api/v1/version',timeout=1) as res:
                            self.assertTrue(json.load(res)['version'])
                        break
                    except OSError:
                        if process.poll() is not None: self.fail('Forgejo stopped before readiness')
                        time.sleep(.2)
                else: self.fail('Forgejo did not become ready')
                auth='Basic '+base64.b64encode(('billy:'+owner_pw).encode()).decode()
                def api(method,path,body=None,authorization=auth):
                    req=urllib.request.Request(url+'/api/v1'+path,method=method,
                        headers={'Authorization':authorization,'Content-Type':'application/json'},
                        data=None if body is None else json.dumps(body).encode())
                    with urllib.request.urlopen(req,timeout=10) as response:
                        data=response.read()
                    return json.loads(data) if data else None

                api('POST','/user/repos',dict(name='moviehub',private=True,auto_init=True,default_branch='main'))
                # Commit known harmless release code using the owner identity before protecting main.
                env['QA_GIT_AUTH']='Authorization: '+auth
                ogit=['git','--config-env=http.extraHeader=QA_GIT_AUTH']
                local(ogit+['clone','-q',url+'/billy/moviehub.git','approved'],check=True)
                tree = base/'approved'
                tests = tree/'android-tv-launcher/tests'
                tests.mkdir(parents=True)
                for filename in ('test_free_vod.py','test_primewire.py','test_scraper_loader.py'):
                    (tests/filename).write_text('assert True\n')
                script = tree/'server/tools/build-scrapers.py'
                script.parent.mkdir(parents=True)
                script.write_text("import pathlib,subprocess,sys\np=pathlib.Path('build/scrapers');p.mkdir(parents=True)\n"
                                  "(p/'scrapers.zip').write_bytes(b'approved fixture')\n"
                                  "subprocess.run(['openssl','dgst','-sha256','-sign',sys.argv[2],'-out',str(p/'scrapers.sig'),str(p/'scrapers.zip')],check=True)\n")
                ogit+=['-C','approved','-c','user.name=Owner','-c','user.email=owner@example.org']
                local(ogit+['add','.'],check=True)
                local(ogit+['commit','-qm','Approved fixture'],check=True)
                local(ogit+['push','-q','origin','HEAD:main'],check=True)
                approved=local(ogit+['rev-parse','HEAD'],check=True).stdout
                bot,read,old=setup.configure(url,'billy',owner_pw,bot_pw,'http://localhost/unused')
                self.assertEqual(old, [])
                _, _, old_after_retry = setup.configure(url,'billy',owner_pw,bot_pw,'http://localhost/unused')
                self.assertEqual(len(old_after_retry), 2)
                runner_binary = os.environ.get('FORGEJO_TEST_RUNNER')
                if runner_binary:
                    self.exercise_runner(local, cli, base, tree, env, url, api, ogit, runner_binary)
                repo='/repos/billy/moviehub'
                self.assertTrue(api('GET',repo,authorization='token '+read)['private'])
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    api('POST',repo+'/issues',{'title':'must not write'},'token '+read)
                self.assertEqual(denied.exception.code,403)
                bauth='Basic '+base64.b64encode((bot+':'+bot_pw).encode()).decode()
                t=api('POST','/users/'+bot+'/tokens',{'name':'qa','scopes':['write:issue','write:repository'],
                      'repositories':[{'owner':'billy','name':'moviehub'}]},bauth)['sha1']
                token='token '+t
                issue=api('POST',repo+'/issues',{'title':'Harmless fixture incident'},token)
                api('POST',repo+'/issues/%d/comments'%issue['number'],{'body':'Playback unverified.'},token)
                env['QA_GIT_AUTH']='Authorization: Basic '+base64.b64encode((bot+':'+t).encode()).decode()
                git=['git','--config-env=http.extraHeader=QA_GIT_AUTH']
                local(git+['clone','-q',url+'/billy/moviehub.git','checkout'],check=True)
                git+=['-C','checkout','-c','user.name=Test','-c','user.email=test@example.org']
                (base/'checkout'/'fixture.txt').write_text('fixture')
                local(git+['add','fixture.txt'],check=True)
                local(git+['commit','-qm','Fixture'],check=True)
                self.assertEqual(local(git+['push','origin','HEAD:refs/heads/repair/issue-1']).returncode,0)
                self.assertNotEqual(local(git+['push','origin','HEAD:refs/heads/main']).returncode,0)
                pr=api('POST',repo+'/pulls',dict(head='repair/issue-1',base='main',title='Review fixture'),token)
                with self.assertRaises(urllib.error.HTTPError):
                    api('POST',repo+'/pulls/%d/merge'%pr['number'],{'Do':'merge'},token)
                self.assertFalse(api('GET',repo+'/pulls/%d'%pr['number'])['merged'])
                self.assertEqual(api('GET',repo+'/actions/variables/MOVIEHUB_FORGEJO_URL')['data'],url)
                if check_publisher:
                    release=['sudo','-n','-H','-u','moviehub-release','--']
                    local(release+['git','-C','/var/lib/moviehub-release','credential-store','store'],input=(
                        'protocol=http\nhost=127.0.0.1:3000\npath=billy/moviehub.git\nusername='+bot+'\npassword='+read+'\n\n').encode(),check=True)
                    local(release+['git','-C','/var/lib/moviehub-release','config','--global','credential.helper','store'],check=True)
                    local(release+['git','-C','/var/lib/moviehub-release','config','--global','credential.useHttpPath','true'],check=True)
                    runner=['sudo','-n','-u','moviehub','--']
                    key='/srv/moviehub/keys/signing.pem'
                    self.assertNotEqual(local(runner+['test','-r',key]).returncode,0)
                    self.assertNotEqual(local(runner+['test','-w','/srv/moviehub/feed/scrapers']).returncode,0)
                    publish=runner+['sudo','-n','-u','moviehub-release','/usr/local/libexec/moviehub-publish','scrapers']
                    candidate=local(git+['rev-parse','HEAD'],check=True).stdout
                    self.assertNotEqual(local(publish,input=candidate).returncode,0)
                    self.assertFalse(Path('/srv/moviehub/feed/scrapers/scrapers.zip').exists())
                    result=local(publish,input=approved)
                    self.assertEqual(result.returncode,0,result.stderr.decode())
                    self.assertEqual(Path('/srv/moviehub/feed/scrapers/scrapers.zip').read_bytes(),b'approved fixture')
                    local(['openssl','dgst','-sha256','-verify','/srv/moviehub/public.pem',
                           '-signature','/srv/moviehub/feed/scrapers/scrapers.sig','/srv/moviehub/feed/scrapers/scrapers.zip'],check=True)
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill();process.wait()


    def exercise_runner(self, local, cli, base, tree, env, url, api, owner_git, binary):
        runner_home = base/'runner'
        runner_home.mkdir()
        secret = secrets.token_hex(20)
        local(cli + ['forgejo-cli','actions','register','--scope','billy/moviehub','--name','moviehub',
                     '--labels','moviehub:host','--secret',secret], check=True)
        result = subprocess.run([binary,'create-runner-file','--instance',url,'--secret',secret,
                                 '--name','moviehub'], cwd=runner_home,env=env,capture_output=True,timeout=20)
        self.assertEqual(result.returncode,0)
        (runner_home/'config.yml').write_bytes((Path(__file__).resolve().parents[1]/'runner.yml').read_bytes())
        log = (runner_home/'test-run.log').open('w')
        worker = subprocess.Popen([binary,'--config','config.yml','daemon'],cwd=runner_home,env=env,
                                  stdout=log,stderr=log)
        try:
            # A static one-step job exercises server scheduling, the registered runner and the host label.
            workflow = tree/'.forgejo/workflows/offline-fixture.yml'
            workflow.parent.mkdir(parents=True)
            workflow.write_text('name: Fixture\non: [push]\njobs:\n  fixture:\n    runs-on: moviehub\n'
                                '    timeout-minutes: 480\n'
                                '    steps:\n      - run: echo moviehub-runner-ready\n')
            local(owner_git+['switch','-qc','qa/runner-start'],check=True)
            local(owner_git+['add','.forgejo'],check=True)
            local(owner_git+['commit','-qm','Offline runner fixture'],check=True)
            local(owner_git+['push','-q','origin','HEAD:refs/heads/qa/runner-start'],check=True)
            sha=local(owner_git+['rev-parse','HEAD'],check=True).stdout.decode().strip()
            for _ in range(90):
                results = api('GET','/repos/billy/moviehub/actions/runs')
                matches = [run for run in results['workflow_runs'] if run['commit_sha'] == sha]
                if matches and matches[0]['status'] in ('success', 'failure', 'cancelled', 'skipped'):
                    self.assertEqual(matches[0]['status'], 'success')
                    return
                if worker.poll() is not None:
                    self.fail('registered runner exited before completing its job')
                time.sleep(1)
            self.fail('registered runner did not finish its harmless fixture job')
        finally:
            worker.terminate()
            try: worker.wait(timeout=5)
            except subprocess.TimeoutExpired: worker.kill();worker.wait()
            log.close()


if __name__=='__main__':
    unittest.main(verbosity=2)
