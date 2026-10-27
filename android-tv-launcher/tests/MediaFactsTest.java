package local.tv.home;

public class MediaFactsTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        MediaFacts.Value file = new MediaFacts.Value(
            MediaFacts.Origin.FILE,
            1920,
            800,
            "video/avc",
            "audio/eac3",
            6,
            "eng",
            8_200_000,
            384_000,
            7_200_000
        );
        check(file.tier() == 1080, "wide film resolution");
        check(file.picture().contains("1920×800"), "exact dimensions");
        check(file.audio().equals("EAC3 5.1 · eng"), "real audio metadata");
        check(file.audioRank() == 1, "5.1 rank");
        check(
            file.listLabel("fixture.example").startsWith("1080p ✓ · 5.1 · fixture.example\n"),
            "quality channels website first"
        );
        check(file.listLabel("fixture.example").contains("1920×800"), "list dimensions");
        check(file.listLabel("fixture.example").contains("EAC3 · eng"), "list audio format and language");
        check(
            file.listLabel("fixture.example").contains("8.2 Mb/s video + 384 kb/s audio"),
            "list declared rates"
        );
        check(
            MediaFacts.UNKNOWN.listLabel("fixture.example").startsWith(
                "Resolution unknown · Audio unknown · fixture.example"
            ),
            "no invented list facts"
        );
        check(file.bitrate().equals("8.2 Mb/s video + 384 kb/s audio"), "separate declared track rates");
        check(
            new MediaFacts.Value(MediaFacts.Origin.FILE, 1920, 1080, "", "aac", 2, "", 0, 128000, 0)
                .bitrate()
                .equals("Video bitrate unknown · 128 kb/s audio"),
            "known audio rate survives missing video rate"
        );
        check(!file.describe().contains("Atmos"), "channels do not prove Atmos");
        check(
            MediaFacts.UNKNOWN.describe().equals("Resolution unknown · Audio unknown · Bitrate unknown"),
            "unknown stays unknown"
        );
        MediaFacts.Value playlist = new MediaFacts.Value(
            MediaFacts.Origin.PLAYLIST,
            3840,
            2160,
            "",
            "",
            0,
            "",
            12_000_000,
            0,
            0
        );
        check(playlist.picture().contains("Up to 4K"), "adaptive ceiling");
        check(playlist.picture().contains("playlist"), "declared not played");
        check(playlist.bitrate().equals("12.0 Mb/s (playlist)"), "manifest aggregate rate");
        check(playlist.audio().equals("Audio unknown"), "no invented playlist audio");
        check(
            playlist.listLabel("fixture.example").startsWith("Up to 4K (playlist)"),
            "playlist ceiling on list"
        );
        check(!playlist.listLabel("fixture.example").contains("✓"), "playlist not marked file confirmed");
        check(MediaFacts.audioName("audio/mp4a-latm").equals("AAC"), "AAC MIME");
        check(MediaFacts.audioName("audio/ac3").equals("AC3"), "AC3 MIME");
        check(MediaFacts.audioName("opus").equals("Opus"), "Opus");
        MediaFacts.Value bad = new MediaFacts.Value(
            MediaFacts.Origin.FILE,
            -1,
            -2,
            "",
            "",
            -8,
            "und",
            -10,
            -5,
            -1
        );
        check(bad.describe().equals(MediaFacts.UNKNOWN.describe()), "invalid metadata ignored");
        check(bad.durationMs == 0, "negative duration ignored");
        check(
            new MediaFacts.Value(MediaFacts.Origin.FILE, 1920, 1080, "", "aac", 2, "", 0, 0, 0).audioRank() ==
                0,
            "stereo not surround"
        );
        System.out.println(checks + " media facts checks passed");
    }
}
