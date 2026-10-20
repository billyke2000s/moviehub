package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;

/** Real BufferQueue/EGL regression: different pictures can carry identical presentation times. */
@RunWith(AndroidJUnit4.class)
public final class LiveVideoOutputTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private void awaitFrames(LiveVideoOutput output, long after) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (output.frames <= after && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue("Repeated timestamp hid a rendered buffer", output.frames > after);
    }

    @Test
    public void repeatedEglTimestampsDoNotLeaveAStillFrameOverTheVideo() throws Exception {
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        );
        LibVLC[] engine = new LibVLC[1];
        MediaPlayer[] player = new MediaPlayer[1];
        LiveVideoOutput[] state = new LiveVideoOutput[1];
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface producer = EGL14.EGL_NO_SURFACE;
        try {
            instrumentation.runOnMainSync(() -> {
                FrameLayout host = new FrameLayout(activity);
                activity.setContentView(host);
                engine[0] = new LibVLC(activity.getApplicationContext(), new ArrayList<>());
                player[0] = new MediaPlayer(engine[0]);
                state[0] = new LiveVideoOutput(player[0]);
                state[0].show(host);
            });
            LiveVideoOutput output = state[0];
            int[] versions = new int[2];
            assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1));
            int[] attributes = {
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0));
            assertTrue(count[0] > 0);
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE },
                0
            );
            producer = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output.surface,
                new int[] { EGL14.EGL_NONE },
                0
            );
            assertTrue(EGL14.eglMakeCurrent(display, producer, producer, context));
            for (int picture = 0; picture < 3; picture++) {
                long frames = output.frames;
                GLES20.glClearColor(1, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                assertTrue(EGLExt.eglPresentationTimeANDROID(display, producer, 0));
                assertTrue(EGL14.eglSwapBuffers(display, producer));
                awaitFrames(output, frames);
            }
            instrumentation.runOnMainSync(() -> {
                assertEquals(0, output.texture.getTimestamp());
                assertTrue(output.refreshView());
            });
            long frames = output.frames;
            GLES20.glClearColor(0, 1, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            assertTrue(EGLExt.eglPresentationTimeANDROID(display, producer, 0));
            assertTrue(EGL14.eglSwapBuffers(display, producer));
            awaitFrames(output, frames);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertFalse("Handoff still covers a valid zero-timestamp picture", output.showingPreview());
                Bitmap rendered = output.view().getBitmap(4, 4);
                assertNotNull(rendered);
                try {
                    int pixel = rendered.getPixel(2, 2);
                    assertTrue("The new green picture was not visible", ((pixel >> 8) & 255) > 200);
                    assertTrue("The old red cover remained", ((pixel >> 16) & 255) < 30);
                } finally {
                    rendered.recycle();
                }
                assertEquals("Producer was detached during a view refresh", 0, output.surfaceDestructions);
            });
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (producer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, producer);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            instrumentation.runOnMainSync(() -> {
                if (state[0] != null) state[0].close();
                if (player[0] != null) player[0].release();
                if (engine[0] != null) engine[0].release();
                activity.finish();
            });
        }
    }
}
