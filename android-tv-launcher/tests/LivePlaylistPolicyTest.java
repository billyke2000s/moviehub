package local.tv.home;

import java.io.IOException;

public final class LivePlaylistPolicyTest {

    public static void main(String[] args) throws Exception {
        String clip = "#EXTM3U\n#EXTINF:6,\nmessage.ts\n#EXTINF:6,\nmessage2.ts\n";
        LivePlaylistPolicy.validate(clip);
        LivePlaylistPolicy.validate("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n");
        LivePlaylistPolicy.validate("#EXTM3U\n#EXTINF:121,\nrecording.ts\n#EXT-X-ENDLIST\n");
        rejected(clip + "#EXT-X-ENDLIST\n");
        rejected("#EXTM3U\n#EXTINF:120,\nmessage.ts\n#EXT-X-ENDLIST\n");
        rejected("#EXTM3U\n#EXTINF:NaN,\nmessage.ts\n");
        rejected("#EXTM3U\n#EXTINF:-6,\nmessage.ts\n");
        System.out.println("LivePlaylistPolicyTest: 7 checks passed");
    }

    private static void rejected(String playlist) throws Exception {
        try {
            LivePlaylistPolicy.validate(playlist);
            throw new AssertionError("Invalid live playlist accepted");
        } catch (IOException expected) {}
    }
}
