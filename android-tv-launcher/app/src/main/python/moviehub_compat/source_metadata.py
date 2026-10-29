"""Optional display-only metadata from bytes already read by the media probe. No requests."""
import re


def codec_name(identifier):
    code = identifier.lower().strip()
    stem = code.split('.')[0]
    return {'avc1': 'AVC (H.264)', 'avc3': 'AVC (H.264)', 'hvc1': 'HEVC (H.265)',
            'hev1': 'HEVC (H.265)', 'av01': 'AV1', 'vp09': 'VP9', 'vp9': 'VP9',
            'ec-3': 'E-AC-3', 'ac-3': 'AC-3', 'opus': 'Opus', 'flac': 'FLAC'}.get(
                stem, 'AAC' if code.startswith('mp4a.40.') else '')


def describe(prefix, headers, playlist=False):
    fields = []
    if prefix.startswith((b'#EXTM3U', b'\xef\xbb\xbf#EXTM3U')):
        codecs = []
        # CODECS describes advertised variants, not the decoder's selected audio track.
        for line in prefix.decode('utf-8', errors='replace').splitlines():
            if not line.startswith(('#EXT-X-STREAM-INF:', '#EXT-X-I-FRAME-STREAM-INF:')):
                continue
            match = re.search(r'(?:^|,)CODECS="([^"]{1,300})"', line.partition(':')[2])
            if match:
                for code in match[1].split(','):
                    name = codec_name(code)
                    if name and name not in codecs:
                        codecs.append(name)
        if codecs:
            fields.append('Manifest codecs: ' + ' / '.join(codecs))
    # A 206 Content-Length is the sample size, never the movie's file size.
    total = re.fullmatch(r'bytes \d+-\d+/(\d+)', headers.get('Content-Range', ''), re.I)
    if total and int(total[1]) > 0 and not playlist and not prefix.startswith((b'#EXTM3U', b'\xef\xbb\xbf#EXTM3U')) and b'<MPD' not in prefix[:1024]:
        size = int(total[1])
        fields.append('File: %.2f %s' % ((size / 1024 ** 3, 'GiB') if size >= 1024 ** 3
                                       else (size / 1024 ** 2, 'MiB')))
    return ' · '.join(fields)
