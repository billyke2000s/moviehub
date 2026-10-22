package local.tv.home;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.function.LongSupplier;

/** Optional bounded still image during surface handoff; stale PixelCopy callbacks never move video. */
final class LiveHandoffCover {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LongSupplier pictures;
    private ImageView view;
    private Bitmap image;
    private int generation;
    private long shownAt, baseline;
    private boolean closed;
    private Runnable captureTimeout;
    private final Runnable check = this::check;

    LiveHandoffCover(LongSupplier pictures) {
        this.pictures = pictures;
    }

    void capture(SurfaceView surface, Runnable next) {
        clear();
        int ticket = generation;
        if (closed) return;
        if (
            surface == null ||
            !surface.getHolder().getSurface().isValid() ||
            surface.getWidth() <= 0 ||
            surface.getHeight() <= 0
        ) {
            next.run();
            return;
        }
        int width = Math.min(960, surface.getWidth());
        int height = Math.min(960, Math.max(1, (width * surface.getHeight()) / surface.getWidth()));
        Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        boolean[] finished = { false };
        Runnable finish = () -> {
            if (finished[0] || closed || generation != ticket) return;
            finished[0] = true;
            if (captureTimeout != null) main.removeCallbacks(captureTimeout);
            captureTimeout = null;
            next.run();
        };
        captureTimeout = finish;
        main.postDelayed(finish, 200);
        try {
            PixelCopy.request(
                surface,
                copy,
                result -> {
                    if (
                        result == PixelCopy.SUCCESS && !closed && generation == ticket && !finished[0]
                    ) image = copy;
                    // Do not recycle an asynchronously copied/drawn bitmap. It is bounded and collected normally.
                    finish.run();
                },
                main
            );
        } catch (RuntimeException error) {
            finish.run();
        }
    }

    void show(FrameLayout destination) {
        if (image == null || closed || destination == null) return;
        removeView();
        view = new ImageView(destination.getContext());
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageBitmap(image);
        destination.addView(view, new FrameLayout.LayoutParams(-1, -1));
        baseline = pictures.getAsLong();
        shownAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(check);
        main.postDelayed(check, 100);
    }

    private void check() {
        if (closed || view == null) return;
        long elapsed = SystemClock.elapsedRealtime() - shownAt;
        // VLC's hardware displayed counter is unreliable. Decoding plus a grace interval is best effort.
        if (elapsed >= 5000 || (elapsed >= 1500 && pictures.getAsLong() > baseline + 2)) clear();
        else main.postDelayed(check, 100);
    }

    private void removeView() {
        if (view != null) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view.setImageDrawable(null);
            view = null;
        }
    }

    void clear() {
        generation++;
        main.removeCallbacks(check);
        if (captureTimeout != null) main.removeCallbacks(captureTimeout);
        captureTimeout = null;
        removeView();
        image = null;
    }

    boolean showing() {
        return view != null;
    }

    void close() {
        closed = true;
        clear();
    }
}
