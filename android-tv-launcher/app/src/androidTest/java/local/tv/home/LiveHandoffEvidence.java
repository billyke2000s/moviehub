package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Checks the picture on screen after a real Activity handoff, not just LibVLC's clock. */
final class LiveHandoffEvidence {

    /** The accepted blink while the picture moves to its new surface. */
    static final long HANDOFF_LIMIT_MS = 2000;
    private final Instrumentation instrumentation;
    private final LiveVideoOutput output;
    private long pictures;

    LiveHandoffEvidence(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        output = LivePlayer.output();
        assertNotNull("Live video output exists", output);
    }

    void awaitPicture(boolean handoff) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        boolean[] ready = { false };
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                assertSame("Handoff replaced the video output", output, LivePlayer.output());
                ready[0] = output.pictures() > pictures + 1 && output.isShown();
            });
            if (!ready[0]) SystemClock.sleep(20);
        }
        assertTrue("Destination did not show fresh pictures within 8 seconds", ready[0]);
        if (handoff) {
            long gap = output.lastHandoffGapMs;
            android.util.Log.i("MovieHubHandoff", "Fresh picture after " + gap + " ms");
            assertTrue(
                "Picture took " + gap + " ms to return after the move (limit " + HANDOFF_LIMIT_MS + " ms)",
                gap >= 0 && gap <= HANDOFF_LIMIT_MS
            );
        }
        assertPictureNotBlank();
        instrumentation.runOnMainSync(() -> pictures = output.pictures());
    }

    /** Reads the SurfaceView's actual pixels (PixelCopy), which are not part of the view tree. */
    private void assertPictureNotBlank() {
        SurfaceView[] surface = { null };
        instrumentation.runOnMainSync(() -> surface[0] = output.surfaceView());
        assertNotNull("The video layout has a surface", surface[0]);
        int width = Math.max(1, Math.min(96, surface[0].getWidth()));
        int height = Math.max(1, Math.min(54, surface[0].getHeight()));
        Bitmap picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        HandlerThread thread = new HandlerThread("pixel-copy");
        thread.start();
        try {
            CountDownLatch done = new CountDownLatch(1);
            int[] result = { -1 };
            PixelCopy.request(
                surface[0],
                picture,
                code -> {
                    result[0] = code;
                    done.countDown();
                },
                new Handler(thread.getLooper())
            );
            assertTrue("Picture copy timed out", done.await(5, TimeUnit.SECONDS));
            assertEquals("The picture could be read", PixelCopy.SUCCESS, result[0]);
            int brightest = 0;
            int darkest = 255;
            for (int y = 0; y < picture.getHeight(); y++) {
                for (int x = 0; x < picture.getWidth(); x++) {
                    int pixel = picture.getPixel(x, y);
                    int light = Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255));
                    brightest = Math.max(brightest, light);
                    darkest = Math.min(darkest, light);
                }
            }
            assertTrue("The picture on screen is black or a blank fill", brightest - darkest > 32);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            picture.recycle();
            thread.quitSafely();
        }
    }
}
