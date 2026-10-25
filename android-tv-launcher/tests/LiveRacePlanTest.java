package local.tv.home;

import java.util.Arrays;
import java.util.List;

public final class LiveRacePlanTest {

    private static int checks;

    private static void same(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Race " + checks + ": expected [" + expected + "] got [" + actual + "]");
        }
        checks++;
    }

    public static void main(String[] args) {
        // Copies 0..4: SD, SD, HD (720), FHD (1080), SD. Priority: 1080, 720, then guide order.
        int[] copies = { 0, 1, 2, 3, 4 };
        int[] quality = { 480, 480, 720, 1080, 480 };
        LiveRacePlan plan = new LiveRacePlan(copies, quality, 0);
        same(Arrays.asList(3, 2, 0), plan.launch());
        same(Arrays.asList(), plan.launch());

        // A fast SD link while the 1080p and 720p are still being looked up: wait inside the window…
        plan.resolved(0);
        same(-1, plan.pick(1000));
        // …the 720p arrives; the 1080p could still beat it, so keep waiting…
        plan.resolved(2);
        same(-1, plan.pick(2000));
        // …the window closes: play the best found so far.
        same(2, plan.pick(LiveRacePlan.WINDOW_MS));

        // The 720p does not play: the SD already found plays at once, without waiting again,
        // even though the 1080p is still being looked up.
        plan.failed(2);
        same(Arrays.asList(1), plan.launch());
        same(0, plan.pick(LiveRacePlan.WINDOW_MS + 1));
        same(true, plan.remaining());

        // The best possible copy found first plays immediately, inside the window.
        LiveRacePlan best = new LiveRacePlan(copies, quality, 0);
        best.launch();
        best.resolved(3);
        same(3, best.pick(10));

        // Equal quality keeps the guide's copy order, and lookups beyond three wait their turn.
        LiveRacePlan sd = new LiveRacePlan(new int[] { 5, 6, 7, 8 }, new int[] { 480, 480, 480, 480 }, 0);
        same(Arrays.asList(5, 6, 7), sd.launch());
        sd.resolved(7);
        sd.resolved(6);
        // Nothing better than SD is pending, so no wait even inside the window; 6 is ahead of 7.
        same(6, sd.pick(5));
        sd.failed(5);
        same(Arrays.asList(8), sd.launch());
        same(7, sd.pick(6));
        sd.failed(7);
        sd.failed(8);
        same(false, sd.remaining());
        same(-1, sd.pick(100000));

        // Nothing found yet: nothing to play.
        LiveRacePlan none = new LiveRacePlan(new int[] { 1, 2 }, new int[] { 720, 480 }, 0);
        List<Integer> started = none.launch();
        same(2, started.size());
        same(-1, none.pick(100000));
        System.out.println(checks + " live source race checks passed");
    }
}
