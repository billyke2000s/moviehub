package local.tv.home;

import java.util.*;
import java.util.regex.*;

/** Playlist declarations and the specific resources whose media headers can be inspected. */
final class HlsMediaInfo {

    final MediaFacts.Value declared;
    final String variant, audioPlaylist, initialization, segment;
    final boolean encrypted, byteRanges;

    HlsMediaInfo(String text, int preferred) {
        Map<String, String> chosen = Collections.emptyMap();
        String selected = "";
        int best = Integer.MIN_VALUE;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            Map<String, String> a = attributes(lines[i]);
            String[] size = a.getOrDefault("RESOLUTION", "").split("x");
            int tier = size.length == 2 ? VideoDetails.tier(number(size[0]), number(size[1])) : 0;
            int rank = tier <= preferred ? tier : -tier;
            if (rank <= best || i + 1 >= lines.length || lines[i + 1].startsWith("#")) continue;
            best = rank;
            chosen = a;
            selected = lines[i + 1].trim();
        }
        variant = selected;
        String group = chosen.getOrDefault("AUDIO", "");
        Map<String, String> rendition = Collections.emptyMap();
        String init = "",
            first = "";
        boolean protectedMedia = false,
            ranges = false;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attributes(line);
                if (
                    a.getOrDefault("TYPE", "").equals("AUDIO") &&
                    !group.isEmpty() &&
                    group.equals(a.get("GROUP-ID")) &&
                    (rendition.isEmpty() || a.getOrDefault("DEFAULT", "").equals("YES"))
                ) rendition = a;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attributes(line);
                init = a.getOrDefault("URI", "");
                ranges |= a.containsKey("BYTERANGE");
            } else if (line.startsWith("#EXT-X-KEY:")) {
                protectedMedia |= !attributes(line).getOrDefault("METHOD", "NONE").equals("NONE");
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) ranges = true;
            else if (!line.startsWith("#") && !line.trim().isEmpty() && first.isEmpty()) first = line.trim();
        }
        initialization = init;
        segment = variant.isEmpty() ? first : "";
        encrypted = protectedMedia;
        byteRanges = ranges;
        audioPlaylist = rendition.getOrDefault("URI", "");
        String[] size = chosen.getOrDefault("RESOLUTION", "").split("x");
        String video = "",
            audio = "";
        boolean ambiguousAudio = false;
        for (String raw : chosen.getOrDefault("CODECS", "").split(",")) {
            String c = raw.trim().toLowerCase(Locale.ROOT);
            String name = audioCodec(c);
            if (!name.isEmpty()) {
                if (!audio.isEmpty() && !audio.equals(name)) ambiguousAudio = true;
                audio = name;
            } else if (!VideoDetails.codecName(c).isEmpty()) video = c;
        }
        declared = new MediaFacts.Value(
            MediaFacts.Origin.PLAYLIST,
            size.length == 2 ? number(size[0]) : 0,
            size.length == 2 ? number(size[1]) : 0,
            video,
            ambiguousAudio ? "" : audio,
            number(rendition.getOrDefault("CHANNELS", "").split("/", 2)[0]),
            rendition.getOrDefault("LANGUAGE", ""),
            number(chosen.getOrDefault("AVERAGE-BANDWIDTH", chosen.getOrDefault("BANDWIDTH", ""))),
            0,
            0
        );
    }

    static Map<String, String> attributes(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        int colon = line.indexOf(':');
        Matcher m = Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").matcher(
            line.substring(colon + 1)
        );
        while (m.find()) result.put(m.group(1), m.group(2) == null ? m.group(3).trim() : m.group(2));
        return result;
    }

    static int number(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String audioCodec(String c) {
        if (c.startsWith("mp4a.40.") || c.equals("aac")) return "AAC";
        if (c.equals("ac-3")) return "AC3";
        if (c.equals("ec-3")) return "EAC3";
        if (c.equals("opus")) return "Opus";
        if (c.equals("flac")) return "FLAC";
        return "";
    }
}
