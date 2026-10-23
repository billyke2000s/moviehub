package local.tv.home;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;

/** One tiny asynchronous PixelCopy every two seconds; no captured content is retained or logged. */
final class LiveSurfaceProbe {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LivePresentation state = new LivePresentation();
    private long sampledAt = -1;
    private int generation;
    private boolean pending, repaired;
    private String status = "Not sampled";

    void sample(SurfaceView surface, long now, boolean watching) {
        if (!watching) {
            reset();
            return;
        }
        if (pending || (sampledAt >= 0 && now - sampledAt < 2000)) return;
        sampledAt = now;
        if (surface == null || !surface.isShown() || !surface.getHolder().getSurface().isValid()) {
            state.result(now, false);
            status = "Video surface unavailable";
            return;
        }
        int ticket = generation;
        pending = true;
        Bitmap sample = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(
                surface,
                sample,
                result -> {
                    pending = false;
                    if (ticket != generation) return;
                    // ERROR_TIMEOUT/UNKNOWN alone cannot establish that video output is broken.
                    if (result == PixelCopy.SUCCESS) {
                        state.result(android.os.SystemClock.elapsedRealtime(), true);
                        status = "Rendered buffer available";
                    } else if (
                        result == PixelCopy.ERROR_SOURCE_NO_DATA || result == PixelCopy.ERROR_SOURCE_INVALID
                    ) {
                        state.result(android.os.SystemClock.elapsedRealtime(), false);
                        status = "Rendered buffer missing";
                    }
                },
                main
            );
        } catch (RuntimeException error) {
            pending = false;
            status = "Surface probe unavailable";
        }
    }

    boolean repairAllowed() {
        return !repaired;
    }

    void repaired() {
        repaired = true;
        reset();
    }

    boolean unavailable() {
        return repaired || state.unavailable();
    }

    void newStream() {
        repaired = false;
        reset();
    }

    boolean missing(long now) {
        return state.missing(now, 8000);
    }

    String status() {
        return status;
    }

    void reset() {
        generation++;
        state.reset();
        sampledAt = -1;
    }
}
