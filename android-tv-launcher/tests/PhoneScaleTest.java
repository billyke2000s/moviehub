package local.tv.home;

/** The phone layout's scale: the short side becomes the TV pages' 540-unit height. */
public final class PhoneScaleTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("Phone scale check " + checks + ": " + what);
    }

    /** Units on the short side at that density. */
    private static float units(int shortSidePx) {
        return (shortSidePx * 160f) / PhoneScale.densityDpi(shortSidePx);
    }

    public static void main(String[] args) {
        check(PhoneScale.densityDpi(1080) == 320, "a 1080p TV box keeps its usual 320 dpi");
        check(PhoneScale.densityDpi(1440) == 427, "a 1440p phone");
        check(PhoneScale.densityDpi(720) == 213, "a 720p phone");
        check(PhoneScale.densityDpi(1600) == 474, "a tablet gets larger pages");
        for (int side : new int[] { 720, 1080, 1170, 1440, 1600 })
            check(Math.abs(units(side) - 540) < 1, side + " px short side holds 540 units");
        check(PhoneScale.densityDpi(300) == PhoneScale.MIN_DPI, "never below the readable minimum");
        check(PhoneScale.densityDpi(0) == 0, "unknown size leaves the density alone");
        check(PhoneScale.densityDpi(-5) == 0, "negative size leaves the density alone");
        System.out.println(checks + " phone scale checks passed");
    }
}
