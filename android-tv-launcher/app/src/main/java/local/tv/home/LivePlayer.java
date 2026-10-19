package local.tv.home;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * The one Live TV player, shared by the full-screen player and the picture in the guide's corner,
 * as a satellite box keeps one tuner: going between them moves the picture to another view
 * instead of stopping and reconnecting. Movies and series keep their own player. Main thread only.
 */
final class LivePlayer {

    private static LibVLC engine;
    private static String engineOptions;
    private static MediaPlayer player;
    private static String url;
    /** A stream has been started and has not failed, ended or been stopped. */
    private static boolean alive;
    /** Who shows the picture now (the full-screen player or the guide picture), and where. */
    private static Object owner;
    private static VLCVideoLayout layout;
    private static MediaPlayer.EventListener listener;
    private static Runnable resolutionFailed;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService resolver = Executors.newFixedThreadPool(2);
    private static Future<?> pending;
    private static NativeHttp request;
    private static int generation;
    private static boolean resolving;
    private static boolean refreshed;
    private static Context appContext;
    private static List<String> lastOptions;

    /** Fixture transport for device tests; never assigned by app code. */
    interface Resolver {
        String resolve(String locator, NativeHttp http) throws Exception;
    }

    static Resolver testResolver;
    /** How many streams were opened, so tests can tell a handover from a restart. */
    static int opened;

    private LivePlayer() {}

    static MediaPlayer player() {
        return player;
    }

    /** A stream is playing or opening, so a new view can take it over without reconnecting. */
    static boolean active() {
        return player != null && (alive || player.isPlaying());
    }

    /**
     * Plays {@code streamUrl}. When that stream is already playing (and {@code restart} is false)
     * it carries on untouched and this returns true. Otherwise the player opens it (keeping the
     * engine) and this returns false.
     */
    static boolean play(Context context, String streamUrl, List<String> options, boolean restart) {
        String optionKey = String.valueOf(options);
        if (engine == null || !optionKey.equals(engineOptions)) {
            stop();
            engine = new LibVLC(context.getApplicationContext(), new ArrayList<>(options));
            engineOptions = optionKey;
            player = new MediaPlayer(engine);
            player.setEventListener(LivePlayer::event);
        }
        if (!restart && streamUrl.equals(url) && (alive || player.isPlaying())) {
            resume();
            return true;
        }
        if (!streamUrl.equals(url) || !restart) refreshed = false;
        appContext = context.getApplicationContext();
        lastOptions = new ArrayList<>(options);
        cancelResolution();
        player.stop();
        url = streamUrl;
        alive = true;
        opened++;
        if (PlutoSource.nativeUrl(streamUrl) || EngineSources.locator(streamUrl)) {
            resolving = true;
            int ticket = generation;
            NativeHttp http = new NativeHttp();
            request = http;
            pending = resolver.submit(() -> {
                try {
                    Resolver fixture = testResolver;
                    EngineSources.Stream stream = EngineSources.locator(streamUrl)
                        ? EngineSources.resolve(context.getApplicationContext(), streamUrl, http)
                        : null;
                    String resolved =
                        stream != null
                            ? stream.url
                            : fixture == null
                              ? PlutoSource.resolve(streamUrl, http)
                              : fixture.resolve(streamUrl, http);
                    http.check();
                    main.post(() -> {
                        if (ticket != generation || player == null) return;
                        resolving = false;
                        open(
                            resolved,
                            stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                            stream == null ? null : stream.referer
                        );
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (ticket != generation || player == null) return;
                        resolving = false;
                        alive = false;
                        Diagnostics.recovered("Resolving native live channel", e);
                        if (resolutionFailed != null) resolutionFailed.run();
                    });
                }
            });
        } else open(streamUrl, null, null);
        return false;
    }

    private static void open(String stream, String userAgent, String referer) {
        Media media = new Media(engine, Uri.parse(stream));
        media.setHWDecoderEnabled(true, false);
        if (userAgent != null) media.addOption(":http-user-agent=" + userAgent);
        if (referer != null && !referer.isEmpty()) media.addOption(":http-referrer=" + referer);
        player.setMedia(media);
        media.release();
        player.play();
    }

    private static void cancelResolution() {
        resolving = false;
        generation++;
        if (request != null) request.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        request = null;
    }

    static void resume() {
        if (player != null && !resolving && !player.isPlaying()) player.play();
    }

    private static void event(MediaPlayer.Event event) {
        if (
            (event.type == MediaPlayer.Event.EncounteredError ||
                event.type == MediaPlayer.Event.EndReached) &&
            EngineSources.locator(url) &&
            !refreshed &&
            appContext != null
        ) {
            refreshed = true;
            EngineSources.invalidate();
            play(appContext, url, lastOptions, true);
            return;
        }
        // Not "Stopped": that comes late from our own stop() when opening a new stream, and would
        // mark the new stream as finished.
        if (
            event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached
        ) alive = false;
        if (listener != null) listener.onEvent(event);
    }

    /**
     * Shows the picture in {@code view} for {@code who}, moving it from wherever it was. Events
     * now go to {@code events}.
     */
    static void attach(Object who, VLCVideoLayout view, MediaPlayer.EventListener events) {
        attach(who, view, events, null);
    }

    static void attach(Object who, VLCVideoLayout view, MediaPlayer.EventListener events, Runnable failed) {
        if (player == null) return;
        if (layout != view) {
            boolean moving = layout != null;
            if (moving) player.detachViews();
            player.attachViews(view, null, false, false);
            layout = view;
            // Do not restart the video decoder during a view handover. LibVLC
            // binds its existing decoder to the new video output when the
            // destination surface becomes available.
        }
        owner = who;
        listener = events;
        resolutionFailed = failed;
    }

    static boolean owns(Object who) {
        return player != null && owner == who;
    }

    /** Stops the player when {@code who} still shows it (nobody took it over). */
    static void stopIfOwner(Object who) {
        if (owns(who)) stop();
    }

    /** Stops and releases everything. */
    static void stop() {
        cancelResolution();
        if (player != null) {
            player.setEventListener(null);
            player.stop();
            if (layout != null) player.detachViews();
            player.release();
            player = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
        appContext = null;
        lastOptions = null;
        refreshed = false;
        engineOptions = null;
        url = null;
        alive = false;
        owner = null;
        layout = null;
        listener = null;
        resolutionFailed = null;
    }
}
