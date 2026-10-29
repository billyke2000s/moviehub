package local.tv.home;

import java.util.Locale;
import java.util.regex.Pattern;

/** Display names for explicit codec identifiers; no inference from resolution or channel count. */
final class MediaFormats {

    private MediaFormats() {}

    static String codec(String value, boolean audio) {
        if (value == null) return "";
        String c = value.trim().toLowerCase(Locale.ROOT);
        var tag = Pattern.compile("\\(([a-z0-9]{3,4})\\)$").matcher(c);
        if (tag.find()) c = tag.group(1);
        if (audio) {
            if (c.contains("e-ac-3") || c.contains("e-ac3")) return "E-AC-3";
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
            c.contains("hevc") || c.contains("h265") || c.contains("hvc1") || c.contains("hev1")
        ) return "HEVC (H.265)";
        if (c.contains("h264") || c.contains("avc")) return "AVC (H.264)";
        return VideoDetails.codecName(c);
    }

    static String fourcc(int code) {
        StringBuilder value = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            int ch = (code >>> (8 * i)) & 255;
            if (ch < 32 || ch > 126) return "";
            value.append((char) ch);
        }
        return value.toString();
    }

    static String append(String name, int id, boolean audio, int fourcc, String codec, String original) {
        String format = codec(fourcc(fourcc), audio);
        if (format.isEmpty()) return append(name, id, audio, codec, original);
        return id < 0 ? "Off" : name + " · " + format;
    }

    static String append(String name, int id, boolean audio, String codec, String original) {
        if (id < 0) return "Off";
        String format = codec(codec, audio);
        if (format.isEmpty()) format = codec(original, audio);
        return format.isEmpty() ? name : name + " · " + format;
    }
}
