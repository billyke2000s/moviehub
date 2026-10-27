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
    private final AtomicInteger slowRequests = new AtomicInteger();
    private boolean oldHttp, oldAutoUpdates;

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
        UiEvidence.capture("experimental-timeout");
        fail("Live TV player timed out; focus=" + (activity != null && activity.hasWindowFocus()));
    }

    @Before
    public void prepare() throws Exception {
        Context context = remote.getTargetContext();
        // Runs in both MovieHub and the separate -Pexperimental test app.
        assertTrue(context.getPackageName().startsWith("local.tv.home"));
        oldHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        oldAutoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
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
            if (path.equals("/slow.m3u8")) {
                slowRequests.incrementAndGet();
                // Longer than LiveStage's old periodic retry, shorter than the HTTP read timeout.
                SystemClock.sleep(17000);
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
    public void anExhaustedChannelTimerCannotInterruptASlowReplacement() {
        play("/media.m3u8");
        remote.runOnMainSync(() -> {
            // Exhausted-source callback from the production LiveScreen contract.
            activity.stage.liveSourceFailed();
            activity.stage.switchTo("Slow replacement", link("/slow.m3u8"), "slow");
        });
        await(() -> slowRequests.get() == 1);
        await(() -> ExoLive.isPlaying() && rendered() > 5);
        assertEquals("A new selection gets its full startup budget", 1, slowRequests.get());
        picturesAdvance();
    }

    @Test
    public void exhaustedDaddyLiveLookupReportsFailureWithoutRepeatingTheWalk() {
        AtomicInteger failures = new AtomicInteger();
        remote.runOnMainSync(() -> {
            ExoLive.attach(
                this,
                ExoLive.createVideoView(activity),
                new ExoLive.Listener() {
                    public void playing() {}

                    public void buffering(boolean buffering) {}

                    public void failed(String reason) {
                        failures.incrementAndGet();
                    }
                }
            );
            ExoLive.resolutionFailed(AdapterSources.locator("dlhd", "42"));
            assertEquals(1, failures.get());
            assertFalse("An exhausted lookup must reach source failover immediately", ExoLive.retryPending());
        });
    }

    @Test
    public void aFailingSourceWithAnotherWaitingMovesOnInsteadOfReopening() {
        AtomicInteger failures = new AtomicInteger();
        remote.runOnMainSync(() ->
            ExoLive.attach(
                this,
                ExoLive.createVideoView(activity),
                new ExoLive.Listener() {
                    public void playing() {}

                    public void buffering(boolean buffering) {}

                    public void failed(String reason) {
                        failures.incrementAndGet();
                    }

                    public boolean hasAlternative() {
                        return true;
                    }
                }
            )
        );
        remote.runOnMainSync(() -> ExoLive.play(activity, link("/missing.ts"), true));
        await(() -> failures.get() == 1);
        SystemClock.sleep(1300);
        remote.runOnMainSync(() -> {
            assertFalse("No reopen of the failed source while another waits", ExoLive.retryPending());
            assertEquals("Reported once, so the owner moves to the next source once", 1, failures.get());
        });
    }

    @Test
    public void unavailableZliveStopsAndAllowsManualReconnectWithoutEndlessRetries() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(remote.getTargetContext());
        ChannelDatabase.Writer writer = db.writer("enginezlive", () -> {}, n -> {});
        try {
            writer.accept(
                new Playlist.Channel(
                    "Unavailable ZLive UK",
                    link("/missing.ts"),
                    "UK",
                    "",
                    "enginezlive:fixture.uk",
                    "Fixture server"
                )
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        remote.runOnMainSync(() ->
            activity.stage.fullScreen("Unavailable ZLive UK", link("/missing.ts"), "fixture")
        );
        await(() -> activity.stage.liveFailed());
        remote.runOnMainSync(() -> {
            assertFalse("No automatic reopen after sources are exhausted", ExoLive.retryPending());
            assertTrue(activity.stage.full());
            assertTrue(activity.stage.zapper().sourceProvider().contains("Fixture server"));
        });
        SystemClock.sleep(LiveStage.RETRY_MS + 500);
        remote.runOnMainSync(() -> {
            assertTrue("Failure stays bounded", activity.stage.liveFailed());
            assertFalse(ExoLive.retryPending());
            activity.stage.switchTo("Working channel", link("/media.m3u8"), "working");
        });
        await(() -> ExoLive.isPlaying() && rendered() > 5);
    }

    /** One channel, three copies from three lists, in guide order: dead, working, slow. */
    private void raceChannel() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        String[] sources = { "m3u", "fta", "free" };
        String[] paths = { "/missing.ts", "/media.m3u8", "/slow.m3u8" };
        for (int i = 0; i < sources.length; i++) {
            ChannelDatabase.Writer writer = db.writer(sources[i], () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Race Channel UK",
                        link(paths[i]),
                        "Entertainment",
                        "",
                        "RaceChannel.uk"
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
    }

    @Test
    public void aDeadCopyHandsOverToAWorkingOneWithoutWaitingForTheSlowOne() throws Exception {
        raceChannel();
        remote.runOnMainSync(() -> activity.stage.fullScreen("Race Channel UK", link("/missing.ts"), "race"));
        // The dead copy fails; the other copies are looked up together and the working one plays.
        await(() -> ExoLive.isPlaying() && rendered() > 5);
        remote.runOnMainSync(() -> {
            assertEquals("The working copy plays", 1, activity.stage.zapper().selectedCopy());
            assertTrue(
                "The other copies were looked up by the source race",
                ExoLive.eventLog().contains("Link found by the source race")
            );
        });
        assertEquals("The slow copy is never opened while a working one plays", 0, slowRequests.get());
        picturesAdvance();
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
            activity.stage.switchTo("Late queued selection", link("/media.m3u8"), "late");
            assertNull("A background callback cannot restart playback", ExoLive.player());
        });
    }

    @Test
    public void pauseResumeBackgroundAndScreenDestructionCleanUp() {
        play("/media.m3u8");
        remote.runOnMainSync(ExoLive::pause);
        await(() -> !ExoLive.isPlaying());
        remote.runOnMainSync(ExoLive::resume);
        picturesAdvance();
        AtomicInteger guideRefreshes = new AtomicInteger();
        remote.runOnMainSync(() -> {
            activity.resumeRefresh = guideRefreshes::incrementAndGet;
            assertTrue(activity.moveTaskToBack(true));
        });
        await(() -> ExoLive.player() == null && !activity.hasWindowFocus());
        remote.runOnMainSync(() -> {
            assertNull(ExoLive.picture());
            remote
                .getTargetContext()
                .startActivity(
                    new Intent(remote.getTargetContext(), MainActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                );
        });
        await(() -> activity.hasWindowFocus() && ExoLive.isPlaying() && rendered() > 5);
        remote.runOnMainSync(() -> assertTrue(activity.stage.full()));
        assertEquals("Foreground return must not run the deferred guide navigation", 0, guideRefreshes.get());
        remote.runOnMainSync(() -> activity.finish());
        await(() -> activity.isDestroyed());
        remote.runOnMainSync(() -> {
            assertNull(ExoLive.player());
            assertNull(ExoLive.picture());
        });
    }

    @Test
    public void audioSubtitlesAndRemoteBannerUseTheExoPlayer() {
        play("/master.m3u8");
        await(() -> !ExoLiveTracks.audioTracks().isEmpty() && !ExoLiveTracks.subtitleTracks().isEmpty());
        remote.runOnMainSync(() -> {
            ExoLiveTracks.selectAudioTrack(0);
            ExoLiveTracks.selectSubtitleTrack(0);
        });
        await(() -> ExoLiveTracks.selectedAudioTrack() == 0 && ExoLiveTracks.selectedSubtitleTrack() == 0);
        remote.runOnMainSync(() -> {
            ExoLiveTracks.selectSubtitleTrack(-1);
            assertEquals(-1, ExoLiveTracks.selectedSubtitleTrack());
            activity.stage.zapper().overlay.hideBanner();
        });
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> activity.stage.zapper().overlay.bannerShowing());
        assertNull(SidePanel.current);
        UiEvidence.capture("experimental-fullscreen-banner");
        picturesAdvance();
    }

    // ---- Guide and channel behaviour through the real guide (ported from the VLC-era PlaybackTest) --

    private String oldFree, oldLast;

    /** Channels named {@code names[i]} at {@code paths[i]}; one writer per source so equal names become copies. */
    private void channels(String[][] rows) throws Exception {
        Context context = remote.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        oldFree = prefs.getString(FreeChannels.PREF, null);
        oldLast = prefs.getString(LiveTvScreen.LAST_CHANNEL, null);
        prefs.edit().putString(FreeChannels.PREF, "off").commit();
        ChannelDatabase db = ChannelDatabase.get(context);
        for (String[] row : rows) {
            ChannelDatabase.Writer writer = db.writer(row[0], () -> {}, n -> {});
            try {
                writer.accept(new Playlist.Channel(row[1], link(row[2]), "General", "", row[3]));
                writer.commit();
            } finally {
                writer.abort();
            }
        }
    }

    private void restoreChannelPrefs() {
        android.content.SharedPreferences.Editor edit = remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit();
        if (oldFree == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, oldFree);
        if (oldLast == null) edit.remove(LiveTvScreen.LAST_CHANNEL);
        else edit.putString(LiveTvScreen.LAST_CHANNEL, oldLast);
        edit.commit();
        ChannelDatabase.get(remote.getTargetContext()).clear();
    }

    private void openGuide(int cells) {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(() -> activity.liveTv.cells.size() == cells);
    }

    /** Whether a visible text view under {@code view} contains {@code text}. */
    private static boolean shows(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (
            view instanceof android.widget.TextView &&
            ((android.widget.TextView) view).getText().toString().contains(text)
        ) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (shows(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private int cell(String name) {
        int[] found = { -1 };
        remote.runOnMainSync(() -> {
            for (int i = 0; i < activity.liveTv.cells.size(); i++) if (
                ((android.widget.TextView) activity.liveTv.cells.get(i)).getText().toString().contains(name)
            ) found[0] = i;
        });
        assertTrue(name + " listed", found[0] >= 0);
        return found[0];
    }

    @Test
    public void theGuidePlaysTheLastChannelInItsCorner() throws Exception {
        try {
            channels(new String[][] { { "picture-test", "Alpha", "/a.m3u8", "" } });
            remote
                .getTargetContext()
                .getSharedPreferences("ui", 0)
                .edit()
                .putString(LiveTvScreen.LAST_CHANNEL, ChannelQuery.key(link("/a.m3u8")))
                .commit();
            openGuide(1);
            await(() -> ExoLive.isPlaying() && rendered() > 5 && !activity.stage.full());
            remote.runOnMainSync(() -> assertEquals(link("/a.m3u8"), ExoLive.currentUrl()));
            visibleVideo();
            UiEvidence.capture("guide-picture");
        } finally {
            restoreChannelPrefs();
        }
    }

    @Test
    public void aBrokenChannelSaysSoInThePictureAndTheRemoteMovesOn() throws Exception {
        try {
            channels(new String[][] {
                { "broken-test", "Alpha", "/a.m3u8", "" },
                { "broken-test-2", "Broken", "/missing.ts", "" },
            });
            openGuide(2);
            int broken = cell("Broken");
            remote.runOnMainSync(() -> activity.liveTv.cells.get(broken).performClick());
            // ExoPlayer keeps retrying a channel with no other copy and says so in the picture.
            await(
                () ->
                    activity.stage.full() &&
                    shows(activity.getWindow().getDecorView(), "Still trying this channel")
            );
            remote.runOnMainSync(() -> assertFalse(ExoLive.isPlaying()));
            // The failure is shown in the picture, with no panel in the way.
            remote.runOnMainSync(() ->
                assertFalse(
                    "No Can't play panel over Live TV",
                    SidePanel.current != null && SidePanel.current.isShowing()
                )
            );
            UiEvidence.capture("live-channel-not-working");
            // The remote goes straight on to the other channel.
            remote.sendKeyDownUpSync(broken == 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
            await(() -> ExoLive.isPlaying() && rendered() > 5);
            remote.runOnMainSync(() -> assertEquals(link("/a.m3u8"), ExoLive.currentUrl()));
        } finally {
            restoreChannelPrefs();
        }
    }

    @Test
    public void aManualBackupSurvivesGuideAndFullScreenWithoutReopening() throws Exception {
        try {
            String name = "Sky Sports Main Event UK",
                epg = "SkySportsMainEvent.uk";
            channels(new String[][] {
                { "m3u", name, "/main.m3u8", epg },
                { "free", name, "/backup.m3u8", epg },
            });
            openGuide(1);
            remote.runOnMainSync(() -> activity.liveTv.cells.get(0).performClick());
            await(() -> activity.stage.full() && ExoLive.isPlaying() && rendered() > 5);
            await(() -> activity.stage.zapper() != null && activity.stage.zapper().copies().size() == 2);
            String[] chosen = { null };
            remote.runOnMainSync(() -> {
                LiveZapper zapper = activity.stage.zapper();
                // Choose whichever copy is not playing (copies are ordered by source).
                boolean onBackup = link("/backup.m3u8").equals(ExoLive.currentUrl());
                String wanted = onBackup ? "m3u" : "free";
                chosen[0] = link(onBackup ? "/main.m3u8" : "/backup.m3u8");
                int other = -1;
                for (int i = 0; i < zapper.copies().size(); i++) if (
                    wanted.equals(zapper.copies().get(i).source)
                ) other = i;
                assertTrue("The other copy is listed", other >= 0);
                zapper.useCopy(other, false);
            });
            await(() -> ExoLive.isPlaying() && rendered() > 5 && chosen[0].equals(ExoLive.currentUrl()));
            await(() -> !activity.stage.zapper().automaticSources());
            int requests = opens.get();
            ExoPlayer[] original = { null };
            remote.runOnMainSync(() -> original[0] = ExoLive.player());
            for (int trip = 0; trip < 2; trip++) {
                remote.runOnMainSync(() -> activity.stage.leave());
                picturesAdvance();
                remote.runOnMainSync(() -> activity.liveTv.cells.get(0).performClick());
                await(() -> activity.stage.full());
                picturesAdvance();
                remote.runOnMainSync(() -> {
                    assertEquals("The chosen copy carries on", chosen[0], ExoLive.currentUrl());
                    assertSame("The same player survives", original[0], ExoLive.player());
                    assertFalse("Manual mode is kept", activity.stage.zapper().automaticSources());
                });
            }
            assertEquals("Guide and full screen never reopen the chosen copy", requests, opens.get());
            UiEvidence.capture("live-manual-backup");
        } finally {
            restoreChannelPrefs();
        }
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
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("http", oldHttp)
            .putBoolean("autoUpdates", oldAutoUpdates)
            .commit();
        LiveZapper.session = null;
    }
}
