package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Actual HLS format changes, same-channel recovery and controls during a deliberately slow native join. */
@RunWith(AndroidJUnit4.class)
public final class LiveRecoveryTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private final Context context = remote.getTargetContext();
    private final ExecutorService sockets = Executors.newCachedThreadPool();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final CountDownLatch unblock = new CountDownLatch(1);
    private final String locator = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
    private volatile boolean closed;
    private ServerSocket server;
    private VlcPlaybackActivity activity;
    private ChannelDatabase db;
    private boolean oldHttp;

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25);
        assertTrue("Live recovery timed out", condition.getAsBoolean());
    }

    private byte[] asset(String name) throws IOException {
        try (
            InputStream in = remote.getContext().getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    @Before
    public void open() throws Exception {
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        remote.runOnMainSync(LivePlayer::stop);
        byte[] small = asset("live-small.ts"),
            large = asset("live-large.ts");
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        sockets.submit(() -> {
            while (!closed)
                try {
                    Socket s = server.accept();
                    sockets.submit(() -> serve(s, small, large));
                } catch (IOException error) {
                    if (!closed) throw new UncheckedIOException(error);
                }
        });
        db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("nativepluto", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Recovery fixture", locator, "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        LivePlayer.testResolver = (url, http) -> {
            resolutions.incrementAndGet();
            return "http://127.0.0.1:" + server.getLocalPort() + "/changes.m3u8";
        };
        LiveZapper.session = null;
        activity = (VlcPlaybackActivity) remote.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("live", true)
                .putExtra("url", locator)
                .putExtra("id", ChannelQuery.key(locator))
                .putExtra("title", "Recovery fixture")
        );
        await(
            () ->
                LivePlayer.player() != null &&
                !LiveNative.busy(LivePlayer.player()) &&
                LivePlayer.player().isPlaying() &&
                LivePlayer.output().pictures() > 10 &&
                activity.hasWindowFocus()
        );
    }

    private void serve(Socket connection, byte[] small, byte[] large) {
        try (Socket socket = connection) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String first = in.readLine();
            if (first == null) return;
            String path = first.split(" ")[1],
                line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {}
            byte[] body;
            if (path.contains(".m3u8")) {
                StringBuilder list = new StringBuilder(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n"
                );
                for (int i = 0; i < 40; i++) {
                    if (i > 0) list.append("#EXT-X-DISCONTINUITY\n");
                    list.append("#EXTINF:3.0,\n")
                        .append(i % 2 == 0 ? "small.ts" : "large.ts")
                        .append("?segment=")
                        .append(i)
                        .append('\n');
                }
                list.append("#EXT-X-ENDLIST\n");
                body = list.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            } else body = path.startsWith("/small") ? small : large;
            OutputStream out = socket.getOutputStream();
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: " +
                    (path.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t") +
                    "\r\nContent-Length: " +
                    body.length +
                    "\r\nConnection: close\r\n\r\n"
                ).getBytes()
            );
            out.write(body);
            out.flush();
        } catch (IOException ignored) {}
    }

    @After
    public void close() throws Exception {
        unblock.countDown();
        LiveNative.testStopper = null;
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null) SidePanel.current.dismiss();
            if (activity != null) activity.finish();
            LivePlayer.stop();
        });
        LivePlayer.testResolver = null;
        LiveZapper.session = null;
        closed = true;
        if (server != null) server.close();
        sockets.shutdownNow();
        if (db != null) db.clear();
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
    }

    @Test
    public void resolutionAndTimestampChangesKeepVideoAndAudioAdvancing() {
        MediaPlayer same = LivePlayer.player();
        long frames = LivePlayer.output().pictures();
        int[] resets = { 0 };
        long[] previousTime = { same.getTime() },
            advanced = { 0 },
            latestFrames = { frames };
        try {
            await(() -> {
                if (LiveNative.busy(same)) return false;
                long time = same.getTime();
                // HLS discontinuities can reset the reported clock. Require eleven seconds of
                // forward clock progress across those resets, plus real decoded-frame progress.
                if (time < previousTime[0]) resets[0]++;
                if (time > previousTime[0]) advanced[0] += time - previousTime[0];
                previousTime[0] = time;
                latestFrames[0] = LivePlayer.output().pictures();
                return advanced[0] >= 11000 && latestFrames[0] > frames + 100;
            });
        } catch (AssertionError error) {
            throw new AssertionError(
                "HLS transition: clock=" +
                    previousTime[0] +
                    ", forward=" +
                    advanced[0] +
                    ", decoded=" +
                    latestFrames[0] +
                    ", initial=" +
                    frames +
                    ", resolutions=" +
                    resolutions.get() +
                    ", recovery=" +
                    LivePlayer.videoRecovery(),
                error
            );
        }
        android.util.Log.i(
            "MovieHubHandoff",
            "HLS clock resets=" +
                resets[0] +
                ", forward ms=" +
                advanced[0] +
                ", decoded frames=" +
                (latestFrames[0] - frames)
        );
        assertSame(same, LivePlayer.player());
        assertEquals(1, resolutions.get());
        assertTrue(same.getAudioTrack() >= 0);
        new LiveHandoffEvidence(remote).awaitPicture(false);
        UiEvidence.capture("live-hls-resolution-discontinuity");
    }

    @Test
    public void plutoReconnectsTwiceThenStopsInsteadOfLooping() {
        remote.runOnMainSync(() -> assertTrue(LivePlayer.retryProvider()));
        await(
            () ->
                resolutions.get() == 2 &&
                !LiveNative.busy(LivePlayer.player()) &&
                LivePlayer.player().isPlaying()
        );
        remote.runOnMainSync(() -> assertTrue(LivePlayer.retryProvider()));
        await(
            () ->
                resolutions.get() == 3 &&
                !LiveNative.busy(LivePlayer.player()) &&
                LivePlayer.player().isPlaying()
        );
        remote.runOnMainSync(() -> assertFalse(LivePlayer.retryProvider()));
        assertEquals(locator, LivePlayer.currentUrl());
    }

    @Test
    public void slowNativeStopDoesNotBlockBackOrColourControls() throws Exception {
        MediaPlayer selected = LivePlayer.player();
        CountDownLatch stopping = new CountDownLatch(1);
        LiveNative.testStopper = target -> {
            if (target == selected) try {
                stopping.countDown();
                unblock.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            target.stop();
        };
        remote.runOnMainSync(() -> {
            assertTrue(LivePlayer.output().refreshView());
            assertTrue(LivePlayer.retryProvider());
        });
        assertTrue("Native shutdown entered the deliberate wait", stopping.await(5, TimeUnit.SECONDS));
        assertTrue(LiveNative.busy(selected));
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        assertFalse(activity.isFinishing());
        assertTrue(activity.zapper.overlay.bannerShowing());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        remote.waitForIdleSync();
        assertTrue(
            "Options open while native stop is blocked",
            SidePanel.current != null && SidePanel.current.isShowing()
        );
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        unblock.countDown();
        await(() -> !LiveNative.busy(selected) && selected.isPlaying());
    }

    @Test
    public void missingSurfaceRecoversWithoutClosingTheChannelScreen() {
        android.view.SurfaceView before = LivePlayer.output().surfaceView();
        remote.runOnMainSync(() -> before.setVisibility(View.INVISIBLE));
        await(
            () ->
                LivePlayer.output() != null &&
                LivePlayer.output().surfaceView() != before &&
                LivePlayer.output().isShown()
        );
        assertFalse(activity.isFinishing());
        new LiveHandoffEvidence(remote).awaitPicture(false);
        await(() -> !LivePlayer.output().presentationUnavailable());
    }
}
