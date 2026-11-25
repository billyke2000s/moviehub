"""Compare observations from clean builds. A probe error is never a failed playback case."""
import json
import os
import subprocess
from collections import Counter
from pathlib import Path
import re


def vod_cases(rows, provider):
    """Evidence from the packaged LibVLC single-source test, keyed by the exact catalogue request."""
    if not rows or rows[-1] != {'kind': 'finished', 'shard': 0, 'shards': 1}:
        raise ValueError('incomplete Android playback report')
    if any(r.get('website') != provider for r in rows[:-1]):
        raise ValueError('unexpected website in Android report')
    cases = {}
    for lookup in rows:
        if lookup.get('kind') != 'lookup':
            continue
        key = (lookup['tmdb'], lookup['season'], lookup['episode'])
        if key in cases:
            raise ValueError('duplicate lookup in Android report')
        count = lookup['sources']
        if type(count) is not int or count < 0:
            raise ValueError('invalid source count')
        plays = [r for r in rows if r.get('kind') == 'playback'
                 and (r['tmdb'], r['season'], r['episode']) == key]
        if len(plays) != count:
            raise ValueError('incomplete playback records')
        case = 'TMDB %d season %d episode %d' % key
        passed = next((r for r in plays if r.get('status') == 'verified' and r.get('decoded') is True
               and r.get('decodedFrames', 0) > 5 and (r.get('decodedAudio', 0) > 0 or r.get('playedAudioBuffers', 0) > 0)
               and r.get('plausibleRuntime') is True and r.get('midpointDecoded') is True), None)
        if passed:
            cases[key] = (True, '%s: %d video frames, %d decoded audio and %d played audio buffers; midpoint passed'
                          % (case, passed['decodedFrames'], passed.get('decodedAudio', 0), passed.get('playedAudioBuffers', 0)))
        elif plays and all(r.get('status') in ('no_decoded_video', 'audio_unverified', 'runtime_mismatch',
                                                'seek_unverified') for r in plays):
            cases[key] = (False, case + ': ' + ', '.join(sorted({r['status'] for r in plays})))
        elif not plays and lookup.get('status') in ('no_matches', 'rejected_media', 'resolution_failed', 'unsupported_host'):
            cases[key] = (False, case + ': ' + lookup['status'])
    return cases


def live_cases(rows, provider):
    """A server decode can identify the sampled catalogue entry. Its name cannot identify a broadcast."""
    if len(rows) != 1 or rows[0]['id'] != provider:
        raise ValueError('wrong provider in Live TV report')
    row = rows[0]
    if row.get('catalogue') != 'ok' or row.get('playbackScope') == 'not_tested':
        return {}
    samples = row.get('samples', [])
    ids = Counter(s.get('caseId') for s in samples)
    cases = {}
    for sample in samples:
        name = sample.get('name')
        key = sample.get('caseId', '')
        if not name or not re.fullmatch('[0-9a-f]{64}', key) or ids[key] != 1:
            continue
        play = sample.get('play', '')
        resolved = sample.get('resolve', '')
        video, audio = sample.get('videoFrames', 0), sample.get('audioFrames', 0)
        if play == 'ok' and resolved.startswith('ok (') and video >= 10 and audio >= 10:
            cases[key] = (True, '%s: %d video and %d audio frames' % (name, video, audio))
        elif play.startswith('PROVIDER?:') or resolved.startswith('PROVIDER:'):
            cases[key] = (False, name + (': decode failed' if play else ': resolve failed'))
    return cases


def transition(before, after, control):
    """Require the same case to fail on base, pass on candidate, and still fail on a fresh base run."""
    return ['base [%s], candidate [%s], base recheck [%s]' % (before[key][1], detail, control[key][1])
            for key, (passed, detail) in after.items()
            if passed and before.get(key, (None,))[0] is False and control.get(key, (None,))[0] is False]


def measure(folder, source):
    kind, provider = source.split(':', 1)
    env = os.environ.copy()
    for key in ('FORGEJO_TOKEN', 'NTFY_URL', 'GITHUB_STEP_SUMMARY'):
        env.pop(key, None)

    def run(args, timeout, capture=False):
        return subprocess.run(args, cwd=folder, env=env, check=True, timeout=timeout,
                              stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, text=True).stdout

    if kind == 'vod':
        serial = env.get('MOVIEHUB_AUDIT_EMULATOR')
        if not serial:
            raise ValueError('Android playback pending: set MOVIEHUB_AUDIT_EMULATOR to a dedicated test emulator')
        env['ANDROID_SERIAL'] = serial
        if run(['adb', 'shell', 'getprop', 'ro.kernel.qemu'], 20, True).strip() != '1':
            raise ValueError('configured device is not a test emulator')
        # A fresh debug app prevents previous OTA modules, backups and old instrumentation reports
        # from satisfying the next revision. These package IDs are exclusive to the opted-in emulator.
        for package in ('local.tv.home', 'local.tv.home.test'):
            if run(['adb', 'shell', 'pm', 'path', package], 20, True).strip():
                run(['adb', 'uninstall', package], 30)
        abi = run(['adb', 'shell', 'getprop', 'ro.product.cpu.abi'], 20, True).strip()
        if abi == 'x86_64':
            env['ORG_GRADLE_PROJECT_moviehubAbi'] = 'x86_64'
        output = folder / 'vod-live-audit.jsonl'
        output.unlink(missing_ok=True)
        run(['bash', 'android-tv-launcher/tests/run-live-vod-audit.sh', '0', '1', provider], 2700)
        return vod_cases([json.loads(line) for line in output.read_text().splitlines()], provider)
    rhino = Path(env.get('RHINO_JAR', ''))
    if not rhino.is_file():
        raise ValueError('Live TV verification requires RHINO_JAR')
    run(['ffmpeg', '-version'], 10)
    run(['npm', 'ci', '--ignore-scripts', '--prefix', 'provider-engine'], 180)
    run(['node', 'provider-engine/tools/build.mjs'], 180)
    out = folder / 'build/provider-tests'
    out.mkdir(parents=True, exist_ok=True)
    java = folder / 'android-tv-launcher/app/src/main/java/local/tv/home'
    classes = ['ProviderScript', 'ProviderCodec', 'ProviderSignature', 'ProviderUrls', 'ProviderSegments']
    run(['javac', '-cp', str(rhino.resolve()), '-d', str(out), *[str(java / (name + '.java')) for name in classes],
         'android-tv-launcher/tests/ProviderProbeCli.java'], 90)
    output = out / 'live-probe.json'
    output.unlink(missing_ok=True)
    run(['node', 'provider-engine/tools/live-probe.mjs', provider], 1800)
    return live_cases(json.loads(output.read_text()), provider)
