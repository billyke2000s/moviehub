package local.tv.home;

public final class PlaybackBufferTest {

    public static void main(String[] args) {
        check(1500, PlaybackBuffer.live("https://example.test/live.ts?token=.m3u8", false));
        check(2500, PlaybackBuffer.live("https://example.test/LIVE.M3U8?token=x", false));
        check(2500, PlaybackBuffer.live("https://example.test/hls/channel/index", false));
        check(1500, PlaybackBuffer.live("https://example.test/live.ts#hls/.m3u8", false));
        check(4000, PlaybackBuffer.live("http://127.0.0.1/live.m3u8", true));
        check(1500, PlaybackBuffer.live(null, false));
        check(1500, PlaybackBuffer.live("not a URI", false));
        if (
            PlaybackBuffer.VOD_MS <= PlaybackBuffer.LIVE_MS || PlaybackBuffer.VOD_MS > PlaybackBuffer.RELAY_MS
        ) throw new AssertionError("VOD headroom must stay bounded");
        System.out.println("8 transport buffering checks passed");
    }

    private static void check(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}
