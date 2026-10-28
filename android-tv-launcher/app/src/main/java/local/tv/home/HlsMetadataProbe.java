package local.tv.home;

import android.media.MediaDataSource;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/** Inspects init/segment headers through the same relay and total probe budget; never decodes. */
final class HlsMetadataProbe {

    // At most two extra I/O tasks for the existing two source probes. No queue, decoder
    // or per-source thread; idle workers disappear and all reads share the caller's budget.
    private static final ThreadPoolExecutor AUDIO = new ThreadPoolExecutor(
        0,
        2,
        10,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        task -> {
            Thread thread = new Thread(task, "hls-audio-headers");
            thread.setDaemon(true);
            return thread;
        }
    );

    static MediaFacts.Value read(
        String base,
        String text,
        int preferred,
        String audioLanguages,
        MediaMetadataProbe.Budget budget
    ) {
        HlsMediaInfo root = new HlsMediaInfo(text, preferred, audioLanguages);
        MediaFacts.Value declared = root.declared;
        HlsMediaInfo media = root;
        String location = base,
            audioBase = base,
            audioPlaylist = root.audioPlaylist;
        Future<MediaFacts.Value> pendingAudio = audio(
            audioBase,
            audioPlaylist,
            preferred,
            audioLanguages,
            budget
        );
        try {
            for (int depth = 0; !media.variant.isEmpty() && depth < 3; depth++) {
                location = resource(location, media.variant);
                String next = playlist(location, budget);
                trace("video playlist", budget);
                if (!next.startsWith("#EXTM3U")) return declared;
                media = new HlsMediaInfo(next, preferred, audioLanguages);
                if (media.declared.width > 0) declared = media.declared;
                if (!media.audioPlaylist.isEmpty()) {
                    if (pendingAudio != null) pendingAudio.cancel(true);
                    audioBase = location;
                    audioPlaylist = media.audioPlaylist;
                    pendingAudio = audio(audioBase, audioPlaylist, preferred, audioLanguages, budget);
                }
            }
            MediaFacts.Value video = headers(location, media, budget);
            MediaFacts.Value audio = audioPlaylist.isEmpty() ? video : MediaFacts.UNKNOWN;
            if (pendingAudio != null) {
                try {
                    audio = pendingAudio.isDone()
                        ? pendingAudio.get()
                        : pendingAudio.get(budget.timeout(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Exception error) {
                    Diagnostics.recovered("HLS metadata audio", error);
                }
            }
            return combine(declared, video, audio);
        } catch (Exception error) {
            Diagnostics.recovered("HLS metadata playlist", error);
            return declared;
        } finally {
            if (pendingAudio != null) pendingAudio.cancel(true);
        }
    }

    private static Future<MediaFacts.Value> audio(
        String base,
        String path,
        int preferred,
        String languages,
        MediaMetadataProbe.Budget budget
    ) {
        if (path.isEmpty()) return null;
        try {
            return AUDIO.submit(() -> {
                try {
                    String location = resource(base, path);
                    HlsMediaInfo info = new HlsMediaInfo(playlist(location, budget), preferred, languages);
                    trace("audio playlist", budget);
                    return headers(location, info, budget);
                } catch (Exception error) {
                    Diagnostics.recovered("HLS metadata audio", error);
                    return MediaFacts.UNKNOWN;
                }
            });
        } catch (RejectedExecutionException busy) {
            return null;
        }
    }

    private static MediaFacts.Value combine(
        MediaFacts.Value declared,
        MediaFacts.Value video,
        MediaFacts.Value audio
    ) {
        boolean dimensions = video.width > 0 && video.height > 0;
        boolean audioHeader = !audio.audioCodec.isEmpty();
        int bandwidth = declared.videoBitrate;
        return new MediaFacts.Value(
            dimensions ? MediaFacts.Origin.STREAM : MediaFacts.Origin.PLAYLIST,
            dimensions ? video.width : declared.width,
            dimensions ? video.height : declared.height,
            video.videoCodec.isEmpty() ? declared.videoCodec : video.videoCodec,
            audioHeader ? audio.audioCodec : declared.audioCodec,
            audioHeader ? audio.channels : declared.channels,
            audio.language.isEmpty() || audio.language.equals("und") ? declared.language : audio.language,
            dimensions ? video.videoBitrate : bandwidth,
            audio.audioBitrate,
            0
        ).context(
            dimensions,
            dimensions ? bandwidth : 0,
            !audioHeader && (!declared.audioCodec.isEmpty() || declared.channels > 0) ? "playlist" : ""
        );
    }

    private static MediaFacts.Value headers(
        String base,
        HlsMediaInfo info,
        MediaMetadataProbe.Budget budget
    ) {
        if (info.byteRanges || !info.variant.isEmpty()) return MediaFacts.UNKNOWN;
        if (info.encrypted && !HlsEncryption.supported(info.key)) return MediaFacts.UNKNOWN;
        String part = info.initialization.isEmpty() ? info.segment : info.initialization;
        if (part.isEmpty()) return MediaFacts.UNKNOWN;
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(resource(base, part), budget)) {
            if (!info.encrypted) return MediaMetadataProbe.extract(data);
            // Ordinary identity AES-128 HLS uses a playlist-advertised 16-byte key. Never
            // request SAMPLE-AES/DRM keys, and never persist or log key/IV material.
            if (!info.initialization.isEmpty() && !info.key.containsKey("IV")) return MediaFacts.UNKNOWN;
            byte[] key = new byte[17];
            try (
                MediaMetadataProbe.Data keyData = new MediaMetadataProbe.Data(
                    resource(base, info.key.get("URI")),
                    budget
                )
            ) {
                if (keyData.readAt(0, key, 0, key.length) != 16) return MediaFacts.UNKNOWN;
                trace("key read", budget);
                byte[] encoded = new byte[65536];
                int n = data.readAt(0, encoded, 0, encoded.length);
                trace("prefix read bytes=" + n, budget);
                if (n < 16) return MediaFacts.UNKNOWN;
                boolean complete = data.getSize() == n;
                if (!complete) n -= n % 16;
                byte[] decoded = HlsEncryption.decrypt(
                    encoded,
                    n,
                    Arrays.copyOf(key, 16),
                    HlsEncryption.iv(info.sequence, info.key.get("IV")),
                    complete
                );
                try (Prefix plain = new Prefix(decoded, budget)) {
                    MediaFacts.Value result = MediaMetadataProbe.extract(plain);
                    trace("extracted audio=" + result.audioCodec + " channels=" + result.channels, budget);
                    return result;
                } finally {
                    Arrays.fill(decoded, (byte) 0);
                }
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (Exception error) {
            Diagnostics.recovered("HLS media headers", error);
            return MediaFacts.UNKNOWN;
        }
    }

    private static void trace(String phase, MediaMetadataProbe.Budget budget) throws IOException {
        android.util.Log.i("MovieHubHlsMeta", Thread.currentThread().getName() + " " + phase + " remaining=" + budget.timeout());
    }\n
    private static final class Prefix extends MediaDataSource {

        private final byte[] bytes;
        private final MediaMetadataProbe.Budget budget;

        Prefix(byte[] bytes, MediaMetadataProbe.Budget budget) {
            this.bytes = bytes;
            this.budget = budget;
        }

        public int readAt(long position, byte[] target, int offset, int size) throws IOException {
            budget.timeout();
            if (size == 0) return 0;
            if (position < 0) throw new IOException("Invalid metadata offset");
            if (position >= bytes.length) return -1;
            int n = Math.min(size, bytes.length - (int) position);
            System.arraycopy(bytes, (int) position, target, offset, n);
            return n;
        }

        public long getSize() {
            return bytes.length;
        }

        public void close() {}
    }

    private static String playlist(String url, MediaMetadataProbe.Budget budget) throws IOException {
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url, budget)) {
            byte[] bytes = new byte[65536];
            int n = data.readAt(0, bytes, 0, bytes.length);
            String text = n <= 0 ? "" : new String(bytes, 0, n, StandardCharsets.UTF_8).trim();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static String resource(String base, String raw) throws IOException {
        URI parent = URI.create(base),
            target = parent.resolve(raw);
        // Relay rewrites every granted playlist URI. Never follow an unrewritten remote URL here.
        if (
            !parent.getScheme().equals(target.getScheme()) ||
            !parent.getRawAuthority().equals(target.getRawAuthority()) ||
            target.getUserInfo() != null ||
            target.getFragment() != null
        ) throw new IOException("Unregistered metadata resource");
        return target.toString();
    }
}
