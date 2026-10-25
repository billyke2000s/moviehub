package local.tv.home;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
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
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one Live TV player, on Media3 ExoPlayer, drawn by Media3's own
 * {@link PlayerView} (the standard Android TV picture: correct shape, subtitles, surface lifecycle).
 * There is one view for all of Live TV ({@link LiveStage}); it is resized, never handed over.
 *
 * <p>Channel links are found by the existing {@link LiveStream#resolve} step, unchanged. Live
 * playback uses ExoPlayer's own handling (live edge, quality switching, device workarounds).
 * Errors reopen the channel at most twice; after that the owner keeps trying (see LiveStage).
 *
 * <p>An event log records what the player did (decoder, video formats, quality switches, dropped
 * frames, errors, and the picture stopping while sound plays) so a freeze can be explained. It only
 * records; it never repairs.
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoLive {

    interface Listener {
        void playing();

        void buffering(boolean buffering);

        void failed(String reason);

        /**
         * Whether another source of this channel is waiting to be tried. A failing source then
         * moves on at once instead of being reopened, as the standard player does.
         */
        default boolean hasAlternative() {
            return false;
        }
    }

    /** Reopens per channel before the owner is told it failed. */
    static final int MAX_REOPENS = 2;
    /** Events kept for the log. */
    static final int LOG_SIZE = 60;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private static ExoPlayer player;
    private static PlayerView view;
    private static Context app;
    private static String url;
    private static ProviderRelay relay;
    private static Object owner;
    private static Listener listener;
    private static int generation, reopens;
    private static Runnable pendingReopen;
    private static Future<?> resolution;
    private static NativeHttp resolvingHttp;
    private static boolean hlsRetried;
    private static String status = "Idle",
        decoder = "";
    private static final ArrayDeque<String> log = new ArrayDeque<>();
    /** Rendered-frame count at the last check, to notice the picture stopping while sound plays. */
    private static int lastRendered = -1,
        stillSeconds;
    /** Frames actually drawn per second, from the last one-second check; 0 when unknown. */
    private static float drawnFps;
    private static long lastCheckAt;
    private static final Runnable watch = new Runnable() {
        @Override
        public void run() {
            checkPicture();
            main.postDelayed(this, 1000);
        }
    };

    private ExoLive() {}

    static boolean available() {
        return true;
    }

    /** The one picture for Live TV: Media3's PlayerView, without its own on-screen controls. */
    static View createVideoView(Context context) {
        PlayerView picture = new PlayerView(context);
        picture.setUseController(false);
        picture.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        picture.setShutterBackgroundColor(Color.BLACK);
        picture.setKeepContentOnPlayerReset(true);
        picture.setFocusable(false);
        picture.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return picture;
    }

    /**
     * Plays {@code locator} (a stored channel address or native/provider locator). When it is
     * already playing and {@code restart} is false it carries on and this returns true.
     */
    /**
     * Plays {@code locator} from a link LiveRace already found, without looking it up again. If
     * it later fails, the usual path applies: another source when one waits, else a fresh lookup.
     */
    static void playResolved(Context context, String locator, LiveStream.Resolution source) {
        app = context.getApplicationContext();
        ensurePlayer();
        if (!locator.equals(url)) {
            reopens = 0;
            hlsRetried = false;
            event("New channel");
        }
        cancelReopen();
        cancelResolution();
        url = locator;
        int ticket = ++generation;
        player.stop();
        closeRelay();
        EngineSources.Stream stream = source.provider;
        event("Link found by the source race");
        start(
            ticket,
            source.relay == null ? source.url : source.relay.url,
            // As open(): provider headers, Pluto's agent for native Pluto, none for a plain address.
            stream != null
                ? stream.userAgent
                : PlutoSource.nativeUrl(locator)
                  ? PlutoSource.USER_AGENT
                  : null,
            stream == null ? null : stream.referer,
            source.relay
        );
    }

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
            event("New channel");
        }
        cancelReopen();
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
        if (view != null) view.setPlayer(player);
        player.addListener(
            new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        status = "Buffering";
                        if (listener != null) listener.buffering(true);
                    } else if (state == Player.STATE_READY) {
                        status = "Playing";
                        if (listener != null) listener.buffering(false);
                    } else if (state == Player.STATE_ENDED) {
                        event("Stream ended");
                        reopen("The live stream ended");
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    reopens = 0;
                    if (listener != null) listener.playing();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Diagnostics.recovered("ExoPlayer live " + error.getErrorCodeName(), error);
                    event("Error " + error.getErrorCodeName());
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
        player.addAnalyticsListener(
            new AnalyticsListener() {
                @Override
                public void onVideoDecoderInitialized(
                    EventTime time,
                    String name,
                    long initializedTimestampMs,
                    long initializationDurationMs
                ) {
                    decoder = name;
                    event("Video decoder " + name);
                }

                @Override
                public void onVideoInputFormatChanged(
                    EventTime time,
                    Format format,
                    DecoderReuseEvaluation reuse
                ) {
                    event(
                        "Video " +
                            format.width +
                            "×" +
                            format.height +
                            (format.frameRate > 0
                                ? String.format(Locale.ROOT, " %.2f fps", format.frameRate)
                                : "") +
                            (format.codecs == null ? "" : " " + format.codecs) +
                            (format.bitrate > 0 ? " " + format.bitrate / 1000 + " kb/s" : "") +
                            (reuse == null
                                ? ""
                                : reuse.result == DecoderReuseEvaluation.REUSE_RESULT_NO
                                  ? " (decoder restarted)"
                                  : "")
                    );
                }

                @Override
                public void onDroppedVideoFrames(EventTime time, int dropped, long elapsedMs) {
                    event("Dropped " + dropped + " frames in " + elapsedMs + " ms");
                }

                @Override
                public void onSurfaceSizeChanged(EventTime time, int width, int height) {
                    event("Picture size " + width + "×" + height);
                }

                @Override
                public void onVideoCodecError(EventTime time, Exception error) {
                    event("Video decoder error " + error.getClass().getSimpleName());
                }

                @Override
                public void onAudioUnderrun(
                    EventTime time,
                    int bufferSize,
                    long bufferSizeMs,
                    long elapsedSinceLastFeedMs
                ) {
                    event("Sound ran short");
                }
            }
        );
        main.removeCallbacks(watch);
        main.postDelayed(watch, 1000);
    }

    /** Records (only) when the picture stops advancing while the sound plays on. */
    private static void checkPicture() {
        if (player == null || !player.isPlaying()) {
            lastRendered = -1;
            stillSeconds = 0;
            drawnFps = 0;
            return;
        }
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters == null) return;
        counters.ensureUpdated();
        int rendered = counters.renderedOutputBufferCount;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastCheckAt;
        // Only a steady one-second sample counts; a restart or a late check is not measured.
        if (lastRendered >= 0 && rendered >= lastRendered && elapsed >= 500 && elapsed <= 3000) {
            drawnFps = (1000f * (rendered - lastRendered)) / elapsed;
        }
        lastCheckAt = now;
        if (rendered == lastRendered) {
            stillSeconds++;
            if (stillSeconds == 3) event("Picture stopped advancing while sound plays");
        } else {
            if (stillSeconds >= 3) event("Picture moving again after " + stillSeconds + " s");
            stillSeconds = 0;
        }
        lastRendered = rendered;
    }

    private static void event(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        log.addFirst(time + "  " + text);
        while (log.size() > LOG_SIZE) log.removeLast();
    }

    /** The event log, newest first. */
    static String eventLog() {
        if (log.isEmpty()) return "Nothing recorded yet.";
        StringBuilder text = new StringBuilder();
        for (String line : log) text.append(line).append('\n');
        return text.toString().trim();
    }

    /** Reopens the channel (a fresh link lookup), or tells the owner after {@link #MAX_REOPENS}. */
    private static void reopen(String reason) {
        if (url == null) return;
        // Another copy is waiting: go to it now. Reopening a failed source (a fresh lookup each
        // time) before trying the others kept the picture black for a long time.
        if (listener != null && listener.hasAlternative()) {
            if (AdapterSources.locator(url)) AdapterSources.failed(url);
            status = "Failed: " + reason;
            event("Moving to another source (" + reasonShort(reason) + ")");
            listener.failed(reason);
            return;
        }
        if (reopens >= MAX_REOPENS) {
            status = "Failed: " + reason;
            if (listener != null) listener.failed(reason);
            return;
        }
        reopens++;
        // Retire the selected upstream player, rather than preferring the same failed one again.
        if (AdapterSources.locator(url)) AdapterSources.failed(url);
        status = "Reopening (" + reopens + " of " + MAX_REOPENS + ")";
        event("Reopening the channel (" + reasonShort(reason) + ")");
        cancelReopen();
        int ticket = generation;
        pendingReopen = () -> {
            pendingReopen = null;
            if (ticket == generation && url != null && player != null) open();
        };
        main.postDelayed(pendingReopen, 1000);
    }

    private static String reasonShort(String reason) {
        return reason.length() > 60 ? reason.substring(0, 60) + "…" : reason;
    }

    private static void cancelReopen() {
        if (pendingReopen != null) main.removeCallbacks(pendingReopen);
        pendingReopen = null;
    }

    private static void cancelResolution() {
        if (resolvingHttp != null) resolvingHttp.cancel();
        if (resolution != null) resolution.cancel(true);
        resolvingHttp = null;
        resolution = null;
    }

    private static void open() {
        cancelReopen();
        cancelResolution();
        int ticket = ++generation;
        String locator = url;
        if (locator == null || player == null) return;
        player.stop();
        closeRelay();
        status = "Finding the stream";
        long started = SystemClock.elapsedRealtime();
        if (!(PlutoSource.nativeUrl(locator) || EngineSources.locator(locator))) {
            start(ticket, locator, null, null, null);
            return;
        }
        Context context = app;
        NativeHttp http = new NativeHttp();
        resolvingHttp = http;
        resolution = resolver.submit(() -> {
            try {
                LiveStream.Resolution source = LiveStream.resolve(context, locator, http, null);
                EngineSources.Stream stream = source.provider;
                main.post(() -> {
                    event("Link found in " + (SystemClock.elapsedRealtime() - started) / 1000 + " s");
                    start(
                        ticket,
                        source.relay == null ? source.url : source.relay.url,
                        stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                        stream == null ? null : stream.referer,
                        source.relay
                    );
                });
            } catch (Exception e) {
                Diagnostics.recovered("Resolving live channel for ExoPlayer", e);
                main.post(() -> {
                    if (ticket != generation) return;
                    event("Link not found after " + (SystemClock.elapsedRealtime() - started) / 1000 + " s");
                    resolutionFailed(locator);
                });
            }
        });
    }

    /** A complete DaddyLive walk already exhausted its candidates; do not repeat it three times. */
    static void resolutionFailed(String locator) {
        String reason = "This channel's address could not be found";
        if (locator.startsWith(AdapterSources.ROOT + "dlhd/")) {
            AdapterSources.failed(locator);
            status = "Failed: " + reason;
            if (listener != null) listener.failed(reason);
        } else reopen(reason);
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
        player.prepare();
        player.play();
        status = "Opening";
    }

    /** Draws the picture in {@code picture} (the view from {@link #createVideoView}) for {@code who}. */
    static void attach(Object who, View picture, Listener events) {
        owner = who;
        listener = events;
        if (picture instanceof PlayerView && picture != view) {
            if (view != null) view.setPlayer(null);
            view = (PlayerView) picture;
            if (app == null) app = picture.getContext().getApplicationContext();
        }
        if (app != null) ensurePlayer();
        if (view != null && view.getPlayer() != player) view.setPlayer(player);
    }

    /** {@code who} is leaving: stops playback only if nothing else has taken the picture over. */
    static void stopIfOwner(Object who) {
        if (owner != who) return;
        stop();
    }

    static void stop() {
        generation++;
        cancelReopen();
        cancelResolution();
        main.removeCallbacks(watch);
        listener = null;
        owner = null;
        if (view != null) view.setPlayer(null);
        view = null;
        if (player != null) {
            player.release();
            player = null;
        }
        lastRendered = -1;
        stillSeconds = 0;
        drawnFps = 0;
        reopens = 0;
        hlsRetried = false;
        decoder = "";
        closeRelay();
        url = null;
        owner = null;
        listener = null;
        status = "Stopped";
    }

    private static void closeRelay() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }

    /** Package-private playback evidence for the ExoLiveTest instrumentation suite. */
    static ExoPlayer player() {
        return player;
    }

    static PlayerView picture() {
        return view;
    }

    static boolean retryPending() {
        return pendingReopen != null;
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

    /**
     * The picture playing, for the information panel: size, frame rate, codec and bitrate, or ""
     * before the first video frame. The frame rate is the one measured on screen when known, as
     * many live streams do not state theirs.
     */
    static String videoDetails() {
        if (player == null) return "";
        Format format = player.getVideoFormat();
        if (format == null) return "";
        // The stream's own rate when it states one; otherwise the rate measured on screen.
        float fps = format.frameRate > 0 ? format.frameRate : Math.round(drawnFps);
        int bitrate = format.bitrate > 0 ? format.bitrate : format.averageBitrate;
        String codec = format.sampleMimeType != null ? format.sampleMimeType : format.codecs;
        return VideoDetails.describe(format.width, format.height, fps, codec, bitrate);
    }

    static String status() {
        return status + (decoder.isEmpty() ? "" : " · " + decoder);
    }

    // ---- Tracks ---------------------------------------------------------------------------------

    /** Audio tracks of the channel playing, as "Language · codec · channels". */
    static List<String> audioTracks() {
        List<String> names = new ArrayList<>();
        for (Format format : formats(C.TRACK_TYPE_AUDIO)) {
            String language = format.language == null ? "Unknown language" : format.language;
            String codec =
                format.sampleMimeType == null ? "" : " · " + format.sampleMimeType.replace("audio/", "");
            String layout = format.channelCount > 0 ? " · " + format.channelCount + " ch" : "";
            names.add(language + codec + layout);
        }
        return names;
    }

    static int selectedAudioTrack() {
        return selected(C.TRACK_TYPE_AUDIO);
    }

    static void selectAudioTrack(int wanted) {
        select(C.TRACK_TYPE_AUDIO, wanted);
    }

    /** Subtitle tracks, as "Language" (with a label when the stream gives one). */
    static List<String> subtitleTracks() {
        List<String> names = new ArrayList<>();
        for (Format format : formats(C.TRACK_TYPE_TEXT)) {
            String language = format.language == null ? "Unknown language" : format.language;
            names.add(format.label == null ? language : language + " · " + format.label);
        }
        return names;
    }

    /** The subtitle track shown, or -1 when subtitles are off. */
    static int selectedSubtitleTrack() {
        if (
            player == null ||
            player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        ) {
            return -1;
        }
        return selected(C.TRACK_TYPE_TEXT);
    }

    /** Shows subtitle track {@code wanted}, or turns subtitles off when it is -1. */
    static void selectSubtitleTrack(int wanted) {
        if (player == null) return;
        if (wanted < 0) {
            player.setTrackSelectionParameters(
                player
                    .getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            );
            return;
        }
        player.setTrackSelectionParameters(
            player
                .getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        );
        select(C.TRACK_TYPE_TEXT, wanted);
    }

    private static List<Format> formats(int type) {
        List<Format> formats = new ArrayList<>();
        if (player == null) return formats;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) formats.add(group.getTrackFormat(i));
        }
        return formats;
    }

    private static int selected(int type) {
        if (player == null) return -1;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++, index++) if (group.isTrackSelected(i)) return index;
        }
        return -1;
    }

    private static void select(int type, int wanted) {
        if (player == null) return;
        int index = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
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
}
