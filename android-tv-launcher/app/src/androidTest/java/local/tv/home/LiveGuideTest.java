package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;

/** The classic-guide Live TV grid with fixture channels: numbering, ten per page, paging by D-pad. */
@RunWith(AndroidJUnit4.class)
public class LiveGuideTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    SharedPreferences prefs;
    String free;
    boolean updates;

    @Before
    public void start() throws Exception {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        free = prefs.getString(FreeChannels.PREF, null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putString(FreeChannels.PREF, "off").putBoolean("autoUpdates", false).commit();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("guide-test", () -> {}, n -> {});
        try {
            for (int i = 0; i < 25; i++) writer.accept(
                new Playlist.Channel(
                    String.format(java.util.Locale.ROOT, "Channel %02d", i),
                    "https://example.test/live/" + i + ".m3u8",
                    "News",
                    "",
                    ""
                )
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        if (activity != null) remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
        if (free == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, free);
        edit.commit();
        ParentalLock.clear(remote.getTargetContext());
        ChannelDatabase.get(remote.getTargetContext()).clear();
    }

    void await(String what, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 8000;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        fail("Timed out: " + what);
    }

    String focused() {
        View focus = activity.getCurrentFocus();
        return focus instanceof TextView ? ((TextView) focus).getText().toString() : String.valueOf(focus);
    }

    @Test
    public void tenChannelsPerPageNumberedFrom101AndPagedWithTheRemote() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        UiEvidence.capture("live-guide");
        for (int i = 0; i < 10; i++) {
            remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            remote.waitForIdleSync();
        }
        await(
            "second page",
            () -> activity.liveTv.pageStart == 10 && focused().startsWith("111  Channel 10")
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN); // green: page down
        await("page down", () -> activity.liveTv.pageStart == 20 && activity.liveTv.cells.size() == 5);
        await("on the first row of the last page", () -> focused().startsWith("121  Channel 20"));
        // Up from the top row turns back a page and lands on its last row.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await(
            "back up to the previous page, last row",
            () -> activity.liveTv.pageStart == 10 && focused().startsWith("120  Channel 19")
        );
    }

    @Test
    public void typedNumbersProgrammeInfoAndTheParentalLock() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        // Typing a number goes straight to that channel, as on a satellite box.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_2);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_3);
        await("typed 123", () -> activity.liveTv.pageStart == 20 && focused().startsWith("123  Channel 22"));
        remote.waitForIdleSync();
        // Right moves onto the programmes, keeping the channel names on the left.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await("on the programme", () -> activity.liveTv.rows.programmes.get(2).get(0).hasFocus());
        // Right again, past the last slot, moves the times on half an hour (same row).
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(
            "times moved on",
            () ->
                activity.liveTv.windowOffset == LiveTvScreen.HALF_HOUR &&
                activity.liveTv.rows.programmes.size() == 5 &&
                activity.liveTv.rows.programmes.get(2).get(0).hasFocus()
        );
        UiEvidence.capture("live-guide-later");
        // Left goes back to now, then onto the channel name.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        await(
            "back to now",
            () ->
                activity.liveTv.windowOffset == 0 &&
                activity.liveTv.rows.programmes.size() == 5 &&
                activity.liveTv.rows.programmes.get(2).get(0).hasFocus()
        );
        remote.waitForIdleSync();
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        await("on the channel name", () -> focused().startsWith("123  Channel 22"));
        // i shows the programme information.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_INFO);
        await("programme information", () -> SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        // Options → Tomorrow: the same time tomorrow; Back to today returns.
        remote.runOnMainSync(() -> activity.liveTv.nextDay(2));
        await(
            "tomorrow",
            () -> activity.liveTv.windowOffset == LiveTvScreen.MAX_OFFSET && activity.liveTv.cells.size() == 5
        );
        UiEvidence.capture("live-guide-tomorrow");
        remote.runOnMainSync(() -> activity.liveTv.previousDay(2));
        await("today", () -> activity.liveTv.windowOffset == 0 && activity.liveTv.cells.size() == 5);
        // A locked channel asks for the PIN instead of playing.
        ChannelDatabase.Row row = ChannelDatabase.get(activity)
            .browse("all", "", java.util.Collections.emptySet(), 22)
            .get(0);
        ParentalLock.setPin(activity, "1234");
        ParentalLock.setChannel(activity, row, true);
        await("guide still showing", () -> activity.liveTv.cells.size() == 5);
        remote.runOnMainSync(() -> activity.liveTv.cells.get(2).performClick());
        await("PIN asked", () -> SidePanel.current != null && SidePanel.current.isShowing());
        UiEvidence.capture("live-pin");
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
    }

    @Test
    public void tabsAndTheGuideMenuWorkLikeTheSatelliteGuide() {
        remote.runOnMainSync(() -> activity.liveTv.live());
        await(
            "first page",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        // Up from the top row: the tabs, on All Channels.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("on the tabs", () -> activity.liveTv.frame.tabs.get(0).hasFocus());
        UiEvidence.capture("live-guide-tabs");
        // Right: Favourites, shown straight away with the focus kept on the tabs.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(
            "favourites tab",
            () -> activity.liveTv.filter.equals("favourites") && activity.liveTv.frame.tabs.get(1).hasFocus()
        );
        // Left again and down: back in All Channels on the first row.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        await(
            "all channels tab",
            () -> activity.liveTv.filter.equals("all") && activity.liveTv.cells.size() == 10
        );
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("first row", () -> focused().startsWith("101  Channel 00"));
        // BACK: the guide menu; 1 opens All Channels again.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(
            "guide menu",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        UiEvidence.capture("live-guide-menu");
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_1);
        await(
            "all channels from the menu",
            () -> activity.liveTv.cells.size() == 10 && focused().startsWith("101  Channel 00")
        );
        // Blue: the sections (the guide menu), as on the satellite guide.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_BLUE);
        await(
            "sections",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
    }

    /** Every row of a guide menu is on screen in full (none cut off at the bottom). */
    void assertAllRowsOnScreen(int count) {
        assertEquals(count, activity.liveTv.menu.rows.size());
        for (View row : activity.liveTv.menu.rows) {
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue("menu row visible", row.getGlobalVisibleRect(visible));
            assertTrue("menu row cut off", row.getHeight() > 0 && visible.height() >= row.getHeight());
        }
    }

    @Test
    public void liveTvOpensTheGuideMenuWithEveryRowAndTheTopPartsWorking() {
        // Live TV from Home: the guide menu first, like the TV Guide button.
        remote.runOnMainSync(() -> activity.liveTv.start());
        await(
            "guide menu",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        remote.waitForIdleSync();
        UiEvidence.capture("live-guide-menu-first");
        remote.runOnMainSync(() -> assertAllRowsOnScreen(10));
        // Down to the last row (0 More…) and select: the second page, all on screen too.
        for (int i = 0; i < 9; i++) remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("on More…", () -> activity.liveTv.menu.rows.get(9).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(
            "more",
            () -> activity.liveTv.menu.rows.size() == 6 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> assertAllRowsOnScreen(6));
        // BACK: the main menu again; 9 opens Music in the grid.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("main menu", () -> activity.liveTv.menu.rows.size() == 10);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_9);
        await("music", () -> activity.liveTv.filter.equals("section:Music"));
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(
            "menu after music",
            () -> activity.liveTv.menu.rows.size() == 10 && activity.liveTv.menu.rows.get(0).hasFocus()
        );
        // Up from the first row: the parts along the top; right to Reminders, which opens.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("top parts", () -> activity.liveTv.menu.tabs.get(0).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await("reminders part", () -> activity.liveTv.menu.tabs.get(1).hasFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("reminders open", () -> SidePanel.current != null && SidePanel.current.isShowing());
        remote.runOnMainSync(() -> SidePanel.current.dismiss());
        // Search opens its form (a dialog over the menu, so the menu loses the window focus).
        await("menu focused again", () -> activity.hasWindowFocus());
        remote.runOnMainSync(() -> activity.liveTv.menu.tabs.get(2).requestFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("search open", () -> !activity.hasWindowFocus());
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("search closed", () -> activity.hasWindowFocus());
    }
}
