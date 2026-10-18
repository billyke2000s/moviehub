package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;

/** The safety notice shows when MovieHub first opens and stops showing once read. */
@RunWith(AndroidJUnit4.class)
public class SafetyNoticeTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    final Context context = remote.getTargetContext();
    final SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    MainActivity activity;
    boolean updates;

    @Before
    public void start() {
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean(SafetyNotice.PREF, false).putBoolean("autoUpdates", false).commit();
    }

    @After
    public void stop() {
        if (activity != null) remote.runOnMainSync(() -> activity.finish());
        prefs.edit().putBoolean(SafetyNotice.PREF, true).putBoolean("autoUpdates", updates).commit();
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

    @Test
    public void showsOnFirstStartUntilRead() {
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        await("notice shown", () -> SidePanel.current != null && SidePanel.current.isShowing());
        UiEvidence.capture("safety-notice");
        // "I understand" has the focus.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("read", () -> SafetyNotice.seen(context));
    }
}
