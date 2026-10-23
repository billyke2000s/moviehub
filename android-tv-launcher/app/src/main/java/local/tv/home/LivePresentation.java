package local.tv.home;

/** Missing rendered buffers, not image colour/content: black programmes and static shots are valid. */
final class LivePresentation {

    private long missingSince = -1;

    void result(long now, boolean hasBuffer) {
        if (hasBuffer) missingSince = -1;
        else if (missingSince < 0) missingSince = now;
    }

    boolean missing(long now, long limit) {
        return missingSince >= 0 && now - missingSince >= limit;
    }

    void reset() {
        missingSince = -1;
    }
}
