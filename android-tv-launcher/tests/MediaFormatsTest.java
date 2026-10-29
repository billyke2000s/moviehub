package local.tv.home;

public final class MediaFormatsTest {

    private static int checks;

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }

    public static void main(String[] args) {
        same(
            "Track 1 - [English] · E-AC-3",
            MediaFormats.append("Track 1 - [English]", 4, true, "eac3", null)
        );
        same(
            "Track 1 - [English] · HEVC (H.265)",
            MediaFormats.append("Track 1 - [English]", 8, false, "hevc", null)
        );
        same("Original name · AAC", MediaFormats.append("Original name", 2, true, "", "mp4a"));
        same("Off", MediaFormats.append("Disable", -1, true, "eac3", null));
        same("Original name", MediaFormats.append("Original name", 2, true, "unknown", "unknown"));
        same("Original name", MediaFormats.append("Original name", 2, true, "hevc", null));
        same("Original name", MediaFormats.append("Original name", 2, false, "eac3", null));
        same("MPEG Audio", MediaFormats.codec("mpga", true));
        same("TrueHD", MediaFormats.codec("trhd", true));
        same("AC-3", MediaFormats.codec("a52 ", true));
        same(
            "Reported: E-AC-3 · HEVC (H.265) · 10-bit · HDR10",
            SourceFormats.describe("", "Movie.2020.DDP5.1.HEVC.10bit.HDR10")
        );
        same("5.1 · AVC (H.264)", SourceFormats.reported("Film 5.1 H.264"));
        same("", SourceFormats.reported("Film HDRip 2020 1080p"));
        same("", SourceFormats.reported("upscaled264 audioUnknown"));
        same("Manifest codecs: AAC · Reported: DTS", SourceFormats.describe("Manifest codecs: AAC", "DTS"));
        System.out.println(checks + " media format display checks passed");
    }
}
