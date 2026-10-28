package local.tv.home;

import java.util.*;
import java.util.regex.*;

/** Playlist declarations and the specific resources whose media headers can be inspected. */
final class HlsMediaInfo {

    final MediaFacts.Value declared;
    final String variant, audioPlaylist, initialization, segment;
    final boolean encrypted, byteRanges;
    final Map<String, String> key;
    final String sequence;

    HlsMediaInfo(String text, int preferred) {
        this(text, preferred, "eng,en");
    }

    HlsMediaInfo(String text, int preferred, String audioLanguages) {
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
            first = "",
            mediaSequence = "0";
        boolean ranges = false;
        Map<String, String> activeKey = Collections.emptyMap(),
            initKey = activeKey,
            firstKey = activeKey;
        int bestAudio = Integer.MIN_VALUE;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attributes(line);
                int rank =
                    languageRank(a.getOrDefault("LANGUAGE", ""), audioLanguages) * 10 +
                    (a.getOrDefault("DEFAULT", "").equals("YES")
                        ? 2
                        : a.getOrDefault("AUTOSELECT", "").equals("YES")
                          ? 1
                          : 0);
                if (
                    a.getOrDefault("TYPE", "").equals("AUDIO") &&
                    !group.isEmpty() &&
                    group.equals(a.get("GROUP-ID")) &&
                    rank > bestAudio
                ) {
                    rendition = a;
                    bestAudio = rank;
                }
            } else if (first.isEmpty() && line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attributes(line);
                init = a.getOrDefault("URI", "");
                initKey = activeKey;
                ranges |= a.containsKey("BYTERANGE");
            } else if (first.isEmpty() && line.startsWith("#EXT-X-KEY:")) activeKey = attributes(line);
            else if (first.isEmpty() && line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) mediaSequence = line
                .substring(line.indexOf(':') + 1)
                .trim();
            else if (first.isEmpty() && line.startsWith("#EXT-X-BYTERANGE:")) ranges = true;
            else if (!line.startsWith("#") && !line.trim().isEmpty() && first.isEmpty()) {
                first = line.trim();
                firstKey = activeKey;
            }
        }
        initialization = init;
        segment = variant.isEmpty() ? first : "";
        key = initialization.isEmpty() ? firstKey : initKey;
        sequence = mediaSequence;
        encrypted = !key.getOrDefault("METHOD", "NONE").equals("NONE");
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

    private static int languageRank(String actual, String preferred) {
        String[] languages = preferred.split(",");
        for (int i = 0; i < languages.length; i++) if (
            canonicalLanguage(actual).equals(canonicalLanguage(languages[i])) && !actual.isEmpty()
        ) return Math.max(1, 100 - i);
        return 0;
    }

    private static String canonicalLanguage(String raw) {
        String language = raw.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        try {
            return new Locale(language).getISO3Language();
        } catch (MissingResourceException ignored) {
            return language;
        }
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
