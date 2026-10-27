package local.tv.home;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Inspects init/segment headers through the same relay and total probe budget; never decodes. */
final class HlsMetadataProbe {

    static MediaFacts.Value read(String base, String text, int preferred, MediaMetadataProbe.Budget budget) {
        HlsMediaInfo root = new HlsMediaInfo(text, preferred);
        MediaFacts.Value declared = root.declared;
        HlsMediaInfo media = root;
        String location = base;
        try {
            for (int depth = 0; !media.variant.isEmpty() && depth < 3; depth++) {
                location = resource(location, media.variant);
                String next = playlist(location, budget);
                if (!next.startsWith("#EXTM3U")) return declared;
                media = new HlsMediaInfo(next, preferred);
                if (media.declared.width > 0) declared = media.declared;
            }
        } catch (Exception error) {
            Diagnostics.recovered("HLS metadata playlist", error);
            return declared;
        }
        MediaFacts.Value video = headers(location, media, budget);
        MediaFacts.Value audio = video;
        if (!root.audioPlaylist.isEmpty()) {
            audio = MediaFacts.UNKNOWN;
            try {
                String audioLocation = resource(base, root.audioPlaylist);
                HlsMediaInfo audioInfo = new HlsMediaInfo(playlist(audioLocation, budget), preferred);
                audio = headers(audioLocation, audioInfo, budget);
            } catch (Exception error) {
                Diagnostics.recovered("HLS metadata audio", error);
            }
        }
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
        ).context(dimensions, dimensions ? bandwidth : 0, "");
    }

    private static MediaFacts.Value headers(
        String base,
        HlsMediaInfo info,
        MediaMetadataProbe.Budget budget
    ) {
        // No keys, decryption, or potentially unrelated byte-range resources are inspected.
        if (info.encrypted || info.byteRanges || !info.variant.isEmpty()) return MediaFacts.UNKNOWN;
        String part = info.initialization.isEmpty() ? info.segment : info.initialization;
        if (part.isEmpty()) return MediaFacts.UNKNOWN;
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(resource(base, part), budget)) {
            return MediaMetadataProbe.extract(data);
        } catch (Exception error) {
            Diagnostics.recovered("HLS media headers", error);
            return MediaFacts.UNKNOWN;
        }
    }

    private static String playlist(String url, MediaMetadataProbe.Budget budget) throws IOException {
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url, budget)) {
            byte[] bytes = new byte[65536];
            int n = data.readAt(0, bytes, 0, bytes.length);
            return n <= 0 ? "" : new String(bytes, 0, n, StandardCharsets.UTF_8).trim();
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
