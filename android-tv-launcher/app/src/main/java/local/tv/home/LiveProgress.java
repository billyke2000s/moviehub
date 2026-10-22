package local.tv.home;

/** Detects a stopped live clock or video counter, independently of buffering events. */
final class LiveProgress {

    private long position = -1,
        pictures = -1,
        clockAt,
        videoAt;

    boolean stalled(long now, long time, long frames, boolean watching, long limit) {
        if (!watching) {
            position = pictures = -1;
            return false;
        }
        if (position < 0 || time != position) clockAt = now;
        if (pictures < 0 || frames != pictures) videoAt = now;
        position = time;
        pictures = frames;
        // Some live demuxers do not expose a useful clock. Actual advancing video wins.
        return frames >= 0 ? now - videoAt >= limit : now - clockAt >= limit;
    }

    boolean clockMoving(long now, long limit) {
        return position >= 0 && now - clockAt < limit;
    }

    void reset() {
        position = pictures = -1;
    }
}
