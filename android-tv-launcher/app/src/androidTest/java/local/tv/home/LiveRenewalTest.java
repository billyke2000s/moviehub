package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

/** Actual VLC playback while provider renewal is deliberately blocked, failed or cancelled. */
@RunWith(AndroidJUnit4.class)
public final class LiveRenewalTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private final Context context = remote.getTargetContext();
    private final CountDownLatch requested = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger resolutions = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final ExecutorService sockets = Executors.newCachedThreadPool();
    private ServerSocket server;
    private VlcPlaybackActivity activity;
    private boolean oldHttp;
    private volatile boolean closed, fail;
    private String fixture;

    @Before
    public void prepare() throws Exception {
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        remote.runOnMainSync(LivePlayer::stop);
        LiveZapper.session = null;
        byte[] video;
        try (InputStream input = remote.getContext().getAssets().open("playback.ts")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            video = bytes.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        fixture = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        sockets.submit(() -> {
            while (!closed)
                try {
                    Socket socket = server.accept();
                    sockets.submit(() -> serve(socket, video));
                } catch (IOException stopped) {
                    if (!closed) throw new UncheckedIOException(stopped);
                }
        });
        LivePlayer.testResolver = (locator, http) -> {
            if (resolutions.incrementAndGet() > 1) {
                requested.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture timeout");
                http.check();
                if (fail) throw new IOException("Fixture renewal failure");
            }
            return fixture;
        };
    }

    private void serve(Socket socket, byte[] video) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line = reader.readLine();
            if (line == null) return;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}
            requests.incrementAndGet();
            OutputStream output = connection.getOutputStream();
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: " +
                    video.length +
                    "\r\nConnection: close\r\n\r\n"
                ).getBytes(java.nio.charset.StandardCharsets.US_ASCII)
            );
            output.write(video);
            output.flush();
        } catch (IOException ignored) {}
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] ready = { false };
            remote.runOnMainSync(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            SystemClock.sleep(30);
        }
        remote.runOnMainSync(() -> assertTrue("Live renewal condition timed out", condition.getAsBoolean()));
    }

    private void start(boolean nativeSource) {
        activity = (VlcPlaybackActivity) remote.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", nativeSource ? PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09" : fixture)
                .putExtra("live", true)
                .putExtra("title", "Renewal fixture")
        );
        await(
            () ->
                LivePlayer.player() != null &&
                LivePlayer.player().isPlaying() &&
                LivePlayer.output().pictures() > 5
        );
    }

    private void renewAndObserve() throws Exception {
        MediaPlayer original = LivePlayer.player();
        long[] before = { 0 };
        remote.runOnMainSync(() -> {
            before[0] = LivePlayer.output().pictures();
            LivePlayer.renew();
        });
        assertTrue(requested.await(5, TimeUnit.SECONDS));
        SystemClock.sleep(700);
        remote.runOnMainSync(() -> {
            assertTrue("Old stream plays throughout link lookup", original.isPlaying());
            assertSame(original, LivePlayer.player());
            assertTrue("Old video still decodes", LivePlayer.output().pictures() > before[0]);
        });
    }

    @Test
    public void renewalDoesNotStopPlaybackUntilTheLinkReturns() throws Exception {
        start(true);
        renewAndObserve();
        int before = requests.get();
        release.countDown();
        await(() -> !LivePlayer.isResolving() && requests.get() > before && LivePlayer.player().isPlaying());
        assertEquals(2, resolutions.get());
    }

    @Test
    public void failedRenewalKeepsTheWorkingStream() throws Exception {
        start(true);
        fail = true;
        renewAndObserve();
        int before = requests.get();
        release.countDown();
        await(() -> !LivePlayer.isResolving());
        remote.runOnMainSync(() -> {
            assertTrue(LivePlayer.player().isPlaying());
            assertFalse(activity.liveFailed);
            assertEquals("No reopen after failed refresh", before, requests.get());
        });
    }

    @Test
    public void pauseDuringRenewalRejectsItsLateLink() throws Exception {
        start(true);
        renewAndObserve();
        int before = requests.get();
        remote.runOnMainSync(() -> LivePlayer.player().pause());
        await(() -> !LivePlayer.player().isPlaying());
        remote.waitForIdleSync();
        release.countDown();
        await(() -> !LivePlayer.isResolving());
        SystemClock.sleep(300);
        remote.runOnMainSync(() -> {
            assertFalse("Renewal must not resume a paused stream", LivePlayer.player().isPlaying());
            assertEquals(before, requests.get());
        });
    }

    @Test
    public void ordinaryIptvCanRepairAStillPictureWithoutReopening() {
        start(false);
        int opened = LivePlayer.opened;
        long[] decoded = { 0 };
        remote.runOnMainSync(() -> {
            long now = SystemClock.elapsedRealtime();
            long time = LivePlayer.player().getTime(),
                frames = LivePlayer.output().pictures();
            decoded[0] = frames;
            int repairs = LivePlayer.output().repairs;
            LivePlayer.observeProgress(now - 3000, time, frames, false);
            LivePlayer.observeProgress(now - 3000, time, frames, true);
            LivePlayer.observeProgress(now, time + 3000, frames, true);
            assertEquals(repairs + 1, LivePlayer.output().repairs);
        });
        await(
            () ->
                LivePlayer.output().isShown() &&
                LivePlayer.player().isPlaying() &&
                LivePlayer.output().pictures() > decoded[0] + 2
        );
        assertEquals("Surface repair does not reopen ordinary IPTV", opened, LivePlayer.opened);
    }

    @Test
    public void unreportedOutputStillWaitsBeforeReplacingItsSurface() {
        start(false);
        long before = LivePlayer.output().pictures();
        remote.runOnMainSync(() -> {
            LiveVideoOutput output = LivePlayer.output();
            android.view.SurfaceView surface = output.surfaceView();
            output.videoOutputs(0);
            assertTrue(output.refreshView());
            assertSame("No detach before native shutdown acknowledgement", surface, output.surfaceView());
        });
        await(() -> LivePlayer.output().isShown() && LivePlayer.output().pictures() > before + 3);
    }

    @Test
    public void missingShutdownAcknowledgementStopsNativeReaderBeforeMoving() {
        start(false);
        int before = requests.get();
        remote.runOnMainSync(() -> {
            // Suppress Vout acknowledgement to exercise the bounded native-stop fallback.
            LivePlayer.player().setEventListener(null);
            assertTrue(LivePlayer.output().refreshView());
        });
        await(
            () -> requests.get() > before && LivePlayer.player().isPlaying() && LivePlayer.output().isShown()
        );
    }

    @Test
    public void persistentFreezeReachesFailureInsteadOfRepairingForever() {
        start(false);
        AtomicInteger failures = new AtomicInteger();
        remote.runOnMainSync(() -> {
            LivePlayer.attach(activity, activity.video, event -> {}, failures::incrementAndGet);
            long time = LivePlayer.player().getTime(),
                frames = LivePlayer.output().pictures();
            int repairs = LivePlayer.output().repairs;
            LivePlayer.observeProgress(0, time, frames, false);
            LivePlayer.observeProgress(1000, time, frames, true);
            LivePlayer.observeProgress(4000, time + 3000, frames, true);
            assertEquals(repairs + 1, LivePlayer.output().repairs);
            // The repair hides the surface temporarily. That must not grant another repair.
            LivePlayer.observeProgress(4500, time + 3500, frames, false);
            LivePlayer.observeProgress(5000, time + 4000, frames, true);
            LivePlayer.observeProgress(14000, time + 13000, frames, true);
            assertEquals("One repair per persistent freeze", repairs + 1, LivePlayer.output().repairs);
            assertEquals("Persistent failure reaches source recovery", 1, failures.get());
        });
    }

    @Test
    public void leavingDuringRenewalRejectsTheLateResult() throws Exception {
        start(true);
        renewAndObserve();
        remote.runOnMainSync(activity::finish);
        await(activity::isDestroyed);
        int before = requests.get();
        release.countDown();
        SystemClock.sleep(300);
        remote.runOnMainSync(() -> assertNull(LivePlayer.player()));
        assertEquals(before, requests.get());
    }

    @After
    public void cleanup() throws Exception {
        release.countDown();
        remote.runOnMainSync(() -> {
            if (activity != null) activity.finish();
            LivePlayer.stop();
        });
        remote.waitForIdleSync();
        LivePlayer.testResolver = null;
        closed = true;
        if (server != null) server.close();
        sockets.shutdownNow();
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", oldHttp).commit();
    }
}
