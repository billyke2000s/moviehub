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
            if (request != null && request.contains("/missing")) {
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
            instrumentation.runOnMainSync(() -> main.liveTv.live());
            await(() -> main.liveTv.picture.playing && main.liveTv.cells.size() == 1, 30000);
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
            UiEvidence.capture("live-back-in-guide");
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
