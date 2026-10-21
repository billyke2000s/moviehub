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
        LiveZapper.session = new LiveZapper.Session("all", "", Collections.emptySet(), 0);
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
    }

    @Test
    public void manualChoicePinsOneSourceAndAutomaticCanBeRestoredWithoutEagerProbing() {
        assertTrue(activity.zapper.automaticSources());
        assertEquals("Loading the channel did not probe its backup", 1, resolutions.get());
        remote.runOnMainSync(() -> activity.zapper.useCopy(0, false));
        await(() -> !activity.zapper.automaticSources());
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
}
