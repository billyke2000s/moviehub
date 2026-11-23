package local.tv.home;

/** The live recovery rules ExoLive uses (LiveStallRules). */
public final class LiveStallRulesTest {

    private static int checks;

    public static void main(String[] args) {
        startupIsNotCounted();
        aLongStallIsStuck();
        briefStallsAreNeverActedOn();
        frequentPausesAreReportedAsPoorWithoutTearingDown();
        pausingIsNeverBuffering();
        aDeadStreamIsRecoveredButAStillPictureWithSoundIsNot();
        reconnectsAreBoundedAndRefilledOnlyBySustainedPicture();
        System.out.println(checks + " live stall rule checks passed");
    }

    private static LiveStallRules watching() {
        LiveStallRules rules = new LiveStallRules();
        rules.newSelection();
        rules.firstPicture();
        return rules;
    }

    private static void startupIsNotCounted() {
        LiveStallRules rules = new LiveStallRules();
        rules.newSelection();
        check("startup buffering", rules.buffering(0, true, true) == LiveStallRules.NONE);
        check("startup long wait", rules.tick(30_000, 0, 0, false, true) == LiveStallRules.NONE);
        // A reconnect opens again: its startup is not counted either, until its first picture.
        rules.firstPicture();
        rules.opened();
        check("reopen startup", rules.buffering(40_000, true, true) == LiveStallRules.NONE);
        check("reopen long wait", rules.tick(70_000, 0, 0, false, true) == LiveStallRules.NONE);
    }

    private static void aLongStallIsStuck() {
        LiveStallRules rules = watching();
        rules.tick(0, 0, 10, true, true);
        check("stall starts", rules.buffering(1000, true, true) == LiveStallRules.NONE);
        check(
            "eight seconds is no longer enough",
            rules.tick(9000, 0, 10, false, true) == LiveStallRules.NONE
        );
        check("under twelve seconds", rules.tick(12_999, 0, 10, false, true) == LiveStallRules.NONE);
        check("twelve seconds", rules.tick(13_000, 0, 10, false, true) == LiveStallRules.STUCK);
        check("acted once", rules.tick(14_000, 0, 10, false, true) == LiveStallRules.NONE);
    }

    private static void briefStallsAreNeverActedOn() {
        // Three short stalls a minute moved a working source away before; now nothing happens.
        LiveStallRules rules = watching();
        long frames = 10;
        for (long t = 0; t < 60_000; t += 1000) {
            boolean stall = t % 20_000 == 0;
            if (stall) check("brief stall " + t, rules.buffering(t, true, true) == LiveStallRules.NONE);
            if (t % 20_000 == 500 || t % 20_000 == 1000) rules.buffering(t, false, true);
            frames += 25;
            check("tick " + t, rules.tick(t, t, frames, !stall, true) == LiveStallRules.NONE);
        }
    }

    private static void frequentPausesAreReportedAsPoorWithoutTearingDown() {
        LiveStallRules rules = watching();
        // Three-second pauses every ten seconds: the fourth reaches 12 s in the minute.
        int verdict = LiveStallRules.NONE;
        long frames = 10;
        long t = 0;
        for (int pause = 0; pause < 4 && verdict == LiveStallRules.NONE; pause++) {
            verdict = rules.buffering(t, true, true);
            for (long s = t + 1000; s <= t + 3000 && verdict == LiveStallRules.NONE; s += 1000) {
                verdict = rules.tick(s, s, frames, false, true);
            }
            if (verdict == LiveStallRules.NONE) verdict = rules.buffering(t + 3000, false, true);
            for (long s = t + 4000; s < t + 10_000 && verdict == LiveStallRules.NONE; s += 1000) {
                frames += 25;
                verdict = rules.tick(s, s, frames, true, true);
            }
            t += 10_000;
        }
        check("poor, not stuck", verdict == LiveStallRules.POOR);
        check("reported once", rules.tick(t, t, frames + 25, true, true) == LiveStallRules.NONE);
    }

    private static void pausingIsNeverBuffering() {
        LiveStallRules rules = watching();
        check("paused stall", rules.buffering(0, true, false) == LiveStallRules.NONE);
        check("paused long", rules.tick(30_000, 0, 10, false, false) == LiveStallRules.NONE);
        // A stall that began while watching is dropped by pausing.
        rules.buffering(31_000, true, true);
        check("pause mid-stall", rules.tick(40_000, 0, 10, false, false) == LiveStallRules.NONE);
        check("resume", rules.tick(41_000, 0, 10, false, true) == LiveStallRules.NONE);
    }

    private static void aDeadStreamIsRecoveredButAStillPictureWithSoundIsNot() {
        LiveStallRules dead = watching();
        dead.tick(0, 5000, 100, true, true);
        check("seven seconds still", dead.tick(7999, 5000, 100, true, true) == LiveStallRules.NONE);
        check("nothing moved for eight", dead.tick(8000, 5000, 100, true, true) == LiveStallRules.DEAD);

        LiveStallRules sound = watching();
        for (long t = 0; t <= 12_000; t += 1000) {
            check(
                "still picture with sound " + t,
                sound.tick(t, 5000 + t, 100, true, true) == LiveStallRules.NONE
            );
        }
    }

    private static void reconnectsAreBoundedAndRefilledOnlyBySustainedPicture() {
        LiveStallRules rules = watching();
        check("first", rules.reconnect());
        check("second", rules.reconnect());
        check("third refused", !rules.reconnect());
        // One frame does not refill the budget (the old ExoLive rule did, and could loop).
        rules.opened();
        rules.firstPicture();
        check("one frame", !rules.reconnect());
        long frames = 0;
        for (long t = 0; t < LiveRestartBudget.HEALTHY_MS; t += 1000) {
            rules.tick(t, t, (frames += 15), true, true);
        }
        check("29 s", !rules.reconnect());
        // The first sample sets the baseline; then 30 s of rising frames.
        for (long t = 0; t <= LiveRestartBudget.HEALTHY_MS + 1000; t += 1000) {
            rules.tick(t, t, (frames += 15), true, true);
        }
        check("30 s refills", rules.reconnect());
        check("count", rules.reconnects() == 1);
        rules.newSelection();
        check("new selection", rules.reconnects() == 0);
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live stall rule check " + checks + ": " + name);
    }
}
