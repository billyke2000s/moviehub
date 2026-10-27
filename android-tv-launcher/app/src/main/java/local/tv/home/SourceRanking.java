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
        if (word(text, "2160p?|4k")) return 2160;
        if (word(text, "1440p?|2k")) return 1440;
        if (word(text, "1080[pi]?|fhd|full[ -]?hd")) return 1080;
        if (word(text, "720p?|hd")) return 720;
        if (word(text, "576p?")) return 576;
        if (word(text, "uhd")) return 2160;
        // A confirmed small picture ("360p", "240p") ranks below a plain SD claim.
        java.util.regex.Matcher low = Pattern.compile("(?<![a-z0-9])([1-4]\\d{2})p(?![a-z0-9])").matcher(
            text
        );
        if (low.find()) return Math.min(480, Integer.parseInt(low.group(1)));
        return 480;
    }

    /** An audio codec named before the channel count ("DDP5.1", "DTS-HD MA 7 1", "TrueHD 7.1"). */
    private static final String CODEC =
        "(?:ddp?|dd\\+|e?ac-?3|aac|dts(?:-?hd)?(?:[ .-]?ma)?|truehd|flac|opus|l?pcm)";

    /** Not part of a longer number, and not a file size ("5.1 GB"). */
    private static final String NOT_SIZE = "(?![0-9])(?![ .]?(?:gb|gib|mb|mib|tb)(?![a-z]))";

    private static boolean channels(String text, String layout, String count) {
        String first = layout.substring(0, 1);
        return (
            // "5.1" on its own, or after a codec with a space or dot instead ("DDP5 1").
            Pattern.compile("(?<![a-z0-9.])" + first + "\\.1" + NOT_SIZE)
                .matcher(text)
                .find() ||
            Pattern.compile("(?<![a-z0-9])" + CODEC + "[ .-]?" + first + "[ .]1" + NOT_SIZE)
                .matcher(text)
                .find() ||
            word(text, count + "ch")
        );
    }

    /**
     * The release name's claimed audio: 3 for Dolby Atmos, 2 for 7.1, 1 for 5.1, 0 when it does not
     * say. Like picture quality it is the website's claim, not read from the file. Among links of
     * equal picture quality more channels rank higher.
     */
    static int audio(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (word(text, "atmos")) return 3;
        if (channels(text, "7.1", "8")) return 2;
        if (channels(text, "5.1", "6")) return 1;
        return 0;
    }

    /** "Atmos", "7.1", "5.1", or "" when the release name does not say. */
    static String audioLabel(String description) {
        int audio = audio(description);
        return audio == 3 ? "Atmos" : audio == 2 ? "7.1" : audio == 1 ? "5.1" : "";
    }

    static boolean camera(String description) {
        return word(description.toLowerCase(Locale.ROOT), "cam|camrip|hdcam|hdts|telesync|telecine|tsrip");
    }

    static int rank(String description, int preferred) {
        int quality = resolution(description);
        int score = quality <= preferred ? quality : -quality;
        if (camera(description)) score -= 10000;
        return score;
    }

    static int readyRank(String description, int preferred, double probeMbps, long responseMs) {
        return readyRank(description, preferred, probeMbps, responseMs, false);
    }

    /**
     * {@code confirmed} means the quality was read from the file or stream itself rather than taken
     * from the website. Among equal quality it outranks any transfer sample; it never lifts a source
     * into a higher quality.
     */
    static int readyRank(
        String description,
        int preferred,
        double probeMbps,
        long responseMs,
        boolean confirmed
    ) {
        return readyRank(description, preferred, probeMbps, responseMs, confirmed, 0);
    }

    /**
     * {@code audio} is {@link #audio} of the release name. Among equal quality a confirmed picture
     * comes first, then more audio channels, then the transfer sample.
     */
    static int readyRank(
        String description,
        int preferred,
        double probeMbps,
        long responseMs,
        boolean confirmed,
        int audio
    ) {
        // Transfer samples include startup latency and are an estimate, never a stability guarantee.
        double sample = Double.isFinite(probeMbps) ? Math.max(0, Math.min(99, probeMbps)) : 0;
        int response = (int) Math.max(0, 999 - Math.max(0, Math.min(999, responseMs)));
        return (
            rank(description, preferred) * 100000 +
            (confirmed ? 400000 : 0) +
            Math.max(0, Math.min(3, audio)) * 100000 +
            (int) (sample * 1000) +
            response
        );
    }
}
