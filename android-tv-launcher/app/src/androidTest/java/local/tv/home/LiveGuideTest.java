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
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_GREEN);
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
        // Yellow: the same time tomorrow, then back to now.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_YELLOW);
        await(
            "tomorrow",
            () -> activity.liveTv.windowOffset == LiveTvScreen.MAX_OFFSET && activity.liveTv.cells.size() == 5
        );
        UiEvidence.capture("live-guide-tomorrow");
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_PROG_YELLOW);
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
}
