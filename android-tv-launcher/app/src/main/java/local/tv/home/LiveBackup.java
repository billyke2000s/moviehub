package local.tv.home;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/** One muted standby for the shared live session. All state and native access stay on main. */
final class LiveBackup {

    private static final long RESOLVE_MS = 45000,
        START_MS = 15000;
    private final Context context;
    private final LibVLC engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<ChannelDatabase.Row> copies = new ArrayList<>();
    private Future<?> pending;
    private NativeHttp request;
    private MediaPlayer original, candidate;
    private VLCVideoLayout view;
    private FrameLayout host;
    private Resolved resolved;
    private ChannelDatabase.Row selected;
    private String locator;
    private int ticket;
    private boolean preparing, closed, exhausted, candidatePlaying, candidateBuffering;
    private long deadline,
        frames = -1,
        audio = -1,
        observedAt;
    private final Runnable check = this::check;

    LiveBackup(Context context, LibVLC engine) {
        this.context = context.getApplicationContext();
        this.engine = engine;
    }

    MediaPlayer player() {
        return candidate;
    }

    boolean preparing() {
        return preparing;
    }

    boolean exhausted() {
        return exhausted;
    }

    boolean contains(String url) {
        String key = ChannelQuery.key(url);
        for (ChannelDatabase.Row row : copies) if (row.key.equals(key)) return true;
        return false;
    }

    void configure(List<ChannelDatabase.Row> found) {
        String current = LivePlayer.currentUrl();
        if (current == null) return;
        String key = ChannelQuery.key(current);
        boolean matches = false;
        for (ChannelDatabase.Row row : found) if (row.key.equals(key)) matches = true;
        // A late database callback from another channel must not replace this session's backups.
        if (!matches) return;
        copies = new ArrayList<>(found);
    }

    void newSelection() {
        cancel();
        exhausted = false;
    }

    void clearSources() {
        cancel();
        copies.clear();
        exhausted = false;
    }

    /** True means recovery is being prepared, or exhausted backups leave working playback alone. */
    boolean recover() {
        if (closed || !LivePlayer.automaticSources || copies.size() < 2) return false;
        if (preparing) return true;
        if (exhausted) return LivePlayer.player() != null && LivePlayer.player().isPlaying();
        LiveVideoOutput output = LivePlayer.output();
        if (!LivePlayer.canPrepareBackup() || output == null || output.host() == null) return false;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        if (memory.lowMemory) {
            exhausted = true;
            LivePlayer.backupStatus("Backup preparation skipped: low memory; keeping current source");
            return true;
        }
        original = LivePlayer.player();
        host = output.host();
        String currentKey = ChannelQuery.key(LivePlayer.currentUrl());
        LivePlayer.attemptedSources.add(currentKey);
        int active = 0;
        for (int i = 0; i < copies.size(); i++) if (copies.get(i).key.equals(currentKey)) active = i;
        for (int offset = 1; offset < copies.size(); offset++) {
            ChannelDatabase.Row next = copies.get((active + offset) % copies.size());
            if (LivePlayer.attemptedSources.contains(next.key)) continue;
            selected = next;
            LivePlayer.attemptedSources.add(next.key);
            prepare();
            return true;
        }
        exhausted = true;
        LivePlayer.backupStatus("No working backup; keeping current source");
        return original.isPlaying();
    }

    private void prepare() {
        preparing = true;
        candidatePlaying = candidateBuffering = false;
        frames = audio = -1;
        observedAt = 0;
        int attempt = ++ticket;
        ChannelDatabase.Row row = selected;
        deadline = SystemClock.elapsedRealtime() + RESOLVE_MS;
        NativeHttp http = new NativeHttp();
        request = http;
        LivePlayer.backupStatus("Preparing another live source; current source retained");
        main.postDelayed(check, 100);
        pending = worker.submit(() -> {
            Resolved stream = null;
            try {
                String url = ChannelDatabase.get(context).url(row.id, row.key);
                stream = Resolved.resolve(context, url, http);
                http.check();
                Resolved ready = stream;
                main.post(() -> {
                    if (!valid(attempt)) {
                        ready.close();
                        return;
                    }
                    locator = url;
                    resolved = ready;
                    open(attempt);
                });
            } catch (Exception error) {
                if (stream != null) stream.close();
                main.post(() -> {
                    if (!valid(attempt)) return;
                    Diagnostics.recovered("Preparing live backup", error);
                    failed();
                });
            }
        });
    }

    private boolean valid(int attempt) {
        return (
            !closed &&
            preparing &&
            attempt == ticket &&
            original == LivePlayer.player() &&
            LivePlayer.automaticSources &&
            !LivePlayer.userPaused() &&
            LivePlayer.output() != null &&
            LivePlayer.output().host() == host
        );
    }

    private void open(int attempt) {
        try {
            // SurfaceView alpha is unreliable on API 28. Stage outside the host's visible bounds,
            // then move this same native surface into place: no detach or decoder restart at cut.
            view = new VLCVideoLayout(host.getContext());
            view.setTranslationX(-Math.max(1024, host.getWidth() + 320));
            host.addView(view, new FrameLayout.LayoutParams(160, 90));
            candidate = new MediaPlayer(engine);
            candidate.attachViews(view, null, false, false);
            candidate.setVolume(0);
            candidate.setRate(original.getRate());
            candidate.setAspectRatio(original.getAspectRatio());
            candidate.setEventListener(event -> {
                if (!valid(attempt)) return;
                if (event.type == MediaPlayer.Event.Playing) candidatePlaying = true;
                else if (event.type == MediaPlayer.Event.Buffering) candidateBuffering =
                    event.getBuffering() < 100;
                else if (
                    event.type == MediaPlayer.Event.EncounteredError ||
                    event.type == MediaPlayer.Event.EndReached
                ) failed();
            });
            Media media = new Media(engine, Uri.parse(resolved.url));
            try {
                media.setHWDecoderEnabled(true, false);
                media.addOption(
                    ":network-caching=" + PlaybackBuffer.live(resolved.url, resolved.relay != null)
                );
                if (resolved.agent != null) media.addOption(":http-user-agent=" + resolved.agent);
                if (resolved.referer != null && !resolved.referer.isEmpty()) media.addOption(
                    ":http-referrer=" + resolved.referer
                );
                candidate.setMedia(media);
            } finally {
                media.release();
            }
            deadline = SystemClock.elapsedRealtime() + START_MS;
            candidate.play();
        } catch (RuntimeException error) {
            Diagnostics.recovered("Opening live backup", error);
            failed();
        }
    }

    private void check() {
        if (!valid(ticket)) {
            cancel();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (candidate != null && candidatePlaying && !candidateBuffering && candidate.isPlaying()) {
            IMedia media = candidate.getMedia();
            try {
                IMedia.Stats stats = media == null ? null : media.getStats();
                boolean radio = selected.section.equals("Radio");
                long nextFrames = stats == null ? -1 : stats.decodedVideo;
                long nextAudio = stats == null ? -1 : stats.decodedAudio;
                boolean hasAudio = candidate.getAudioTracksCount() > 0;
                boolean ready =
                    stats != null && (radio ? nextAudio > 0 : nextFrames > 2) && (!hasAudio || nextAudio > 0);
                if (
                    ready &&
                    observedAt != 0 &&
                    now - observedAt >= 500 &&
                    (radio ? nextAudio >= audio + 3 : nextFrames >= frames + 3) &&
                    (!hasAudio || nextAudio > audio)
                ) {
                    if (LiveNative.busy(original)) {
                        main.postDelayed(check, 100);
                        return;
                    }
                    promote();
                    return;
                }
                if (ready && observedAt == 0) {
                    observedAt = now;
                    frames = nextFrames;
                    audio = nextAudio;
                }
            } finally {
                if (media != null) media.release();
            }
        } else observedAt = 0;
        if (now >= deadline) {
            failed();
            return;
        }
        main.postDelayed(check, 100);
    }

    private void promote() {
        MediaPlayer ready = candidate;
        VLCVideoLayout readyView = view;
        Resolved stream = resolved;
        candidate = null;
        view = null;
        resolved = null;
        preparing = false;
        ticket++;
        main.removeCallbacks(check);
        pending = null;
        request = null;
        LivePlayer.adoptBackup(original, ready, readyView, host, locator, stream.relay, stream.expiresAt);
        stream.relay = null;
        original = null;
        host = null;
        selected = null;
        locator = null;
        exhausted = false;
    }

    private void failed() {
        discard();
        preparing = false;
        ticket++;
        if (!recover()) LivePlayer.backupUnavailable();
    }

    void cancel() {
        if (preparing && selected != null) LivePlayer.attemptedSources.remove(selected.key);
        preparing = false;
        ticket++;
        discard();
    }

    private void discard() {
        main.removeCallbacks(check);
        if (request != null) request.cancel();
        if (pending != null) pending.cancel(true);
        request = null;
        pending = null;
        MediaPlayer retired = candidate;
        VLCVideoLayout retiredView = view;
        Resolved retiredStream = resolved;
        candidate = null;
        resolved = null;
        if (retired != null) {
            retired.setEventListener(null);
            engine.retain();
            LiveNative.stop(retired, () -> {
                retired.detachViews();
                retired.release();
                if (retiredStream != null) retiredStream.close();
                if (retiredView != null && retiredView.getParent() instanceof ViewGroup) (
                    (ViewGroup) retiredView.getParent()
                ).removeView(retiredView);
                engine.release();
            });
        } else {
            if (retiredStream != null) retiredStream.close();
            if (retiredView != null && retiredView.getParent() instanceof ViewGroup) (
                (ViewGroup) retiredView.getParent()
            ).removeView(retiredView);
        }
        view = null;
        original = null;
        host = null;
        selected = null;
        locator = null;
    }

    void close() {
        closed = true;
        cancel();
        copies.clear();
        worker.shutdownNow();
    }

    /** The candidate owns its relay until promotion, and closes late or cancelled resolutions. */
    private static final class Resolved {

        String url, agent, referer;
        long expiresAt;
        ProviderRelay relay;

        static Resolved resolve(Context context, String locator, NativeHttp http) throws Exception {
            Resolved result = new Resolved();
            EngineSources.Stream stream = EngineSources.locator(locator)
                ? EngineSources.resolve(context, locator, http)
                : null;
            if (stream != null) {
                result.url = stream.url;
                result.agent = stream.userAgent;
                result.referer = stream.referer;
                result.expiresAt = stream.expiresAt;
                if (
                    ProviderRelay.needed(stream) ||
                    locator.contains("/engine/dlhd/") ||
                    locator.contains("/engine/zlive/")
                ) result.relay = new ProviderRelay(stream);
                if (result.relay != null) result.url = result.relay.url;
            } else if (PlutoSource.nativeUrl(locator)) {
                LivePlayer.Resolver fixture = LivePlayer.testResolver;
                result.url =
                    fixture == null ? PlutoSource.resolve(locator, http) : fixture.resolve(locator, http);
                result.agent = PlutoSource.USER_AGENT;
            } else result.url = Playlist.networkUrl(
                locator,
                context.getSharedPreferences("ui", 0).getBoolean("http", false)
            );
            return result;
        }

        void close() {
            if (relay != null) relay.close();
            relay = null;
        }
    }
}
