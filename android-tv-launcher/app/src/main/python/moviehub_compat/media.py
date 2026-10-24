"""Read declared duration from bounded media bytes; missing metadata stays unknown."""
import re
import struct

def duration_seconds(data, kind):
    if b'#EXTM3U' in data[:16]:
        # A rolling live/event playlist is not the full programme duration.
        if b'#EXT-X-ENDLIST' not in data: return None
        values = re.findall(rb'^#EXTINF:([0-9]+(?:\.[0-9]+)?)', data, re.M)
        return sum(float(value) for value in values) if values else None
    if 'dash+xml' in kind:
        match = re.search(rb'mediaPresentationDuration=[\'"]PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?[\'"]', data)
        if match: return sum(float(value or 0)*scale for value,scale in zip(match.groups(),(3600,60,1)))
    def boxes(start, end, depth=0):
        if depth > 2: return None
        while start + 8 <= end:
            size, name = struct.unpack_from('>I4s', data, start)
            header = 8
            if size == 1:
                if start + 16 > end: return None
                size = struct.unpack_from('>Q', data, start+8)[0]; header = 16
            if size == 0: size = end-start
            if size < header: return None
            body = start+header
            available = min(end, start+size)
            if name == b'mvhd' and body+20 <= available:
                version = data[body]
                offset = body + (20 if version == 1 else 12)
                width = 12 if version == 1 else 8
                if version not in (0,1) or offset+width > available: return None
                scale = struct.unpack_from('>I', data, offset)[0]
                duration = struct.unpack_from('>Q' if version == 1 else '>I', data, offset+4)[0]
                if scale and duration not in (0xffffffff,0xffffffffffffffff): return duration/scale
            if name == b'moov':
                found = boxes(body, available, depth+1)
                if found is not None: return found
            start += size
        return None
    return boxes(0, len(data))
