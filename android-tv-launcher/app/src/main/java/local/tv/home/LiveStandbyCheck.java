package local.tv.home;

/**
 * When a copy loading in the background (ExoStandby) is ready to take over, without the player:
 * it must have played on its own for {@link #PLAYED_MS} without a break (so it has really loaded,
 * not just answered), within {@link #TIMEOUT_MS} of starting. Until then the copy on screen
 * carries on, and if the time runs out nothing changes on screen at all.
 */
final class LiveStandbyCheck {

    /** Continuous playing needed before the switch. */
    static final long PLAYED_MS = 1500;
    /** Time allowed to get there. */
    static final long TIMEOUT_MS = 10_000;

    static final int WAIT = 0,
        READY = 1,
        FAILED = 2;

    private final long started;
    private long playingFrom = -1;

    LiveStandbyCheck(long now) {
        started = now;
    }

    /** A sample of the background player: whether it plays and its playback position. */
    int sample(long now, boolean playing, long position) {
        if (!playing || (playingFrom >= 0 && position < playingFrom)) {
            // Not playing, or it jumped back (rejoining its live window): count again.
            playingFrom = playing ? position : -1;
        } else if (playingFrom < 0) playingFrom = position;
        else if (position - playingFrom >= PLAYED_MS) return READY;
        return now - started >= TIMEOUT_MS ? FAILED : WAIT;
    }
}
