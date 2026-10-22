package local.tv.home;

import java.util.Locale;

/** Stable, local ranking of ready-to-play sources at the chosen maximum resolution. */
final class SourceRanking {

    static int resolution(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (text.contains("2160p") || text.contains("4k")) return 2160;
        if (text.contains("1080p")) return 1080;
        if (text.contains("720p")) return 720;
        return 480;
    }

    static int rank(String description, int preferred) {
        int quality = resolution(description);
        return quality <= preferred ? quality : -quality;
    }
}
