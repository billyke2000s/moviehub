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
        assertTrue("Managed playback timed out", condition.getAsBoolean());
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
        instrumentation.runOnMainSync(() -> {
            activity.vod.seeking(35_000);
            activity.player.setTime(35_000);
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
