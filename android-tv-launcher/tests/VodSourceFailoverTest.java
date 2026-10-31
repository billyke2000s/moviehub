package local.tv.home;

import java.util.Arrays;

/** Policy boundaries, exclusions, duplicate rejection, provider pinning and bounded exhaustion. */
public final class VodSourceFailoverTest {

    private static int checks;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("VOD check " + checks);
    }

    private static VodSourceFailover queue() {
        VodSourceFailover q = new VodSourceFailover();
        q.setSources(
            Arrays.asList(
                new VodSourceFailover.Source("https://example.org/a", "A", "one"),
                new VodSourceFailover.Source("https://example.org/b", "B", "one"),
                new VodSourceFailover.Source("https://example.org/c", "C", "two")
            )
        );
        return q;
    }

    public static void main(String[] args) {
        var q = queue();
        check(!q.onBufferingStarted(0));
        check(!q.isStalled(5000));
        q.onPlaying();
        check(!q.onBufferingStarted(1000));
        check(!q.isStalled(4999));
        check(q.isStalled(5000));
        q.onBufferingEnded();
        check(!q.isStalled(5001));
        check(q.onBufferingStarted(60000));
        check(q.failover().label.equals("B"));
        check(!q.onBufferingStarted(62000));
        q.onPlaying();
        q.setSeeking(true);
        check(!q.onBufferingStarted(65000));
        q.setSeeking(false);
        q.setUserPaused(true);
        check(!q.onBufferingStarted(66000));
        q.setUserPaused(false);
        check(!q.onBufferingStarted(67000));
        check(q.failover().label.equals("C"));
        check(q.failover() == null);
        check(q.failover() == null);
        q = queue();
        q.onPlaying();
        check(!q.onBufferingStarted(1000));
        q.onBufferingEnded();
        check(!q.onBufferingStarted(61001));
        q.onBufferingEnded();
        check(q.onBufferingStarted(62000));
        q = queue();
        check(q.select(0, true));
        check(q.failover().label.equals("B"));
        check(q.failover() == null);
        check(q.select(2, false));
        check(q.currentIndex() == 2);
        check(!q.select(-1, false));
        q = queue();
        check(q.select(1, false));
        q.exclude(0);
        check(q.failover().label.equals("C"));
        check(q.failover() == null);
        q.setSources(
            Arrays.asList(
                new VodSourceFailover.Source("a", "A", "p"),
                new VodSourceFailover.Source("a", "duplicate", "p"),
                null
            )
        );
        check(q.sources().size() == 1);
        check(q.failover() == null);
        frozenClock();
        System.out.println(checks + " VOD failover checks passed");
    }

    /** A clock that stops while playing, with no buffering, seek or pause, is a frozen source. */
    private static void frozenClock() {
        long F = VodSourceFailover.FROZEN_MS;
        var q = queue();
        // Before anything has played, a still clock is startup, not a freeze.
        check(!q.onClock(0, 0, false));
        check(!q.onClock(F + 5000, 0, false));
        q.onPlaying();
        // A moving clock is never frozen.
        for (int i = 1; i <= 30; i++) check(!q.onClock(i * 1000L, i * 1000L, false));
        // The clock stops at 30 s: the first still sample starts the count.
        check(!q.onClock(31_000, 30_000, false));
        check(!q.onClock(31_000 + F - 1, 30_000, false));
        check(q.onClock(31_000 + F, 30_000, false));
        // Reported once per freeze.
        check(!q.onClock(31_000 + F + 1000, 30_000, false));
        // It moves again, then stops again: a new freeze.
        check(!q.onClock(50_000, 30_500, false));
        check(!q.onClock(51_000, 30_500, false));
        check(q.onClock(51_000 + F, 30_500, false));

        // Buffering is left to the buffering rules.
        q = queue();
        q.onPlaying();
        check(!q.onClock(0, 5000, false));
        for (long t = 1000; t <= 3 * F; t += 1000) check(!q.onClock(t, 5000, true));
        // Buffering ends but the clock still does not move: counted from then.
        check(!q.onClock(3 * F + 1000, 5000, false));
        check(q.onClock(4 * F + 1000, 5000, false));

        // A user pause or a seek never counts as a freeze, and resets the count.
        q = queue();
        q.onPlaying();
        check(!q.onClock(0, 5000, false));
        check(!q.onClock(1000, 5000, false));
        q.setUserPaused(true);
        check(!q.onClock(1000 + 2 * F, 5000, false));
        q.setUserPaused(false);
        check(!q.onClock(2000 + 2 * F, 5000, false));
        check(!q.onClock(2000 + 3 * F - 1, 5000, false));
        check(q.onClock(2000 + 3 * F, 5000, false));
        q = queue();
        q.onPlaying();
        check(!q.onClock(0, 5000, false));
        check(!q.onClock(1000, 5000, false));
        q.setSeeking(true);
        check(!q.onClock(1000 + 2 * F, 5000, false));

        // A new source starts fresh: nothing counts until it plays.
        q = queue();
        q.onPlaying();
        check(!q.onClock(0, 5000, false));
        check(!q.onClock(1000, 5000, false));
        check(q.failover().label.equals("B"));
        check(!q.onClock(1000 + 2 * F, 5000, false));
        q.onPlaying();
        check(!q.onClock(2000 + 2 * F, 5000, false));
        check(!q.onClock(3000 + 2 * F, 5000, false));
        check(q.onClock(3000 + 3 * F, 5000, false));
    }
}
