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
    static final long TIME_LIMIT_MS = 5000;

    static MediaFacts.Value read(SourceRepository.Source source, int preferred, AtomicBoolean cancelled) {
        return read(source, preferred, "eng,en", cancelled);
    }

    static MediaFacts.Value read(
        SourceRepository.Source source,
        int preferred,
        String audioLanguages,
        AtomicBoolean cancelled
    ) {
        ProviderRelay relay = null;
        String stage = "transport";
        try {
            EngineSources.Stream transport = source.stream;
            if (transport == null) transport = new EngineSources.Stream(
                new JSONObject()
                    .put("url", source.metadataLink == null ? source.uri : source.metadataLink)
                    .put("headers", source.headers)
            );
            relay = new ProviderRelay(transport);
            Budget budget = new Budget(cancelled);
            try (Data data = new Data(relay.url, budget)) {
                stage = "header read";
                android.util.Log.i("MovieHubHlsMeta", "root start");
                byte[] prefix = new byte[65536];
                int n = data.readAt(0, prefix, 0, prefix.length);
                if (n <= 0) return MediaFacts.UNKNOWN;
                String text = new String(prefix, 0, n, StandardCharsets.UTF_8).trim();
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                android.util.Log.i("MovieHubHlsMeta", "root end remaining=" + budget.timeout());
                if (text.startsWith("#EXTM3U")) return HlsMetadataProbe.read(
                    relay.url,
                    text,
                    preferred,
                    audioLanguages,
                    budget
                );
                // DASH and unsupported/incomplete containers remain unknown until playback.
                if (text.startsWith("<")) return MediaFacts.UNKNOWN;
                stage = "container extraction";
                return extract(data);
            }
        } catch (Exception error) {
            // Keep only the phase and exception type, never URLs, headers or exception messages.
            Diagnostics.recovered("Source metadata " + stage, error);
            return MediaFacts.UNKNOWN;
        } finally {
            if (relay != null) relay.close();
        }
    }

    static MediaFacts.Value extract(MediaDataSource data) throws IOException {
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

    private static int number(MediaFormat f, String key) {
        return f.containsKey(key) ? f.getInteger(key) : 0;
    }

    static MediaFacts.Value playlist(String text, int preferred) {
        return new HlsMediaInfo(text, preferred).declared;
    }

    static final class Budget {

        final AtomicBoolean cancelled;
        final long deadline = System.nanoTime() + TIME_LIMIT_MS * 1_000_000;
        int received;
        private int reserved;

        Budget(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        synchronized Permit reserve(int requested) throws IOException {
            timeout();
            int count = Math.min(requested, BYTE_LIMIT - received - reserved);
            if (count <= 0) throw new IOException("Metadata byte limit");
            reserved += count;
            return new Permit(count);
        }

        final class Permit implements AutoCloseable {

            final int count;
            private int remaining;

            Permit(int count) {
                this.count = count;
                remaining = count;
            }

            void record(int count) {
                synchronized (Budget.this) {
                    remaining -= count;
                    reserved -= count;
                    received += count;
                }
            }

            public void close() {
                synchronized (Budget.this) {
                    reserved -= remaining;
                    remaining = 0;
                }
            }
        }

        int timeout() throws IOException {
            if (
                cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline
            ) throw new InterruptedIOException("Metadata deadline");
            return (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
        }
    }

    /** Random access lets MP4 indexes at the end be read without downloading the intervening video. */
    static final class Data extends MediaDataSource {

        private final String url;
        private final Budget budget;
        private volatile long size = -1;
        // Extractors sniff several formats and revisit the index. Retain every fetched block
        // within the existing total byte budget instead of refetching an evicted prefix.
        private final Map<Long, byte[]> chunks = new LinkedHashMap<>();
        private boolean closed;

        Data(String url, AtomicBoolean cancelled) {
            this(url, new Budget(cancelled));
        }

        Data(String url, Budget budget) {
            this.url = url;
            this.budget = budget;
        }

        private int timeout() throws IOException {
            if (closed) throw new IOException("Metadata reader closed");
            return budget.timeout();
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int count)
            throws IOException {
            timeout();
            if (count == 0) return 0;
            if (position < 0) throw new IOException("Invalid metadata offset");
            if (size >= 0 && position >= size) return -1;
            long chunkAt = (position / 65536) * 65536;
            byte[] chunk = chunks.get(chunkAt);
            if (chunk == null) {
                try (Budget.Permit permit = budget.reserve(65536)) {
                    int want = permit.count;
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(timeout());
                    c.setReadTimeout(timeout());
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("Range", "bytes=" + chunkAt + "-" + (chunkAt + want - 1));
                    c.setRequestProperty("Accept-Encoding", "identity");
                    try {
                        int status = c.getResponseCode();
                        if (status != 200 && status != 206) throw new IOException(
                            "Metadata response rejected"
                        );
                        if (status == 200 && chunkAt != 0) throw new IOException("Metadata range ignored");
                        String range = c.getHeaderField("Content-Range");
                        if (status == 206) {
                            Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(
                                range == null ? "" : range
                            );
                            if (!m.matches() || Long.parseLong(m.group(1)) != chunkAt) throw new IOException(
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
                                permit.record(n);
                            }
                        }
                        chunk = out.toByteArray();
                        chunks.put(chunkAt, chunk);
                    } finally {
                        c.disconnect();
                    }
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
            chunks.clear();
        }
    }
}
