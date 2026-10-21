package local.tv.home;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    /** If VLC does not report its video output closed, carry on after this long. */
    private static final long CLOSE_WAIT_MS = 600;
    /** If the new surface does not report a size, switch the picture on after this long. */
    private static final long SURFACE_WAIT_MS = 3000;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable closeTimeout = this::finishMove;

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
        captureThen(this::closeVideoThen);
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
        main.removeCallbacks(closeTimeout);
        if (pendingTrack >= 0 && vouts > 0) {
            player.setVideoTrack(-1);
            main.postDelayed(closeTimeout, CLOSE_WAIT_MS);
        } else finishMove();
    }

    /**
     * A new stream is about to open (channel or source change). Finish any move now without
     * switching the old stream's video track back on: VLC picks the new stream's own tracks.
     */
    void streamChanging() {
        waitingTrack = NO_TRACK;
        // A different stream: the held picture belongs to the old one.
        main.removeCallbacks(coverCheck);
        removeCover(false);
        if (pendingTrack == NO_TRACK) return;
        pendingTrack = -1;
        finishMove();
    }

    /** LivePlayer passes on VLC's Vout events: how many video outputs are open. */
    void videoOutputs(int count) {
        vouts = count;
        if (count == 0 && pendingTrack != NO_TRACK) {
            main.removeCallbacks(closeTimeout);
            main.post(this::finishMove);
        }
    }

    private void finishMove() {
        main.removeCallbacks(closeTimeout);
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
        showCover(holder);
        int track = pendingTrack;
        pendingTrack = NO_TRACK;
        if (track >= 0) whenSurfaceReady(track);
        else removeCover(true);
    }

    /**
     * Switches the video track back on once the layout's surface exists and has a size. Right
     * after a move into a window that is still opening it does not yet; switching on then let VLC
     * decode one frame and stall (CI, 21 October 2026). Falls back after SURFACE_WAIT_MS.
     */
    private void whenSurfaceReady(int track) {
        VLCVideoLayout target = layout;
        SurfaceView surface = surfaceView();
        boolean[] done = { false };
        waitingTrack = track;
        Runnable enable = () -> {
            if (done[0] || closed || layout != target || waitingTrack != track) return;
            done[0] = true;
            waitingTrack = NO_TRACK;
            player.setVideoTrack(track);
            watchForPicture();
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
        holder.addCallback(ready);
        main.postDelayed(() -> {
            holder.removeCallback(ready);
            enable.run();
        }, SURFACE_WAIT_MS);
    }

    private void attach(FrameLayout destination) {
        destination.removeAllViews();
        layout = new VLCVideoLayout(destination.getContext());
        destination.addView(layout, 0, new FrameLayout.LayoutParams(-1, -1));
        // As VlcPlaybackActivity attaches films: no subtitle surface, no TextureView.
        player.attachViews(layout, null, false, false);
    }

    // ---- Still picture over the move ------------------------------------------------------------
    // After a move the decoder waits for the stream's next keyframe, so the new surface is black
    // for a second or two. Hold the last picture there instead and fade it out when live video
    // returns. PixelCopy cannot read some hardware decoder surfaces (it reports no data on the CI
    // emulator); then there is no still and the move shows black as before.

    private Bitmap still;
    private ImageView cover;
    private long coverShown, coverDecoded, coverDisplayed;
    private static final long CAPTURE_WAIT_MS = 250,
        COVER_MAX_MS = 5000,
        DECODED_GRACE_MS = 1500;
    private final Runnable coverCheck = this::checkCover;

    /** Copies the picture on screen (best effort, at most CAPTURE_WAIT_MS), then runs next. */
    private void captureThen(Runnable next) {
        SurfaceView surface = surfaceView();
        boolean[] ran = { false };
        Runnable once = () -> {
            if (ran[0]) return;
            ran[0] = true;
            next.run();
        };
        if (still != null || surface == null || !surface.getHolder().getSurface().isValid()) {
            once.run();
            return;
        }
        int width = Math.min(960, Math.max(1, surface.getWidth()));
        int height = Math.max(
            1,
            (width * Math.max(1, surface.getHeight())) / Math.max(1, surface.getWidth())
        );
        Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(
                surface,
                copy,
                result -> {
                    if (result == PixelCopy.SUCCESS && !closed && !ran[0]) {
                        still = copy;
                        // Cover the old place too while VLC closes its video output.
                        if (layout != null && layout.getParent() instanceof FrameLayout) showCover(
                            (FrameLayout) layout.getParent()
                        );
                    } else if (still != copy) copy.recycle();
                    once.run();
                },
                main
            );
        } catch (RuntimeException e) {
            copy.recycle();
            once.run();
            return;
        }
        main.postDelayed(once, CAPTURE_WAIT_MS);
    }

    private void showCover(FrameLayout host) {
        if (still == null || host == null || closed) return;
        if (cover != null && cover.getParent() instanceof ViewGroup) (
            (ViewGroup) cover.getParent()
        ).removeView(cover);
        cover = new ImageView(host.getContext());
        // Letterboxed like the video itself, not stretched to the box.
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setImageBitmap(still);
        host.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        coverShown = SystemClock.elapsedRealtime();
    }

    /** The track is back on: remove the still once a new picture is shown (or after a while). */
    private void watchForPicture() {
        if (still == null) return;
        long[] counts = counts();
        coverDecoded = counts[0];
        coverDisplayed = counts[1];
        coverShown = SystemClock.elapsedRealtime();
        main.removeCallbacks(coverCheck);
        main.postDelayed(coverCheck, 100);
    }

    private void checkCover() {
        if (still == null || closed) return;
        long[] counts = counts();
        long waited = SystemClock.elapsedRealtime() - coverShown;
        boolean displayed = counts[1] > coverDisplayed;
        // Decoded pictures are shown slightly later than they are decoded; allow for that when
        // VLC's displayed count does not move (hardware decoding).
        boolean decodedLongEnough = counts[0] > coverDecoded && waited >= DECODED_GRACE_MS;
        if (displayed || decodedLongEnough || waited >= COVER_MAX_MS) removeCover(true);
        else main.postDelayed(coverCheck, 100);
    }

    private void removeCover(boolean fade) {
        ImageView gone = cover;
        Bitmap image = still;
        cover = null;
        still = null;
        if (gone == null) {
            if (image != null) image.recycle();
            return;
        }
        Runnable drop = () -> {
            if (gone.getParent() instanceof ViewGroup) ((ViewGroup) gone.getParent()).removeView(gone);
            gone.setImageDrawable(null);
        };
        if (fade) gone.animate().alpha(0f).setDuration(200).withEndAction(drop).start();
        else drop.run();
    }

    /** VLC's raw decoded and displayed counts for the current stream ({-1, -1} if unknown). */
    private long[] counts() {
        IMedia media = player.getMedia();
        if (media == null) return new long[] { -1, -1 };
        try {
            IMedia.Stats stats = media.getStats();
            return stats == null
                ? new long[] { -1, -1 }
                : new long[] { stats.decodedVideo, stats.displayedPictures };
        } catch (RuntimeException e) {
            return new long[] { -1, -1 };
        } finally {
            media.release();
        }
    }

    /** A still picture is covering a move, for tests. */
    boolean showingCover() {
        return cover != null;
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
            if (shown >= 0 && transferStarted != 0 && shown > transferPictures) {
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
        captureThen(this::closeVideoThen);
        return true;
    }

    boolean isShown() {
        return layout != null && layout.isShown();
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

    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(closeTimeout);
        main.removeCallbacks(coverCheck);
        pendingDestination = null;
        pendingTrack = NO_TRACK;
        removeCover(false);
        if (layout != null) player.detachViews();
        if (layout != null && layout.getParent() instanceof ViewGroup) {
            ((ViewGroup) layout.getParent()).removeView(layout);
        }
        layout = null;
        holder = null;
    }
}
