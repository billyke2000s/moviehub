package local.tv.home;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

/**
 * One decoder output for the whole live session. Activities own only its presentation.
 * Retain the SurfaceTexture when a TextureView leaves its window, then give it to the
 * destination view. LibVLC never loses its Surface during a guide/full-screen handoff.
 * Main thread only.
 */
final class LiveVideoOutput {

    final SurfaceTexture texture = new SurfaceTexture(false);
    final Surface surface = new Surface(texture);
    private final IVLCVout vout;
    private TextureView view;
    private FrameLayout holder;
    private ImageView preview;
    private Bitmap still;
    private boolean closed;
    private long transferStarted;
    private long transferTimestamp;
    private long timestamp;
    /** Actual texture updates, rather than player-clock or Playing events. */
    volatile long frames;
    volatile long lastHandoffGapMs;
    volatile int handoffs;
    volatile int coveredHandoffs;
    volatile int surfaceDestructions;

    LiveVideoOutput(MediaPlayer player) {
        vout = player.getVLCVout();
        vout.addCallback(
            new IVLCVout.Callback() {
                @Override
                public void onSurfacesCreated(IVLCVout output) {}

                @Override
                public void onSurfacesDestroyed(IVLCVout output) {
                    surfaceDestructions++;
                }
            }
        );
        vout.setVideoSurface(surface, null);
        // With no layout listener LibVLC composites video, aspect ratio and subtitles through
        // its GLES output. No Activity-owned SurfaceView or subtitle surface can disappear.
        vout.attachViews();
    }

    void show(FrameLayout destination) {
        if (closed || holder == destination) return;
        Bitmap nextStill = null;
        if (view != null && view.isAvailable() && frames > 0) {
            int width = Math.min(960, Math.max(1, view.getWidth()));
            int height = Math.max(1, (width * view.getHeight()) / Math.max(1, view.getWidth()));
            nextStill = view.getBitmap(width, height);
        }
        if (view != null) {
            transferStarted = SystemClock.elapsedRealtime();
            transferTimestamp = timestamp;
            handoffs++;
            // Removal synchronously detaches the old TextureView's GL consumer. Its callback
            // returns false: Android must not release the retained producer's BufferQueue.
            ((ViewGroup) view.getParent()).removeView(view);
        }
        clearPreview();
        holder = destination;
        view = new TextureView(destination.getContext());
        TextureView current = view;
        current.setSurfaceTexture(texture);
        current.setSurfaceTextureListener(
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                    resize(current, width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                    SurfaceTexture surfaceTexture,
                    int width,
                    int height
                ) {
                    resize(current, width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                    if (closed || view != current) return;
                    long updated = surfaceTexture.getTimestamp();
                    if (updated == timestamp) return;
                    timestamp = updated;
                    frames++;
                    if (transferStarted != 0 && updated != transferTimestamp) {
                        lastHandoffGapMs = SystemClock.elapsedRealtime() - transferStarted;
                        transferStarted = 0;
                    }
                    clearPreview();
                }
            }
        );
        destination.addView(current, 0, new FrameLayout.LayoutParams(-1, -1));
        if (nextStill != null) {
            coveredHandoffs++;
            still = nextStill;
            preview = new ImageView(destination.getContext());
            preview.setScaleType(ImageView.ScaleType.FIT_XY);
            preview.setImageBitmap(still);
            destination.addView(preview, 1, new FrameLayout.LayoutParams(-1, -1));
        }
        // setSurfaceTexture does not send onSurfaceTextureAvailable. Explicitly resize after
        // layout, including when reusing the same surface in a newly created Activity.
        current.post(() -> resize(current, current.getWidth(), current.getHeight()));
    }

    private void resize(TextureView current, int width, int height) {
        if (closed || view != current || width <= 0 || height <= 0) return;
        vout.setWindowSize(width, height);
    }

    boolean isShown() {
        return view != null && view.isShown();
    }

    TextureView view() {
        return view;
    }

    boolean showingPreview() {
        return preview != null;
    }

    private void clearPreview() {
        if (preview != null) {
            preview.setImageDrawable(null);
            if (preview.getParent() instanceof ViewGroup) {
                ((ViewGroup) preview.getParent()).removeView(preview);
            }
            preview = null;
        }
        // The renderer may still hold the previous display list for this frame. Drop our
        // reference instead of recycling a bitmap that could still be drawn by RenderThread.
        still = null;
    }

    void close() {
        closed = true;
        vout.detachViews();
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        clearPreview();
        view = null;
        holder = null;
        surface.release();
        texture.release();
    }
}
