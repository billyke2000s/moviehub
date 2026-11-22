package local.tv.home;

/** LiveStandbyCheck: a background copy takes over only after it has really played. */
public final class LiveStandbyCheckTest {

    private static int checks;

    public static void main(String[] args) {
        readyAfterPlayingContinuously();
        aBreakStartsTheCountAgain();
        aJumpBackStartsTheCountAgain();
        failsWhenItNeverPlaysLongEnough();
        System.out.println(checks + " live standby checks passed");
    }

    private static void readyAfterPlayingContinuously() {
        LiveStandbyCheck check = new LiveStandbyCheck(0);
        check("loading", check.sample(250, false, 0) == LiveStandbyCheck.WAIT);
        check("first frame", check.sample(500, true, 20_000) == LiveStandbyCheck.WAIT);
        check("1.25 s played", check.sample(1750, true, 21_250) == LiveStandbyCheck.WAIT);
        check("1.5 s played", check.sample(2000, true, 21_500) == LiveStandbyCheck.READY);
    }

    private static void aBreakStartsTheCountAgain() {
        LiveStandbyCheck check = new LiveStandbyCheck(0);
        check.sample(250, true, 0);
        check("1 s", check.sample(1250, true, 1000) == LiveStandbyCheck.WAIT);
        check("buffers", check.sample(1500, false, 1000) == LiveStandbyCheck.WAIT);
        check("plays again", check.sample(2500, true, 1000) == LiveStandbyCheck.WAIT);
        check("1.4 s since", check.sample(3900, true, 2400) == LiveStandbyCheck.WAIT);
        check("1.5 s since", check.sample(4000, true, 2500) == LiveStandbyCheck.READY);
    }

    private static void aJumpBackStartsTheCountAgain() {
        LiveStandbyCheck check = new LiveStandbyCheck(0);
        check.sample(250, true, 50_000);
        // Rejoined its live window further back: the count restarts from there.
        check("jumped back", check.sample(1250, true, 40_000) == LiveStandbyCheck.WAIT);
        check("not from the old start", check.sample(2000, true, 41_400) == LiveStandbyCheck.WAIT);
        check("1.5 s from the jump", check.sample(2250, true, 41_500) == LiveStandbyCheck.READY);
    }

    private static void failsWhenItNeverPlaysLongEnough() {
        LiveStandbyCheck check = new LiveStandbyCheck(1000);
        check("waiting", check.sample(10_999, false, 0) == LiveStandbyCheck.WAIT);
        check("ten seconds", check.sample(11_000, false, 0) == LiveStandbyCheck.FAILED);
        LiveStandbyCheck stuttering = new LiveStandbyCheck(0);
        for (long t = 0; t < 10_000; t += 500) {
            boolean playing = (t / 500) % 2 == 0;
            check("stutter " + t, stuttering.sample(t, playing, t) == LiveStandbyCheck.WAIT);
        }
        check("never long enough", stuttering.sample(10_000, true, 10_000) == LiveStandbyCheck.FAILED);
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live standby check " + checks + ": " + name);
    }
}
