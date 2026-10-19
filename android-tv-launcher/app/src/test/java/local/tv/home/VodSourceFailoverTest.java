package local.tv.home;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class VodSourceFailoverTest {
    private VodSourceFailover queue() {
        VodSourceFailover q = new VodSourceFailover();
        q.setSources(Arrays.asList(
            new VodSourceFailover.Source("https://example.org/a", "A", "free"),
            new VodSourceFailover.Source("https://example.org/b", "B", "free"),
            new VodSourceFailover.Source("https://example.org/c", "C", "premiumize")
        ));
        return q;
    }

    @Test public void twoStallsInMinuteTriggerFailover() {
        VodSourceFailover q = queue();
        assertFalse(q.onBufferingStarted(1000)); // startup ignored
        q.onPlaying();
        assertFalse(q.onBufferingStarted(2000));
        q.onBufferingEnded();
        assertTrue(q.onBufferingStarted(4000));
        assertEquals("https://example.org/b", q.failover().url);
    }

    @Test public void continuousStallTriggersAtFourSeconds() {
        VodSourceFailover q = queue();
        q.onPlaying();
        q.onBufferingStarted(1000);
        assertFalse(q.isStalled(4999));
        assertTrue(q.isStalled(5000));
    }

    @Test public void seekingAndPauseDoNotCount() {
        VodSourceFailover q = queue();
        q.onPlaying();
        q.setSeeking(true);
        assertFalse(q.onBufferingStarted(1000));
        q.setSeeking(false);
        q.setUserPaused(true);
        assertFalse(q.onBufferingStarted(2000));
        q.setUserPaused(false);
        assertFalse(q.onBufferingStarted(3000));
    }

    @Test public void manualPremiumizeSelectionStaysWithinProvider() {
        VodSourceFailover q = queue();
        assertTrue(q.select(2, true));
        assertNull(q.failover());
    }

    @Test public void exhaustedSourcesDoNotLoop() {
        VodSourceFailover q = queue();
        assertNotNull(q.failover());
        assertNotNull(q.failover());
        assertNull(q.failover());
    }
}
