package local.tv.home;

import java.net.URI;
import java.util.Locale;

/** Bounded transport profiles, not a claim of dynamically measured network adaptation. */
final class PlaybackBuffer {

    static final int LIVE_MS = 1500;
    static final int HLS_MS = 2500;
    static final int RELAY_MS = 4000;
    static final int VOD_MS = 2500;

    private PlaybackBuffer() {}

    static int live(String url, boolean relayed) {
        if (relayed) return RELAY_MS;
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                path = path.toLowerCase(Locale.ROOT);
                if (path.endsWith(".m3u8") || path.contains("/hls/")) return HLS_MS;
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // URL validation belongs to the playback entry point, not profile selection.
        }
        return LIVE_MS;
    }
}
