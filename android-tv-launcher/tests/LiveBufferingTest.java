package local.tv.home;

/** LiveBuffering: brief pauses are left alone, a poor picture and a stuck stream are told apart. */
public final class LiveBufferingTest {

    private static int checks;

    public static void main(String[] args) {
        nothingCountsWhileNotWatching();
        oneUnbrokenStallIsStuckOnce();
        shortPausesNeverReachPoor();
        aFifthOfTheMinuteBufferingIsPoor();
        oldBufferingLeavesTheWindow();
        poorStartsCountingAfreshAfterItIsReported();
        aStallInProgressCountsButKeepsItsStart();
        System.out.println(checks + " live buffering checks passed");
    }

    private static void nothingCountsWhileNotWatching() {
        LiveBuffering live = new LiveBuffering();
        live.update(0, true, false);
        check("not watching: not stuck", !live.stuck(60_000, false));
        check("not watching: not poor", !live.poor(60_000, false));
        check("not watching: nothing counted", live.bufferedMs(60_000) == 0);
        // Pausing drops a stall that began while watching.
        live.update(70_000, true, true);
        check("paused mid-stall", !live.stuck(80_000, false));
        check("forgotten", !live.persistent(90_000));
    }

    private static void oneUnbrokenStallIsStuckOnce() {
        LiveBuffering live = new LiveBuffering();
        live.update(1000, true, true);
        check("under twelve seconds", !live.stuck(12_999, true));
        check("not persistent yet", !live.persistent(12_999));
        check("twelve seconds", live.stuck(13_000, true));
        check("persistent", live.persistent(13_000));
        check("reported once", !live.stuck(20_000, true));
        live.update(20_001, false, true);
        check("ended", !live.persistent(20_002));
        // A new stall can be stuck again.
        live.update(30_000, true, true);
        check("next stall", live.stuck(42_000, true));
    }

    private static void shortPausesNeverReachPoor() {
        LiveBuffering live = new LiveBuffering();
        // Ten half-second pauses in a minute (the old rule moved on after three): 5 s in all.
        for (int i = 0; i < 10; i++) {
            live.update(i * 6000L, true, true);
            live.update(i * 6000L + 500, false, true);
            check("brief pause " + i, !live.poor(i * 6000L + 501, true));
        }
        check("five seconds counted", live.bufferedMs(55_000) == 5000);
        check("never stuck", !live.stuck(60_000, true));
    }

    private static void aFifthOfTheMinuteBufferingIsPoor() {
        LiveBuffering live = new LiveBuffering();
        // Four-second pauses every ten seconds: 12 s of buffering after the third.
        live.update(0, true, true);
        live.update(4000, false, true);
        check("4 s", !live.poor(4000, true));
        live.update(10_000, true, true);
        live.update(14_000, false, true);
        check("8 s", !live.poor(14_000, true));
        live.update(20_000, true, true);
        check("11.999 s", !live.poor(23_999, true));
        check("12 s while still pausing", live.poor(24_000, true));
        check("not stuck: each pause is short", !live.stuck(24_000, true));
    }

    private static void oldBufferingLeavesTheWindow() {
        LiveBuffering live = new LiveBuffering();
        live.update(0, true, true);
        live.update(8000, false, true);
        // Eight seconds a minute and a bit ago no longer count.
        live.update(70_000, true, true);
        live.update(74_000, false, true);
        check("old stall forgotten", live.bufferedMs(74_000) == 4000);
        check("so not poor", !live.poor(74_000, true));
        // A stall straddling the window's start counts only its part inside.
        LiveBuffering edge = new LiveBuffering();
        edge.update(0, true, true);
        edge.update(10_000, false, true);
        check("part inside", edge.bufferedMs(65_000) == 5000);
    }

    private static void poorStartsCountingAfreshAfterItIsReported() {
        LiveBuffering live = new LiveBuffering();
        live.update(0, true, true);
        live.update(6000, false, true);
        live.update(10_000, true, true);
        live.update(16_000, false, true);
        check("poor", live.poor(16_000, true));
        check("not again at once", !live.poor(17_000, true));
        check("counting afresh", live.bufferedMs(17_000) == 0);
        live.update(20_000, true, true);
        live.update(31_000, false, true);
        check("eleven more seconds", !live.poor(31_000, true));
        live.update(40_000, true, true);
        check("twelve more seconds", live.poor(41_000, true));
    }

    private static void aStallInProgressCountsButKeepsItsStart() {
        LiveBuffering live = new LiveBuffering();
        live.update(0, true, true);
        live.update(6000, false, true);
        live.update(10_000, true, true);
        check("poor during a stall", live.poor(16_000, true));
        // The stall that began at 10 s is still stuck at 22 s: poor did not move its start.
        check("stuck from its own start", live.stuck(22_000, true));
        // Only its part after the report counts towards poor again.
        check("counted from the report", live.bufferedMs(22_000) == 6000);
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live buffering check " + checks + ": " + name);
    }
}
