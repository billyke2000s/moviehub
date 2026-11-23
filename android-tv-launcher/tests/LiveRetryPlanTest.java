package local.tv.home;

/** LiveRetryPlan: never giving up, growing waits, and the last working copy tried first. */
public final class LiveRetryPlanTest {

    private static int checks;

    public static void main(String[] args) {
        waitsGrowAndThenRepeat();
        zliveIsNeverRetriedTooSoon();
        aCopyThatPlayedProperlyIsRememberedAndTriedFirst();
        aCopyThatOnlyFlickeredIsNotRemembered();
        aProvenPictureStartsTheWaitsShortAgain();
        anotherChannelForgetsEverything();
        System.out.println(checks + " live retry checks passed");
    }

    private static void waitsGrowAndThenRepeat() {
        LiveRetryPlan plan = new LiveRetryPlan();
        long[] expected = { 2000, 5000, 10_000, 15_000, 30_000, 60_000, 60_000, 60_000 };
        for (int i = 0; i < expected.length; i++) check("wait " + i, plan.nextDelay(false) == expected[i]);
        check("attempts counted", plan.attempts() == expected.length);
    }

    private static void zliveIsNeverRetriedTooSoon() {
        LiveRetryPlan plan = new LiveRetryPlan();
        check("first ZLive wait", plan.nextDelay(true) == LiveRetryPlan.ZLIVE_MIN_MS);
        plan.nextDelay(true);
        plan.nextDelay(true);
        plan.nextDelay(true);
        check("longer waits kept", plan.nextDelay(true) == 30_000);
    }

    private static void aCopyThatPlayedProperlyIsRememberedAndTriedFirst() {
        LiveRetryPlan plan = new LiveRetryPlan();
        check("nothing known", plan.good() < 0 && !plan.goodTurn());
        plan.pictureShown(2, "copy-2", 0);
        plan.stopped(LiveRetryPlan.PROVEN_MS);
        check("remembered", plan.good() == 2 && "copy-2".equals(plan.goodUrl()));
        plan.nextDelay(false);
        check("first try: the working copy alone", plan.goodTurn());
        plan.nextDelay(false);
        check("second try: every copy", !plan.goodTurn());
        plan.nextDelay(false);
        check("third try: the working copy again", plan.goodTurn());
        // Another copy that never played properly does not replace it.
        plan.pictureShown(0, "copy-0", 100_000);
        plan.stopped(101_000);
        check("still the working copy", plan.good() == 2);
    }

    private static void aCopyThatOnlyFlickeredIsNotRemembered() {
        LiveRetryPlan plan = new LiveRetryPlan();
        plan.pictureShown(1, "copy-1", 0);
        plan.stopped(LiveRetryPlan.PROVEN_MS - 1);
        check("not proven", plan.good() < 0);
        plan.stopped(LiveRetryPlan.PROVEN_MS * 5);
        check("stopping twice proves nothing", plan.good() < 0);
    }

    private static void aProvenPictureStartsTheWaitsShortAgain() {
        LiveRetryPlan plan = new LiveRetryPlan();
        plan.nextDelay(false);
        plan.nextDelay(false);
        plan.nextDelay(false);
        plan.pictureShown(0, "copy-0", 0);
        plan.stopped(60_000);
        check("attempts reset", plan.attempts() == 0);
        check("short wait again", plan.nextDelay(false) == 2000);
    }

    private static void anotherChannelForgetsEverything() {
        LiveRetryPlan plan = new LiveRetryPlan();
        plan.pictureShown(3, "copy-3", 0);
        plan.stopped(30_000);
        plan.nextDelay(false);
        plan.newChannel();
        check("forgotten", plan.good() < 0 && plan.goodUrl() == null && plan.attempts() == 0);
        check("no working-copy turn", !plan.goodTurn());
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live retry check " + checks + ": " + name);
    }
}
