package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.SystemClock;

/** Checks rendered video after a real Activity handoff, not just LibVLC's clock. */
final class LiveHandoffEvidence {

    private final Instrumentation instrumentation;
    private final LiveVideoOutput output;
    private long frames;

    LiveHandoffEvidence(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        output = LivePlayer.output();
        assertNotNull("Live decoder output exists", output);
    }

    void awaitPicture(boolean handoff) {
        long deadline = SystemClock.elapsedRealtime() + 1500;
        boolean[] ready = { false };
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                assertSame("Handoff replaced decoder output", output, LivePlayer.output());
                assertTrue("Decoder Surface is still valid", output.surface.isValid());
                assertEquals("Handoff detached native video output", 0, output.surfaceDestructions);
                ready[0] =
                    output.frames > frames + 1 &&
                    output.view() != null &&
                    output.view().isShown() &&
                    !output.showingPreview();
            });
            if (!ready[0]) SystemClock.sleep(20);
        }
        assertTrue("Destination did not render fresh frames within 1.5 seconds", ready[0]);
        instrumentation.runOnMainSync(() -> {
            assertSame(
                "View uses the retained SurfaceTexture",
                output.texture,
                output.view().getSurfaceTexture()
            );
            Bitmap picture = output.view().getBitmap(96, 54);
            assertNotNull("Rendered picture can be read", picture);
            try {
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
                assertTrue("Rendered destination is black or a blank fill", brightest - darkest > 32);
            } finally {
                picture.recycle();
            }
            if (handoff) {
                assertEquals(
                    "Every handoff retained a visible frame",
                    output.handoffs,
                    output.coveredHandoffs
                );
                assertTrue(
                    "Handoff frame gap exceeded 1.5 seconds: " + output.lastHandoffGapMs,
                    output.lastHandoffGapMs <= 1500
                );
                android.util.Log.i(
                    "MovieHubHandoff",
                    "Fresh destination frame after " + output.lastHandoffGapMs + " ms"
                );
            }
            frames = output.frames;
        });
    }
}
