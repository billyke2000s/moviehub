package local.tv.home.shim;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Plain-JVM checks for the launcher shim's crash-loop rule. Run without Android. */
public class CrashGuardTest {

    static int passed;

    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }

    public static void main(String[] args) {
        long now = 10_000_000L;
        long minute = 60_000L;

        check(!CrashGuard.useFallback(Collections.emptyList(), now), "no crashes opens MovieHub");
        check(
            !CrashGuard.useFallback(Arrays.asList(now - 1000, now - 2000), now),
            "two crashes still open MovieHub"
        );
        check(
            CrashGuard.useFallback(Arrays.asList(now - 1000, now - 2000, now - 3000), now),
            "three crashes in a minute use the stock launcher"
        );
        check(
            !CrashGuard.useFallback(Arrays.asList(now - 4 * minute, now - 1000, now - 2000), now),
            "a crash older than the window does not count"
        );
        check(
            !CrashGuard.useFallback(Arrays.asList(now - 1000, now - 2000, now - 3000), now + 3 * minute),
            "after the window MovieHub is tried again"
        );
        check(
            !CrashGuard.useFallback(Arrays.asList(now + minute, now - 1000, now - 2000), now),
            "a crash time in the future (clock change) is ignored"
        );

        List<Long> recent = CrashGuard.recent(Arrays.asList(now - 1000, now - 5 * minute, now - 3000), now);
        check(recent.equals(Arrays.asList(now - 3000, now - 1000)), "recent keeps the window, oldest first");

        List<Long> times = Arrays.asList(1L, 22L, 333L);
        check(CrashGuard.decode(CrashGuard.encode(times)).equals(times), "encode/decode round trip");
        check(CrashGuard.decode("").isEmpty(), "empty storage decodes to no crashes");
        check(CrashGuard.decode(null).isEmpty(), "missing storage decodes to no crashes");
        check(CrashGuard.decode("5,x,7").equals(Arrays.asList(5L, 7L)), "corrupt entries are skipped");

        System.out.println(passed + " launcher shim crash-guard checks passed");
    }
}
