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
    public void physicalColoursKeepWorkingWhileAnotherPlayerPanelOwnsFocus() {
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_RED);
        await(() -> SidePanel.current != null && SidePanel.current.isShowing());
        android.app.Dialog audio = SidePanel.current;
        await(() -> audio.getWindow().getDecorView().hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
        await(() -> SidePanel.current != null && SidePanel.current != audio);
        android.app.Dialog sources = SidePanel.current;
        assertFalse("Green closes Audio", audio.isShowing());
        await(() -> sources.getWindow().getDecorView().hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        await(() -> SidePanel.current != null && SidePanel.current != sources);
        android.app.Dialog options = SidePanel.current;
        assertFalse("Blue closes Sources", sources.isShowing());
        await(() -> options.getWindow().getDecorView().hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_YELLOW);
        await(() -> SidePanel.current == null && activity.zapper.overlay.infoShowing());
        assertFalse("Yellow closes Options", options.isShowing());
        assertEquals("Colour shortcuts never restart the source", 1, resolutions.get());
    }

    @Test
    public void completeHeaderSurvivesProgrammeUpdatesAndBackControls() {
        remote.runOnMainSync(() -> {
            activity.zapper.overlay.hideBanner();
            activity.zapper.overlay.typing("325");
            activity.zapper.overlay.hideBanner();
            activity.zapper.overlay.banner(
                "325",
                "Pluto TV Movies",
                "Black Knight",
                "Now 12.49pm",
                "The Way Back (2011)",
                "Next 2.39pm",
                "A long programme description that wraps onto two lines while the channel header stays visible.",
                600,
                "Sources · Auto · Pluto TV · 1 of 3",
                false
            );
            activity.zapper.overlay.focusSources();
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        remote.runOnMainSync(() -> {
            android.widget.LinearLayout strip = activity.zapper.overlay.channelStrip;
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue(strip.getGlobalVisibleRect(visible));
            assertTrue("Full channel strip has reserved height", strip.getHeight() >= activity.dp(64));
            assertEquals("Header is not vertically clipped", strip.getHeight(), visible.height());
            for (int i = 0; i < strip.getChildCount(); i++) {
                android.view.View label = strip.getChildAt(i);
                assertTrue("Each channel/header label is visible", label.getGlobalVisibleRect(visible));
                assertEquals("Header label is not vertically clipped", label.getHeight(), visible.height());
                assertTrue("Header label has width", visible.width() > 0);
            }
        });
        UiEvidence.capture("live-complete-channel-header");
    }

    private boolean containsText(android.view.View view, String expected) {
        if (
            view instanceof android.widget.TextView &&
            expected.contentEquals(((android.widget.TextView) view).getText())
        ) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (
                containsText(group.getChildAt(i), expected)
            ) return true;
        }
        return false;
    }

    private void playerKey(int action, int key, int repeat) {
        playerKey(new KeyEvent(1, 2, action, key, repeat));
    }

    private void playerKey(KeyEvent event) {
        remote.runOnMainSync(() -> {
            if (SidePanel.current != null) SidePanel.current.dispatchKeyEvent(event);
            else activity.dispatchKeyEvent(event);
        });
        remote.waitForIdleSync();
    }

    @Test
    public void colourPressWorksBeforeReleaseAndDoesNotRepeatAcrossWindows() {
        remote.runOnMainSync(() -> activity.zapper.overlay.focusSources());
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0);
        android.app.Dialog audio = SidePanel.current;
        assertNotNull("A press opens Audio without waiting for release", audio);
        assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"));
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 0);
        assertSame("The release must not reopen Audio", audio, SidePanel.current);
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_GREEN, 0);
        android.app.Dialog sources = SidePanel.current;
        assertNotSame("Green works while a panel owns input", audio, sources);
        assertTrue(containsText(sources.getWindow().getDecorView(), "Sources"));
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_GREEN, 1);
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_GREEN, 0);
        assertSame("Held/released green must not reopen Sources", sources, SidePanel.current);
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_BLUE, 0);
        android.app.Dialog options = SidePanel.current;
        assertTrue(containsText(options.getWindow().getDecorView(), "Options"));
        playerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW, 0);
        assertNull(SidePanel.current);
        assertTrue(activity.zapper.overlay.infoShowing());
        playerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW, 0);
        assertTrue("Yellow release must not close Information again", activity.zapper.overlay.infoShowing());
    }

    @Test
    public void standardColoursCannotBeSwappedByOldLearnedAliases() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        java.util.Map<String, ?> previous = prefs.getAll();
        try {
            prefs
                .edit()
                .putInt("remoteRed", KeyEvent.KEYCODE_PROG_GREEN)
                .putInt("remoteGreen", KeyEvent.KEYCODE_PROG_BLUE)
                .putInt("remoteYellow", KeyEvent.KEYCODE_F1)
                .putInt("remoteBlue", KeyEvent.KEYCODE_PROG_YELLOW)
                .commit();
            assertEquals('r', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_RED));
            assertEquals('g', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_GREEN));
            assertEquals('y', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_YELLOW));
            assertEquals('b', LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_PROG_BLUE));
            assertEquals(
                "Nonstandard learned aliases are retained",
                'y',
                LiveTvScreen.colour(prefs, KeyEvent.KEYCODE_F1)
            );
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
            await(() -> SidePanel.current != null && SidePanel.current.isShowing());
            remote.runOnMainSync(() ->
                assertTrue(containsText(SidePanel.current.getWindow().getDecorView(), "Sources"))
            );
        } finally {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : LiveTvScreen.COLOUR_PREFS) {
                if (previous.containsKey(key)) edit.putInt(key, (Integer) previous.get(key));
                else edit.remove(key);
            }
            edit.commit();
        }
    }

    @Test
    public void bannerKeepsFullHeaderAndColoursWhenOptionalPanelsExceedTheViewport() {
        android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
        SkyOverlay[] overlay = new SkyOverlay[1];
        remote.runOnMainSync(() -> {
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            content.addView(host, new android.widget.FrameLayout.LayoutParams(-1, activity.dp(260)));
            overlay[0] = new SkyOverlay(activity, host);
            overlay[0].banner(
                "407",
                "Sky Sports F1 (720p)",
                "No programme information",
                "",
                "",
                "",
                "",
                -1,
                "Sources · Auto · FreeShot (UK sports) · 2 of 3",
                false
            );
            overlay[0].info(
                "407 Sky Sports F1",
                "Programme information",
                "19:42",
                "Line one\nLine two\nLine three\nLine four\nLine five\nLine six\nLine seven\nLine eight"
            );
            overlay[0].pause(true, true);
            overlay[0].focusSources();
        });
        try {
            remote.waitForIdleSync();
            SystemClock.sleep(250);
            remote.runOnMainSync(() -> {
                android.graphics.Rect visible = new android.graphics.Rect();
                android.view.View strip = overlay[0].channelStrip;
                assertTrue("Full header remains shown in limited space", strip.getGlobalVisibleRect(visible));
                assertTrue(strip.getHeight() >= activity.dp(64));
                assertEquals(
                    "Header never gives up its height to optional panels",
                    strip.getHeight(),
                    visible.height()
                );
                for (android.view.View control : overlay[0].controls) {
                    assertTrue(
                        "Source and colour buttons remain visible",
                        control.getGlobalVisibleRect(visible)
                    );
                    assertEquals(control.getHeight(), visible.height());
                }
            });
            UiEvidence.capture("live-banner-constrained-viewport");
        } finally {
            remote.runOnMainSync(() -> ((android.view.ViewGroup) host.getParent()).removeView(host));
        }
    }

    @Test
    public void entryOkClickAndBackUseTheSameFullBanner() {
        remote.runOnMainSync(() -> {
            assertTrue("Live input focuses the visible video", activity.video.hasFocus());
            assertNull("Live TV never attaches the film overlay", activity.controls.getParent());
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        android.graphics.Rect initial = new android.graphics.Rect();
        remote.runOnMainSync(() ->
            assertTrue(activity.zapper.overlay.channelStrip.getGlobalVisibleRect(initial))
        );
        for (int i = 0; i < 3; i++) {
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            remote.waitForIdleSync();
            assertNull("OK refreshes the banner rather than opening Options", SidePanel.current);
            assertTrue(activity.zapper.overlay.bannerShowing());
        }
        remote.runOnMainSync(() -> {
            activity.zapper.overlay.hideBanner();
            ((android.view.View) activity.video.getParent()).performClick();
            assertTrue("Click uses the same live banner", activity.zapper.overlay.bannerShowing());
        });
        remote.waitForIdleSync();
        SystemClock.sleep(250);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            android.graphics.Rect after = new android.graphics.Rect();
            assertTrue(activity.zapper.overlay.channelStrip.getGlobalVisibleRect(after));
            assertEquals("Every reveal retains the complete header layout", initial, after);
            assertEquals(
                "Back only enables focus on the existing controls",
                0,
                activity.zapper.overlay.focusedControl()
            );
            assertNull(SidePanel.current);
            assertEquals(android.view.View.GONE, activity.controls.getVisibility());
        });
        UiEvidence.capture("live-entry-ok-click-back-one-banner");
    }

    private KeyEvent remoteEvent(int action, int key, int scan, int repeat, long pressTime) {
        return new KeyEvent(
            pressTime,
            pressTime + 1,
            action,
            key,
            repeat,
            0,
            77,
            scan,
            0,
            android.view.InputDevice.SOURCE_DPAD
        );
    }

    @Test
    public void standardColourDeliveryVariantsShareOneActionAcrossWindows() {
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 398, 2, 100));
        android.app.Dialog audio = SidePanel.current;
        assertNotNull("First received press need not have repeat count zero", audio);
        assertTrue(containsText(audio.getWindow().getDecorView(), "Audio & Subtitles"));
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 398, 3, 100));
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 398, 0, 100));
        assertSame("A held press and release crossing windows run only once", audio, SidePanel.current);
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_GREEN, 399, 0, 200));
        android.app.Dialog sources = SidePanel.current;
        assertTrue(
            "Release-only green still opens Sources",
            containsText(sources.getWindow().getDecorView(), "Sources")
        );
        playerKey(remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 401, 0, 300));
        android.app.Dialog options = SidePanel.current;
        assertTrue(
            "Android's standard blue scan identity works without translation",
            containsText(options.getWindow().getDecorView(), "Options")
        );
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 401, 0, 300));
        assertSame(options, SidePanel.current);
        playerKey(remoteEvent(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_PROG_YELLOW, 400, 1, 400));
        assertNull(SidePanel.current);
        assertTrue(activity.zapper.overlay.infoShowing());
        playerKey(remoteEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW, 400, 0, 400));
        assertTrue(
            "The final release does not toggle Information off",
            activity.zapper.overlay.infoShowing()
        );
        android.content.SharedPreferences prefs = activity.getSharedPreferences("ui", 0);
        for (int i = 0; i < 4; i++) assertEquals(
            LiveTvScreen.COLOURS[i],
            LiveTvScreen.colour(
                prefs,
                remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 398 + i, 0, 500 + i)
            )
        );
        assertEquals(
            "A known non-colour key is never remapped using its scan",
            (char) 0,
            LiveTvScreen.colour(
                prefs,
                remoteEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 398, 0, 600)
            )
        );
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
