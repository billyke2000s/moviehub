package local.tv.home;

import java.util.Locale;

/** Display names for explicit codec identifiers; no inference from resolution or channel count. */
final class MediaFormats {

    private MediaFormats() {}

    static String codec(String value, boolean audio) {
        if (value == null) return "";
        String c = value.trim().toLowerCase(Locale.ROOT);
        if (audio) {
            if (c.equals("eac3") || c.equals("e-ac-3") || c.equals("ec-3")) return "E-AC-3";
            if (c.equals("a52") || c.equals("ac3") || c.equals("ac-3")) return "AC-3";
            if (c.equals("mp4a") || c.equals("aac")) return "AAC";
            if (c.equals("dts") || c.equals("dtsc")) return "DTS";
            if (c.equals("trhd") || c.equals("truehd")) return "TrueHD";
            if (c.equals("mlp")) return "MLP";
            if (c.equals("flac")) return "FLAC";
            if (c.equals("alac")) return "ALAC";
            if (c.equals("opus")) return "Opus";
            if (c.equals("vorb") || c.equals("vorbis")) return "Vorbis";
            if (c.equals("mp3")) return "MP3";
            if (c.equals("mpga")) return "MPEG Audio";
            return "";
        }
        if (
            c.equals("hevc") || c.equals("h265") || c.equals("hvc1") || c.equals("hev1")
        ) return "HEVC (H.265)";
        if (c.equals("h264") || c.equals("avc1") || c.equals("avc3")) return "AVC (H.264)";
        return VideoDetails.codecName(c);
    }

    static String append(String name, int id, boolean audio, String codec, String original) {
        if (id < 0) return "Off";
        String format = codec(codec, audio);
        if (format.isEmpty()) format = codec(original, audio);
        return format.isEmpty() ? name : name + " · " + format;
    }
}
