package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real Media3 decoding and screen lifetime, using loopback media rather than provider accounts. */
@RunWith(AndroidJUnit4.class)
public final class ExoLiveTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private ServerSocket server;
    private volatile boolean closed;
    private byte[] media;
    private final AtomicInteger opens = new AtomicInteger();
    private boolean oldHttp;

    private String link(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    private void await(BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + 30000;
        boolean[] done = { false };
        do {
            remote.runOnMainSync(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Experimental player timed out");
    }

    @Before
    public void prepare() throws Exception {
        Context context = remote.getTargetContext();
        assertEquals("local.tv.home.experimental", context.getPackageName());
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        try (InputStream in = remote.getContext().getAssets().open("playback.ts")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            media = out.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> serve(socket), "ExoFixtureResponse").start();
                } catch (IOException e) {
                    if (!closed) throw new RuntimeException(e);
                }
            }
        }, "ExoFixtureServer").start();
        remote.runOnMainSync(ExoLive::stop);
        LiveZapper.session = null;
        ChannelDatabase.get(context).clear();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        await(() -> activity.hasWindowFocus());
        remote.runOnMainSync(() -> activity.stage.setPage(new FrameLayout(activity)));
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String request = reader.readLine(),
                line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}
            if (request == null) return;
            String path = request.split(" ")[1];
            if (path.startsWith("/missing")) {
                connection
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                            StandardCharsets.US_ASCII
                        )
                    );
                return;
            }
            byte[] bytes = media;
            String mime = "video/mp2t";
            if (path.equals("/master.m3u8")) {
                mime = "application/vnd.apple.mpegurl";
                bytes = (
                    "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=NO,AUTOSELECT=YES,URI=\"subs.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES=\"subs\"\nmedia.m3u8\n"
                ).getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith(".m3u8")) {
                mime = "application/vnd.apple.mpegurl";
                bytes = (
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:31\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30,\n" +
                    (path.equals("/subs.m3u8") ? "subtitle.vtt" : "segment.ts") +
                    "\n#EXT-X-ENDLIST\n"
                ).getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/subtitle.vtt")) {
                mime = "text/vtt";
                bytes =
                    "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n00:00:00.000 --> 00:00:29.000\nExperimental subtitle fixture\n\n".getBytes(
                        StandardCharsets.UTF_8
                    );
            } else opens.incrementAndGet();
            OutputStream out = connection.getOutputStream();
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: " +
                    mime +
                    "\r\nContent-Length: " +
                    bytes.length +
                    "\r\nConnection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            if (!request.startsWith("HEAD")) out.write(bytes);
            out.flush();
        } catch (IOException ignored) {}
    }

    private void play(String path) {
        remote.runOnMainSync(() -> activity.stage.fullScreen("Fixture", link(path), "fixture"));
        await(() -> ExoLive.isPlaying() && rendered() > 5);
    }

    private int rendered() {
        ExoPlayer player = ExoLive.player();
        if (player == null || player.getVideoDecoderCounters() == null) return 0;
        player.getVideoDecoderCounters().ensureUpdated();
        return player.getVideoDecoderCounters().renderedOutputBufferCount;
    }

    private void picturesAdvance() {
        int[] before = { 0 };
        remote.runOnMainSync(() -> before[0] = rendered());
        await(() -> rendered() > before[0] + 3);
    }

    private void visibleVideo() {
        Rect rect = new Rect();
        remote.runOnMainSync(() -> assertTrue(ExoLive.picture().getGlobalVisibleRect(rect)));
        Bitmap image = remote.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        int min = 255,
            max = 0;
        for (int y = rect.top + rect.height() / 4; y < rect.bottom - rect.height() / 4; y += 8) {
            for (int x = rect.left + rect.width() / 4; x < rect.right - rect.width() / 4; x += 8) {
                int color = image.getPixel(x, y);
                int brightness = (((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255)) / 3;
                min = Math.min(min, brightness);
                max = Math.max(max, brightness);
            }
        }
        image.recycle();
        assertTrue("Video region must contain a decoded picture", max - min > 20);
    }

    @Test
    public void guideFullScreenAndBackKeepTheSamePlayerAndConnection() {
        FrameLayout box = new FrameLayout(activity);
        remote.runOnMainSync(() -> {
            FrameLayout page = new FrameLayout(activity);
            page.addView(box, new FrameLayout.LayoutParams(480, 270));
            activity.stage.setPage(page);
            activity.stage.picture(box, null);
            ExoLive.play(activity, link("/media.m3u8"), false);
        });
        await(() -> ExoLive.isPlaying() && rendered() > 5);
        ExoPlayer[] original = { null };
        View[] picture = { null };
        remote.runOnMainSync(() -> {
            original[0] = ExoLive.player();
            picture[0] = ExoLive.picture();
        });
        int requests = opens.get();
        for (int i = 0; i < 2; i++) {
            play("/media.m3u8");
            picturesAdvance();
            visibleVideo();
            remote.runOnMainSync(() -> activity.stage.leave());
            picturesAdvance();
            visibleVideo();
            remote.runOnMainSync(() -> {
                assertSame(original[0], ExoLive.player());
                assertSame(picture[0], ExoLive.picture());
            });
        }
        assertEquals("Resizing must not reopen the stream", requests, opens.get());
        UiEvidence.capture("experimental-guide-picture");
    }

    @Test
    public void failedChannelRetryCannotRestartTheNextSelection() {
        remote.runOnMainSync(() -> activity.stage.fullScreen("Broken", link("/missing.ts"), "missing"));
        await(ExoLive::retryPending);
        play("/media.m3u8");
        int requests = opens.get();
        SystemClock.sleep(1300);
        picturesAdvance();
        assertEquals("The cancelled retry cannot reopen a working replacement", requests, opens.get());
        remote.runOnMainSync(() -> assertFalse(ExoLive.retryPending()));
    }

    @Test
    public void stoppingDuringRetryReleasesPlayerViewAndPendingCallbacks() {
        remote.runOnMainSync(() -> activity.stage.fullScreen("Broken", link("/missing.ts"), "missing"));
        await(ExoLive::retryPending);
        remote.runOnMainSync(() -> activity.stage.stopped());
        SystemClock.sleep(1300);
        remote.runOnMainSync(() -> {
            assertNull(ExoLive.player());
            assertNull(ExoLive.picture());
            assertNull(ExoLive.currentUrl());
            assertFalse(ExoLive.retryPending());
        });
    }

    @Test
    public void pauseResumeBackgroundAndScreenDestructionCleanUp() {
        play("/media.m3u8");
        remote.runOnMainSync(ExoLive::pause);
        await(() -> !ExoLive.isPlaying());
        remote.runOnMainSync(ExoLive::resume);
        picturesAdvance();
        remote.runOnMainSync(() -> {
            activity.stage.stopped();
            assertNull(ExoLive.player());
            assertNull(ExoLive.picture());
            activity.stage.started();
        });
        await(() -> ExoLive.isPlaying() && rendered() > 5);
        remote.runOnMainSync(() -> activity.finish());
        await(() -> activity.isDestroyed());
        remote.runOnMainSync(() -> {
            assertNull(ExoLive.player());
            assertNull(ExoLive.picture());
        });
    }

    @Test
    public void audioSubtitlesAndRemoteBannerUseTheExperimentalPlayer() {
        play("/master.m3u8");
        await(() -> !ExoLive.audioTracks().isEmpty() && !ExoLive.subtitleTracks().isEmpty());
        remote.runOnMainSync(() -> {
            ExoLive.selectAudioTrack(0);
            ExoLive.selectSubtitleTrack(0);
        });
        await(() -> ExoLive.selectedAudioTrack() == 0 && ExoLive.selectedSubtitleTrack() == 0);
        remote.runOnMainSync(() -> {
            ExoLive.selectSubtitleTrack(-1);
            assertEquals(-1, ExoLive.selectedSubtitleTrack());
            activity.stage.zapper().overlay.hideBanner();
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> activity.stage.zapper().overlay.bannerShowing());
        assertNull(SidePanel.current);
        UiEvidence.capture("experimental-fullscreen-banner");
        picturesAdvance();
    }

    @After
    public void cleanup() throws Exception {
        remote.runOnMainSync(() -> {
            if (activity != null && !activity.isDestroyed()) activity.finish();
            ExoLive.stop();
        });
        remote.waitForIdleSync();
        closed = true;
        if (server != null) server.close();
        remote.getTargetContext().getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
        LiveZapper.session = null;
    }
}
