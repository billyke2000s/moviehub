"""Measure captured emulator audio and prepare rendered frames for explicit content review."""
import array
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys


def decode(path, start, seconds):
    command = ['ffmpeg', '-v', 'error', '-ss', str(max(0, start)), '-i', str(path),
               '-t', str(seconds), '-ac', '2', '-ar', '16000', '-f', 's16le', 'pipe:1']
    result = subprocess.run(command, capture_output=True, timeout=45, check=True)
    pcm = array.array('h', result.stdout)
    if sys.byteorder != 'little':
        pcm.byteswap()
    return pcm


def levels(pcm):
    channels = []
    for index in range(2):
        samples = pcm[index::2]
        energy = sum(value * value for value in samples) / max(1, len(samples))
        rms = math.sqrt(energy) / 32768
        active = sum(math.sqrt(sum(x * x for x in samples[i:i + 8000]) /
                               max(1, len(samples[i:i + 8000]))) / 32768 > .001
                     for i in range(0, len(samples), 8000)) * .5
        channels.append(dict(rmsDbfs=round(20 * math.log10(max(rms, 1e-6)), 2),
                             activeSeconds=active))
    return dict(seconds=len(pcm) / 32000, channels=channels,
                signalDetected=any(row['activeSeconds'] >= .5 for row in channels))


def calibration(pcm):
    mono = [(pcm[i] + pcm[i + 1]) / 65536 for i in range(0, len(pcm) - 1, 2)]
    count = len(mono)
    real = sum(value * math.cos(2 * math.pi * 440 * i / 16000) for i, value in enumerate(mono))
    imag = sum(value * math.sin(2 * math.pi * 440 * i / 16000) for i, value in enumerate(mono))
    power = sum(value * value for value in mono) / max(1, count)
    tone = (real * real + imag * imag) / max(1, count * count)
    ratio = tone / max(power, 1e-12)
    return dict(valid=count >= 16000 and power > 1e-6 and ratio > .05,
                tonePowerRatio=round(ratio, 4), **levels(pcm))


def wav(path, pcm):
    import wave
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(pcm.tobytes())


def contact_sheet(directory, row):
    from PIL import Image, ImageDraw
    sheet = Image.new('RGB', (1280, 840), '#161616')
    draw = ImageDraw.Draw(sheet)
    draw.text((12, 8), str(row['website']) + ' | ' + str(row['title']), fill='white')
    draw.text((12, 28), 'Sample evidence; movie/episode identity requires visual review.', fill='white')
    drawn = []
    for index, label in enumerate(('start', 'early', 'middle', 'late')):
        path = directory / (row['evidenceId'] + '-' + label + '-0.jpg')
        if not path.exists():
            continue
        with Image.open(path) as frame:
            picture = frame.convert('RGB').resize((640, 360))
            x, y = index % 2 * 640, index // 2 * 390 + 60
            sheet.paste(picture, (x, y))
            draw.text((x + 8, y - 16), label, fill='white')
        drawn.append(hashlib.sha256(path.read_bytes()).hexdigest())
    sheet.save(directory / (row['evidenceId'] + '-contact.jpg'), quality=85)
    return len(set(drawn))


def review(report, audio, metadata, directory):
    rows = [json.loads(line) for line in report.read_text().splitlines()]
    began = json.loads(metadata.read_text())['startEpochMs']
    fixture = next(row for row in rows if row['kind'] == 'audio_fixture')
    pcm = decode(audio, (fixture['startEpochMs'] - began) / 1000 - .3,
                 (fixture['endEpochMs'] - fixture['startEpochMs']) / 1000 + .6)
    checked = calibration(pcm)
    wav(directory / 'audio-calibration.wav', pcm)
    if not checked['valid']:
        raise RuntimeError('Captured emulator audio failed the known-tone calibration')
    output = [dict(kind='audio_calibration', **checked)]
    for row in rows:
        if row['kind'] != 'playback' or not row.get('evidenceId'):
            continue
        if not re.fullmatch(r'[0-9]{4}', row['evidenceId']):
            raise ValueError('Invalid evidence filename')
        evidence = dict(row, audioCalibrationValid=True)
        windows = {}
        for label in ('start', 'middle', 'late'):
            if label + 'EpochMs' not in row:
                continue
            pcm = decode(audio, (row[label + 'EpochMs'] - began) / 1000 - .3,
                         row[label + 'ObservationSeconds'] + .6)
            windows[label] = levels(pcm)
            wav(directory / (row['evidenceId'] + '-' + label + '.wav'), pcm)
        evidence['capturedAudio'] = windows
        evidence['distinctPositionFrames'] = contact_sheet(directory, row)
        evidence['contentReview'] = 'pending'
        evidence['verification'] = ('technical_failure' if row['status'] != 'verified' else
                                    'audio_signal_missing' if len(windows) != 3 or
                                    not all(window['signalDetected'] for window in windows.values()) else
                                    'needs_content_review')
        output.append(evidence)
    (directory / 'review.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in output))
    print(json.dumps(dict(audioCalibrationValid=True, candidates=len(output) - 1,
                          contentConfirmed=0, pendingContentReview=len(output) - 1)))


if __name__ == '__main__':
    review(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
