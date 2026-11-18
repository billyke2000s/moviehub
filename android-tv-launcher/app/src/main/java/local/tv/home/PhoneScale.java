package local.tv.home;

/**
 * Phone layout: the TV pages, held sideways and scaled so the phone's short side counts as
 * {@link #DESIGN_HEIGHT_DP} units, the height the TV pages are laid out for (a 1080p TV box at its
 * usual density). Everything shrinks evenly, nothing is cut off, and a wider phone simply gains
 * room at the sides. Plain Java, so the core tests can check it.
 */
final class PhoneScale {

    /** The TV pages' design height: 1080 pixels at 320 dpi. */
    static final int DESIGN_HEIGHT_DP = 540;

    /** Never smaller than this, so text stays readable on very small or low-resolution phones. */
    static final int MIN_DPI = 120;

    private PhoneScale() {}

    /**
     * The screen density that makes {@code shortSidePx} equal {@link #DESIGN_HEIGHT_DP} units, or
     * 0 to leave the density as it is (no size known).
     */
    static int densityDpi(int shortSidePx) {
        if (shortSidePx <= 0) return 0;
        return Math.max(MIN_DPI, Math.round((shortSidePx * 160f) / DESIGN_HEIGHT_DP));
    }
}
