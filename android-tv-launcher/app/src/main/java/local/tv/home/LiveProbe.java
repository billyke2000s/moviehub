package local.tv.home;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Checks that a live copy really delivers stream data before the player is moved to it, so a
 * working (if slow) picture is never swapped for a dead one. A found link is not proof: this asks
 * for the playlist, as the player would, then for the start of the first piece of video it names
 * (following one level of quality variants). A plain stream address must answer with data.
 * Blocking: call it off the main thread. It does not prove the picture decodes.
 */
final class LiveProbe {

    /** Each request must answer within this. */
    static final int TIMEOUT_MS = 5000;
    /** The most of a playlist read. */
    static final int PLAYLIST_BYTES = 256 * 1024;
    /** Bytes of a video segment that show it is arriving. */
    static final int SEGMENT_BYTES = 188;

    private LiveProbe() {}

    /** Whether {@code address} answers with stream data, sent with the player's headers. */
    static boolean answers(String address, String userAgent, String referer) {
        try {
            Fetched first = fetch(address, userAgent, referer, PLAYLIST_BYTES);
            if (first == null) return false;
            if (!playlist(address, first.body)) return true;
            String text = new String(first.body, StandardCharsets.UTF_8);
            String next = nextUri(text);
            if (next == null) return false;
            URL nextUrl = new URL(first.url, next);
            if (master(text)) {
                // A list of qualities: open the first, then its first segment.
                Fetched variant = fetch(nextUrl.toString(), userAgent, referer, PLAYLIST_BYTES);
                if (variant == null) return false;
                String variantText = new String(variant.body, StandardCharsets.UTF_8);
                if (!variantText.trim().startsWith("#EXTM3U")) return false;
                String segment = nextUri(variantText);
                if (segment == null) return false;
                nextUrl = new URL(variant.url, segment);
            }
            return fetch(nextUrl.toString(), userAgent, referer, SEGMENT_BYTES) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The first address an HLS playlist names: a quality variant in a master playlist, else the
     * first media segment. Null when the text is not a playlist or names nothing.
     */
    static String nextUri(String playlist) {
        if (playlist == null || !playlist.trim().startsWith("#EXTM3U")) return null;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
    }

    /** Whether an HLS playlist lists qualities rather than segments. */
    static boolean master(String playlist) {
        return playlist != null && playlist.contains("#EXT-X-STREAM-INF");
    }

    /** Whether the answer for {@code address} is an HLS playlist (by its address or its text). */
    static boolean playlist(String address, byte[] body) {
        String start = new String(body, 0, Math.min(body.length, 16), StandardCharsets.UTF_8).trim();
        return start.startsWith("#EXTM3U") || address.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static final class Fetched {

        final URL url;
        final byte[] body;

        Fetched(URL url, byte[] body) {
            this.url = url;
            this.body = body;
        }
    }

    /** Up to {@code limit} bytes from a 2xx answer, or null (no answer, an error, or no bytes). */
    private static Fetched fetch(String address, String userAgent, String referer, int limit) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            String scheme = url.getProtocol();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty(
                "User-Agent",
                userAgent
            );
            if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (InputStream in = connection.getInputStream()) {
                while (out.size() < limit) {
                    int n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
                    if (n < 0) break;
                    out.write(buffer, 0, n);
                }
            }
            if (out.size() == 0) return null;
            return new Fetched(connection.getURL(), out.toByteArray());
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
