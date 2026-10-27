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
        // A source that never moves: switched after NO_PROGRESS_MS, whatever its events said.
        q = queue();
        q.onPlaying();
        long wait = VodSourceFailover.NO_PROGRESS_MS;
        check(!q.noProgress(0, 0));
        check(!q.noProgress(wait - 1, 0));
        check(q.noProgress(wait, 0));
        // Then the wait starts again rather than firing every second.
        check(!q.noProgress(wait + 1000, 0));
        check(q.noProgress(2 * wait, 0));
        // Moving picture is progress; a tiny re-report of the same place is not.
        q = queue();
        check(!q.noProgress(0, 1000));
        check(!q.noProgress(wait - 1, 1200));
        check(q.noProgress(wait, 1300));
        q = queue();
        for (long t = 0; t <= 3 * wait; t += 1000) check(!q.noProgress(t, t));
        // Pausing or seeking never counts, and the wait starts afresh afterwards.
        q = queue();
        check(!q.noProgress(0, 5000));
        q.setUserPaused(true);
        check(!q.noProgress(wait * 2, 5000));
        q.setUserPaused(false);
        check(!q.noProgress(wait * 2 + 1000, 5000));
        check(!q.noProgress(wait * 3, 5000));
        check(q.noProgress(wait * 3 + 1000, 5000));
        q = queue();
        check(!q.noProgress(0, 5000));
        q.setSeeking(true);
        check(!q.noProgress(wait * 2, 5000));
        q.setSeeking(false);
        check(!q.noProgress(wait * 2 + 1, 5000));
        // Switching source starts the wait again.
        q = queue();
        check(!q.noProgress(0, 0));
        check(q.failover() != null);
        check(!q.noProgress(wait, 0));
        System.out.println(checks + " VOD failover checks passed");
    }
}
