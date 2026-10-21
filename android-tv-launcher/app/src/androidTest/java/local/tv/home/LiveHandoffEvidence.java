package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.SurfaceView;

/** Checks the picture on screen after a real Activity handoff, not just LibVLC's clock. */
final class LiveHandoffEvidence {

    /**
     * The accepted blink while the picture moves: VLC closes its video output, the surface
     * changes, and decoding restarts at the next keyframe (1 s apart in the fixture).
     */
    static final long HANDOFF_LIMIT_MS = 3000;
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
                // After a move, wait for the first picture drawn on the new surface itself, not
                // pictures counted before the move.
                ready[0] =
                    output.pictures() > pictures + 1 &&
                    output.isShown() &&
                    (!handoff || output.lastHandoffGapMs >= 0);
            });
            if (!ready[0]) SystemClock.sleep(20);
        }
        String[] state = { "" };
        instrumentation.runOnMainSync(() -> {
            org.videolan.libvlc.MediaPlayer player = LivePlayer.player();
            state[0] =
                "pictures " +
                output.pictures() +
                " (before " +
                pictures +
                "), shown " +
                output.isShown() +
                ", gap " +
                output.lastHandoffGapMs +
                " ms, moves " +
                output.handoffs +
                ", playing " +
                (player != null && player.isPlaying()) +
                ", video tracks " +
                (player == null ? -1 : player.getVideoTracksCount());
        });
        assertTrue("Destination did not show fresh pictures within 8 seconds: " + state[0], ready[0]);
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

    /**
     * Reads what is actually on screen where the video surface is. A screenshot includes the
     * SurfaceView's layer; PixelCopy cannot read a hardware decoder's surface (SOURCE_NO_DATA).
     */
    private void assertPictureNotBlank() {
        int[] box = new int[4];
        instrumentation.runOnMainSync(() -> {
            SurfaceView surface = output.surfaceView();
            assertNotNull("The video layout has a surface", surface);
            int[] at = new int[2];
            surface.getLocationOnScreen(at);
            box[0] = at[0];
            box[1] = at[1];
            box[2] = surface.getWidth();
            box[3] = surface.getHeight();
        });
        assertTrue("The video surface has a size", box[2] > 0 && box[3] > 0);
        Bitmap shot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot taken", shot);
        // Some Android versions return a hardware bitmap, whose pixels cannot be read directly.
        Bitmap screen =
            shot.getConfig() == Bitmap.Config.HARDWARE ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
        if (screen != shot) shot.recycle();
        try {
            int brightest = 0;
            int darkest = 255;
            for (int row = 1; row < 10; row++) {
                for (int column = 1; column < 10; column++) {
                    int x = Math.min(screen.getWidth() - 1, box[0] + (box[2] * column) / 10);
                    int y = Math.min(screen.getHeight() - 1, box[1] + (box[3] * row) / 10);
                    int pixel = screen.getPixel(Math.max(0, x), Math.max(0, y));
                    int light = Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255));
                    brightest = Math.max(brightest, light);
                    darkest = Math.min(darkest, light);
                }
            }
            assertTrue("The picture on screen is black or a blank fill", brightest - darkest > 32);
        } finally {
            screen.recycle();
        }
    }
}
