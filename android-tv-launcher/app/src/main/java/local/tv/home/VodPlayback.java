package local.tv.home;

import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.concurrent.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Keeps the old VOD stream until a muted replacement has decoded at the resume point. Main thread only. */
final class VodPlayback {

    private final VlcPlaybackActivity activity;
    private final VodSources.Session session;
    final VodSourceFailover queue = new VodSourceFailover();
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private Future<?> resolving;
    private MediaPlayer candidate;
    private VLCVideoLayout candidateView;
    private VodSources.Resolved resolved;
    private int ticket, active;
    private boolean preparing, closed, playing;
    private long deadline, target, seekUntil, lastCatchup;
    private final Runnable check = this::check;

    VodPlayback(VlcPlaybackActivity activity, VodSources.Session session) {
        this.activity = activity;
        this.session = session;
        queue.setSources(session.sources);
        active = session.active;
        queue.select(active, session.pinned);
    }

    int activeIndex() {
        return active;
    }

    boolean preparing() {
        return preparing;
    }

    boolean hasChoices() {
        return session.sources.size() > 1;
    }

    void onPlaying() {
        queue.onPlaying();
    }

    void onBuffering(boolean buffering) {
        queue.setUserPaused(activity.paused);
        queue.setSeeking(
            activity.dragging || activity.skips.pending() || SystemClock.elapsedRealtime() < seekUntil
        );
        if (buffering) {
            if (queue.onBufferingStarted(SystemClock.elapsedRealtime())) failover();
        } else queue.onBufferingEnded();
    }

    void tick() {
        if (closed) return;
        boolean seeking =
            activity.dragging || activity.skips.pending() || SystemClock.elapsedRealtime() < seekUntil;
        queue.setSeeking(seeking);
        queue.setUserPaused(activity.paused);
        if ((seeking || activity.paused) && preparing) cancelPreparation();
        if (!preparing && queue.isStalled(SystemClock.elapsedRealtime())) failover();
    }

    void seeking() {
        seekUntil = SystemClock.elapsedRealtime() + 5000;
        queue.setSeeking(true);
        cancelPreparation();
    }

    void pauseChanged() {
        queue.setUserPaused(activity.paused);
        if (activity.paused) cancelPreparation();
    }

    boolean failover() {
        if (closed || !hasChoices()) return false;
        if (preparing) return true;
        if (queue.failover() == null) return false;
        prepare(queue.currentIndex());
        return true;
    }

    void select(int index) {
        cancelPreparation();
        if (!queue.select(index, true)) return;
        session.pinned = true;
        if (index == active) {
            if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
            return;
        }
        prepare(index);
    }

    void automatic() {
        cancelPreparation();
        session.pinned = false;
        queue.select(active, false);
        if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
    }

    private void prepare(int index) {
        preparing = true;
        playing = false;
        lastCatchup = 0;
        int request = ++ticket;
        target = activity.player == null ? 0 : Math.max(0, activity.player.getTime());
        deadline = SystemClock.elapsedRealtime() + 45000;
        activity.status.setText("Preparing " + session.sources.get(index).label + "…");
        activity.status.setVisibility(View.VISIBLE);
        activity.overlayHandler.postDelayed(check, 100);
        resolving = resolver.submit(() -> {
            try {
                VodSources.Resolved next = session.resolve.get(index);
                String link = Playlist.networkUrl(
                    next.url,
                    activity.getSharedPreferences("ui", 0).getBoolean("http", false)
                );
                activity.runOnUiThread(() -> {
                    if (closed || request != ticket || !activity.foreground) return;
                    resolved = new VodSources.Resolved(link, next.files);
                    try {
                        candidateView = new VLCVideoLayout(activity);
                        candidateView.setAlpha(0f);
                        activity.video.addView(candidateView, new FrameLayout.LayoutParams(-1, -1));
                        candidate = new MediaPlayer(activity.filmEngine());
                        // A TextureView allows the prepared picture to stay hidden until the cut, including API 28.
                        candidate.attachViews(candidateView, null, false, true);
                        candidate.setVolume(0);
                        candidate.setRate(activity.playbackRate);
                        if (activity.aspect != null) candidate.setAspectRatio(activity.aspect);
                        candidate.setEventListener(event -> {
                            if (closed || request != ticket || !preparing) return;
                            if (event.type == MediaPlayer.Event.Playing) playing = true;
                            else if (
                                event.type == MediaPlayer.Event.EncounteredError ||
                                event.type == MediaPlayer.Event.EndReached
                            ) failed();
                        });
                        Media media = new Media(activity.filmEngine(), Uri.parse(link));
                        try {
                            media.setHWDecoderEnabled(true, false);
                            if (target > 1000) media.addOption(":start-time=" + target / 1000);
                            candidate.setMedia(media);
                        } finally {
                            media.release();
                        }
                        candidate.play();
                    } catch (RuntimeException error) {
                        failed();
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!closed && request == ticket) failed();
                });
            }
        });
    }

    private void check() {
        if (closed || !preparing) return;
        if (!activity.foreground || activity.paused) {
            cancelPreparation();
            return;
        }
        if (candidate != null && playing && candidate.isPlaying()) {
            IMedia media = candidate.getMedia();
            boolean decoded = false;
            if (media != null) try {
                IMedia.Stats stats = media.getStats();
                decoded = stats != null && stats.decodedVideo > 0;
            } finally {
                media.release();
            }
            long oldTime = activity.player == null ? target : Math.max(target, activity.player.getTime());
            long nextTime = candidate.getTime();
            if (decoded && Math.abs(nextTime - oldTime) <= 2000) {
                MediaPlayer ready = candidate;
                VLCVideoLayout view = candidateView;
                String link = resolved.url;
                if (resolved.files != null) SourcesScreen.Binge.files = resolved.files;
                candidate = null;
                candidateView = null;
                preparing = false;
                active = queue.currentIndex();
                session.active = active;
                queue.onPlaying();
                activity.adoptVod(ready, view, link);
                return;
            }
            // The original stream may recover during lookup. Catch the replacement up before cutting.
            if (
                decoded &&
                nextTime < oldTime - 2000 &&
                candidate.isSeekable() &&
                SystemClock.elapsedRealtime() - lastCatchup >= 2000
            ) {
                lastCatchup = SystemClock.elapsedRealtime();
                candidate.setTime(oldTime);
            }
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            failed();
            return;
        }
        activity.overlayHandler.postDelayed(check, 100);
    }

    private void failed() {
        discardCandidate();
        preparing = false;
        ticket++;
        if (queue.failover() != null) {
            prepare(queue.currentIndex());
            return;
        }
        activity.status.setText("No working backup. The current source is still selected.");
        activity.status.setVisibility(View.VISIBLE);
    }

    private void discardCandidate() {
        activity.overlayHandler.removeCallbacks(check);
        if (resolving != null) resolving.cancel(true);
        resolving = null;
        if (candidate != null) {
            candidate.setEventListener(null);
            candidate.stop();
            candidate.detachViews();
            candidate.release();
            candidate = null;
        }
        if (candidateView != null && candidateView.getParent() instanceof ViewGroup) (
            (ViewGroup) candidateView.getParent()
        ).removeView(candidateView);
        candidateView = null;
        resolved = null;
    }

    private void cancelPreparation() {
        if (!preparing) return;
        ticket++;
        preparing = false;
        discardCandidate();
        queue.select(active, session.pinned);
        if (activity.player != null && activity.player.isPlaying()) queue.onPlaying();
    }

    void close() {
        closed = true;
        ticket++;
        preparing = false;
        discardCandidate();
        resolver.shutdownNow();
    }
}
