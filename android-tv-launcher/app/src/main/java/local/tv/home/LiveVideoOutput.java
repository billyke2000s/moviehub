package local.tv.home;

import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * Where the one live player draws its picture: VLC's standard video layout (a SurfaceView), the
 * same way the film player and most Android video apps draw. The hardware decoder hands pictures
 * to the screen directly, so the picture no longer depends on the app's main thread redrawing.
 *
 * <p>Moving between the guide's mini picture and the full screen detaches the picture from one
 * layout and attaches it to the next. Only the screen surface changes: the player, its media,
 * the network connection and the sound carry on, so the link is never reopened. The picture may
 * blink while the new surface takes over (accepted by the owner, 21 October 2026).
 *
 * <p>This replaced a shared TextureView/SurfaceTexture, whose picture could stutter or stop while
 * the sound played on, because each frame waited for the main thread to redraw. Main thread only.
 */
final class LiveVideoOutput {

    private final MediaPlayer player;
    private final LiveHandoffCover cover = new LiveHandoffCover(this::pictures);
    private VLCVideoLayout layout;
    private FrameLayout holder;
    private boolean closed;
    /** Moves between layouts (guide and full screen), for tests and diagnostics. */
    volatile int handoffs;
    /** Video restarts by the freeze watchdog (refreshView), for tests and diagnostics. */
    volatile int repairs;
    /** How long the last move took to show a new picture, or -1 while waiting. */
    volatile long lastHandoffGapMs = -1;
    private long transferStarted;
    private long transferPictures;
    /** VLC's count restarts with each stream (a source switch); these keep ours rising. */
    private long countedBefore;
    private long lastCount;

    LiveVideoOutput(MediaPlayer player) {
        this.player = player;
    }

    /** VLC's video outputs now open (MediaPlayer Vout events, via LivePlayer). */
    private int vouts;
    /** A move waiting for VLC to close its video output, or null. */
    private FrameLayout pendingDestination;
    /** The video track switched off for a move or repair, or NO_TRACK when none is. */
    private int pendingTrack = NO_TRACK;
    /** The video track waiting for a new surface to be ready, or NO_TRACK. */
    private int waitingTrack = NO_TRACK;
    private static final int NO_TRACK = -2;
    /** If video shutdown is not acknowledged, stop the native reader before moving its window. */
    private static final long CLOSE_WAIT_MS = 600;
    /** If the new surface does not report a size, switch the picture on after this long. */
    private static final long SURFACE_WAIT_MS = 3000;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean restartAfterMove;
    private int pendingAudioTrack = NO_TRACK;
    private final Runnable closeTimeout = this::stopBeforeMove;
    private final Runnable deferredFinish = this::finishMove;
    private Runnable pendingSurfaceTimeout;
    private android.view.SurfaceHolder pendingSurfaceHolder;
    private android.view.SurfaceHolder.Callback pendingSurfaceCallback;

    private void cancelSurfaceWait() {
        if (pendingSurfaceTimeout != null) main.removeCallbacks(pendingSurfaceTimeout);
        if (
            pendingSurfaceHolder != null && pendingSurfaceCallback != null
        ) pendingSurfaceHolder.removeCallback(pendingSurfaceCallback);
        pendingSurfaceTimeout = null;
        pendingSurfaceHolder = null;
        pendingSurfaceCallback = null;
    }

    void show(FrameLayout destination) {
        if (closed || holder == destination) return;
        holder = destination;
        if (layout == null && pendingDestination == null) {
            attach(destination);
            return;
        }
        handoffs++;
        // Read the count before marking the move, so this read is not taken as the first
        // new picture.
        transferStarted = 0;
        transferPictures = Math.max(0, pictures());
        lastHandoffGapMs = -1;
        transferStarted = SystemClock.elapsedRealtime();
        pendingDestination = destination;
        // PixelCopy is optional and asynchronous. Never delay closing the native video output:
        // the old Activity may lose its surface while that copy is pending.
        cover.capture(surfaceView(), () -> {
            if (pendingTrack == NO_TRACK) cover.show(holder);
        });
        closeVideoThen();
    }

    /**
     * As VLC's own Android app does around surface changes: switch the video track off, wait for
     * VLC to close its video output, then change surfaces and switch the track back on. Changing
     * the surface under a running video output crashed VLC's window thread (AWindowHandler,
     * SIGSEGV) or left decoding stopped in CI (21 October 2026). Media, network and sound carry on.
     */
    private void closeVideoThen() {
        if (pendingTrack == NO_TRACK) {
            // A track still waiting for the last move's surface is the one to restore.
            pendingTrack = waitingTrack != NO_TRACK ? waitingTrack : player.getVideoTrack();
            waitingTrack = NO_TRACK;
        }
        cancelSurfaceWait();
        main.removeCallbacks(closeTimeout);
        // Vout can still be zero while AWindowHandler is creating its EGL window. Always
        // request shutdown and wait for its acknowledgement before releasing the old surface.
        player.setVideoTrack(-1);
        main.postDelayed(closeTimeout, CLOSE_WAIT_MS);
    }

    /** Never destroy a native window merely because its shutdown acknowledgement is late. */
    private void stopBeforeMove() {
        if (closed || pendingTrack == NO_TRACK) return;
        pendingAudioTrack = player.getAudioTrack();
        player.stop();
        vouts = 0;
        restartAfterMove = true;
        finishMove();
    }

    /**
     * A new stream is about to open (channel or source change). Finish any move now without
     * switching the old stream's video track back on: VLC picks the new stream's own tracks.
     */
    void streamChanging() {
        pendingAudioTrack = NO_TRACK;
        restartAfterMove = false;
        cover.clear();
        cancelSurfaceWait();
        waitingTrack = NO_TRACK;
        if (pendingTrack == NO_TRACK) return;
        pendingTrack = -1;
        finishMove();
    }

    /** Restore the user's audio selection once a forced restart has rediscovered its tracks. */
    void restoreAudioTrack() {
        if (closed || pendingAudioTrack == NO_TRACK || restartAfterMove || !player.isPlaying()) return;
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
        if (tracks == null) return;
        for (MediaPlayer.TrackDescription track : tracks) {
            if (track.id != pendingAudioTrack) continue;
            if (player.setAudioTrack(pendingAudioTrack)) pendingAudioTrack = NO_TRACK;
            return;
        }
    }

    /** LivePlayer passes on VLC's Vout events: how many video outputs are open. */
    void videoOutputs(int count) {
        vouts = count;
        if (count == 0 && pendingTrack != NO_TRACK) {
            main.removeCallbacks(closeTimeout);
            main.post(deferredFinish);
        }
    }

    private void finishMove() {
        main.removeCallbacks(closeTimeout);
        main.removeCallbacks(deferredFinish);
        if (closed || pendingTrack == NO_TRACK) return;
        FrameLayout destination = pendingDestination;
        pendingDestination = null;
        if (destination != null) {
            if (layout != null) {
                player.detachViews();
                if (layout.getParent() instanceof ViewGroup) ((ViewGroup) layout.getParent()).removeView(
                    layout
                );
                layout = null;
            }
            attach(destination);
        }
        // Frames decoded while closing the old output do not establish a destination picture.
        transferPictures = Math.max(0, pictures());
        cover.show(holder);
        int track = pendingTrack;
        pendingTrack = NO_TRACK;
        if (track >= 0 || restartAfterMove) whenSurfaceReady(track);
    }

    /**
     * Switches the video track back on once the layout's surface exists and has a size. Right
     * after a move into a window that is still opening it does not yet; switching on then let VLC
     * decode one frame and stall (CI, 21 October 2026). Falls back after SURFACE_WAIT_MS.
     */
    private void whenSurfaceReady(int track) {
        cancelSurfaceWait();
        VLCVideoLayout target = layout;
        SurfaceView surface = surfaceView();
        boolean[] done = { false };
        waitingTrack = track;
        Runnable enable = () -> {
            if (done[0] || closed || layout != target || waitingTrack != track) return;
            done[0] = true;
            waitingTrack = NO_TRACK;
            cancelSurfaceWait();
            if (restartAfterMove) {
                restartAfterMove = false;
                player.play();
            } else player.setVideoTrack(track);
        };
        if (surface == null) {
            enable.run();
            return;
        }
        android.view.SurfaceHolder holder = surface.getHolder();
        android.graphics.Rect frame = holder.getSurfaceFrame();
        if (holder.getSurface().isValid() && frame.width() > 0 && frame.height() > 0) {
            enable.run();
            return;
        }
        android.view.SurfaceHolder.Callback ready = new android.view.SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(android.view.SurfaceHolder created) {}

            @Override
            public void surfaceChanged(
                android.view.SurfaceHolder changed,
                int format,
                int width,
                int height
            ) {
                if (width <= 0 || height <= 0) return;
                changed.removeCallback(this);
                main.post(enable);
            }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder destroyed) {}
        };
        pendingSurfaceHolder = holder;
        pendingSurfaceCallback = ready;
        holder.addCallback(ready);
        pendingSurfaceTimeout = () -> {
            cancelSurfaceWait();
            enable.run();
        };
        main.postDelayed(pendingSurfaceTimeout, SURFACE_WAIT_MS);
    }

    private void attach(FrameLayout destination) {
        destination.removeAllViews();
        layout = new VLCVideoLayout(destination.getContext());
        destination.addView(layout, 0, new FrameLayout.LayoutParams(-1, -1));
        // As VlcPlaybackActivity attaches films: no subtitle surface, no TextureView.
        player.attachViews(layout, null, false, false);
    }

    /**
     * Video frames decoded in this session, across source switches, or -1 while the current
     * stream has decoded none yet. A stopped picture over playing sound shows as no change.
     */
    long pictures() {
        IMedia media = player.getMedia();
        if (media == null) return -1;
        try {
            IMedia.Stats stats = media.getStats();
            // Decoded video frames: VLC's displayed-picture count barely moves when the hardware
            // decoder draws straight to the surface (CI, 21 October 2026). Zero until the first
            // frame; treated as unknown so the stall check falls back to the playback clock.
            long count = stats == null || stats.decodedVideo <= 0 ? -1 : stats.decodedVideo;
            if (count < 0) return -1;
            if (count < lastCount) countedBefore += lastCount;
            lastCount = count;
            long shown = countedBefore + count;
            if (
                transferStarted != 0 &&
                shown > transferPictures &&
                pendingDestination == null &&
                pendingTrack == NO_TRACK &&
                waitingTrack == NO_TRACK &&
                isShown()
            ) {
                lastHandoffGapMs = SystemClock.elapsedRealtime() - transferStarted;
                transferStarted = 0;
            }
            return shown;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            media.release();
        }
    }

    /** The freeze watchdog's repair: restart the picture without touching the stream or sound. */
    boolean refreshView() {
        if (
            closed || holder == null || !isShown() || pendingTrack != NO_TRACK || waitingTrack != NO_TRACK
        ) return false;
        if (player.getVideoTrack() < 0) return false;
        // Restart only the video decoder and output on the same surface (no destination), the
        // same off-wait-on sequence as a move. Media, network and sound are untouched.
        repairs++;
        // A video-track toggle alone can leave VLC bound to a valid but black SurfaceView.
        // Recreate and reattach the output exactly as a successful full-screen/guide handoff
        // does, without stopping the MediaPlayer or reopening the network stream.
        pendingDestination = holder;
        closeVideoThen();
        return true;
    }

    boolean isShown() {
        SurfaceView surface = surfaceView();
        return (
            !closed &&
            layout != null &&
            layout.isShown() &&
            pendingDestination == null &&
            pendingTrack == NO_TRACK &&
            waitingTrack == NO_TRACK &&
            surface != null &&
            surface.isShown() &&
            surface.getWidth() > 0 &&
            surface.getHeight() > 0 &&
            surface.getHolder().getSurface().isValid()
        );
    }

    /** The layout's video surface (for tests that read its picture), or null. */
    SurfaceView surfaceView() {
        return layout == null ? null : findSurface(layout);
    }

    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            SurfaceView found = findSurface(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Cancel callbacks while keeping the native window alive until MediaPlayer.stop returns. */
    void stopping() {
        if (closed) return;
        closed = true;
        pendingAudioTrack = NO_TRACK;
        restartAfterMove = false;
        cover.close();
        main.removeCallbacks(deferredFinish);
        cancelSurfaceWait();
        main.removeCallbacks(closeTimeout);
        // whenSurfaceReady also schedules a delayed enable callback. Its closed guard
        // prevents it from accessing the native player after this output is released.
        pendingDestination = null;
        pendingTrack = NO_TRACK;
    }

    void close() {
        stopping();
        if (layout != null) player.detachViews();
        if (layout != null && layout.getParent() instanceof ViewGroup) {
            ((ViewGroup) layout.getParent()).removeView(layout);
        }
        layout = null;
        holder = null;
    }
}
