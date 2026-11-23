package local.tv.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The problems the family Help page shows (Settings → System → Help). Plain Java, so the rules are
 * checked without a device.
 *
 * <p>Only problems the person at the TV can fix themselves are listed, each with one fix. Problems
 * only the owner can fix (servers, providers, accounts) are never shown here: they are in Settings
 * → Setup → Engineer view. A vague "something is wrong" is never shown either.
 */
final class HelpChecks {

    /** What a problem's one button does. */
    enum Fix {
        WIFI_SETTINGS,
        LOWER_QUALITY,
        STORAGE_SETTINGS,
        UPDATE,
    }

    static final class Problem {

        final String title, detail, action;
        final Fix fix;

        Problem(String title, String detail, String action, Fix fix) {
            this.title = title;
            this.detail = detail;
            this.action = action;
            this.fix = fix;
        }
    }

    /** What the box knows about itself; filled in by the Help page. */
    static final class Facts {

        /** The box has a network connection (Wi-Fi or cable). */
        boolean connected = true;
        /**
         * Download speed in Mb/s from a real speed test; below 0 when unknown. Never the player's
         * download rate: that falls to almost nothing once a film has buffered, whatever the line.
         */
        float mbps = -1;
        /** Free and total space where MovieHub keeps its data; 0 total when unknown. */
        long freeBytes, totalBytes;
        /** The quality automatic play prefers: 720, 1080 or 2160. */
        int preferredQuality = 1080;
        /** A newer MovieHub version is ready to install. */
        boolean updateReady;
    }

    /** Below this a 1080p picture usually keeps stopping to load. */
    static final float HD_MBPS = 8f;
    /** Below this a 4K picture usually keeps stopping to load. */
    static final float UHD_MBPS = 25f;
    /** Less free space than this, or less than 3% of the total, is "full". */
    static final long LOW_SPACE_BYTES = 500L * 1024 * 1024;

    private HelpChecks() {}

    static List<Problem> problems(Facts facts) {
        List<Problem> out = new ArrayList<>();
        if (!facts.connected) {
            out.add(
                new Problem(
                    "This box isn't connected to the internet",
                    "Wi-Fi is off or not connected, and no cable is plugged in.",
                    "Open Wi-Fi settings",
                    Fix.WIFI_SETTINGS
                )
            );
        } else if (facts.mbps >= 0) {
            boolean uhd = facts.preferredQuality >= 2160;
            float needed = uhd ? UHD_MBPS : HD_MBPS;
            if (facts.preferredQuality > 720 && facts.mbps < needed) out.add(
                new Problem(
                    "Your internet is too slow for " +
                        (uhd ? "4K" : "HD") +
                        " (" +
                        String.format(Locale.ROOT, "%.0f", Math.floor(facts.mbps)) +
                        " Mb/s)",
                    "Films may keep stopping to load. A lower quality plays smoothly at this speed.",
                    uhd && facts.mbps >= HD_MBPS ? "Use 1080p instead" : "Use 720p instead",
                    Fix.LOWER_QUALITY
                )
            );
        }
        if (
            facts.totalBytes > 0 &&
            (facts.freeBytes < LOW_SPACE_BYTES || facts.freeBytes * 100 < facts.totalBytes * 3)
        ) out.add(
            new Problem(
                "Storage is full",
                "Only " +
                    megabytes(facts.freeBytes) +
                    " left. Apps and films can stop working until space is freed.",
                "Clear space",
                Fix.STORAGE_SETTINGS
            )
        );
        if (facts.updateReady) out.add(
            new Problem(
                "An update is ready",
                "A newer version of MovieHub can be installed now.",
                "Update",
                Fix.UPDATE
            )
        );
        return out;
    }

    /** The quality the "lower quality" fix switches to, for this speed. */
    static int lowerQuality(Facts facts) {
        return facts.preferredQuality >= 2160 && facts.mbps >= HD_MBPS ? 1080 : 720;
    }

    static String megabytes(long bytes) {
        long mb = Math.max(0, bytes) / (1024 * 1024);
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024f) : mb + " MB";
    }
}
