"""Measure captured emulator audio and prepare rendered frames for explicit content review."""
import array
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys


def decode(path, start, seconds, stream=0):
    command = ['ffmpeg', '-v', 'error', '-ss', str(max(0, start)),
               '-protocol_whitelist', 'file,pipe', '-format_whitelist',
               'mpegts,mov,matroska,avi,aac,wav,mp3,ogg,flac', '-i', str(path),
               '-t', str(seconds), '-map', '0:a:' + str(stream), '-ac', '2', '-ar', '16000', '-f', 's16le', 'pipe:1']
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
        active = 0
        for offset in range(0, len(samples), 8000):
            block = samples[offset:offset + 8000]
            if math.sqrt(sum(value * value for value in block) / len(block)) / 32768 > .001:
                active += len(block) / 16000
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


def decoder_calibration(directory):
    path = directory / 'decoder-calibration.m4a'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'sine=frequency=440:sample_rate=16000:duration=2', '-ac', '2', '-c:a', 'aac', str(path)],
                   check=True, capture_output=True, timeout=30)
    checked = calibration(decode(path, 0, 2))
    path.unlink()
    if not checked['valid']:
        raise RuntimeError('Known encoded-audio fixture failed decoding/signal calibration')
    return dict(kind='decoder_calibration', physicalAudioOutputTested=False, **checked)


def audio_tracks(path):
    result = subprocess.run(['ffprobe', '-v', 'error', '-format_whitelist',
                             'mpegts,mov,matroska,avi,aac,wav,mp3,ogg,flac', '-select_streams', 'a',
                             '-show_entries', 'stream=codec_name,channels:stream_tags=language',
                             '-of', 'json', str(path)], capture_output=True, timeout=30, check=True)
    return json.loads(result.stdout).get('streams', [])[:8]


def language(value):
    value = str(value or '').strip().lower().replace('_', '-').split('-', 1)[0]
    if value in ('und', 'mis'):
        return ''
    groups = [('en', 'eng', 'english'), ('hi', 'hin', 'hindi'), ('ja', 'jpn', 'japanese'),
              ('ko', 'kor', 'korean'), ('it', 'ita', 'italian'), ('fr', 'fra', 'fre', 'french'),
              ('ar', 'ara', 'arabic'), ('ta', 'tam', 'tamil'), ('te', 'tel', 'telugu')]
    return next((group[0] for group in groups if value in group), value)


def selected_audio_signal(tracks, selected_language, events_confirmed):
    selected = language(selected_language)
    matching = [track for track in tracks if selected and language(track['language']) == selected]
    if len(tracks) == 1 and (not selected or not language(tracks[0]['language'])):
        matching = tracks
    # Native recording requests eight seconds; tiny closed-file fragments cannot
    # establish sustained source audio even if one fragment contains a signal.
    return events_confirmed and any(track['seconds'] >= 4 and track['signalDetected']
                                    for track in matching)


def review(report, raw, directory):
    import shutil
    directory.mkdir(exist_ok=True)
    rows = [json.loads(line) for line in report.read_text().splitlines()]
    output = [decoder_calibration(directory)]
    for row in rows:
        if row['kind'] != 'playback' or not row.get('evidenceId'):
            continue
        identity = row['evidenceId']
        if not re.fullmatch(r'[0-9]{4}', identity):
            raise ValueError('Invalid evidence filename')
        evidence = dict(row, physicalAudioOutputTested=False, audioEvidenceVersion=3)
        windows = {}
        for clip in row.get('recordedClips', []):
            label = clip['position']
            if label not in ('start', 'middle', 'late') or clip['directory'] != identity + '-' + label:
                raise ValueError('Invalid native clip directory')
            tracks = []
            errors = []
            for filename in clip.get('files', [])[:4]:
                if not re.fullmatch(r'sample-[0-9]+\.media', filename):
                    raise ValueError('Invalid native clip filename')
                path = raw / clip['directory'] / filename
                try:
                    metadata = audio_tracks(path)
                    for index, track in enumerate(metadata):
                        pcm = decode(path, 0, 8, index)
                        stats = dict(levels(pcm), codec=track.get('codec_name'),
                                     language=(track.get('tags') or {}).get('language', ''),
                                     channelCount=track.get('channels'))
                        name = identity + '-' + label + '-audio-' + str(len(tracks)) + '.wav'
                        wav(directory / name, pcm)
                        stats['file'] = name
                        tracks.append(stats)
                except (subprocess.SubprocessError, OSError, ValueError) as error:
                    errors.append(type(error).__name__)
            windows[label] = dict(tracks=tracks, capped=clip.get('capped', False), errors=errors,
                                  recordingEventsConfirmed=clip.get('eventsConfirmed', False),
                                  minimumDecodedSeconds=4,
                                  selectedAudioSignalVerified=selected_audio_signal(
                                      tracks, row.get('selectedAudioLanguage'), clip.get('eventsConfirmed', False)))
        evidence['capturedSourceAudio'] = windows
        for frame in raw.glob(identity + '-*.jpg'):
            if re.fullmatch(identity + r'-(start|early|middle|late)-[01]\.jpg', frame.name):
                shutil.copyfile(frame, directory / frame.name)
        evidence['distinctPositionFrames'] = contact_sheet(directory, row)
        evidence['contentReview'] = 'pending'
        evidence['verification'] = ('technical_failure' if row['status'] != 'verified' else
                                    'audio_signal_or_selection_unverified' if len(windows) != 3 or
                                    not all(window['selectedAudioSignalVerified'] for window in windows.values()) else
                                    'rendered_content_unverified' if evidence['distinctPositionFrames'] < 3 else
                                    'needs_content_review')
        output.append(evidence)
    (directory / 'review.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in output))
    usable = sum(row.get('distinctPositionFrames', 0) >= 3 and
                 len(row.get('capturedSourceAudio', {})) == 3 and
                 all(window['selectedAudioSignalVerified']
                     for window in row['capturedSourceAudio'].values()) for row in output[1:])
    print(json.dumps(dict(decoderCalibrationValid=True, candidates=len(output) - 1,
                          usableEvidenceCandidates=usable, contentConfirmed=0, physicalAudioOutputTested=False)))
    if not usable:
        raise RuntimeError('No candidate produced complete rendered-frame and non-silent audio evidence')


if __name__ == '__main__':
    review(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
