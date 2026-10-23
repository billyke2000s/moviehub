package local.tv.home;

import android.content.Context;
import android.net.Uri;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/** Resolves and opens a live transport; selection and recovery stay with LivePlayer. */
final class LiveStream {

    static final class Resolution {

        final EngineSources.Stream provider;
        final String url;
        final ProviderRelay relay;

        Resolution(EngineSources.Stream provider, String url, ProviderRelay relay) {
            this.provider = provider;
            this.url = url;
            this.relay = relay;
        }
    }

    static Resolution resolve(Context context, String locator, NativeHttp http, LivePlayer.Resolver fixture)
        throws Exception {
        EngineSources.Stream stream = EngineSources.locator(locator)
            ? EngineSources.resolve(context.getApplicationContext(), locator, http)
            : null;
        String resolved =
            stream != null
                ? stream.url
                : fixture == null
                  ? PlutoSource.resolve(locator, http)
                  : fixture.resolve(locator, http);
        ProviderRelay nextRelay =
            stream != null &&
            (ProviderRelay.needed(stream) ||
                locator.contains("/engine/dlhd/") ||
                locator.contains("/engine/zlive/"))
                ? new ProviderRelay(stream)
                : null;
        try {
            http.check();
        } catch (Exception e) {
            if (nextRelay != null) nextRelay.close();
            throw e;
        }
        return new Resolution(stream, resolved, nextRelay);
    }

    static void play(
        LibVLC engine,
        MediaPlayer player,
        String stream,
        String userAgent,
        String referer,
        ProviderRelay relay,
        boolean softwareVideo
    ) {
        Media media = new Media(engine, Uri.parse(stream));
        media.setHWDecoderEnabled(!softwareVideo, false);
        media.addOption(":network-caching=" + PlaybackBuffer.live(stream, relay != null));
        if (userAgent != null) media.addOption(":http-user-agent=" + userAgent);
        if (referer != null && !referer.isEmpty()) media.addOption(":http-referrer=" + referer);
        player.setMedia(media);
        media.release();
        player.play();
    }
}
