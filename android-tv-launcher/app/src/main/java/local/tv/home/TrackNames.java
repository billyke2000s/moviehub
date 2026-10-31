package local.tv.home;

import java.util.Locale;

/**
 * Plain names for Live TV audio and subtitle tracks: "English · Stereo · AAC" instead of
 * "eng · mp4a-latm · 2 ch". The same facts are kept (language, label, channels, format), only
 * written in words. Plain Java, so the core tests can check it.
 */
final class TrackNames {

    private TrackNames() {}

    /**
     * Three-letter codes (ISO 639-2, both the terminology and the older bibliographic forms that
     * broadcasts often use, such as "fre" and "ger") to the two-letter codes Java names well.
     */
    private static final java.util.Map<String, String> THREE_TO_TWO = threeToTwo();

    private static java.util.Map<String, String> threeToTwo() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (String two : Locale.getISOLanguages()) {
            try {
                map.put(Locale.forLanguageTag(two).getISO3Language(), two);
            } catch (java.util.MissingResourceException ignored) {
                /* No three-letter form. */
            }
        }
        String[] bibliographic = {
            "alb",
            "sq",
            "arm",
            "hy",
            "baq",
            "eu",
            "bur",
            "my",
            "chi",
            "zh",
            "cze",
            "cs",
            "dut",
            "nl",
            "fre",
            "fr",
            "geo",
            "ka",
            "ger",
            "de",
            "gre",
            "el",
            "ice",
            "is",
            "mac",
            "mk",
            "mao",
            "mi",
            "may",
            "ms",
            "per",
            "fa",
            "rum",
            "ro",
            "slo",
            "sk",
            "tib",
            "bo",
            "wel",
            "cy",
        };
        for (int i = 0; i < bibliographic.length; i += 2) map.put(bibliographic[i], bibliographic[i + 1]);
        return map;
    }

    /** "English" for "en", "eng" or "en-GB"; null for missing or undetermined codes. */
    static String language(String code) {
        if (code == null) return null;
        String tag = code.trim().replace('_', '-');
        if (tag.isEmpty() || tag.equalsIgnoreCase("und") || tag.equalsIgnoreCase("mis")) return null;
        String base = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
        String two = THREE_TO_TWO.get(base.toLowerCase(Locale.ROOT));
        Locale locale = Locale.forLanguageTag(two != null ? two : tag);
        String name = locale.getDisplayLanguage(Locale.UK);
        if (name.isEmpty() || name.equalsIgnoreCase(tag) || name.equalsIgnoreCase(locale.getLanguage())) {
            return tag;
        }
        return name;
    }

    /** "Mono", "Stereo", "5.1 surround", "7.1 surround", or "N channels"; "" when unknown. */
    static String channels(int count) {
        if (count <= 0) return "";
        if (count == 1) return "Mono";
        if (count == 2) return "Stereo";
        if (count == 6) return "5.1 surround";
        if (count == 8) return "7.1 surround";
        return count + " channels";
    }

    /** A friendly audio format name from a MIME type; the type's own name when not known. */
    static String audioFormat(String mime) {
        if (mime == null || mime.isEmpty()) return "";
        String type = mime.toLowerCase(Locale.ROOT);
        switch (type) {
            case "audio/mp4a-latm":
            case "audio/aac":
                return "AAC";
            case "audio/ac3":
                return "Dolby Digital";
            case "audio/eac3":
                return "Dolby Digital Plus";
            case "audio/eac3-joc":
                return "Dolby Atmos";
            case "audio/ac4":
                return "Dolby AC-4";
            case "audio/true-hd":
                return "Dolby TrueHD";
            case "audio/vnd.dts":
                return "DTS";
            case "audio/vnd.dts.hd":
                return "DTS-HD";
            case "audio/mpeg":
                return "MP3";
            case "audio/mpeg-l1":
            case "audio/mpeg-l2":
                return "MPEG audio";
            case "audio/opus":
                return "Opus";
            case "audio/vorbis":
                return "Vorbis";
            case "audio/flac":
                return "FLAC";
            default:
                return type.startsWith("audio/") ? type.substring(6).toUpperCase(Locale.ROOT) : mime;
        }
    }

    /** An audio track: language (or "Audio N"), the stream's label, channels and format. */
    static String audio(int number, String language, String label, int channels, String mime) {
        String name = language(language);
        StringBuilder out = new StringBuilder(name == null ? "Audio " + number : name);
        append(out, label, name);
        append(out, channels(channels), null);
        append(out, audioFormat(mime), null);
        return out.toString();
    }

    /** A subtitle track: language (or "Subtitles N") and the stream's label. */
    static String subtitle(int number, String language, String label) {
        String name = language(language);
        StringBuilder out = new StringBuilder(name == null ? "Subtitles " + number : name);
        append(out, label, name);
        return out.toString();
    }

    private static void append(StringBuilder out, String part, String same) {
        if (part == null) return;
        String value = part.trim();
        if (value.isEmpty() || value.equalsIgnoreCase(same)) return;
        out.append(" · ").append(value);
    }
}
