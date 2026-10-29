package local.tv.home;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Release-name fields are provider reports, independent of decoder/manifest metadata. */
final class SourceFormats {

    private SourceFormats() {}

    static String reported(String release) {
        List<String> out = new ArrayList<>();
        add(out, release, "(?:e[ .-]?ac[ .-]?3|ddp(?:[ .]?5[ .]1)?|dd\\+)", "E-AC-3");
        if (!out.contains("E-AC-3")) add(out, release, "(?:ac[ .-]?3|dd5[ .]1)", "AC-3");
        add(out, release, "truehd", "TrueHD");
        add(out, release, "dts[ .-]?hd[ .-]?ma", "DTS-HD MA");
        if (!out.contains("DTS-HD MA")) add(out, release, "dts[ .-]?hd", "DTS-HD");
        if (!out.contains("DTS-HD MA") && !out.contains("DTS-HD")) add(out, release, "dts", "DTS");
        add(out, release, "atmos", "Atmos");
        add(out, release, "aac(?:2[ .]0)?", "AAC");
        add(out, release, "flac", "FLAC");
        add(out, release, "opus", "Opus");
        var channels = Pattern.compile(
            "(?i)(?:(?<![a-z0-9])|(?<=ddp)|(?<=aac)|(?<=dd))(7[ .]1|5[ .]1|2[ .]0)(?![a-z0-9])"
        ).matcher(release);
        if (channels.find()) out.add(channels.group(1).replace(' ', '.'));
        add(out, release, "(?:hevc|[hx][ .]?265)", "HEVC (H.265)");
        add(out, release, "(?:avc|[hx][ .]?264)", "AVC (H.264)");
        add(out, release, "av1", "AV1");
        add(out, release, "(?:10[ .-]?bit|hi10p)", "10-bit");
        add(out, release, "(?:12[ .-]?bit)", "12-bit");
        add(out, release, "(?:dolby[ .]?vision|dovi|dv)", "Dolby Vision");
        add(out, release, "hdr10\\+", "HDR10+");
        if (!out.contains("HDR10+")) add(out, release, "hdr10", "HDR10");
        if (!out.contains("HDR10+") && !out.contains("HDR10")) add(out, release, "hdr", "HDR");
        return String.join(" · ", out);
    }

    private static void add(List<String> out, String text, String expression, String label) {
        var match = Pattern.compile("(?i)(?<![a-z0-9])(?:" + expression + ")(?![a-z0-9])").matcher(text);
        if (match.find()) out.add(label == null ? match.group().replace(' ', '.') : label);
    }

    static String describe(String measured, String release) {
        String report = reported(release);
        return (
            (measured == null ? "" : measured) +
            (report.isEmpty()
                ? ""
                : (measured == null || measured.isEmpty() ? "" : " · ") + "Reported: " + report)
        );
    }
}
