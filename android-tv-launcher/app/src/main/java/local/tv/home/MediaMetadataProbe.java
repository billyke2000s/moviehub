package local.tv.home;

import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import org.json.JSONObject;

/** Two-second, 256 KiB metadata-only reads through the existing guarded relay. No decoding. */
final class MediaMetadataProbe {

    static final int BYTE_LIMIT = 256 * 1024;
    static final long TIME_LIMIT_MS = 2000;

    static MediaFacts.Value read(SourceRepository.Source source, int preferred, AtomicBoolean cancelled) {
        ProviderRelay relay = null;
        try {
            EngineSources.Stream transport = source.stream;
            if (transport == null) transport = new EngineSources.Stream(
                new JSONObject()
                    .put("url", source.metadataLink == null ? source.uri : source.metadataLink)
                    .put("headers", source.headers)
            );
            relay = new ProviderRelay(transport);
            try (Data data = new Data(relay.url, cancelled)) {
                byte[] prefix = new byte[65536];
                int n = data.readAt(0, prefix, 0, prefix.length);
                if (n <= 0) return MediaFacts.UNKNOWN;
                String text = new String(prefix, 0, n, StandardCharsets.UTF_8).trim();
                if (text.startsWith("#EXTM3U")) return playlist(text, preferred);
                // DASH and unsupported/incomplete containers remain unknown until playback.
                if (text.startsWith("<")) return MediaFacts.UNKNOWN;
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(data);
                    int width = 0,
                        height = 0,
                        channels = 0,
                        videoRate = 0,
                        audioRate = 0;
                    long duration = 0;
                    String video = "",
                        audio = "",
                        language = "";
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime == null) continue;
                        if (mime.startsWith("video/") && width == 0) {
                            width = number(f, MediaFormat.KEY_WIDTH);
                            height = number(f, MediaFormat.KEY_HEIGHT);
                            videoRate = number(f, MediaFormat.KEY_BIT_RATE);
                            video = mime;
                            duration = f.containsKey(MediaFormat.KEY_DURATION)
                                ? f.getLong(MediaFormat.KEY_DURATION) / 1000
                                : 0;
                        } else if (mime.startsWith("audio/") && audio.isEmpty()) {
                            audio = mime;
                            channels = number(f, MediaFormat.KEY_CHANNEL_COUNT);
                            audioRate = number(f, MediaFormat.KEY_BIT_RATE);
                            language = f.containsKey(MediaFormat.KEY_LANGUAGE)
                                ? f.getString(MediaFormat.KEY_LANGUAGE)
                                : "";
                        }
                    }
                    return new MediaFacts.Value(
                        MediaFacts.Origin.FILE,
                        width,
                        height,
                        video,
                        audio,
                        channels,
                        language,
                        videoRate,
                        audioRate,
                        duration
                    );
                } finally {
                    extractor.release();
                }
            }
        } catch (Exception error) {
            // Metadata absence is not a source failure, and exception messages may contain URLs.
            return MediaFacts.UNKNOWN;
        } finally {
            if (relay != null) relay.close();
        }
    }

    private static int number(MediaFormat f, String key) {
        return f.containsKey(key) ? f.getInteger(key) : 0;
    }

    static MediaFacts.Value playlist(String text, int preferred) {
        int w = 0,
            h = 0,
            rate = 0,
            best = Integer.MIN_VALUE;
        for (String line : text.split("\\r?\\n")) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            Matcher size = Pattern.compile("(?:^|,)RESOLUTION=(\\d+)x(\\d+)").matcher(line.substring(18));
            if (!size.find()) continue;
            try {
                int width = Integer.parseInt(size.group(1)),
                    height = Integer.parseInt(size.group(2));
                int tier = VideoDetails.tier(width, height),
                    rank = tier <= preferred ? tier : -tier;
                if (rank <= best) continue;
                best = rank;
                w = width;
                h = height;
                rate = 0;
                Matcher bandwidth = Pattern.compile("(?:^|,)(?:AVERAGE-)?BANDWIDTH=(\\d+)").matcher(
                    line.substring(18)
                );
                if (bandwidth.find()) rate = Integer.parseInt(bandwidth.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return new MediaFacts.Value(MediaFacts.Origin.PLAYLIST, w, h, "", "", 0, "", rate, 0, 0);
    }

    /** Random access lets MP4 indexes at the end be read without downloading the intervening video. */
    static final class Data extends MediaDataSource {

        private final String url;
        private final AtomicBoolean cancelled;
        private final long deadline = System.nanoTime() + TIME_LIMIT_MS * 1_000_000;
        private long size = -1,
            chunkAt = -1;
        private byte[] chunk = new byte[0];
        private int received;
        private boolean closed;

        Data(String url, AtomicBoolean cancelled) {
            this.url = url;
            this.cancelled = cancelled;
        }

        private int timeout() throws IOException {
            if (
                closed ||
                cancelled.get() ||
                Thread.currentThread().isInterrupted() ||
                System.nanoTime() >= deadline
            ) throw new InterruptedIOException("Metadata deadline");
            return (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int count)
            throws IOException {
            timeout();
            if (count == 0) return 0;
            if (position < 0) throw new IOException("Invalid metadata offset");
            if (size >= 0 && position >= size) return -1;
            if (position < chunkAt || position >= chunkAt + chunk.length) {
                if (received >= BYTE_LIMIT) throw new IOException("Metadata byte limit");
                int want = Math.min(65536, BYTE_LIMIT - received);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(timeout());
                c.setReadTimeout(timeout());
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Range", "bytes=" + position + "-" + (position + want - 1));
                c.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int status = c.getResponseCode();
                    if (status != 200 && status != 206) throw new IOException("Metadata response rejected");
                    if (status == 200 && position != 0) throw new IOException("Metadata range ignored");
                    String range = c.getHeaderField("Content-Range");
                    if (status == 206) {
                        Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(
                            range == null ? "" : range
                        );
                        if (!m.matches() || Long.parseLong(m.group(1)) != position) throw new IOException(
                            "Metadata range mismatch"
                        );
                        size = Long.parseLong(m.group(3));
                    } else {
                        size = c.getContentLengthLong();
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream(want);
                    try (InputStream input = c.getInputStream()) {
                        byte[] bytes = new byte[8192];
                        while (out.size() < want) {
                            c.setReadTimeout(timeout());
                            int n = input.read(bytes, 0, Math.min(bytes.length, want - out.size()));
                            if (n < 0) break;
                            out.write(bytes, 0, n);
                            received += n;
                        }
                    }
                    chunkAt = position;
                    chunk = out.toByteArray();
                } finally {
                    c.disconnect();
                }
            }
            int available = (int) Math.min(count, chunk.length - (position - chunkAt));
            if (available <= 0) return -1;
            System.arraycopy(chunk, (int) (position - chunkAt), buffer, offset, available);
            return available;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public synchronized void close() {
            closed = true;
            chunk = new byte[0];
        }
    }
}
