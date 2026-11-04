package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.videolan.libvlc.interfaces.IMedia;

/** Real LibVLC playback; all media, URLs and cookies belong to the local synthetic fixture. */
@RunWith(AndroidJUnit4.class)
public final class VodSessionPlaybackTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Map<String, byte[]> assets = new HashMap<>();
    private final AtomicInteger authorizedSegments = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean closed;
    private boolean oldHttp;
    private VlcPlaybackActivity activity;
    private String token;

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        String[] state = { "no session" };
        instrumentation.runOnMainSync(() -> {
            if (activity != null && activity.vod != null) state[0] = activity.vod.diagnosticState();
        });
        assertTrue("Playback condition timed out: " + state[0], condition.getAsBoolean());
    }

    @Before
    public void setup() throws Exception {
        Context context = instrumentation.getTargetContext();
        context
            .getSharedPreferences("positions", 0)
            .edit()
            .remove("vod-managed-fixture")
            .remove("vod-managed-fixture:duration")
            .commit();
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        context.getSharedPreferences("ui", 0).edit().putBoolean("http", true).commit();
        for (String name : instrumentation.getContext().getAssets().list("vod-dash")) {
            try (
                InputStream input = instrumentation
                    .getContext()
                    .getAssets()
                    .open("vod-dash/" + name);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                assets.put("/" + name, bytes.toByteArray());
            }
        }
        try (
            InputStream input = instrumentation.getContext().getAssets().open("vod.mp4");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            assets.put("/vod.mp4", bytes.toByteArray());
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed)
                try {
                    Socket socket = server.accept();
                    new Thread(() -> serve(socket), "vod-session-response").start();
                } catch (IOException ignored) {}
        }, "vod-session-fixture").start();
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII)
            );
            String first = input.readLine(),
                line;
            if (first == null) return;
            String path = first.split(" ")[1];
            int start = 0;
            boolean range = false,
                cookie = false;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.equalsIgnoreCase("Cookie: session=fixture")) cookie = true;
                if (line.toLowerCase(Locale.ROOT).startsWith("range: bytes=")) {
                    String value = line.substring(line.indexOf('=') + 1).split("-", 2)[0];
                    if (!value.isEmpty()) {
                        start = Integer.parseInt(value);
                        range = true;
                    }
                }
            }
            byte[] data = assets.get(path);
            boolean dash = !path.equals("/vod.mp4");
            OutputStream output = connection.getOutputStream();
            if (data == null || (dash && !cookie)) {
                output.write(
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                        StandardCharsets.US_ASCII
                    )
                );
                return;
            }
            if (dash && !path.endsWith(".mpd")) authorizedSegments.incrementAndGet();
            start = Math.min(start, data.length - 1);
            String mime = path.endsWith(".mpd") ? "application/dash+xml" : "video/mp4";
            output.write(
                (
                    "HTTP/1.1 " +
                    (range ? "206 Partial Content" : "200 OK") +
                    "\r\nContent-Type: " +
                    mime +
                    "\r\nContent-Length: " +
                    (data.length - start) +
                    "\r\nAccept-Ranges: bytes\r\n" +
                    (range
                        ? "Content-Range: bytes " +
                          start +
                          "-" +
                          (data.length - 1) +
                          "/" +
                          data.length +
                          "\r\n"
                        : "") +
                    "Connection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII)
            );
            output.write(data, start, data.length - start);
        } catch (Exception ignored) {}
    }

    private void launch(VodSources.Session session) {
        token = VodSources.remember(session);
        Context context = instrumentation.getTargetContext();
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/expired")
                .putExtra("id", "vod-managed-fixture")
                .putExtra("title", "Managed fixture")
                .putExtra("vodSession", token)
        );
    }

    private void open(VodSources.Session session) {
        launch(session);
        await(
            () -> activity.player != null && activity.player.isPlaying() && activity.player.getTime() > 300
        );
    }

    private VodSources.Session session(VodSources.Resolve resolve) {
        VodSources.Session session = new VodSources.Session(
            Collections.singletonList(new VodSourceFailover.Source("source", "Fixture", "fixture")),
            resolve,
            false
        );
        session.managed = true;
        return session;
    }

    @Test
    public void auditSourcesStartIndependentlyWithoutChangingSavedUserProgress() throws Exception {
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences positions = context.getSharedPreferences("positions", 0);
        android.content.SharedPreferences ui = context.getSharedPreferences("ui", 0);
        boolean oldResume = ui.getBoolean("resume", true);
        boolean hadLegacy = positions.contains("media");
        long oldLegacy = positions.getLong("media", 0);
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4";
        Intent previous = FreeVodLiveAuditTest.sourceIntent(context, url, "unused");
        String previousId = previous.getStringExtra("id");
        token = VodSources.remember(session(index -> new VodSources.Resolved(url, null)));
        Intent current = FreeVodLiveAuditTest.sourceIntent(context, url, token);
        String currentId = current.getStringExtra("id");
        assertNotEquals(previousId, currentId);
        assertNotEquals("media", currentId);
        positions.edit().putLong("media", 90_000).putLong(previousId, 120_000).commit();
        ui.edit().putBoolean("resume", true).commit();
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(current);
            await(
                () ->
                    activity.player != null && activity.player.isPlaying() && activity.player.getTime() > 300
            );
            assertTrue("Audit resumed an unrelated source", activity.player.getTime() < 15_000);
            assertEquals(90_000, positions.getLong("media", 0));
            assertEquals(120_000, positions.getLong(previousId, 0));
        } finally {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
                await(activity::isDestroyed);
            }
            ResumePoint.clear(context, previousId, null);
            ResumePoint.clear(context, currentId, null);
            positions
                .edit()
                .remove(previousId + ":duration")
                .remove(currentId + ":duration")
                .commit();
            if (hadLegacy) positions.edit().putLong("media", oldLegacy).commit();
            else positions.edit().remove("media").commit();
            ui.edit().putBoolean("resume", oldResume).commit();
        }
    }

    @Test
    public void initialPlayUsesSessionResolutionAndRefreshesOneExpiredLink() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        String origin = "http://127.0.0.1:" + server.getLocalPort();
        VodSources.Session session = session(index -> new VodSources.Resolved(origin + "/expired", null));
        session.refreshable = index -> true;
        session.refresh = index -> {
            refreshes.incrementAndGet();
            return new VodSources.Resolved(origin + "/vod.mp4", null);
        };
        open(session);
        assertEquals(1, refreshes.get());
        assertEquals(0, session.active);
        assertTrue(activity.getIntent().getStringExtra("url").endsWith("/vod.mp4"));
    }

    @Test
    public void headerProtectedDashTemplatesDecodeAudioAndVideoThroughRelay() throws Exception {
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + uri.getPath()
            ).openConnection();
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", "https://8.8.8.8/manifest.mpd")
                .put("headers", new JSONObject().put("Cookie", "session=fixture"))
        );
        VodSources.Session session = session(index -> new VodSources.Resolved(stream.url, null));
        session.transport = index -> stream;
        open(session);
        await(() -> {
            IMedia media = activity.player == null ? null : activity.player.getMedia();
            if (media == null) return false;
            try {
                IMedia.Stats stats = media.getStats();
                return stats != null && stats.decodedVideo > 0 && stats.decodedAudio > 0;
            } finally {
                media.release();
            }
        });
        assertTrue(authorizedSegments.get() >= 4);
    }

    @Test
    public void managedOpeningRetainsResumePointAndAllowsSeeking() throws Exception {
        instrumentation
            .getTargetContext()
            .getSharedPreferences("positions", 0)
            .edit()
            .putLong("vod-managed-fixture", 12_000)
            .commit();
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4";
        open(session(index -> new VodSources.Resolved(url, null)));
        assertTrue(activity.player.getTime() >= 10_000);
        // Let the opening resume guard expire, so only the audit jump can
        // protect the injected buffering below from becoming a false stall.
        await(() -> activity.player != null && activity.player.getTime() >= 15_000);
        instrumentation.runOnMainSync(() -> {
            FreeVodLiveAuditTest.seekSource(activity, 35_000);
            activity.vod.onBuffering(true);
            assertFalse(activity.vod.queue.isStalled(SystemClock.elapsedRealtime() + 5_000));
            activity.vod.onBuffering(false);
        });
        await(() -> activity.player != null && activity.player.getTime() >= 34_000);
        assertEquals(0, activity.vod.activeIndex());
    }

    @Test
    public void abandonedManagedResolutionIsInterruptedAndCannotStartLate() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        VodSources.Session session = session(index -> {
            started.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
            throw new IllegalStateException("Fixture lookup was not cancelled");
        });
        launch(session);
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.runOnMainSync(activity::finish);
        await(activity::isDestroyed);
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(activity.player);
    }

    @Test
    public void manuallySelectingFailedActiveSourceRetriesItsManagedResolver() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4";
        launch(
            session(index -> {
                if (!ready.get()) throw new IOException("Fixture source temporarily unavailable");
                return new VodSources.Resolved(url, null);
            })
        );
        await(() -> activity.status.getText().toString().contains("No working backup"));
        ready.set(true);
        instrumentation.runOnMainSync(() -> activity.vod.select(0));
        await(
            () -> activity.player != null && activity.player.isPlaying() && activity.player.getTime() > 300
        );
        assertEquals(0, activity.vod.activeIndex());
    }

    void measureKnownFixture() throws Exception {
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + uri.getPath()
            ).openConnection();
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject().put("url", "https://8.8.8.8/vod.mp4").put("headers", new JSONObject())
        );
        VodSources.Session calibration = session(index -> new VodSources.Resolved(stream.url, null));
        calibration.transport = index -> stream;
        calibration.free = true;
        calibration.expectedDuration = 180_000;
        open(calibration);
        long[] initial = calibrationSnapshot();
        assertEquals(1280, initial[4]);
        assertEquals(720, initial[5]);
        org.json.JSONArray windows = new org.json.JSONArray();
        for (long target : new long[] { 0, 90_000, 162_000 }) {
            if (target > 0) {
                instrumentation.runOnMainSync(() -> FreeVodLiveAuditTest.seekSource(activity, target));
                await(() -> activity.player.getTime() >= target - 1000);
            }
            long[] before = calibrationSnapshot();
            long start = SystemClock.elapsedRealtime(),
                videoAt = start,
                audioAt = start;
            long video = before[1],
                audio = before[2],
                frames = 0,
                buffers = 0;
            long videoGap = 0,
                audioGap = 0;
            long[] sample = before;
            while (SystemClock.elapsedRealtime() - start < (target == 162_000 ? 10_000 : 20_000)) {
                SystemClock.sleep(250);
                sample = calibrationSnapshot();
                long now = SystemClock.elapsedRealtime();
                if (sample[1] > video) {
                    frames += sample[1] - video;
                    videoAt = now;
                }
                if (sample[2] > audio) {
                    buffers += sample[2] - audio;
                    audioAt = now;
                }
                video = sample[1];
                audio = sample[2];
                videoGap = Math.max(videoGap, now - videoAt);
                audioGap = Math.max(audioGap, now - audioAt);
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            assertTrue("Fixture must display video", frames > 0);
            assertTrue("Fixture must play audio buffers", buffers > 0);
            windows.put(
                new JSONObject()
                    .put("targetMs", target)
                    .put("elapsedMs", elapsed)
                    .put("displayedFps", (frames * 1000.0) / elapsed)
                    .put("nominalFps", 24)
                    .put("videoGapMs", videoGap)
                    .put("audioGapMs", audioGap)
                    .put("progressMs", sample[0] - before[0])
                    .put("audioBuffers", buffers)
            );
        }
        JSONObject report = new JSONObject()
            .put("kind", "runner_calibration")
            .put("width", initial[4])
            .put("height", initial[5])
            .put("windows", windows)
            .put("providerPlaybackVerified", false);
        try (
            FileWriter writer = new FileWriter(
                new File(instrumentation.getTargetContext().getFilesDir(), "vod-runner-calibration.json")
            )
        ) {
            writer.write(report.toString());
        }
    }

    private long[] calibrationSnapshot() {
        long[] sample = new long[6];
        instrumentation.runOnMainSync(() -> {
            sample[0] = activity.player.getTime();
            IMedia media = activity.player.getMedia();
            if (media == null) return;
            try {
                IMedia.Stats stats = media.getStats();
                sample[1] = stats == null ? 0 : stats.displayedPictures;
                sample[2] = stats == null ? 0 : stats.playedAbuffers;
                for (int index = 0; index < media.getTrackCount(); index++) if (
                    media.getTrack(index) instanceof IMedia.VideoTrack
                ) {
                    IMedia.VideoTrack video = (IMedia.VideoTrack) media.getTrack(index);
                    sample[4] = video.width;
                    sample[5] = video.height;
                }
            } finally {
                media.release();
            }
        });
        return sample;
    }

    @After
    public void cleanup() throws Exception {
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
        ProviderRelay.testConnections = null;
        if (token != null) VodSources.forget(token);
        closed = true;
        if (server != null) server.close();
        instrumentation
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("http", oldHttp)
            .commit();
    }
}
