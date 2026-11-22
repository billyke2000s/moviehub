package local.tv.home;

/**
 * What Live TV does once every copy of a channel has failed, without the timers (see LiveStage).
 * The player is never stopped for good while a copy might come back: it keeps the last picture,
 * says it is reconnecting and tries again after {@link #DELAYS_MS} (growing waits, so a dead
 * channel does not hammer its services). The copy that last played properly, for at least
 * {@link #PROVEN_MS}, is remembered and tried first, on its own, every other time, because a
 * copy that worked a minute ago is the likeliest to work again.
 */
final class LiveRetryPlan {

    /** A copy whose picture ran this long is known to work. */
    static final long PROVEN_MS = 10_000;
    /** Waits before each new try once every copy failed; the last one repeats. */
    static final long[] DELAYS_MS = { 2_000, 5_000, 10_000, 15_000, 30_000, 60_000 };
    /** ZLive's sessions are costly to open: never retried sooner than this. */
    static final long ZLIVE_MIN_MS = 15_000;

    private int showing = -1;
    private String showingUrl;
    private long showingSince;
    private int good = -1;
    private String goodUrl;
    private int attempts;

    /** Another channel: nothing is known about its copies yet. */
    void newChannel() {
        showing = good = -1;
        showingUrl = goodUrl = null;
        attempts = 0;
    }

    /** Copy {@code copy}, opened at {@code url}, drew its first picture. */
    void pictureShown(int copy, String url, long now) {
        showing = copy;
        showingUrl = url;
        showingSince = now;
    }

    /**
     * The copy on screen stopped (it failed, or another took over): if it played long enough it
     * becomes the last working copy, and the waits start short again.
     */
    void stopped(long now) {
        if (showing >= 0 && showingUrl != null && now - showingSince >= PROVEN_MS) {
            good = showing;
            goodUrl = showingUrl;
            attempts = 0;
        }
        showing = -1;
        showingUrl = null;
    }

    /** The copy that last played properly, or -1. */
    int good() {
        return good;
    }

    /** The address that copy played from, or null. */
    String goodUrl() {
        return goodUrl;
    }

    /** Every copy failed: how long to wait before trying again. Counts the attempt. */
    long nextDelay(boolean zlive) {
        long delay = DELAYS_MS[Math.min(attempts, DELAYS_MS.length - 1)];
        attempts++;
        return zlive ? Math.max(delay, ZLIVE_MIN_MS) : delay;
    }

    /** Tries made since the last proven picture. */
    int attempts() {
        return attempts;
    }

    /**
     * Whether the coming try reopens the last working copy on its own (the first try, and every
     * other one after it) rather than every copy again from the first.
     */
    boolean goodTurn() {
        return good >= 0 && goodUrl != null && attempts % 2 == 1;
    }
}
