package local.tv.home;

public class PlaybackEndTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        long min = 60_000;
        // A broken source that ends at once: nothing played, length unknown or known.
        check(!PlaybackEnd.finished(0, 0), "ended at once, no length");
        check(!PlaybackEnd.finished(45 * min, 0), "ended at once, length known");
        // A short clip in place of the episode, even played to its end.
        check(!PlaybackEnd.finished(5_000, 5_000), "5 s clip");
        check(!PlaybackEnd.finished(90_000, 90_000), "90 s clip");
        // Unknown length: only a real amount of playing counts.
        check(!PlaybackEnd.finished(-1, 30_000), "unknown length, 30 s");
        check(PlaybackEnd.finished(-1, 44 * min), "unknown length, 44 min");
        // A stream cut off part-way is not a finish.
        check(!PlaybackEnd.finished(45 * min, 20 * min), "cut off at 20 of 45 min");
        check(!PlaybackEnd.finished(45 * min, 40 * min), "cut off with 5 min left");
        // Real finishes: the end, or inside the credits window.
        check(PlaybackEnd.finished(45 * min, 45 * min), "played to the end");
        check(PlaybackEnd.finished(45 * min, 45 * min - 30_000), "30 s left");
        check(PlaybackEnd.finished(45 * min, 41 * min + 30_000), "3.5 min left (credits)");
        check(PlaybackEnd.finished(45 * min, 45 * min + 3_000), "a tick just past the length");
        // Resumed at 40 min on a broken 3 min copy: the position is beyond its end, not a finish.
        check(!PlaybackEnd.finished(3 * min, 40 * min), "resume point past a short copy");
        check(!PlaybackEnd.finished(30 * min, 40 * min), "resume point past a shorter copy");
        // Leaving the player: a short clip is never watched, a real episode near its end is.
        check(!PlaybackEnd.watchedWhenLeaving(5_000, 5_000), "leave 5 s clip");
        check(!PlaybackEnd.watchedWhenLeaving(0, 45 * min), "leave before playing");
        check(!PlaybackEnd.watchedWhenLeaving(20 * min, 45 * min), "leave half-way");
        check(PlaybackEnd.watchedWhenLeaving(43 * min, 45 * min), "leave in the credits");
        // Where to resume after a dead stream: the time, but never beyond what was played.
        check(PlaybackEnd.playedPosition(20 * min, 20 * min) == 20 * min, "normal reading");
        check(PlaybackEnd.playedPosition(20 * min + 900, 20 * min) == 20 * min + 900, "tick trails");
        check(PlaybackEnd.playedPosition(10 * min, 20 * min) == 10 * min, "after a jump back");
        check(PlaybackEnd.playedPosition(45 * min, 20 * min) == 20 * min + 5000, "dead stream says end");
        check(PlaybackEnd.playedPosition(-1, 20 * min) == 20 * min, "dead stream says nothing");
        check(PlaybackEnd.playedPosition(0, 0) == 0, "nothing played");
        // The completion rule ResumePoint uses is unchanged.
        check(PlaybackEnd.nearEnd(116 * min, 120 * min), "4 min left of 2 h");
        check(!PlaybackEnd.nearEnd(115 * min, 120 * min), "5 min left of 2 h");
        check(PlaybackEnd.nearEnd(27 * min, 30 * min), "3 min left of 30 min");
        check(!PlaybackEnd.nearEnd(26 * min, 30 * min), "4 min left of 30 min (over a tenth)");
        check(!PlaybackEnd.lengthMismatch(0, 45 * min), "unknown actual length");
        check(!PlaybackEnd.lengthMismatch(-1, 45 * min), "negative actual length");
        check(!PlaybackEnd.lengthMismatch(45 * min, 0), "missing catalogue runtime");
        check(!PlaybackEnd.lengthMismatch(45 * min, -1), "negative catalogue runtime");
        check(PlaybackEnd.lengthMismatch(27 * min - 1, 45 * min), "just below 60 percent");
        check(!PlaybackEnd.lengthMismatch(27 * min, 45 * min), "60 percent inclusive");
        check(!PlaybackEnd.lengthMismatch(72 * min, 45 * min), "160 percent inclusive");
        check(PlaybackEnd.lengthMismatch(72 * min + 1, 45 * min), "just above 160 percent");
        check(!PlaybackEnd.lengthMismatch(150 * min, 120 * min), "extended cut allowed");
        check(PlaybackEnd.lengthMismatch(3 * min, 45 * min), "trailer rejected");
        check(!PlaybackEnd.lengthMismatch(Long.MAX_VALUE, Long.MAX_VALUE), "overflow safe");
        check(!PlaybackEnd.prepareNext(0, 45 * min, 45 * min, false), "no preparation before play");
        check(!PlaybackEnd.prepareNext(41 * min, 45 * min, 45 * min, false), "too early");
        check(PlaybackEnd.prepareNext(42 * min, 45 * min, 45 * min, false), "three minutes left");
        check(!PlaybackEnd.prepareNext(43 * min, 45 * min, 45 * min, true), "paused");
        check(!PlaybackEnd.prepareNext(45 * min, 45 * min, 45 * min, false), "already ended");
        check(!PlaybackEnd.prepareNext(2 * min, 3 * min, 45 * min, false), "wrong runtime");
        check(!PlaybackEnd.prepareNext(10_000, 30_000, 0, false), "short clip");
        check(!PlaybackEnd.prepareNext(10_000, 0, 0, false), "unknown length");
        System.out.println("47 playback end and prefetch checks passed");
    }
}
