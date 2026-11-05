package local.tv.home;

/** Duration is evidence, never proof of title identity or matching editions. */
final class VodMediaPolicy {

    static boolean rejected(long expected, long observed) {
        if (observed <= 0) return false;
        if (expected > 0) return observed < expected - Math.max(30_000, expected / 4);
        return observed < 120_000;
    }

    static boolean compatible(long left, long right) {
        return left <= 0 || right <= 0 || Math.abs(left - right) <= Math.max(30_000, left / 100);
    }
}
