package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Experimental builds: the one Live TV player, on Media3 ExoPlayer. It is shared by the guide's
 * corner picture and the full screen, like {@link LivePlayer}, but moving between them only
 * points the same decoder at another surface ({@code setVideoSurfaceView}, which uses
 * {@code MediaCodec.setOutputSurface} on Android 6+), so the picture is never rebuilt.
 *
 * <p>Channel links are found by the existing {@link LiveStream#resolve} step, unchanged. Recovery
 * is bounded: falling behind a live window rejoins it; other errors reopen the channel at most
 * twice, then the owner is told the channel failed (and moves to a backup copy).
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoLive {

    interface Listener {
        void playing();

        void buffering(boolean buffering);

        void failed(String reason);
    }

    /** Reopens per channel before the owner is told it failed. */
    static final int MAX_REOPENS = 2;
    /** Buffering this long after playing reopens the channel. */
    static final long STALL_MS = 15_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private static ExoPlayer player;
    private static Context app;
    private static String url;
    private static ProviderRelay relay;
    private static Object owner;
    private static SurfaceView surface;
    private static Listener listener;
    private static int generation, reopens;
    private static boolean playedOnce, hlsRetried;
    private static String status = "Idle";
    private static final Runnable stalled = () -> reopen("The picture stopped for 15 seconds");

    private ExoLive() {}

    static boolean available() {
        return true;
    }

    /**
     * Plays {@code locator} (a stored channel address or native/provider locator). When it is
     * already playing and {@code restart} is false it carries on and this returns true.
     */
    static boolean play(Context context, String locator, boolean restart) {
        app = context.getApplicationContext();
        ensurePlayer();
        if (!restart && locator.equals(url) && active()) {
            player.play();
            return true;
        }
        if (!locator.equals(url)) {
            reopens = 0;
            hlsRetried = false;
        }
        url = locator;
        open();
        return false;
    }

    private static void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(app).build();
        player.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        );
        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        status = "Buffering";
                        if (listener != null) listener.buffering(true);
                        if (playedOnce) {
                            main.removeCallbacks(stalled);
                            main.postDelayed(stalled, STALL_MS);
                        }
                    } else if (state == Player.STATE_READY) {
                        main.removeCallbacks(stalled);
                        status = "Playing";
                        if (listener != null) listener.buffering(false);
                    } else if (state == Player.STATE_ENDED) {
                        reopen("The live stream ended");
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    playedOnce = true;
                    reopens = 0;
                    if (listener != null) listener.playing();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Diagnostics.recovered("ExoPlayer live " + error.getErrorCodeName(), error);
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        // Fell behind the live window: rejoin live without reopening the channel.
                        player.seekToDefaultPosition();
                        player.prepare();
                        return;
                    }
                    if (
                        !hlsRetried &&
                        (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                    ) {
                        // An address without an extension may still be an HLS playlist.
                        hlsRetried = true;
                        open();
                        return;
                    }
                    reopen("This source could not be played (" + error.getErrorCodeName() + ")");
                }
            }
        );
    }

    /** Reopens the channel (a fresh link lookup), or tells the owner after {@link #MAX_REOPENS}. */
    private static void reopen(String reason) {
        main.removeCallbacks(stalled);
        if (url == null) return;
        if (reopens >= MAX_REOPENS) {
            status = "Failed: " + reason;
            if (listener != null) listener.failed(reason);
            return;
        }
        reopens++;
        status = "Reopening (" + reopens + " of " + MAX_REOPENS + ")";
        main.postDelayed(ExoLive::open, 1000);
    }

    private static void open() {
        int ticket = ++generation;
        String locator = url;
        playedOnce = false;
        main.removeCallbacks(stalled);
        player.stop();
        closeRelay();
        status = "Finding the stream";
        if (!(PlutoSource.nativeUrl(locator) || EngineSources.locator(locator))) {
            start(ticket, locator, null, null, null);
            return;
        }
        Context context = app;
        resolver.execute(() -> {
            try {
                LiveStream.Resolution source = LiveStream.resolve(context, locator, new NativeHttp(), null);
                EngineSources.Stream stream = source.provider;
                main.post(() ->
                    start(
                        ticket,
                        source.relay == null ? source.url : source.relay.url,
                        stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                        stream == null ? null : stream.referer,
                        source.relay
                    )
                );
            } catch (Exception e) {
                Diagnostics.recovered("Resolving live channel for ExoPlayer", e);
                main.post(() -> {
                    if (ticket == generation) reopen("This channel's address could not be found");
                });
            }
        });
    }

    private static void start(
        int ticket,
        String stream,
        String userAgent,
        String referer,
        ProviderRelay next
    ) {
        if (ticket != generation || player == null) {
            if (next != null) next.close();
            return;
        }
        relay = next;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000);
        if (userAgent != null) http.setUserAgent(userAgent);
        if (referer != null && !referer.isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", referer);
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(stream);
        String lower = stream.toLowerCase(Locale.ROOT);
        if (hlsRetried || lower.contains(".m3u8") || lower.contains("m3u8?")) item.setMimeType(
            MimeTypes.APPLICATION_M3U8
        );
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        if (surface != null) player.setVideoSurfaceView(surface);
        player.prepare();
        player.play();
        status = "Opening";
    }

    /** Draws the picture on {@code view} for {@code who}; the previous owner's surface is released. */
    static void attach(Object who, SurfaceView view, Listener events) {
        owner = who;
        listener = events;
        if (player != null && surface != null && surface != view) player.clearVideoSurfaceView(surface);
        surface = view;
        if (player != null) player.setVideoSurfaceView(view);
    }

    /** {@code who} is leaving: stops playback only if nothing else has taken the picture over. */
    static void stopIfOwner(Object who) {
        if (owner != who) return;
        stop();
    }

    static void stop() {
        generation++;
        main.removeCallbacks(stalled);
        if (player != null) {
            if (surface != null) player.clearVideoSurfaceView(surface);
            player.stop();
            player.clearMediaItems();
        }
        closeRelay();
        url = null;
        owner = null;
        listener = null;
        surface = null;
        status = "Stopped";
    }

    private static void closeRelay() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }

    static boolean active() {
        return player != null && url != null && player.getPlaybackState() != Player.STATE_IDLE;
    }

    static boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    static void pause() {
        if (player != null) player.pause();
    }

    static void resume() {
        if (player != null) player.play();
    }

    static String currentUrl() {
        return active() ? url : null;
    }

    static String status() {
        return status;
    }

    /** Audio tracks of the channel playing, as "Language · codec · channels". */
    static List<String> audioTracks() {
        List<String> names = new ArrayList<>();
        for (Format format : audioFormats()) {
            String language = format.language == null ? "Unknown language" : format.language;
            String codec =
                format.sampleMimeType == null ? "" : " · " + format.sampleMimeType.replace("audio/", "");
            String layout = format.channelCount > 0 ? " · " + format.channelCount + " ch" : "";
            names.add(language + codec + layout);
        }
        return names;
    }

    static int selectedAudioTrack() {
        if (player == null) return -1;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++, index++) if (group.isTrackSelected(i)) return index;
        }
        return -1;
    }

    static void selectAudioTrack(int wanted) {
        if (player == null) return;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++, index++) {
                if (index != wanted) continue;
                player.setTrackSelectionParameters(
                    player
                        .getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
                        .build()
                );
                return;
            }
        }
    }

    private static List<Format> audioFormats() {
        List<Format> formats = new ArrayList<>();
        if (player == null) return formats;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) formats.add(group.getTrackFormat(i));
        }
        return formats;
    }
}
