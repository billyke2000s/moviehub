package local.tv.home.shim;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides when MovieHub has crashed often enough that the stock launcher should be used instead.
 * Pure Java with no Android types, so it can be tested on the JVM.
 */
final class CrashGuard {

    /** This many crashes ... */
    static final int CRASH_LIMIT = 3;
    /** ... within this window means MovieHub is crash-looping. */
    static final long WINDOW_MILLIS = 3 * 60 * 1000L;

    private CrashGuard() {}

    /** Keeps only crash times inside the window ending at {@code now}, oldest first. */
    static List<Long> recent(List<Long> crashes, long now) {
        List<Long> kept = new ArrayList<>();
        for (long time : crashes) if (time <= now && now - time < WINDOW_MILLIS) kept.add(time);
        kept.sort(null);
        return kept;
    }

    /** True when MovieHub should not be opened right now. */
    static boolean useFallback(List<Long> crashes, long now) {
        return recent(crashes, now).size() >= CRASH_LIMIT;
    }

    /** Encodes crash times for storage as a comma-separated string. */
    static String encode(List<Long> crashes) {
        StringBuilder out = new StringBuilder();
        for (long time : crashes) {
            if (out.length() > 0) out.append(',');
            out.append(time);
        }
        return out.toString();
    }

    /** Decodes {@link #encode} output, ignoring anything malformed. */
    static List<Long> decode(String stored) {
        List<Long> crashes = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return crashes;
        for (String part : stored.split(",")) {
            try {
                crashes.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A corrupt entry is dropped; the guard then errs towards opening MovieHub.
            }
        }
        return crashes;
    }
}
