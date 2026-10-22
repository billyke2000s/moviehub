package local.tv.home;

/** One surface repair until observed video progress resumes; hiding the surface is not recovery. */
final class LiveRepairBudget {

    private boolean used;
    private long previous = -1;

    void observe(long frames, boolean watching) {
        if (!watching || frames < 0) {
            previous = -1;
            return;
        }
        if (previous >= 0 && frames > previous) used = false;
        previous = frames;
    }

    boolean allowed() {
        return !used;
    }

    void repaired() {
        used = true;
        // Decoder shutdown can finish old queued frames. Establish a new visible baseline first.
        previous = -1;
    }

    void reset() {
        used = false;
        previous = -1;
    }
}
