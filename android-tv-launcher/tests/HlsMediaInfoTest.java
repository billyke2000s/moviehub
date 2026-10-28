package local.tv.home;

public class HlsMediaInfoTest {

    private static int checks;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        String master =
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"surround\",NAME=\"English, original\",DEFAULT=YES,LANGUAGE=\"en\",CHANNELS=\"6\",URI=\"sound.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"other\",CHANNELS=\"8\",URI=\"wrong.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000000,AVERAGE-BANDWIDTH=4500000,CODECS=\"avc1.640028,ec-3\",RESOLUTION=1920x1080,AUDIO=\"surround\"\nvideo.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160\n4k.m3u8\n";
        HlsMediaInfo h = new HlsMediaInfo(master, 1080);
        check(h.variant.equals("video.m3u8"), "preferred variant");
        check(h.audioPlaylist.equals("sound.m3u8"), "matching audio group");
        check(h.declared.audioCodec.equals("EAC3"), "codec after quoted comma");
        check(h.declared.channels == 6, "channel metadata");
        check(h.declared.language.equals("en"), "language metadata");
        check(h.declared.videoBitrate == 4500000, "average declaration preferred");
        check(h.declared.picture().contains("playlist"), "no false file confirmation");
        check(h.declared.audio().contains("5.1"), "audio useful before media read");
        HlsMediaInfo mux = new HlsMediaInfo(
            "#EXTM3U\n#EXT-X-STREAM-INF:CODECS=\"avc1.4d,mp4a.40.2\",RESOLUTION=640x360\nx.m3u8",
            1080
        );
        check(mux.declared.audioCodec.equals("AAC"), "muxed AAC");
        check(mux.declared.channels == 0, "AAC does not imply stereo");
        HlsMediaInfo ambiguous = new HlsMediaInfo(
            master.replace("avc1.640028,ec-3", "avc1.640028,ec-3,mp4a.40.2"),
            1080
        );
        check(ambiguous.declared.audioCodec.isEmpty(), "union of rendition codecs is not selected codec");
        HlsMediaInfo init = new HlsMediaInfo(
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\npart.m4s",
            1080
        );
        check(
            init.initialization.equals("init.mp4") && init.segment.equals("part.m4s"),
            "init and first media resource"
        );
        check(!init.encrypted && !init.byteRanges, "plain independent resources");
        check(
            new HlsMediaInfo("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts", 1080).encrypted,
            "no encrypted media sniffing"
        );
        check(
            new HlsMediaInfo("#EXTM3U\n#EXT-X-BYTERANGE:900@400\na.ts", 1080).byteRanges,
            "do not inspect unrelated byte-range prefix"
        );
        check(HlsMediaInfo.number("999999999999999999") == 0, "overflow ignored");
        check(
            new HlsMediaInfo("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=garbage\n", 1080).declared.width == 0,
            "malformed metadata unknown"
        );
        String audioGroups =
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",DEFAULT=YES,LANGUAGE=\"ita\",URI=\"it.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",DEFAULT=NO,LANGUAGE=\"eng\",URI=\"en.m3u8\"\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,AUDIO=\"g\"\nv.m3u8\n";
        check(
            new HlsMediaInfo(audioGroups, 1080).audioPlaylist.equals("en.m3u8"),
            "English preference overrides Italian default"
        );
        check(
            new HlsMediaInfo(audioGroups, 1080, "ita,it").audioPlaylist.equals("it.m3u8"),
            "user audio preference respected"
        );
        HlsMediaInfo rotation = new HlsMediaInfo(
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:257\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts\n#EXT-X-KEY:METHOD=NONE\nb.ts",
            1080
        );
        check(
            rotation.encrypted && rotation.key.get("URI").equals("key") && rotation.sequence.equals("257"),
            "key belongs to inspected first segment"
        );
        HlsMediaInfo clearInit = new HlsMediaInfo(
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.m4s",
            1080
        );
        check(!clearInit.encrypted, "clear init must not use a later segment key");
        System.out.println(checks + " HLS metadata checks passed");
    }
}
