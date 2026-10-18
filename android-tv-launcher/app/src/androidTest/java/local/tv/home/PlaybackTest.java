package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Exercises the real LibVLC engine using generated video served on device loopback. */
@RunWith(AndroidJUnit4.class)
public class PlaybackTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private byte[] transportStream;
    private VlcPlaybackActivity activity;
    private ServerSocket server;
    private volatile boolean closed;
    private boolean oldHttp;

    private MediaPlayer player() {
        return activity.player;
    }

    private void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        assertTrue("Playback condition timed out", condition.getAsBoolean());
    }

    /** A player control by its description (the controls are icons, not text buttons). */
    private View find(View view, String label) {
        if (
            view.isClickable() && label.contentEquals(String.valueOf(view.getContentDescription()))
        ) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void click(String label) {
        instrumentation.runOnMainSync(() -> {
            View button = find(activity.getWindow().getDecorView(), label);
            assertNotNull(label, button);
            button.performClick();
        });
    }

    @Before
    public void prepare() throws Exception {
        Context context = instrumentation.getTargetContext();
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        byte[] data;
        try (
            InputStream input = instrumentation.getContext().getAssets().open("playback.mp4");
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            data = output.toByteArray();
        }
        try (
            InputStream input = instrumentation.getContext().getAssets().open("playback.ts");
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            transportStream = output.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> serve(socket, data), "fixture-response").start();
                } catch (IOException ignored) {}
            }
        }, "fixture-server").start();
        Intent intent = new Intent(context, VlcPlaybackActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/playback.mp4")
            .putExtra("id", "playback-test")
            .putExtra("title", "Generated playback test");
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(intent);
    }

    private void serve(Socket socket, byte[] data) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)
            );
            String request = reader.readLine(),
                line;
            int start = 0;
            boolean range = false;
            while ((line = reader.readLine()) != null && !line.isEmpty())
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range: bytes=")) {
                    String value = line.substring(line.indexOf('=') + 1).split("-", 2)[0];
                    if (!value.isEmpty()) {
                        start = Integer.parseInt(value);
                        range = true;
                    }
                }
            String mime = "video/mp4";
            if (request != null && request.contains(".m3u8")) {
                data = (
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:31\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30.0,\nhttp://127.0.0.1:" +
                    server.getLocalPort() +
                    "/segment.ts\n#EXT-X-ENDLIST\n"
                ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                mime = "application/vnd.apple.mpegurl";
            } else if (request != null && request.contains(".ts")) {
                data = transportStream;
                mime = "video/mp2t";
            }
            start = Math.max(0, Math.min(data.length - 1, start));
            OutputStream output = connection.getOutputStream();
            String headers =
                "HTTP/1.1 " +
                (range ? "206 Partial Content" : "200 OK") +
                "\r\nContent-Type: " +
                mime +
                "\r\nAccept-Ranges: bytes\r\nContent-Length: " +
                (data.length - start) +
                "\r\n" +
                (range
                    ? "Content-Range: bytes " + start + "-" + (data.length - 1) + "/" + data.length + "\r\n"
                    : "") +
                "Connection: close\r\n\r\n";
            output.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (request != null && !request.startsWith("HEAD")) output.write(
                data,
                start,
                data.length - start
            );
            output.flush();
        } catch (Exception ignored) {}
    }

    @Test
    public void playsSeeksAndHonoursMediaKeysAfterControlsHide() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying() && player().getTime() > 500;
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        MediaPlayer engine = player();
        assertTrue(engine.getLength() > 25000);
        assertTrue(engine.getAudioTracks().length > 0);
        long before = engine.getTime();
        click("+10 s");
        await(() -> engine.getTime() > before + 8000, 5000);
        SystemClock.sleep(5500);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        await(() -> !engine.isPlaying(), 3000);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        await(engine::isPlaying, 3000);
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        await(
            () ->
                instrumentation
                    .getTargetContext()
                    .getSharedPreferences("positions", 0)
                    .getLong("playback-test", 0) > 0,
            5000
        );
    }

    /** Opens a player options dialog, moves down `steps` entries and selects. */
    private void option(Runnable open, int steps) {
        instrumentation.runOnMainSync(open);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        for (int i = 0; i < steps; i++) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        instrumentation.waitForIdleSync();
    }

    @Test
    public void playbackOptionsApplySpeedPictureAndCancellableSleepTimer() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying();
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        MediaPlayer engine = player();
        option(activity.menus::chooseSpeed, 1);
        assertEquals(1.25f, engine.getRate(), .01f);
        option(activity.menus::choosePicture, 1);
        assertEquals("16:9", engine.getAspectRatio());
        option(activity.menus::chooseSleep, 1);
        assertTrue(activity.sleepAt > SystemClock.elapsedRealtime());
        option(activity.menus::chooseSleep, 0);
        assertEquals(0, activity.sleepAt);
        UiEvidence.capture("playback-options");
    }

    @Test
    public void leavingThePlayerTakesTwoBackPresses() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying();
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        // Keys can only be sent once the player window has input focus.
        await(activity::hasWindowFocus, 5000);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertFalse("One Back press keeps playing", activity.isFinishing());
        UiEvidence.capture("player-exit-hint");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertTrue("A second Back press leaves", activity.isFinishing());
    }

    @Test
    public void playsTransportStream() throws Exception {
        playLiveFixture("live.ts");
    }

    @Test
    public void playsHlsWithoutFallingBackToTs() throws Exception {
        playLiveFixture("playlist.m3u8");
    }

    private void playLiveFixture(String path) throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/" + path)
                .putExtra("live", true)
                .putExtra("id", "playback-test")
                .putExtra("title", "Generated live test")
        );
        await(() -> {
            try {
                return (
                    player() != null &&
                    player().isPlaying() &&
                    player().getTime() > 500 &&
                    player().getCurrentVideoTrack() != null
                );
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        assertFalse("The requested format must play directly", activity.triedAlternative);
    }

    @After
    public void cleanup() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        closed = true;
        if (server != null) server.close();
        Context context = instrumentation.getTargetContext();
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
        context
            .getSharedPreferences("positions", 0)
            .edit()
            .remove("playback-test")
            .remove("playback-test:duration")
            .commit();
    }
}
