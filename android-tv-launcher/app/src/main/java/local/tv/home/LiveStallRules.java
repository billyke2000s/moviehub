package local.tv.home;

/**
 * The live-stream recovery rules {@link ExoLive} applies. Pure, so the rules are core-tested: the
 * player reports what it sees, and acts on the verdict a method returns.
 *
 * <ul>
 *   <li>{@link #POOR} ({@link LiveBuffering}): a fifth of the last minute spent buffering. The
 *       picture still plays, so nothing is torn down: the owner may look for a smoother copy in
 *       the background, checked before anything changes on screen (see LiveUpgrade).
 *   <li>{@link #STUCK} ({@link LiveBuffering}): one unbroken 12 s stall with the picture not
 *       moving. The stream has stopped arriving: move to another source, else reconnect.
 *   <li>{@link #DEAD} ({@link LiveProgress}): playing, but neither the picture nor the clock has
 *       moved for {@link #DEAD_MS}, with no error reported. A picture stopped while the clock (the
 *       sound) moves on is only logged by the player.
 *   <li>Reconnects ({@link LiveRestartBudget}): two per selection, refilled only after 30 s of
 *       moving picture, so a stream that plays briefly and dies cannot reconnect forever.
 * </ul>
 *
 * Buffering is counted only after the first picture and never while paused, so startup and a
 * paused picture are never judged.
 */
final class LiveStallRules {

    static final int NONE = 0,
        POOR = 1,
        STUCK = 2,
        DEAD = 3;

    /** The picture must have moved this recently for a long stall to be left alone. */
    static final long MOVING_MS = 2000;
    /** Playing, but nothing at all has moved for this long: the stream is dead. */
    static final long DEAD_MS = 8000;

    private final LiveBuffering buffering = new LiveBuffering();
    private final LiveProgress progress = new LiveProgress();
    private final LiveRestartBudget reconnects = new LiveRestartBudget();
    private boolean pictures;

    /** A new channel or source was chosen, or the viewer asked to reconnect: a fresh budget. */
    void newSelection() {
        opened();
        reconnects.reset();
    }

    /** The same selection is being opened again (a reconnect): startup is not counted. */
    void opened() {
        pictures = false;
        buffering.reset();
        progress.reset();
    }

    /** The first picture of this opening was drawn; stalls count from now on. */
    void firstPicture() {
        pictures = true;
    }

    /** The player started ({@code stalled}) or stopped buffering. Returns a verdict. */
    int buffering(long now, boolean stalled, boolean playWhenReady) {
        boolean watching = pictures && playWhenReady;
        buffering.update(now, stalled, watching);
        return judge(now, watching);
    }

    /**
     * Once a second: {@code position} is the playback clock, {@code frames} the pictures drawn so
     * far, {@code playing} whether the player is actually playing (not buffering or paused).
     * Returns a verdict.
     */
    int tick(long now, long position, long frames, boolean playing, boolean playWhenReady) {
        boolean watching = pictures && playWhenReady;
        reconnects.observe(now, frames, watching && playing);
        int verdict = judge(now, watching);
        if (verdict != NONE) return verdict;
        if (progress.stalled(now, position, frames, watching && playing, DEAD_MS)) {
            boolean clockMoving = progress.clockMoving(now, DEAD_MS);
            progress.reset();
            if (!clockMoving) {
                buffering.reset();
                return DEAD;
            }
        }
        return NONE;
    }

    /** Time spent buffering in the last minute, for the event log. */
    long bufferedMs(long now) {
        return buffering.bufferedMs(now);
    }

    /** Takes one reconnect from the budget; false when both are used and none refilled yet. */
    boolean reconnect() {
        return reconnects.acquire();
    }

    int reconnects() {
        return reconnects.attempts();
    }

    private int judge(long now, boolean watching) {
        if (buffering.stuck(now, watching)) {
            // A picture that still moves is not stuck, whatever the buffering events say.
            if (progress.videoMoving(now, MOVING_MS)) return NONE;
            buffering.reset();
            return STUCK;
        }
        return buffering.poor(now, watching) ? POOR : NONE;
    }
}
