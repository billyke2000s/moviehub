package local.tv.home;

/** Live TV track names in words, keeping language, label, channels and format. */
public final class TrackNamesTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("Track name check " + checks + ": " + what);
    }

    private static void same(String expected, String actual) {
        check(expected.equals(actual), "expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    public static void main(String[] args) {
        same("English", TrackNames.language("en"));
        same("English", TrackNames.language("eng"));
        same("English", TrackNames.language("en-GB"));
        same("English", TrackNames.language("en_GB"));
        same("French", TrackNames.language("fra"));
        same("German", TrackNames.language("de"));
        same("French", TrackNames.language("fre"));
        same("German", TrackNames.language("ger"));
        same("Dutch", TrackNames.language("dut"));
        same("Welsh", TrackNames.language("cym"));
        same("Polish", TrackNames.language("pol"));
        check(TrackNames.language(null) == null, "null");
        check(TrackNames.language(" ") == null, "blank");
        check(TrackNames.language("und") == null, "undetermined");
        same("qaa", TrackNames.language("qaa"));

        same("", TrackNames.channels(0));
        same("Mono", TrackNames.channels(1));
        same("Stereo", TrackNames.channels(2));
        same("5.1 surround", TrackNames.channels(6));
        same("7.1 surround", TrackNames.channels(8));
        same("3 channels", TrackNames.channels(3));

        same("AAC", TrackNames.audioFormat("audio/mp4a-latm"));
        same("Dolby Digital", TrackNames.audioFormat("audio/ac3"));
        same("Dolby Digital Plus", TrackNames.audioFormat("audio/eac3"));
        same("MP3", TrackNames.audioFormat("audio/mpeg"));
        same("XYZ", TrackNames.audioFormat("audio/xyz"));
        same("", TrackNames.audioFormat(null));

        same("English · Stereo · AAC", TrackNames.audio(1, "eng", null, 2, "audio/mp4a-latm"));
        same(
            "English · Audio description · Stereo · AAC",
            TrackNames.audio(2, "en", "Audio description", 2, "audio/mp4a-latm")
        );
        same("English · 5.1 surround · Dolby Digital", TrackNames.audio(1, "en", "English", 6, "audio/ac3"));
        same("Audio 3 · Stereo", TrackNames.audio(3, null, null, 2, null));
        same("Audio 1", TrackNames.audio(1, "und", " ", 0, ""));

        same("English", TrackNames.subtitle(1, "eng", null));
        same("English · SDH", TrackNames.subtitle(1, "eng", "SDH"));
        same("Subtitles 2", TrackNames.subtitle(2, null, null));

        System.out.println(checks + " track name checks passed");
    }
}
