package local.tv.home;

import java.util.Locale;
import java.util.regex.Pattern;

/** Picture quality first, then bounded startup transfer evidence among equal-quality sources. */
final class SourceRanking {

    private static boolean word(String text, String pattern) {
        return Pattern.compile("(?<![a-z0-9])(?:" + pattern + ")(?![a-z0-9])")
            .matcher(text)
            .find();
    }

    static int resolution(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (word(text, "2160p?|4k|uhd")) return 2160;
        if (word(text, "1440p?|2k")) return 1440;
        if (word(text, "1080[pi]?|fhd|full[ -]?hd")) return 1080;
        if (word(text, "720p?|hd")) return 720;
        if (word(text, "576p?")) return 576;
        return 480;
    }

    static int rank(String description, int preferred) {
        int quality = resolution(description);
        int score = quality <= preferred ? quality : -quality;
        if (
            word(description.toLowerCase(Locale.ROOT), "cam|camrip|hdcam|hdts|telesync|telecine|tsrip")
        ) score -= 10000;
        return score;
    }

    static int readyRank(String description, int preferred, double probeMbps, long responseMs) {
        // Transfer samples include startup latency and are an estimate, never a stability guarantee.
        double sample = Double.isFinite(probeMbps) ? Math.max(0, Math.min(99, probeMbps)) : 0;
        int response = (int) Math.max(0, 999 - Math.max(0, Math.min(999, responseMs)));
        return rank(description, preferred) * 100000 + (int) (sample * 1000) + response;
    }
}
