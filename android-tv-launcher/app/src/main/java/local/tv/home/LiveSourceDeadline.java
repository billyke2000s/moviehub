package local.tv.home;

/** Video-start deadlines. DaddyLive also needs a first-frame bound after its lookup succeeds. */
final class LiveSourceDeadline {

    static final long START_MS = 15_000,
        RACE_MS = 8_000,
        ZLIVE_MS = 60_000,
        DADDYLIVE_FIRST_FRAME_MS = 15_000;

    private LiveSourceDeadline() {}

    /**
     * DaddyLive can return a valid playlist but never deliver a picture. Start this clock only
     * after a URL has been resolved and given to the player. A pause or a rendered frame cancels it.
     */
    static boolean daddyFirstFrameMissing(long openedAt, long now, boolean playWhenReady) {
        return (
            playWhenReady &&
            openedAt > 0 &&
            now >= openedAt &&
            now - openedAt >= DADDYLIVE_FIRST_FRAME_MS
        );
    }

    static long milliseconds(boolean alternative, boolean zlive, boolean raced) {
        if (alternative) return raced ? RACE_MS : START_MS;
        return zlive ? ZLIVE_MS : 0;
    }
}
