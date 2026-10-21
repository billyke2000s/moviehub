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
    private byte[] wrappedTransportStream;
    private VlcPlaybackActivity activity;
    private ServerSocket server;
    private volatile boolean closed;
    /** Requests for broken fixture streams, so tests can see that no source is retried in a loop. */
    private final java.util.concurrent.atomic.AtomicInteger missingRequests =
        new java.util.concurrent.atomic.AtomicInteger();
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
        wrappedTransportStream = pngSegment(transportStream);
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
            if (request != null && request.contains("/missing")) {
                missingRequests.incrementAndGet();
                // A broken channel: nothing there.
                connection
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII
                        )
                    );
                return;
            }
            String mime = "video/mp4";
            if (request != null && request.contains(".m3u8")) {
                data = (
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:31\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30.0,\n" +
                    (request.contains("wrapped.m3u8")
                        ? "https://8.8.8.8/channel/segment.png"
                        : "http://127.0.0.1:" + server.getLocalPort() + "/segment.ts") +
                    "\n#EXT-X-ENDLIST\n"
                ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                mime = "application/vnd.apple.mpegurl";
            } else if (request != null && request.contains(".png")) {
                data = wrappedTransportStream;
                mime = "image/png";
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
    public void anotherSourceOfTheSameFilmCarriesOnWhereItStopped() throws Exception {
        assertEquals("title:movie:603", ResumePoint.titleKey(new org.json.JSONObject().put("id", 603)));
        assertEquals(
            "title:tv:1399:1:2",
            ResumePoint.titleKey(new org.json.JSONObject().put("tmdbId", 1399).put("episodeKey", "1399:1:2"))
        );
        assertNull(
            "A series page is not a film",
            ResumePoint.titleKey(
                new org.json.JSONObject().put("id", 1399).put("first_air_date", "2011-04-17")
            )
        );
        assertNull(ResumePoint.titleKey(null));
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences positions = context.getSharedPreferences("positions", 0);
        // Watched to 0:12 from another source; this file has never been played.
        positions.edit().putLong("title:movie:4242", 12000).commit();
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", "http://127.0.0.1:" + server.getLocalPort() + "/playback.mp4")
                    .putExtra("id", "playback-other-source")
                    .putExtra("titleKey", "title:movie:4242")
                    .putExtra("title", "Generated playback test")
            );
            await(() -> {
                try {
                    return player() != null && player().isPlaying() && player().getTime() > 8000;
                } catch (Exception e) {
                    return false;
                }
            }, 30000);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            positions
                .edit()
                .remove("title:movie:4242")
                .remove("playback-other-source")
                .remove("playback-other-source:duration")
                .commit();
        }
    }

    @Test
    public void backHidesTheControlsThenAsksThenLeaves() throws Exception {
        await(() -> {
            try {
                return player() != null && player().isPlaying();
            } catch (Exception e) {
                return false;
            }
        }, 30000);
        // Keys can only be sent once the player window has input focus.
        await(activity::hasWindowFocus, 5000);
        instrumentation.runOnMainSync(activity::revealControls);
        instrumentation.waitForIdleSync();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertFalse("The first Back keeps playing", activity.isFinishing());
        assertEquals("The first Back hides the controls", View.GONE, activity.controls.getVisibility());
        assertEquals("No hint yet", View.GONE, activity.exitHint.getVisibility());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertFalse("The second Back still keeps playing", activity.isFinishing());
        assertEquals("The second Back asks", View.VISIBLE, activity.exitHint.getVisibility());
        assertEquals("Asking does not bring the controls back", View.GONE, activity.controls.getVisibility());
        UiEvidence.capture("player-exit-hint");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        assertTrue("Back again leaves", activity.isFinishing());
    }

    @Test
    public void liveTvChangesChannelWithChannelUpAndNumbers() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String base = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts?channel=";
        ChannelDatabase.Writer writer = db.writer("zap-test", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Alpha", base + "a", "General", "", ""));
            writer.accept(new Playlist.Channel("Bravo", base + "b", "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        LiveZapper.session = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", base + "a")
                    .putExtra("live", true)
                    .putExtra("id", "zap-test")
                    .putExtra("title", "Alpha")
            );
            await(() -> player() != null && player().isPlaying(), 30000);
            await(activity::hasWindowFocus, 5000);
            assertNotNull("Live TV from the guide gets channel changing", activity.zapper);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_CHANNEL_UP);
            await(
                () -> activity.zapper.position() == 1 && "Bravo".contentEquals(activity.titleView.getText()),
                10000
            );
            UiEvidence.capture("live-banner");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_0);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
            await(
                () -> activity.zapper.position() == 0 && "Alpha".contentEquals(activity.titleView.getText()),
                10000
            );
            // Browsing in the banner without changing channel: Right with the controls hidden,
            // Up to the next channel, SELECT to watch it.
            await(() -> player() != null && player().isPlaying(), 30000);
            SystemClock.sleep(500);
            instrumentation.runOnMainSync(() -> activity.controls.setVisibility(View.GONE));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            await(() -> activity.zapper.browsing(), 5000);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            await(() -> activity.zapper.browsePosition == 1, 5000);
            assertEquals("Browsing does not change channel", 0, activity.zapper.position());
            UiEvidence.capture("live-browse");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            await(
                () -> activity.zapper.position() == 1 && "Bravo".contentEquals(activity.titleView.getText()),
                10000
            );
            // The satellite-box overlay: the banner on every change, i for programme information,
            // BACK closes it again without leaving.
            await(() -> activity.zapper.overlay.bannerShowing(), 5000);
            assertEquals("No film controls over Live TV", View.GONE, activity.controls.getVisibility());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO);
            await(() -> activity.zapper.overlay.infoShowing(), 5000);
            UiEvidence.capture("live-information");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> !activity.zapper.overlay.infoShowing(), 5000);
            assertFalse("BACK closed the panel, not the player", activity.isFinishing());
            // Pausing live TV, then catching up with the broadcast.
            await(() -> player() != null && player().isPlaying(), 30000);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            await(() -> activity.behindLive && player() != null && !player().isPlaying(), 5000);
            await(() -> activity.zapper.overlay.pauseShowing(), 5000);
            UiEvidence.capture("live-paused");
            instrumentation.runOnMainSync(activity::backToLive);
            await(() -> !activity.behindLive && player() != null && player().isPlaying(), 30000);
        } finally {
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void liveTvMovesBetweenTheGuideAndFullScreenWithoutReconnecting() throws Exception {
        handover(false);
    }

    @Test
    public void nativeResolvedLiveTvHandsOverWithoutResolvingAgain() throws Exception {
        handover(true);
    }

    private void handover(boolean nativeSource) throws Exception {
        handover(nativeSource, false);
    }

    @Test
    public void aManualBackupSurvivesGuideAndFullScreenWithoutReopening() throws Exception {
        handover(false, true);
    }

    private void handover(boolean nativeSource, boolean manualBackup) throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        activity = null;
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        String free = prefs.getString(FreeChannels.PREF, null),
            last = prefs.getString(LiveTvScreen.LAST_CHANNEL, null);
        boolean updates = prefs.getBoolean("autoUpdates", true);
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String fixture = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts?channel=a";
        java.util.concurrent.atomic.AtomicInteger resolutions =
            new java.util.concurrent.atomic.AtomicInteger();
        if (nativeSource) LivePlayer.testResolver = (locator, http) -> {
            resolutions.incrementAndGet();
            return fixture;
        };
        String url = nativeSource ? PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09" : fixture;
        String backup = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts?channel=backup";
        String channelName = manualBackup ? "Sky Sports Main Event UK" : "Alpha";
        String epg = manualBackup ? "SkySportsMainEvent.uk" : "";
        ChannelDatabase.Writer writer = db.writer(manualBackup ? "m3u" : "handover-test", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel(channelName, url, "General", "", epg));
            writer.commit();
        } finally {
            writer.abort();
        }
        if (manualBackup) {
            ChannelDatabase.Writer alternative = db.writer("free", () -> {}, n -> {});
            try {
                alternative.accept(new Playlist.Channel(channelName, backup, "General", "", epg));
                alternative.commit();
            } finally {
                alternative.abort();
            }
        }
        prefs
            .edit()
            .putString(FreeChannels.PREF, "off")
            .putBoolean("autoUpdates", false)
            .putString(LiveTvScreen.LAST_CHANNEL, ChannelQuery.key(url))
            .commit();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
            VlcPlaybackActivity.class.getName(),
            null,
            false
        );
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        try {
            instrumentation.runOnMainSync(() -> main.liveTv.live());
            await(() -> main.liveTv.picture.playing && main.liveTv.cells.size() == 1, 30000);
            LiveHandoffEvidence rendered = new LiveHandoffEvidence(instrumentation);
            rendered.awaitPicture(false);
            int opened = LivePlayer.opened;
            MediaPlayer retainedPlayer = LivePlayer.player();
            for (int trip = 0; trip < 2; trip++) {
                // waitForMonitorWithTimeout removes the monitor after the first hit.
                if (trip > 0) monitor = instrumentation.addMonitor(
                    VlcPlaybackActivity.class.getName(),
                    null,
                    false
                );
                // SELECT on the channel playing in the corner: the full screen carries it on.
                instrumentation.runOnMainSync(() -> main.liveTv.cells.get(0).performClick());
                VlcPlaybackActivity full = (VlcPlaybackActivity) instrumentation.waitForMonitorWithTimeout(
                    monitor,
                    15000
                );
                assertNotNull("The full screen opened", full);
                activity = full;
                await(
                    () ->
                        LivePlayer.owns(full) &&
                        LivePlayer.player() != null &&
                        LivePlayer.player().isPlaying(),
                    15000
                );
                assertEquals("The full screen carried on the guide's stream", opened, LivePlayer.opened);
                assertSame("The same live player survived", retainedPlayer, LivePlayer.player());
                rendered.awaitPicture(true);
                if (manualBackup) {
                    await(() -> full.zapper != null && full.zapper.copies().size() == 2, 5000);
                    if (trip == 0) {
                        instrumentation.runOnMainSync(() -> full.zapper.useCopy(1, false));
                        await(
                            () -> backup.equals(LivePlayer.currentUrl()) && LivePlayer.player().isPlaying(),
                            15000
                        );
                        opened = LivePlayer.opened;
                    }
                    await(() -> full.zapper.selectedCopy() == 1 && !full.zapper.automaticSources(), 5000);
                }
                UiEvidence.capture("live-full-screen");
                // BACK (after closing the banner) returns to the guide, whose picture carries on.
                for (int i = 0; i < 3 && !full.isFinishing(); i++) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    instrumentation.waitForIdleSync();
                }
                await(() -> full.isFinishing() || full.isDestroyed(), 5000);
                await(
                    () ->
                        main.liveTv.picture.playing &&
                        !LivePlayer.owns(full) &&
                        LivePlayer.player() != null &&
                        LivePlayer.player().isPlaying(),
                    15000
                );
                assertEquals("The guide carried on the full screen's stream", opened, LivePlayer.opened);
                if (nativeSource) assertEquals("Handover never resolves again", 1, resolutions.get());
                rendered.awaitPicture(true);
                UiEvidence.capture("live-back-in-guide");
            }
        } finally {
            LivePlayer.testResolver = null;
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(main::finish);
            await(() -> !main.liveTv.picture.playing, 10000);
            SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
            if (free == null) edit.remove(FreeChannels.PREF);
            else edit.putString(FreeChannels.PREF, free);
            if (last == null) edit.remove(LiveTvScreen.LAST_CHANNEL);
            else edit.putString(LiveTvScreen.LAST_CHANNEL, last);
            edit.commit();
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void liveTvMovesBetweenTheGuideAndFullScreenWithoutReconnectingFromTheGuideMenu()
        throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        activity = null;
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        String free = prefs.getString(FreeChannels.PREF, null),
            last = prefs.getString(LiveTvScreen.LAST_CHANNEL, null);
        boolean updates = prefs.getBoolean("autoUpdates", true);
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts?channel=a";
        ChannelDatabase.Writer writer = db.writer("handover-test", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Alpha", url, "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        prefs
            .edit()
            .putString(FreeChannels.PREF, "off")
            .putBoolean("autoUpdates", false)
            .putString(LiveTvScreen.LAST_CHANNEL, ChannelQuery.key(url))
            .commit();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
            VlcPlaybackActivity.class.getName(),
            null,
            false
        );
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        try {
            // Start clean: no live stream left over from another test.
            instrumentation.runOnMainSync(LivePlayer::stop);
            // As from Home: Live TV opens the guide menu; 1 is All Channels.
            instrumentation.runOnMainSync(() -> main.liveTv.start());
            await(() -> main.liveTv.menu.rows.size() == 10 && main.liveTv.menu.rows.get(0).hasFocus(), 10000);
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
            await(() -> main.liveTv.cells.size() == 1, 15000);
            await(() -> main.liveTv.picture.playing, 45000);
            int opened = LivePlayer.opened;
            // SELECT on the channel playing in the corner: the full screen carries it on.
            instrumentation.runOnMainSync(() -> main.liveTv.cells.get(0).performClick());
            VlcPlaybackActivity full = (VlcPlaybackActivity) instrumentation.waitForMonitorWithTimeout(
                monitor,
                15000
            );
            assertNotNull("The full screen opened", full);
            activity = full;
            await(
                () -> LivePlayer.owns(full) && LivePlayer.player() != null && LivePlayer.player().isPlaying(),
                15000
            );
            assertEquals("The full screen carried on the guide's stream", opened, LivePlayer.opened);
            UiEvidence.capture("live-full-screen-from-menu");
            // BACK (after closing the banner) returns to the guide, whose picture carries on.
            for (int i = 0; i < 3 && !full.isFinishing(); i++) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                instrumentation.waitForIdleSync();
            }
            await(() -> full.isFinishing() || full.isDestroyed(), 5000);
            await(
                () ->
                    main.liveTv.picture.playing &&
                    !LivePlayer.owns(full) &&
                    LivePlayer.player() != null &&
                    LivePlayer.player().isPlaying(),
                15000
            );
            assertEquals("The guide carried on the full screen's stream", opened, LivePlayer.opened);
            UiEvidence.capture("live-back-in-guide-from-menu");
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(main::finish);
            await(() -> !main.liveTv.picture.playing, 10000);
            SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
            if (free == null) edit.remove(FreeChannels.PREF);
            else edit.putString(FreeChannels.PREF, free);
            if (last == null) edit.remove(LiveTvScreen.LAST_CHANNEL);
            else edit.putString(LiveTvScreen.LAST_CHANNEL, last);
            edit.commit();
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void aBrokenChannelSaysSoInThePictureAndTheRemoteMovesOn() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        activity = null;
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        String free = prefs.getString(FreeChannels.PREF, null);
        boolean updates = prefs.getBoolean("autoUpdates", true);
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String base = "http://127.0.0.1:" + server.getLocalPort();
        ChannelDatabase.Writer writer = db.writer("broken-test", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Alpha", base + "/live.ts?channel=a", "General", "", ""));
            writer.accept(new Playlist.Channel("Broken", base + "/missing.ts?channel=b", "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        prefs.edit().putString(FreeChannels.PREF, "off").putBoolean("autoUpdates", false).commit();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
            VlcPlaybackActivity.class.getName(),
            null,
            false
        );
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        try {
            instrumentation.runOnMainSync(() -> main.liveTv.live());
            await(() -> main.liveTv.cells.size() == 2, 15000);
            int[] broken = { -1 };
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < 2; i++) if (
                    ((android.widget.TextView) main.liveTv.cells.get(i))
                        .getText()
                        .toString()
                        .contains("Broken")
                ) broken[0] = i;
            });
            assertTrue("Broken channel listed", broken[0] >= 0);
            instrumentation.runOnMainSync(() -> main.liveTv.cells.get(broken[0]).performClick());
            VlcPlaybackActivity full = (VlcPlaybackActivity) instrumentation.waitForMonitorWithTimeout(
                monitor,
                15000
            );
            assertNotNull("The full screen opened", full);
            activity = full;
            // The failure is shown in the picture, with no panel in the way.
            await(() -> full.liveFailed, 30000);
            instrumentation.runOnMainSync(() ->
                assertFalse(
                    "No Can't play panel over Live TV",
                    SidePanel.current != null && SidePanel.current.isShowing()
                )
            );
            UiEvidence.capture("live-channel-not-working");
            // The remote goes straight on to the other channel.
            instrumentation.sendKeyDownUpSync(
                broken[0] == 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN
            );
            await(
                () -> !full.liveFailed && LivePlayer.player() != null && LivePlayer.player().isPlaying(),
                20000
            );
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(main::finish);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(LivePlayer::stop);
            SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
            if (free == null) edit.remove(FreeChannels.PREF);
            else edit.putString(FreeChannels.PREF, free);
            edit.commit();
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void theGuidePlaysTheLastChannelInItsCorner() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        activity = null;
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        String free = prefs.getString(FreeChannels.PREF, null),
            last = prefs.getString(LiveTvScreen.LAST_CHANNEL, null);
        boolean updates = prefs.getBoolean("autoUpdates", true);
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts?channel=a";
        ChannelDatabase.Writer writer = db.writer("picture-test", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Alpha", url, "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        prefs
            .edit()
            .putString(FreeChannels.PREF, "off")
            .putBoolean("autoUpdates", false)
            .putString(LiveTvScreen.LAST_CHANNEL, ChannelQuery.key(url))
            .commit();
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        try {
            instrumentation.runOnMainSync(() -> main.liveTv.live());
            await(() -> main.liveTv.picture.playing, 30000);
            UiEvidence.capture("guide-picture");
        } finally {
            instrumentation.runOnMainSync(main::finish);
            // The picture stops when the guide closes.
            await(() -> !main.liveTv.picture.playing, 10000);
            SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
            if (free == null) edit.remove(FreeChannels.PREF);
            else edit.putString(FreeChannels.PREF, free);
            if (last == null) edit.remove(LiveTvScreen.LAST_CHANNEL);
            else edit.putString(LiveTvScreen.LAST_CHANNEL, last);
            edit.commit();
            db.clear();
        }
    }

    @Test
    public void playsTransportStream() throws Exception {
        playLiveFixture("live.ts");
    }

    @Test
    public void switchingNativeChannelsCancelsTheOldResolution() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        String first = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
        String second = PlutoSource.ROOT + "6304f8d5ffb61000071ab478";
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        LivePlayer.testResolver = (locator, http) -> {
            if (locator.equals(first)) {
                started.countDown();
                try {
                    new java.util.concurrent.CountDownLatch(1).await(
                        10,
                        java.util.concurrent.TimeUnit.SECONDS
                    );
                } catch (InterruptedException expected) {
                    cancelled.set(true);
                }
                http.check();
            }
            return "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        };
        Context context = instrumentation.getTargetContext();
        LiveZapper.session = null;
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", first)
                .putExtra("live", true)
                .putExtra("id", "native-first")
                .putExtra("title", "First")
        );
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        instrumentation.runOnMainSync(() -> activity.switchTo("Second", second, "native-second"));
        await(
            () -> cancelled.get() && player() != null && player().isPlaying() && player().getTime() > 500,
            30000
        );
        assertEquals("Second", activity.titleView.getText().toString());
    }

    @Test
    public void silentVideoFreezeIsReportedWithoutABufferingEventAndPauseIsIgnored() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String locator = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
        ChannelDatabase.Writer writer = db.writer("nativepluto", () -> {}, n -> {});
        try {
            writer.accept(new Playlist.Channel("Silent freeze", locator, "General", "", ""));
            writer.commit();
        } finally {
            writer.abort();
        }
        LivePlayer.testResolver = (url, http) -> "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        LiveZapper.session = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", locator)
                    .putExtra("live", true)
                    .putExtra("id", ChannelQuery.key(locator))
                    .putExtra("title", "Silent freeze")
            );
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
            instrumentation.runOnMainSync(() -> {
                assertNotNull("Guide session was not attached", activity.zapper);
                LivePlayer.observeProgress(0, 1000, 20, false);
                LivePlayer.observeProgress(1000, 1000, 20, true);
                LivePlayer.observeProgress(12000, 1000, 20, false);
                assertFalse("Pause triggered recovery", activity.liveFailed);
                LivePlayer.observeProgress(13000, 1000, 20, true);
                // Audio/clock progresses, video frames do not; no buffering/error event is sent.
                LivePlayer.observeProgress(21000, 9000, 20, true);
                assertEquals("Unexpected inherited backup", "1 of 1", activity.zapper.sourceLabel());
                assertFalse(
                    "The first clock-moving freeze should refresh only the view",
                    activity.liveFailed
                );
                LivePlayer.observeProgress(22000, 10000, 20, true);
                LivePlayer.observeProgress(30000, 18000, 20, true);
                assertTrue(
                    "A persistent freeze must reach the attached failure callback",
                    activity.liveFailed
                );
            });
            assertTrue(status().contains("unavailable"));
        } finally {
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void aStoppedTextureConsumerRecoversWithoutReopeningTheStreamOrAudio() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        String locator = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
        java.util.concurrent.atomic.AtomicInteger resolutions =
            new java.util.concurrent.atomic.AtomicInteger();
        LivePlayer.testResolver = (url, http) -> {
            resolutions.incrementAndGet();
            return "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        };
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", locator)
                .putExtra("live", true)
                .putExtra("id", "consumer-freeze")
                .putExtra("title", "Consumer freeze")
        );
        await(() -> player() != null && player().isPlaying() && LivePlayer.output().frames > 5, 30000);
        org.videolan.libvlc.MediaPlayer samePlayer = player();
        LiveVideoOutput output = LivePlayer.output();
        int opened = LivePlayer.opened;
        int moves = output.handoffs;
        int audioTrack = samePlayer.getAudioTrack();
        long[] baseline = new long[2];
        instrumentation.runOnMainSync(() -> {
            // Real consumer stall: remove TextureView's frame-notification listener while its
            // producer Surface and the actual media/audio continue. No synthetic buffering event.
            output.texture.setOnFrameAvailableListener(null);
        });
        SystemClock.sleep(150); // Drain a frame already queued before notifications were removed.
        instrumentation.runOnMainSync(() -> {
            baseline[0] = output.frames;
            baseline[1] = samePlayer.getTime();
        });
        await(() -> samePlayer.getTime() > baseline[1] + 1000, 5000);
        assertEquals("Video consumer should actually be frozen", baseline[0], output.frames);
        // Refreshed after PICTURE_REPAIR_MS (2.5 s), well before the 8 s full-stall path.
        await(() -> output.handoffs > moves && output.frames > baseline[0] + 3, 6000);
        assertTrue(
            "The early still-picture refresh did the repair",
            LivePlayer.videoRecovery().startsWith("Picture stopped while the sound played")
        );
        assertSame(samePlayer, LivePlayer.player());
        assertSame(output, LivePlayer.output());
        assertEquals("No source reconnection", opened, LivePlayer.opened);
        assertEquals("No new provider walk", 1, resolutions.get());
        assertEquals("Audio selection survived the view repair", audioTrack, samePlayer.getAudioTrack());
        assertTrue("Audio was selected", audioTrack >= 0);
        assertEquals("Native producer was never detached", 0, output.surfaceDestructions);
        long audioBefore = audioBuffers(samePlayer);
        SystemClock.sleep(1500);
        assertTrue("Audio output stopped during video repair", audioBuffers(samePlayer) > audioBefore);
        assertFalse(activity.liveFailed);
        UiEvidence.capture("live-consumer-recovered-with-audio");
    }

    private long audioBuffers(MediaPlayer mediaPlayer) {
        var media = mediaPlayer.getMedia();
        if (media == null) return -1;
        try {
            return media.getStats().playedAbuffers;
        } finally {
            media.release();
        }
    }

    @Test
    public void reloadingWrappedHlsKeepsRenderedVideoAndAudioOutputAdvancing() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + uri.getRawPath()
            ).openConnection();
        org.json.JSONObject session = new org.json.JSONObject()
            .put("endpoint", "https://8.8.8.8/api/playlist-token?slug=channel")
            .put("slug", "channel")
            .put("token", "fixture-token")
            .put("expiresAtMs", System.currentTimeMillis() + 90000);
        EngineSources.Stream stream = new EngineSources.Stream(
            new org.json.JSONObject()
                .put("url", "https://8.8.8.8/channel/wrapped.m3u8")
                .put("headers", new org.json.JSONObject())
                .put("playlistSession", session)
        );
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            Context context = instrumentation.getTargetContext();
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", relay.url)
                    .putExtra("live", true)
                    .putExtra("id", "wrapped-reload")
                    .putExtra("title", "Wrapped HLS reload")
            );
            for (int reload = 0; reload < 3; reload++) {
                await(
                    () -> player() != null && player().isPlaying() && LivePlayer.output().frames > 5,
                    30000
                );
                long before = audioBuffers(player());
                long frames = LivePlayer.output().frames;
                SystemClock.sleep(1500);
                assertTrue("Video stopped after reload " + reload, LivePlayer.output().frames > frames + 5);
                assertTrue("Audio stopped after reload " + reload, audioBuffers(player()) > before);
                if (reload < 2) instrumentation.runOnMainSync(activity::backToLive);
            }
        } finally {
            ProviderRelay.testConnections = null;
        }
    }

    private static byte[] pngSegment(byte[] video) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream values = new DataOutputStream(header);
        values.writeInt(1);
        values.writeInt(1);
        values.write(new byte[] { 8, 2, 0, 0, 0 });
        pngChunk(result, "IHDR", header.toByteArray());
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream compressed = new java.util.zip.DeflaterOutputStream(pixels)) {
            compressed.write(new byte[] { 0, 0, 0, 0 });
        }
        pngChunk(result, "IDAT", pixels.toByteArray());
        pngChunk(result, "IEND", new byte[0]);
        result.write(video);
        return result.toByteArray();
    }

    private static void pngChunk(OutputStream output, String name, byte[] data) throws IOException {
        DataOutputStream bytes = new DataOutputStream(output);
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        bytes.writeInt(data.length);
        bytes.write(type);
        bytes.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(data);
        bytes.writeInt((int) crc.getValue());
    }

    @Test
    public void slowLinkResolutionDoesNotSpendTheVideoStartupTimeout() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String locator = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
        String fixture = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        for (String source : new String[] { "nativepluto", "free" }) {
            ChannelDatabase.Writer writer = db.writer(source, () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Slow resolver",
                        source.equals("nativepluto") ? locator : fixture,
                        "General",
                        "",
                        ""
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        LivePlayer.testResolver = (url, http) -> {
            started.countDown();
            if (!finish.await(25, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException(
                "Fixture resolution timeout"
            );
            http.check();
            return fixture;
        };
        LiveZapper.session = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", locator)
                    .putExtra("live", true)
                    .putExtra("id", ChannelQuery.key(locator))
                    .putExtra("title", "Slow resolver")
            );
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // Cross the real 15-second startup deadline while the resolver is still pending.
            SystemClock.sleep(16_000);
            instrumentation.runOnMainSync(() -> {
                assertTrue("Resolver abandoned before it returned", LivePlayer.isResolving());
                assertFalse(activity.liveFailed);
                assertEquals("1 of 2", activity.zapper.sourceLabel());
            });
            finish.countDown();
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
            assertEquals("1 of 2", activity.zapper.sourceLabel());
        } finally {
            finish.countDown();
            LiveZapper.session = null;
            db.clear();
        }
    }

    @Test
    public void failedNativeResolutionFallsBackToTheSameChannelsBackup() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        String locator = PlutoSource.ROOT + "5ad8d3a31b95267e225e4e09";
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String fixture = "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
        for (String source : new String[] { "nativepluto", "free" }) {
            ChannelDatabase.Writer writer = db.writer(source, () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Example",
                        source.equals("nativepluto") ? locator : fixture,
                        "General",
                        "",
                        ""
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        LivePlayer.testResolver = (url, http) -> {
            attempts.incrementAndGet();
            throw new IOException("Fixture provider offline");
        };
        LiveZapper.session = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        try {
            activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
                new Intent(context, VlcPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", locator)
                    .putExtra("live", true)
                    .putExtra("id", ChannelQuery.key(locator))
                    .putExtra("title", "Example")
            );
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
            assertEquals("2 of 2", activity.zapper.sourceLabel());
            assertEquals("No unbounded retry on the failed provider", 1, attempts.get());
        } finally {
            LiveZapper.session = null;
            db.clear();
        }
    }

    /**
     * Imports one copy of a channel per source (name, guide id, fixture path each), opens the
     * first copy from the guide's list and returns the first copy's address.
     */
    private String openChannelCopies(String[]... copies) throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        String base = "http://127.0.0.1:" + server.getLocalPort() + "/";
        for (String[] copy : copies) {
            ChannelDatabase.Writer writer = db.writer(copy[0], () -> {}, n -> {});
            try {
                writer.accept(new Playlist.Channel(copy[1], base + copy[3], "Sports", "", copy[2]));
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        java.util.List<ChannelDatabase.Row> all = db.browse("all", "", java.util.Collections.emptySet(), 0);
        assertEquals("Every copy is one channel in the guide", 1, all.size());
        assertEquals(copies.length, db.copies(all.get(0)).size());
        ChannelDatabase.Row first = db.copies(all.get(0)).get(0);
        String url = db.url(first.id, first.key);
        LiveZapper.session = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", url)
                .putExtra("live", true)
                .putExtra("id", first.key)
                .putExtra("title", first.name)
        );
        return url;
    }

    private String status() {
        String[] text = new String[1];
        instrumentation.runOnMainSync(
            () ->
                text[0] =
                    activity.status.getVisibility() == View.VISIBLE
                        ? activity.status.getText().toString()
                        : ""
        );
        return text[0];
    }

    /**
     * The reported problem: Sky Sports F1 (720p) from the free list does not play while a provider
     * has the same channel as "Sky Sports F1 UK". The player must move to the provider's copy
     * instead of saying "This channel isn't working right now."
     */
    @Test
    public void skySportsF1FailsOverToAnotherProvidersCopy() throws Exception {
        try {
            String first = openChannelCopies(
                new String[] { "free", "Sky Sports F1 (720p)", "SkySportsF1.uk@SD", "missing-f1.ts" },
                new String[] { "enginedlhd", "Sky Sports F1 UK", "enginedlhd:60", "live.ts" }
            );
            assertTrue("The failing free-list copy is tried first", first.endsWith("missing-f1.ts"));
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 30000);
            assertEquals("2 of 2", activity.zapper.sourceLabel());
            assertFalse(activity.liveFailed);
            assertFalse(status().contains("isn't working"));
        } finally {
            LiveZapper.session = null;
            ChannelDatabase.get(instrumentation.getTargetContext()).clear();
        }
    }

    @Test
    public void failoverTriesEachCopyInTurnUntilOnePlays() throws Exception {
        try {
            openChannelCopies(
                new String[] { "fta", "Sky Sports F1", "", "missing-a.ts" },
                new String[] { "free", "Sky Sports F1 (720p)", "SkySportsF1.uk@SD", "missing-b.ts" },
                new String[] { "enginedlhd", "Sky Sports Formula 1 UK", "enginedlhd:60", "live.ts" }
            );
            await(() -> player() != null && player().isPlaying() && player().getTime() > 500, 45000);
            assertEquals("3 of 3", activity.zapper.sourceLabel());
            assertFalse(activity.liveFailed);
        } finally {
            LiveZapper.session = null;
            ChannelDatabase.get(instrumentation.getTargetContext()).clear();
        }
    }

    @Test
    public void whenEveryCopyFailsTheChannelSaysSoWithoutRetryingForever() throws Exception {
        try {
            openChannelCopies(
                new String[] { "free", "Sky Sports F1 (720p)", "SkySportsF1.uk@SD", "missing-a.ts" },
                new String[] { "enginedlhd", "Sky Sports F1 UK", "enginedlhd:60", "missing-b.ts" }
            );
            await(() -> activity.liveFailed, 45000);
            assertEquals("Both copies were tried", "2 of 2", activity.zapper.sourceLabel());
            assertTrue(status().contains("isn't working"));
            int requests = missingRequests.get();
            SystemClock.sleep(5000);
            assertEquals("No dead source is retried in a loop", requests, missingRequests.get());
        } finally {
            LiveZapper.session = null;
            ChannelDatabase.get(instrumentation.getTargetContext()).clear();
        }
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
        instrumentation.waitForIdleSync();
        // No live stream carries over into the next test.
        instrumentation.runOnMainSync(LivePlayer::stop);
        LivePlayer.testResolver = null;
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
