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

    void show(FrameLayout destination) {
        if (closed || holder == destination) return;
        if (layout != null) {
            // Only the picture's surface is released; media, network and audio are untouched.
            player.detachViews();
            if (layout.getParent() instanceof ViewGroup) ((ViewGroup) layout.getParent()).removeView(layout);
            handoffs++;
            // Read the count before marking the move, so this read is not taken as the
            // first new picture.
            transferStarted = 0;
            transferPictures = Math.max(0, pictures());
            lastHandoffGapMs = -1;
            transferStarted = SystemClock.elapsedRealtime();
        }
        holder = destination;
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
        if (closed || holder == null || !isShown()) return false;
        int track = player.getVideoTrack();
        if (track < 0) return false;
        // Restart only the video decoder and output, keeping the surface, the media, the
        // network and the sound. Swapping the surface in place raced the old surface's teardown
        // and often left no picture (CI, 21 October 2026).
        repairs++;
        player.setVideoTrack(-1);
        holder.post(() -> {
            if (!closed) player.setVideoTrack(track);
        });
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
        if (layout != null) player.detachViews();
        if (layout != null && layout.getParent() instanceof ViewGroup) {
            ((ViewGroup) layout.getParent()).removeView(layout);
        }
        layout = null;
        holder = null;
    }
}
