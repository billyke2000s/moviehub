package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.videolan.libvlc.LibVLC;
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
    private static boolean renewalPaused;
    private static boolean refreshed, retrying;
    private static final LiveRestartBudget reconnects = new LiveRestartBudget();
    private static boolean softwareVideo, videoFault;
    private static Context appContext;
    private static List<String> lastOptions;
    private static final LiveProgress progress = new LiveProgress();
    private static boolean played, userPaused;
    private static LiveBackup backup;
    private static Runnable playerChanged;
    private static final LiveBuffering buffering = new LiveBuffering();
    private static String backupStatus = "None";
    private static final LiveRepairBudget repairBudget = new LiveRepairBudget();
    static final long PICTURE_REPAIR_MS = 2500;
    private static long pictureRepairedFor = -1;
    private static int viewRepairs;
    private static String lastVideoRecovery = "None",
        lastFailureCause = "None";

    private static final Runnable watchProgress = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (LiveNative.busy(player)) {
                main.postDelayed(this, 1000);
                return;
            }
            int ticket = generation;
            LivePictureRates.sample(player, output, owner, android.os.SystemClock.elapsedRealtime());
            boolean watching =
                played &&
                player.getVideoTracksCount() > 0 &&
                !resolving &&
                alive &&
                player.isPlaying() &&
                output != null &&
                output.shouldRender();
            long now = android.os.SystemClock.elapsedRealtime();
            if (output != null && output.presentationMissing(now, watching)) {
                videoFault = true;
                lastFailureCause = "Video surface unavailable";
                if (output.repairPresentation()) repairBudget.repaired();
                else if (!retryProvider() && resolutionFailed != null) resolutionFailed.run();
                lastVideoRecovery = "Rendered buffers missing; repairing live picture";
            }
            if (player == null || LiveNative.busy(player) || ticket != generation) {
                if (player != null && ticket == generation) main.postDelayed(this, 1000);
                return;
            }
            observeProgress(now, player.getTime(), output == null ? -1 : output.pictures(), watching);
            if (
                player != null && (buffering.stalled(now, bufferWatching()) || buffering.persistent(now))
            ) recoverBuffering();
            if (player != null && ticket == generation) main.postDelayed(this, 1000);
        }
    };

    static void observeProgress(long now, long time, long frames, boolean watching) {
        if (player == null || !alive || LiveNative.busy(player)) return;
        repairBudget.observe(frames, watching);
        reconnects.observe(now, frames, watching && output != null && !output.presentationUnavailable());
        if (!progress.stalled(now, time, frames, watching, LiveZapper.STALL_MS)) {
            if (
                watching &&
                repairBudget.allowed() &&
                progress.pictureFrozen(now, PICTURE_REPAIR_MS) &&
                progress.pictureSince() != pictureRepairedFor &&
                output != null &&
                output.refreshView()
            ) {
                repairBudget.repaired();
                pictureRepairedFor = progress.pictureSince();
                viewRepairs++;
                lastVideoRecovery = "Picture stopped while the sound played; view refreshed";
                Timings.record("Still picture refreshed", Timings.start());
            }
            return;
        }
        boolean clockMoving = progress.clockMoving(now, LiveZapper.STALL_MS);
        videoFault = clockMoving;
        lastFailureCause = "Video progress stopped";
        progress.reset();
        Timings.record("Live video stopped advancing", Timings.start());
        if (clockMoving && repairBudget.allowed() && output != null && output.refreshView()) {
            repairBudget.repaired();
            viewRepairs++;
            lastVideoRecovery = "Video view refreshed while the playback clock advanced";
            return;
        }
        lastVideoRecovery = clockMoving
            ? "Rendered video stopped after a view refresh; trying the source backup"
            : "Playback clock and rendered video stopped; trying the source backup";
        if (prepareBackupForFailure()) return;
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
    /** Device tests only: VLC's detailed log, so a failing CI run shows what VLC did. */
    static boolean testVerbose;
    /** How many streams were opened, so tests can tell a handover from a restart. */
    static int opened;

    private LivePlayer() {}

    static String videoRecovery() {
        return (
            lastVideoRecovery +
            " · " +
            lastFailureCause +
            " · " +
            viewRepairs +
            " view refreshes · " +
            backupStatus +
            " · " +
            (output == null ? "No surface" : output.surfaceHealth())
        );
    }

    static void backups(Context context, List<ChannelDatabase.Row> copies) {
        if (player == null || engine == null) return;
        if (backup == null) backup = new LiveBackup(context, engine);
        backup.configure(copies);
    }

    static boolean preparingBackup() {
        return backup != null && backup.preparing();
    }

    static void pauseForUser() {
        userPaused = true;
        buffering.reset();
        cancelBackup();
    }

    static MediaPlayer standbyPlayer() {
        return backup == null ? null : backup.player();
    }

    static boolean userPaused() {
        return userPaused;
    }

    static boolean canPrepareBackup() {
        return (
            player != null &&
            !LiveNative.busy(player) &&
            played &&
            !userPaused &&
            !resolving &&
            output != null &&
            output.host() != null &&
            output.isShown()
        );
    }

    static boolean prepareBackup() {
        return backup != null && backup.recover();
    }

    static boolean prepareBackupForFailure() {
        return backup != null && !backup.exhausted() && backup.recover();
    }

    static void cancelBackup() {
        if (backup != null) backup.cancel();
    }

    static void retryBackups() {
        if (backup != null) backup.newSelection();
    }

    static void backupStatus(String status) {
        backupStatus = status;
    }

    static void backupUnavailable() {
        if (player != null && !player.isPlaying()) {
            alive = false;
            if (resolutionFailed != null) resolutionFailed.run();
        }
    }

    private static boolean bufferWatching() {
        return (
            played &&
            alive &&
            !userPaused &&
            !resolving &&
            output != null &&
            output.host() != null &&
            output.isShown()
        );
    }

    static void bufferingStarted() {
        if (
            buffering.update(android.os.SystemClock.elapsedRealtime(), true, bufferWatching())
        ) recoverBuffering();
    }

    static void bufferingEnded() {
        buffering.update(android.os.SystemClock.elapsedRealtime(), false, bufferWatching());
    }

    private static void recoverBuffering() {
        if (prepareBackupForFailure()) return;
        // Three brief buffer notifications may be normal HLS period transitions. They can warm
        // an alternate source, but must not tear down a progressing channel with no backup.
        if (!buffering.persistent(android.os.SystemClock.elapsedRealtime())) return;
        if (progress.videoMoving(android.os.SystemClock.elapsedRealtime(), 2000)) return;
        lastFailureCause = "Prolonged buffering";
        if (!retryProvider() && resolutionFailed != null) {
            buffering.reset();
            resolutionFailed.run();
        }
    }

    /** Promotes a running, muted SurfaceView; its decoder and resolved relay are retained. */
    static void adoptBackup(
        MediaPlayer previous,
        MediaPlayer ready,
        VLCVideoLayout layout,
        FrameLayout host,
        String locator,
        ProviderRelay nextRelay,
        long expiresAt
    ) {
        int volume = previous.getVolume();
        ready.setRate(previous.getRate());
        ready.setAspectRatio(previous.getAspectRatio());
        cancelResolution();
        previous.setEventListener(null);
        output.stopping();
        previous.setVolume(0);
        layout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        layout.setTranslationX(0);
        LiveVideoOutput previousOutput = output;
        ProviderRelay previousRelay = relay;
        LiveNative.stop(previous, () -> {
            if (previousRelay != null) previousRelay.close();
            previousOutput.close();
            previous.release();
        });
        player = ready;
        output = new LiveVideoOutput(ready, layout, host);
        relay = nextRelay;
        url = locator;
        alive = played = true;
        userPaused = false;
        refreshed = false;
        opened++;
        buffering.reset();
        progress.reset();
        repairBudget.reset();
        pictureRepairedFor = -1;
        LivePictureRates.reset();
        player.setEventListener(LivePlayer::event);
        player.setVolume(volume);
        backupStatus = "Prepared live backup is playing";
        scheduleRenewal(expiresAt, locator, generation);
        main.removeCallbacks(watchProgress);
        main.postDelayed(watchProgress, 1000);
        if (playerChanged != null) playerChanged.run();
    }

    static LiveVideoOutput output() {
        return output;
    }

    static MediaPlayer player() {
        return player;
    }

    /** The picture playing, for the information panel, or "" when there is none yet. */
    static String videoDetails() {
        MediaPlayer playing = player;
        if (playing == null || LiveNative.busy(playing)) return "";
        try {
            var track = playing.getCurrentVideoTrack();
            if (track == null) return "";
            float fps = track.frameRateDen > 0 ? (float) track.frameRateNum / track.frameRateDen : 0;
            return VideoDetails.describe(track.width, track.height, fps, track.codec, track.bitrate);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A stream is playing or opening, so a new view can take it over without reconnecting. */
    static boolean active() {
        return player != null && (alive || (!LiveNative.busy(player) && player.isPlaying()));
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
        return play(context, streamUrl, options, restart, false);
    }

    /** Renew the current provider link without spending a second decoder or interrupting lookup. */
    static void renew() {
        if (
            appContext != null &&
            player != null &&
            !LiveNative.busy(player) &&
            player.isPlaying() &&
            !resolving
        ) {
            refreshed = false;
            play(appContext, url, lastOptions, true, true);
        }
    }

    private static boolean play(
        Context context,
        String streamUrl,
        List<String> options,
        boolean restart,
        boolean renewalOnly
    ) {
        cancelBackup();
        if (backup != null && (!streamUrl.equals(url) || restart)) backup.newSelection();
        if (backup != null && !backup.contains(streamUrl)) backup.clearSources();
        String optionKey = String.valueOf(options);
        if (engine == null || !optionKey.equals(engineOptions)) {
            stop();
            List<String> vlcOptions = new ArrayList<>(options);
            if (testVerbose) vlcOptions.add("-vv");
            engine = new LibVLC(context.getApplicationContext(), vlcOptions);
            engineOptions = optionKey;
            player = new MediaPlayer(engine);
            output = new LiveVideoOutput(player);
            player.setEventListener(LivePlayer::event);
        }
        if (
            !restart && streamUrl.equals(url) && (alive || (!LiveNative.busy(player) && player.isPlaying()))
        ) {
            resume();
            return true;
        }
        if ((!streamUrl.equals(url) || restart) && !retrying && !renewalOnly) {
            reconnects.reset();
            softwareVideo = videoFault = false;
        }
        if (!streamUrl.equals(url)) {
            automaticSources = true;
            attemptedSources.clear();
        }
        if (!streamUrl.equals(url) || !restart) {
            refreshed = false;
            repairBudget.reset();
        }
        appContext = context.getApplicationContext();
        lastOptions = new ArrayList<>(options);
        boolean retain = renewalOnly && streamUrl.equals(url) && alive && player.isPlaying();
        cancelResolution();
        renewalPaused = false;
        userPaused = false;
        buffering.reset();
        if (!retain) {
            ProviderRelay oldRelay = relay;
            relay = null;
            LiveNative.stop(player, () -> {
                if (oldRelay != null) oldRelay.close();
            });
        }
        url = streamUrl;
        alive = true;
        opened++;
        played = false;
        progress.reset();
        pictureRepairedFor = -1;
        main.removeCallbacks(watchProgress);
        main.postDelayed(watchProgress, 1000);
        if (PlutoSource.nativeUrl(streamUrl) || EngineSources.locator(streamUrl)) {
            resolving = true;
            int ticket = generation;
            NativeHttp http = new NativeHttp();
            request = http;
            pending = resolver.submit(() -> {
                try {
                    LiveStream.Resolution source = LiveStream.resolve(context, streamUrl, http, testResolver);
                    EngineSources.Stream stream = source.provider;
                    ProviderRelay nextRelay = source.relay;
                    String resolved = source.url;
                    main.post(() -> {
                        if (ticket != generation || player == null) {
                            if (nextRelay != null) nextRelay.close();
                            return;
                        }
                        resolving = false;
                        // A pause/focus loss during renewal must not start playback again.
                        if (retain && renewalPaused) {
                            if (nextRelay != null) nextRelay.close();
                            played = true;
                            return;
                        }
                        if (resolutionReady != null) resolutionReady.run();
                        open(
                            nextRelay == null ? resolved : nextRelay.url,
                            stream == null ? PlutoSource.USER_AGENT : stream.userAgent,
                            stream == null ? null : stream.referer,
                            nextRelay
                        );
                        if (stream != null) scheduleRenewal(stream.expiresAt, streamUrl, ticket);
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (ticket != generation || player == null) return;
                        resolving = false;
                        Diagnostics.recovered("Resolving native live channel", e);
                        if (retain && ((!LiveNative.busy(player) && player.isPlaying()) || renewalPaused)) {
                            // A failed refresh does not establish that the existing stream is dead.
                            played = true;
                            progress.reset();
                            return;
                        }
                        alive = false;
                        if (resolutionFailed != null) resolutionFailed.run();
                    });
                }
            });
        } else open(streamUrl, null, null, null);
        return false;
    }

    private static void scheduleRenewal(long expiresAt, String locator, int ticket) {
        if (expiresAt <= 0) return;
        long delay = Math.max(
            30_000,
            Math.min(3 * 3600_000L, expiresAt - System.currentTimeMillis() - 60_000)
        );
        renewal = () -> {
            if (
                ticket == generation &&
                player != null &&
                !LiveNative.busy(player) &&
                player.isPlaying() &&
                locator.equals(url)
            ) renew();
        };
        main.postDelayed(renewal, delay);
    }

    private static ProviderRelay relay;
    private static Runnable renewal;

    /**
     * How far ahead relayed provider streams (FreeShot, DaddyLive, ZLive) are buffered. Their
     * segments arrive through the local relay, and FreeShot's are decoded from PNG first, so a
     * slow segment drained the shared 1.5 s Live TV buffer (owner's phone, 21 October 2026: six
     * short pauses in one session). Other channels keep the shorter buffer and faster start.
     */
    static final int RELAYED_CACHING_MS = PlaybackBuffer.RELAY_MS;

    private static void open(String stream, String userAgent, String referer, ProviderRelay nextRelay) {
        MediaPlayer opening = player;
        int ticket = generation;
        LiveNative.stop(opening, () -> {
            if (player != opening || ticket != generation) {
                if (nextRelay != null) nextRelay.close();
                return;
            }
            closeRelay();
            relay = nextRelay;
            if (output != null) output.streamChanging();
            LiveStream.play(engine, opening, stream, userAgent, referer, nextRelay, softwareVideo);
        });
    }

    private static void cancelResolution() {
        resolving = false;
        if (renewal != null) {
            main.removeCallbacks(renewal);
            renewal = null;
        }
        generation++;
        if (request != null) request.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        request = null;
    }

    private static void closeRelay() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }

    static void resume() {
        if (player != null && !LiveNative.busy(player) && !resolving && !player.isPlaying()) player.play();
    }

    private static void event(MediaPlayer.Event event) {
        if (player == null) return;
        if (LiveNative.busy(player)) {
            if (event.type == MediaPlayer.Event.Vout && output != null) output.videoOutputs(
                event.getVoutCount()
            );
            return;
        }
        if (event.type == MediaPlayer.Event.Paused) {
            userPaused = true;
            buffering.reset();
            cancelBackup();
        } else if (event.type == MediaPlayer.Event.Playing) userPaused = false;
        if (event.type == MediaPlayer.Event.Buffering) {
            if (event.getBuffering() < 100) bufferingStarted();
            else bufferingEnded();
        }
        if (resolving && event.type == MediaPlayer.Event.Paused) renewalPaused = true;
        if (resolving && event.type == MediaPlayer.Event.Playing) renewalPaused = false;
        if (
            resolving &&
            (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.EncounteredError)
        ) return;
        if (event.type == MediaPlayer.Event.EndReached) lastFailureCause = "Live input ended";
        if (event.type == MediaPlayer.Event.EncounteredError) lastFailureCause = "Live input error";
        if (event.type == MediaPlayer.Event.Playing) played = true;
        if (
            output != null &&
            (event.type == MediaPlayer.Event.Playing ||
                event.type == MediaPlayer.Event.ESAdded ||
                event.type == MediaPlayer.Event.Vout)
        ) output.restoreAudioTrack();
        if (event.type == MediaPlayer.Event.Vout && output != null) output.videoOutputs(event.getVoutCount());
        if (
            (event.type == MediaPlayer.Event.EncounteredError ||
                event.type == MediaPlayer.Event.EndReached) &&
            (preparingBackup() || prepareBackupForFailure() || retryProvider())
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

    static boolean repairPicture() {
        if (player == null || userPaused || LiveNative.busy(player)) return false;
        videoFault = true;
        if (output != null && output.refreshView()) {
            repairBudget.repaired();
            viewRepairs++;
            lastVideoRecovery = "Picture repair requested from live controls";
            return true;
        }
        return retryProvider();
    }

    static boolean retryProvider() {
        if (appContext == null || userPaused || resolving || url == null) return false;
        boolean engineSource = EngineSources.locator(url);
        if (engineSource) {
            if (refreshed) return false;
            refreshed = true;
            AdapterSources.failed(url);
            EngineSources.invalidate();
        } else {
            // Initial bad addresses still fail promptly. Reconnect an established live session.
            if (!played && reconnects.attempts() == 0) return false;
            if (!reconnects.acquire()) return false;
            if (PlutoSource.nativeUrl(url)) PlutoSource.invalidate();
            softwareVideo = reconnects.attempts() > 1 && videoFault;
        }
        lastVideoRecovery =
            "Reconnecting selected live source · attempt " +
            (engineSource ? 1 : reconnects.attempts()) +
            (softwareVideo ? " · software video" : "");
        retrying = true;
        try {
            play(appContext, url, lastOptions, true);
        } finally {
            retrying = false;
        }
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
        attach(who, view, events, failed, ready, null);
    }

    static void attach(
        Object who,
        FrameLayout view,
        MediaPlayer.EventListener events,
        Runnable failed,
        Runnable ready,
        Runnable changed
    ) {
        if (player == null) return;
        if (output.host() != view) cancelBackup();
        progress.reset();
        output.show(view);
        owner = who;
        listener = events;
        resolutionFailed = failed;
        resolutionReady = ready;
        playerChanged = changed;
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
        if (backup != null) backup.close();
        backup = null;
        buffering.reset();
        userPaused = false;
        LivePictureRates.reset();
        main.removeCallbacks(watchProgress);
        progress.reset();
        played = false;
        cancelResolution();
        MediaPlayer retiring = player;
        LiveVideoOutput retiringOutput = output;
        LibVLC retiringEngine = engine;
        ProviderRelay retiringRelay = relay;
        player = null;
        engine = null;
        relay = null;
        if (retiring != null) {
            retiring.setEventListener(null);
            if (retiringOutput != null) retiringOutput.stopping();
            LiveNative.stop(retiring, () -> {
                if (retiringRelay != null) retiringRelay.close();
                if (retiringOutput != null) retiringOutput.close();
                retiring.release();
                if (retiringEngine != null) retiringEngine.release();
            });
        } else if (retiringEngine != null) retiringEngine.release();
        appContext = null;
        lastOptions = null;
        lastVideoRecovery = lastFailureCause = "None";
        backupStatus = "None";
        viewRepairs = 0;
        reconnects.reset();
        softwareVideo = videoFault = false;
        refreshed = false;
        repairBudget.reset();
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
        playerChanged = null;
    }
}
