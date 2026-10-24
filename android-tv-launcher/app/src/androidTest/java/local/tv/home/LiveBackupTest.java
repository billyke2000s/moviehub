package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.KeyEvent;
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

/** Real shared-player failover, retaining the original until a muted SurfaceView is playable. */
@RunWith(AndroidJUnit4.class)
public final class LiveBackupTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private final Context context = remote.getTargetContext();
    private final ExecutorService sockets = Executors.newCachedThreadPool();
    private final CountDownLatch resolverRelease = new CountDownLatch(1),
        bodyRelease = new CountDownLatch(1);
    private final Set<String> failures = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> visits = new ConcurrentHashMap<>(),
        requests = new ConcurrentHashMap<>();
    private final List<String> locators = Arrays.asList(
        PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09",
        PlutoSource.ROOT + "6304f8d5ffb61000071ab478",
        PlutoSource.ROOT + "5a4d3a00ad95e4718ae8d8db"
    );
    private final List<ChannelDatabase.Row> copies = new ArrayList<>();
    private volatile boolean closed, holdResolver, holdBodies, missing;
    private ServerSocket server;
    private VlcPlaybackActivity activity;
    private MainActivity guide;
    private ChannelDatabase db;
    private boolean oldHttp, oldUpdates;
    private String oldFree, oldLast;

    private void await(BooleanSupplier condition) {
        await(condition, 15000);
    }

    private void await(BooleanSupplier condition, long limit) {
        long end = SystemClock.elapsedRealtime() + limit;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25);
        assertTrue("Live backup condition timed out", condition.getAsBoolean());
    }

    private int count(Map<String, AtomicInteger> values, String key) {
        AtomicInteger value = values.get(key);
        return value == null ? 0 : value.get();
    }

    @Before
    public void prepare() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        oldHttp = prefs.getBoolean("http", false);
        oldUpdates = prefs.getBoolean("autoUpdates", true);
        oldFree = prefs.getString(FreeChannels.PREF, null);
        oldLast = prefs.getString(LiveTvScreen.LAST_CHANNEL, null);
        prefs
            .edit()
            .putBoolean("http", true)
            .putBoolean("autoUpdates", false)
            .putString(FreeChannels.PREF, "off")
            .commit();
        remote.runOnMainSync(LivePlayer::stop);
        LiveZapper.session = null;
        byte[] video;
        try (InputStream input = remote.getContext().getAssets().open("playback.ts")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = input.read(chunk)) != -1) bytes.write(chunk, 0, n);
            video = bytes.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        sockets.submit(() -> {
            while (!closed)
                try {
                    Socket connection = server.accept();
                    sockets.submit(() -> serve(connection, video));
                } catch (IOException error) {
                    if (!closed) throw new UncheckedIOException(error);
                }
        });
        db = ChannelDatabase.get(context);
        db.clear();
        String[] sources = { "m3u", "free", "nativepluto" };
        for (int i = 0; i < locators.size(); i++) {
            ChannelDatabase.Writer writer = db.writer(sources[i], () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Sky Sports Main Event UK",
                        locators.get(i),
                        "Sports",
                        "",
                        "SkySportsMainEvent.uk"
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
            copies.add(db.byKey(ChannelQuery.key(locators.get(i))));
        }
        LivePlayer.testResolver = (locator, http) -> {
            visits.computeIfAbsent(locator, key -> new AtomicInteger()).incrementAndGet();
            int index = locators.indexOf(locator);
            if (index > 0 && holdResolver) {
                try {
                    resolverRelease.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately complete late: cancellation must still reject this result.
                    resolverRelease.await(20, TimeUnit.SECONDS);
                }
            }
            if (failures.contains(locator)) throw new IOException("Fixture resolution failure");
            return "http://127.0.0.1:" + server.getLocalPort() + "/" + index + ".ts";
        };
        activity = (VlcPlaybackActivity) remote.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("live", true)
                .putExtra("url", locators.get(0))
                .putExtra("id", copies.get(0).key)
                .putExtra("title", "Sky Sports Main Event UK")
        );
        await(
            () ->
                LivePlayer.player() != null &&
                LivePlayer.player().isPlaying() &&
                LivePlayer.output().pictures() > 5 &&
                LivePlayer.player().getAudioTrack() >= 0
        );
        remote.runOnMainSync(() -> LivePlayer.backups(context, copies));
    }

    private void serve(Socket connection, byte[] video) {
        try (Socket socket = connection) {
            socket.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String request = input.readLine();
            if (request == null) return;
            String path = request.split(" ")[1];
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {}
            requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            if (!path.equals("/0.ts") && holdBodies) bodyRelease.await(25, TimeUnit.SECONDS);
            OutputStream output = socket.getOutputStream();
            if (!path.equals("/0.ts") && missing) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes()
                );
            } else {
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: " +
                        video.length +
                        "\r\nConnection: close\r\n\r\n"
                    ).getBytes()
                );
                output.write(video);
            }
            output.flush();
        } catch (Exception ignored) {}
    }

    @Test
    public void thirdBufferPreparesOneMutedBackupThenPromotesThatDecoder() throws Exception {
        holdBodies = true;
        MediaPlayer original = LivePlayer.player();
        long frames = LivePlayer.output().pictures();
        remote.runOnMainSync(() -> {
            for (int i = 0; i < 3; i++) {
                LivePlayer.bufferingEnded();
                LivePlayer.bufferingStarted();
            }
        });
        await(() -> LivePlayer.standbyPlayer() != null && count(requests, "/1.ts") > 0);
        MediaPlayer standby = LivePlayer.standbyPlayer();
        remote.runOnMainSync(() -> {
            assertTrue(LivePlayer.prepareBackup());
            assertSame(standby, LivePlayer.standbyPlayer());
            assertEquals(0, standby.getVolume());
            original.setVolume(37);
        });
        SystemClock.sleep(700);
        assertSame(original, LivePlayer.player());
        assertTrue(original.isPlaying());
        assertTrue(LivePlayer.output().pictures() > frames);
        assertEquals(1, count(visits, locators.get(1)));
        bodyRelease.countDown();
        await(() -> locators.get(1).equals(LivePlayer.currentUrl()) && !LivePlayer.preparingBackup());
        // The locator is updated inside the main-thread handoff, before its Activity callback.
        // Observe the completed UI transaction rather than sampling its intermediate fields.
        remote.waitForIdleSync();
        assertSame("Promote the decoded player; no reopen at the cut", standby, LivePlayer.player());
        assertSame(standby, activity.player);
        assertEquals(locators.get(1), activity.getIntent().getStringExtra("url"));
        assertEquals(37, standby.getVolume());
        assertTrue(standby.getAudioTrack() >= 0);
        new LiveHandoffEvidence(remote).awaitPicture(false);
        int fetched = count(requests, "/1.ts");
        SystemClock.sleep(500);
        assertEquals("The prepared transport was retained", fetched, count(requests, "/1.ts"));
        remote.runOnMainSync(activity::toggle);
        await(() -> !standby.isPlaying());
    }

    @Test
    public void aFailedCandidateKeepsOldFramesAndTriesTheNextCopy() throws Exception {
        failures.add(locators.get(1));
        holdBodies = true;
        MediaPlayer original = LivePlayer.player();
        long frames = LivePlayer.output().pictures();
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(requests, "/2.ts") > 0);
        SystemClock.sleep(700);
        assertSame(original, LivePlayer.player());
        assertTrue(LivePlayer.output().pictures() > frames);
        bodyRelease.countDown();
        await(() -> locators.get(2).equals(LivePlayer.currentUrl()));
        assertEquals(1, count(visits, locators.get(1)));
        assertEquals(1, count(visits, locators.get(2)));
        new LiveHandoffEvidence(remote).awaitPicture(false);
    }

    @Test
    public void exhaustedNativeCandidatesKeepTheWorkingOriginalWithoutLooping() throws Exception {
        missing = true;
        MediaPlayer original = LivePlayer.player();
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(visits, locators.get(2)) == 1 && !LivePlayer.preparingBackup());
        assertSame(original, LivePlayer.player());
        assertTrue(original.isPlaying());
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        SystemClock.sleep(700);
        assertEquals(1, count(visits, locators.get(1)));
        assertEquals(1, count(visits, locators.get(2)));
        assertFalse(activity.liveFailed);
    }

    @Test
    public void pauseRejectsLateResolutionWithoutResumingOrSwitching() throws Exception {
        holdResolver = true;
        MediaPlayer original = LivePlayer.player();
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(visits, locators.get(1)) == 1);
        remote.runOnMainSync(activity::toggle);
        await(() -> !LivePlayer.preparingBackup() && !original.isPlaying());
        resolverRelease.countDown();
        SystemClock.sleep(700);
        assertSame(original, LivePlayer.player());
        assertEquals(locators.get(0), LivePlayer.currentUrl());
        assertFalse(original.isPlaying());
        assertEquals(0, count(requests, "/1.ts"));
    }

    @Test
    public void manualModeCancelsPreparationAndDoesNotStartAnotherBackup() throws Exception {
        holdBodies = true;
        MediaPlayer original = LivePlayer.player();
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> LivePlayer.standbyPlayer() != null);
        remote.runOnMainSync(() -> {
            LivePlayer.automaticSources = false;
            LivePlayer.cancelBackup();
            assertFalse(LivePlayer.prepareBackup());
        });
        bodyRelease.countDown();
        SystemClock.sleep(700);
        assertNull(LivePlayer.standbyPlayer());
        assertSame(original, LivePlayer.player());
        assertTrue(original.isPlaying());
    }

    @Test
    public void stalledCandidateHasABoundedStartupAndKeepsTheOriginal() {
        holdBodies = true;
        remote.runOnMainSync(() -> LivePlayer.backups(context, copies.subList(0, 2)));
        MediaPlayer original = LivePlayer.player();
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(requests, "/1.ts") > 0);
        await(() -> !LivePlayer.preparingBackup(), 18000);
        assertSame(original, LivePlayer.player());
        assertTrue(original.isPlaying());
        assertNull(LivePlayer.standbyPlayer());
    }

    @Test
    public void leavingClosesTheStandbyAndRejectsLateResults() throws Exception {
        holdResolver = true;
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(visits, locators.get(1)) == 1);
        remote.runOnMainSync(activity::finish);
        await(activity::isDestroyed);
        resolverRelease.countDown();
        SystemClock.sleep(700);
        assertNull(LivePlayer.player());
        assertNull(LivePlayer.standbyPlayer());
        assertEquals(0, count(requests, "/1.ts"));
    }

    @Test
    public void channelChangeCancelsOldBackupBeforeItsLinkReturns() throws Exception {
        holdResolver = true;
        remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
        await(() -> count(visits, locators.get(1)) == 1);
        remote.runOnMainSync(() ->
            activity.switchTo("Different channel", locators.get(2), copies.get(2).key)
        );
        resolverRelease.countDown();
        await(() -> locators.get(2).equals(LivePlayer.currentUrl()) && LivePlayer.player().isPlaying());
        SystemClock.sleep(700);
        assertEquals(locators.get(2), LivePlayer.currentUrl());
        assertFalse(LivePlayer.preparingBackup());
        assertEquals(0, count(requests, "/1.ts"));
    }

    @Test
    public void handoffCancelsPreparationAndTheGuideCanPromoteItsOwnBackup() throws Exception {
        remote.runOnMainSync(activity::finish);
        await(activity::isDestroyed);
        context
            .getSharedPreferences("ui", 0)
            .edit()
            .putString(LiveTvScreen.LAST_CHANNEL, copies.get(0).key)
            .commit();
        guide = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.runOnMainSync(() -> guide.liveTv.live());
        await(() -> guide.liveTv.picture.playing && guide.liveTv.cells.size() == 1);
        new LiveHandoffEvidence(remote).awaitPicture(false);
        Instrumentation.ActivityMonitor monitor = remote.addMonitor(
            VlcPlaybackActivity.class.getName(),
            null,
            false
        );
        try {
            remote.runOnMainSync(() -> guide.liveTv.cells.get(0).performClick());
            activity = (VlcPlaybackActivity) remote.waitForMonitorWithTimeout(monitor, 15000);
            assertNotNull(activity);
            await(
                () ->
                    LivePlayer.owns(activity) &&
                    activity.zapper != null &&
                    activity.zapper.copies().size() == 3
            );
            new LiveHandoffEvidence(remote).awaitPicture(false);
            holdResolver = true;
            MediaPlayer original = LivePlayer.player();
            int before = count(visits, locators.get(1));
            remote.runOnMainSync(() -> {
                LivePlayer.backups(context, copies);
                assertTrue(LivePlayer.prepareBackup());
            });
            await(() -> count(visits, locators.get(1)) > before);
            for (int i = 0; i < 3 && !activity.isFinishing(); i++) {
                remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                remote.waitForIdleSync();
            }
            await(() -> LivePlayer.owns(guide.liveTv.picture) && !LivePlayer.preparingBackup());
            resolverRelease.countDown();
            assertSame(original, LivePlayer.player());
            new LiveHandoffEvidence(remote).awaitPicture(false);
            remote.runOnMainSync(() -> assertTrue(LivePlayer.prepareBackup()));
            await(() -> !LivePlayer.preparingBackup() && LivePlayer.player() != original);
            assertTrue(LivePlayer.owns(guide.liveTv.picture));
            assertTrue(guide.liveTv.picture.playing);
            new LiveHandoffEvidence(remote).awaitPicture(false);
            // The promoted standby remains the shared player on a later full-screen visit.
            MediaPlayer promoted = LivePlayer.player();
            int opened = LivePlayer.opened;
            monitor = remote.addMonitor(VlcPlaybackActivity.class.getName(), null, false);
            remote.runOnMainSync(() -> guide.liveTv.cells.get(0).performClick());
            activity = (VlcPlaybackActivity) remote.waitForMonitorWithTimeout(monitor, 15000);
            assertNotNull(activity);
            await(
                () ->
                    LivePlayer.owns(activity) &&
                    activity.zapper != null &&
                    activity.zapper.copies().size() == 3
            );
            new LiveHandoffEvidence(remote).awaitPicture(false);
            assertSame(promoted, LivePlayer.player());
            assertEquals(opened, LivePlayer.opened);
            assertEquals(
                ChannelQuery.key(LivePlayer.currentUrl()),
                activity.zapper.copies().get(activity.zapper.selectedCopy()).key
            );
        } finally {
            remote.removeMonitor(monitor);
        }
    }

    @After
    public void cleanup() throws Exception {
        resolverRelease.countDown();
        bodyRelease.countDown();
        remote.runOnMainSync(() -> {
            if (activity != null) activity.finish();
            if (guide != null) guide.finish();
            LivePlayer.stop();
        });
        remote.waitForIdleSync();
        LivePlayer.testResolver = null;
        LiveZapper.session = null;
        closed = true;
        if (server != null) server.close();
        sockets.shutdownNow();
        if (db != null) db.clear();
        context
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("http", oldHttp)
            .putBoolean("autoUpdates", oldUpdates)
            .putString(FreeChannels.PREF, oldFree)
            .putString(LiveTvScreen.LAST_CHANNEL, oldLast)
            .commit();
    }
}
