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
        remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs.edit().putBoolean("autoUpdates", updates);
        if (free == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, free);
        edit.commit();
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
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await(
            "back up to the previous page, last row",
            () -> activity.liveTv.pageStart == 10 && focused().startsWith("120  Channel 19")
        );
    }
}
