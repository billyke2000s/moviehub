package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/** Live controls and source discovery while a selected resolver is still pending. */
@RunWith(AndroidJUnit4.class)
public final class LiveControlsTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private VlcPlaybackActivity activity;
    private ChannelDatabase db;

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue("Live controls condition timed out", condition.getAsBoolean());
    }

    @Before
    public void open() throws Exception {
        Context context = remote.getTargetContext();
        remote.runOnMainSync(LivePlayer::stop);
        db = ChannelDatabase.get(context);
        db.clear();
        String[] ids = { "5ad8d3a31b95267e225e4e09", "6304f8d5ffb61000071ab478" };
        String[] providers = { "nativepluto", "free" };
        for (int i = 0; i < ids.length; i++) {
            ChannelDatabase.Writer writer = db.writer(providers[i], () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        "Sky Sports Main Event",
                        PlutoSource.ROOT + ids[i],
                        "Sports",
                        "",
                        "SkySportsMainEvent.uk"
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        var channel = db.browse("all", "", Collections.emptySet(), 0).get(0);
        assertEquals(2, db.copies(channel).size());
        LivePlayer.testResolver = (locator, http) -> {
            resolutions.incrementAndGet();
            release.await(30, TimeUnit.SECONDS);
            throw new IOException("Fixture resolver stopped");
        };
        // Cold/direct live launches have no process-global guide session.
        LiveZapper.session = null;
        activity = (VlcPlaybackActivity) remote.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", db.url(channel.id, channel.key))
                .putExtra("id", channel.key)
                .putExtra("title", channel.name)
                .putExtra("live", true)
        );
        await(
            () -> activity.zapper != null && activity.zapper.copies().size() == 2 && resolutions.get() == 1
        );
    }

    @After
    public void close() {
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null && SidePanel.current.isShowing()) SidePanel.current.dismiss();
            if (activity != null) activity.finish();
            LivePlayer.stop();
        });
        release.countDown();
        remote.waitForIdleSync();
        LivePlayer.testResolver = null;
        LiveZapper.session = null;
        if (db != null) db.clear();
    }

    @Test
    public void backRevealsSourcesBeforeLeavingEvenWhenBannerWasAlreadyVisible() {
        var samePlayer = LivePlayer.player();
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        assertFalse(activity.isFinishing());
        assertTrue(activity.zapper.overlay.bannerShowing());
        assertSame(samePlayer, LivePlayer.player());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertNotNull(SidePanel.current);
        assertTrue("SELECT opens Sources", SidePanel.current.isShowing());
        assertEquals("Opening controls and Sources must not resolve backups", 1, resolutions.get());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !activity.dialogOpen && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(activity::isFinishing);
    }

    @Test
    public void bannerButtonsWorkWithoutColourKeysAndAutoNamesTheProvider() {
        remote.runOnMainSync(() ->
            assertEquals(
                "Auto mode names the provider playing now",
                "Sources · Auto · Pluto TV · 1 of 2",
                activity.zapper.sourceText()
            )
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        assertEquals("BACK focuses Sources", 0, activity.zapper.overlay.focusedControl());
        for (int i = 0; i < 3; i++) remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.waitForIdleSync();
        assertEquals("▶ moves along to Information", 3, activity.zapper.overlay.focusedControl());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertTrue("SELECT presses Information", activity.zapper.overlay.infoShowing());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.waitForIdleSync();
        assertEquals("▶ stops at Options, the last button", 4, activity.zapper.overlay.focusedControl());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        remote.waitForIdleSync();
        assertNotNull(SidePanel.current);
        assertTrue("SELECT presses Options", SidePanel.current.isShowing());
        assertEquals("The buttons must not resolve backups", 1, resolutions.get());
        // Leave as the other tests do: close the panels, then BACK out of the player, so no
        // window from this test is left over the next test's video.
        remote.runOnMainSync(() -> {
            SidePanel.current.dismiss();
            activity.zapper.hideInfo();
        });
        remote.waitForIdleSync();
        await(() -> !activity.dialogOpen && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(activity::isFinishing);
    }

    @Test
    public void manualChoicePinsOneSourceAndAutomaticCanBeRestoredWithoutEagerProbing() {
        assertTrue(activity.zapper.automaticSources());
        assertEquals("Loading the channel did not probe its backup", 1, resolutions.get());
        remote.runOnMainSync(() -> activity.zapper.useCopy(0, false));
        await(() -> !activity.zapper.automaticSources());
        assertEquals(
            "Selected channel survives Activity recreation",
            activity.playbackUrl(),
            activity.getIntent().getStringExtra("url")
        );
        remote.runOnMainSync(() ->
            assertFalse(
                "Manual mode must stay on the selected source",
                activity.zapper.failover("fixture failure")
            )
        );
        int before = resolutions.get();
        SystemClock.sleep(500);
        assertEquals("No background backup discovery", before, resolutions.get());
        remote.runOnMainSync(() -> {
            activity.zapper.automaticSources(true);
            assertTrue(
                "Automatic mode can try the next saved copy",
                activity.zapper.failover("fixture failure")
            );
        });
        await(() -> activity.zapper.selectedCopy() == 1 && resolutions.get() > before);
        remote.runOnMainSync(() ->
            assertFalse(
                "Automatic mode stops after the last copy",
                activity.zapper.failover("fixture failure")
            )
        );
    }

    @Test
    public void automaticRestoredOnTheLastManualSourceTriesEarlierCopiesOnlyOnce() {
        remote.runOnMainSync(() -> activity.zapper.useCopy(1, false));
        await(() -> activity.zapper.selectedCopy() == 1 && resolutions.get() == 2);
        remote.runOnMainSync(() -> {
            activity.zapper.automaticSources(true);
            assertTrue(activity.zapper.failover("fixture failure"));
        });
        await(() -> activity.zapper.selectedCopy() == 0 && resolutions.get() == 3);
        remote.runOnMainSync(() ->
            assertFalse(
                "No automatic loop back to a tried source",
                activity.zapper.failover("fixture failure")
            )
        );
    }

    @Test
    public void physicalColourButtonsOpenTheirAdvertisedPlayerActions() {
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_RED);
        remote.waitForIdleSync();
        assertTrue("Red opens Audio & subtitles", SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !activity.dialogOpen && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
        remote.waitForIdleSync();
        assertTrue("Green opens Sources", SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        remote.waitForIdleSync();
        await(() -> !activity.dialogOpen && SidePanel.current == null && activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_YELLOW);
        remote.waitForIdleSync();
        assertTrue("Yellow opens information", activity.zapper.overlay.infoShowing());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        remote.waitForIdleSync();
        assertTrue("Blue opens Options", SidePanel.current != null && SidePanel.current.isShowing());
    }

    @Test
    public void recreatedPlayerKeepsLiveControlsWithoutStaticGuideState() {
        android.app.Instrumentation.ActivityMonitor monitor = remote.addMonitor(
            VlcPlaybackActivity.class.getName(),
            null,
            false
        );
        remote.runOnMainSync(() -> {
            LiveZapper.session = null;
            activity.recreate();
        });
        VlcPlaybackActivity recreated = (VlcPlaybackActivity) remote.waitForMonitorWithTimeout(
            monitor,
            10000
        );
        remote.removeMonitor(monitor);
        assertNotNull("Player recreation completed", recreated);
        activity = recreated;
        await(
            () -> activity.zapper != null && activity.zapper.copies().size() == 2 && activity.hasWindowFocus()
        );
        assertEquals(android.view.View.GONE, activity.controls.getVisibility());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
        await(() -> SidePanel.current != null && SidePanel.current.isShowing());
        assertTrue(activity.zapper.sourceText().contains("Pluto TV"));
    }

    @Test
    public void learnedColourWinsOverNumberEntryWithControlsHidden() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        boolean existed = prefs.contains("remoteGreen");
        int previous = prefs.getInt("remoteGreen", -1);
        try {
            prefs.edit().putInt("remoteGreen", KeyEvent.KEYCODE_7).commit();
            remote.runOnMainSync(() -> activity.zapper.overlay.hideBanner());
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_7);
            await(() -> SidePanel.current != null && SidePanel.current.isShowing());
            assertEquals("Colour did not change channel", 0, activity.zapper.position());
            assertEquals("Colour did not resolve another source", 1, resolutions.get());
        } finally {
            if (existed) prefs.edit().putInt("remoteGreen", previous).commit();
            else prefs.edit().remove("remoteGreen").commit();
        }
    }

    @Test
    public void bannerColourButtonsFitWithLongSourceAndChannelNames() {
        remote.runOnMainSync(() ->
            activity.zapper.overlay.banner(
                "401",
                "A very long channel name with region and quality information",
                "Programme on now",
                "Now 12:00",
                "Next programme",
                "Next 13:00",
                "Description",
                500,
                "Sources · Auto · A provider with a very long name · 10 of 100",
                false
            )
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            android.graphics.Rect screen = new android.graphics.Rect();
            activity.getWindow().getDecorView().getGlobalVisibleRect(screen);
            for (android.view.View control : activity.zapper.overlay.controls) {
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("Every colour/source control is visible", control.getGlobalVisibleRect(visible));
                assertEquals("Control is not clipped horizontally", control.getWidth(), visible.width());
                assertTrue("Control stays inside the display", screen.contains(visible));
            }
        });
    }

    @Test
    public void guideSnapshotSurvivesAndDoesNotShareMutableStaticPosition() {
        LiveZapper.Session original = new LiveZapper.Session(
            "favourites",
            "sports",
            Collections.singleton("fixture-key"),
            4
        );
        android.os.Bundle snapshot = LiveGuideState.save(original);
        original.position = 99;
        LiveZapper.session = null;
        LiveZapper.Session restored = LiveGuideState.restore(snapshot);
        assertEquals("favourites", restored.filter);
        assertEquals("sports", restored.search);
        assertEquals(4, restored.position);
        assertTrue(restored.favourites.contains("fixture-key"));
    }
}
