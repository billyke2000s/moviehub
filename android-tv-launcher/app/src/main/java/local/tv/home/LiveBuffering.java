package local.tv.home;

import java.util.ArrayDeque;

/**
 * Live buffering thresholds, independent of the stricter VOD policy. Uses monotonic milliseconds.
 *
 * <p>Two different things are measured, because they need different answers:
 *
 * <ul>
 *   <li><b>Stuck</b>: one stall of {@link #STALL_MS} without a break. The stream has stopped
 *       arriving, so the player has to act.
 *   <li><b>Poor</b>: {@link #POOR_MS} spent buffering within the last {@link #WINDOW_MS} (a fifth of
 *       the time), however it is split up. The picture still plays, but keeps pausing. A few short
 *       pauses are normal for live HLS and never reach this, so a stream that "barely lagged" is
 *       left alone.
 * </ul>
 *
 * Nothing counts while the viewer is not watching (before the first picture, or paused).
 */
final class LiveBuffering {

    /** One unbroken stall this long means the stream is stuck. */
    static final long STALL_MS = 12_000;
    /** Time spent buffering within {@link #WINDOW_MS} that makes the picture poor. */
    static final long POOR_MS = 12_000;
    static final long WINDOW_MS = 60_000;

    /** Finished stalls inside the window, as {start, end} pairs. */
    private final ArrayDeque<long[]> stalls = new ArrayDeque<>();
    private boolean buffering, stuckReported;
    private long since;
    /** Buffering before this time no longer counts towards poor (it was already reported). */
    private long countFrom = Long.MIN_VALUE;

    /** The player started ({@code stalled}) or stopped buffering. */
    void update(long now, boolean stalled, boolean watching) {
        if (!watching) {
            reset();
            return;
        }
        if (stalled && !buffering) {
            buffering = true;
            since = now;
            stuckReported = false;
        } else if (!stalled && buffering) {
            buffering = false;
            stalls.addLast(new long[] { since, now });
        }
        forget(now);
    }

    /** True once per stall, when it has lasted {@link #STALL_MS}. */
    boolean stuck(long now, boolean watching) {
        if (!watching) {
            reset();
            return false;
        }
        if (!stuckReported && persistent(now)) {
            stuckReported = true;
            return true;
        }
        return false;
    }

    /**
     * True once when the time spent buffering in the last minute reaches {@link #POOR_MS}. The
     * count then starts again, so the next report needs as much buffering again.
     */
    boolean poor(long now, boolean watching) {
        if (!watching) {
            reset();
            return false;
        }
        forget(now);
        if (bufferedMs(now) < POOR_MS) return false;
        // Start counting afresh. A stall in progress keeps its start for the stuck rule, so its
        // time already counted is skipped by moving the window's start instead.
        stalls.clear();
        countFrom = now;
        return true;
    }

    /** The stall in progress has lasted at least {@link #STALL_MS}. */
    boolean persistent(long now) {
        return buffering && now - since >= STALL_MS;
    }

    /** Time spent buffering within the last {@link #WINDOW_MS}, including a stall in progress. */
    long bufferedMs(long now) {
        long from = Math.max(now - WINDOW_MS, countFrom);
        long total = 0;
        for (long[] stall : stalls) total += Math.max(0, stall[1] - Math.max(stall[0], from));
        if (buffering) total += now - Math.max(since, from);
        return total;
    }

    void reset() {
        stalls.clear();
        buffering = stuckReported = false;
        countFrom = Long.MIN_VALUE;
    }

    private void forget(long now) {
        while (!stalls.isEmpty() && stalls.peekFirst()[1] <= now - WINDOW_MS) stalls.removeFirst();
    }
}
