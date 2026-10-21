package local.tv.home;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

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
    /** Selection mode follows the active stream through guide/full-screen view changes. */
    static boolean automaticSources = true;
    static final Set<String> attemptedSources = new HashSet<>();
    /** A stream has been started and has not failed, ended or been stopped. */
    private static boolean alive;
    /** Who shows the picture now (the full-screen player or the guide picture), and where. */
    private static Object owner;
    private static LiveVideoOutput output;
    private static MediaPlayer.EventListener listener;
    private static Runnable resolutionFailed;
    private static Runnable resolutionReady;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService resolver = Executors.newFixedThreadPool(2);
    private static Future<?> pending;
    private static NativeHttp request;
    private static int generation;
    private static boolean resolving;
    private static boolean refreshed;
    private static Context appContext;
    private static List<String> lastOptions;
    private static final LiveProgress progress = new LiveProgress();
    private static boolean played;
    private static boolean viewRepaired;
    /**
     * How long a still picture over playing sound is left before its view is refreshed, as a move
     * to the mini guide picture and back does. The full-stall path below still waits
     * LiveZapper.STALL_MS before trying a backup source.
     */
    static final long PICTURE_REPAIR_MS = 2500;
    /** The freeze (by its start) already given its early refresh, so each freeze gets one. */
    private static long pictureRepairedFor = -1;
    private static int viewRepairs;
    private static String lastVideoRecovery = "None";

    private static final Runnable watchProgress = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            int ticket = generation;
            boolean watching =
                (EngineSources.locator(url) || PlutoSource.nativeUrl(url)) &&
                played &&
                player.getVideoTracksCount() > 0 &&
                !resolving &&
                alive &&
                player.isPlaying() &&
                output != null &&
                output.isShown();
            observeProgress(
                android.os.SystemClock.elapsedRealtime(),
                player.getTime(),
                output == null ? -1 : output.frames,
                watching
            );
            if (player != null && ticket == generation) main.postDelayed(this, 1000);
        }
    };

    static void observeProgress(long now, long time, long frames, boolean watching) {
        if (!(EngineSources.locator(url) || PlutoSource.nativeUrl(url))) return;
        if (!progress.stalled(now, time, frames, watching, LiveZapper.STALL_MS)) {
            // The owner saw the picture stop while the sound played on (21 October 2026), and
            // moving to the mini picture fixed it at once. Do that here after a short wait,
            // once for each freeze; a freeze it does not clear still reaches the path below.
            if (
                watching &&
                progress.pictureFrozen(now, PICTURE_REPAIR_MS) &&
                progress.pictureSince() != pictureRepairedFor &&
                output != null
            ) {
                pictureRepairedFor = progress.pictureSince();
                if (output.refreshView()) {
                    viewRepairs++;
                    lastVideoRecovery = "Picture stopped while the sound played; view refreshed";
                    Timings.record("Still picture refreshed", Timings.start());
                }
            }
            return;
        }
        boolean clockMoving = progress.clockMoving(now, LiveZapper.STALL_MS);
        progress.reset();
        Timings.record("Live video stopped advancing", Timings.start());
        if (clockMoving && !viewRepaired && output != null && output.refreshView()) {
            viewRepaired = true;
            viewRepairs++;
            lastVideoRecovery = "Video view refreshed while the playback clock advanced";
            return;
        }
        lastVideoRecovery = clockMoving
            ? "Rendered video stopped after a view refresh; trying the source backup"
            : "Playback clock and rendered video stopped; trying the source backup";
        if (retryProvider()) return;
        alive = false;
        player.pause();
        if (resolutionFailed != null) resolutionFailed.run();
    }

    /** Fixture transport for device tests; never assigned by app code. */
    interface Resolver {
        String resolve(String locator, NativeHttp http) throws Exception;
    }

    static Resolver testResolver;
    /** How many streams were opened, so tests can tell a handover from a restart. */
    static int opened;

    private LivePlayer() {}

    static String videoRecovery() {
        return lastVideoRecovery + " · " + viewRepairs + " view refreshes";
    }

    static LiveVideoOutput output() {
        return output;
    }

    static MediaPlayer player() {
        return player;
    }

    /** A stream is playing or opening, so a new view can take it over without reconnecting. */
    static boolean active() {
        return player != null && (alive || player.isPlaying());
    }

    static boolean isResolving() {
        return resolving;
    }

    static String currentUrl() {
        return active() ? url : null;
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
            output = new LiveVideoOutput(player);
            player.setEventListener(LivePlayer::event);
        }
        if (!restart && streamUrl.equals(url) && (alive || player.isPlaying())) {
            resume();
            return true;
        }
        if (!streamUrl.equals(url)) {
            automaticSources = true;
            attemptedSources.clear();
        }
        if (!streamUrl.equals(url) || !restart) {
            refreshed = false;
            viewRepaired = false;
        }
        appContext = context.getApplicationContext();
        lastOptions = new ArrayList<>(options);
        cancelResolution();
        player.stop();
        url = streamUrl;
        alive = true;
        opened++;
        played = false;
        progress.reset();
        main.removeCallbacks(watchProgress);
        main.postDelayed(watchProgress, 1000);
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
                    ProviderRelay nextRelay =
                        stream != null &&
                        (ProviderRelay.needed(stream) ||
                            streamUrl.contains("/engine/dlhd/") ||
                            streamUrl.contains("/engine/zlive/"))
                            ? new ProviderRelay(stream)
                            : null;
                    try {
                        http.check();
                    } catch (Exception e) {
                        if (nextRelay != null) nextRelay.close();
                        throw e;
                    }
                    main.post(() -> {
                        if (ticket != generation || player == null) {
                            if (nextRelay != null) nextRelay.close();
                            return;
                        }
                        relay = nextRelay;
                        resolving = false;
                        if (resolutionReady != null) resolutionReady.run();
                        open(
                            nextRelay == null ? resolved : nextRelay.url,
                            stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                            stream == null ? null : stream.referer
                        );
                        if (stream != null && stream.expiresAt > 0) {
                            long delay = Math.max(
                                30_000,
                                Math.min(
                                    3 * 3600_000L,
                                    stream.expiresAt - System.currentTimeMillis() - 60_000
                                )
                            );
                            renewal = () -> {
                                if (
                                    ticket == generation &&
                                    player != null &&
                                    player.isPlaying() &&
                                    streamUrl.equals(url)
                                ) {
                                    refreshed = false;
                                    play(appContext, url, lastOptions, true);
                                }
                            };
                            main.postDelayed(renewal, delay);
                        }
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

    private static ProviderRelay relay;
    private static Runnable renewal;

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
        if (renewal != null) {
            main.removeCallbacks(renewal);
            renewal = null;
        }
        if (relay != null) {
            relay.close();
            relay = null;
        }
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
        if (event.type == MediaPlayer.Event.Playing) played = true;
        if (
            (event.type == MediaPlayer.Event.EncounteredError ||
                event.type == MediaPlayer.Event.EndReached) &&
            retryProvider()
        ) {
            return;
        }
        // Not "Stopped": that comes late from our own stop() when opening a new stream, and would
        // mark the new stream as finished.
        if (
            event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached
        ) alive = false;
        if (listener != null) listener.onEvent(event);
    }

    private static boolean retryProvider() {
        if (!EngineSources.locator(url) || refreshed || appContext == null) return false;
        refreshed = true;
        AdapterSources.failed(url);
        EngineSources.invalidate();
        play(appContext, url, lastOptions, true);
        return true;
    }

    /**
     * Shows the picture in {@code view} for {@code who}, moving it from wherever it was. Events
     * now go to {@code events}.
     */
    static void attach(Object who, FrameLayout view, MediaPlayer.EventListener events) {
        attach(who, view, events, null);
    }

    static void attach(Object who, FrameLayout view, MediaPlayer.EventListener events, Runnable failed) {
        attach(who, view, events, failed, null);
    }

    static void attach(
        Object who,
        FrameLayout view,
        MediaPlayer.EventListener events,
        Runnable failed,
        Runnable ready
    ) {
        if (player == null) return;
        progress.reset();
        output.show(view);
        owner = who;
        listener = events;
        resolutionFailed = failed;
        resolutionReady = ready;
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
        main.removeCallbacks(watchProgress);
        progress.reset();
        played = false;
        cancelResolution();
        if (player != null) {
            player.setEventListener(null);
            player.stop();
            if (output != null) output.close();
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
        viewRepaired = false;
        engineOptions = null;
        url = null;
        alive = false;
        owner = null;
        automaticSources = true;
        attemptedSources.clear();
        output = null;
        listener = null;
        resolutionFailed = null;
        resolutionReady = null;
    }
}
