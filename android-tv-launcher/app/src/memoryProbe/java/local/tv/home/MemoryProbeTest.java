package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Memory probe, not a pass/fail feature test. Built only with -PmemoryProbe (and -Pexperimental),
 * so normal device tests never run it. It drives the heaviest real use: every free service on, a
 * full channel refresh from the live services, paging the guide, then watching a channel. The
 * workflow's shell script samples memory from outside while this runs and reads the phase markers
 * logged here under the "MemoryProbe" tag. Live services may fail from a CI runner; that is
 * recorded, not treated as a failure. The test fails only if MovieHub stops running.
 */
@RunWith(AndroidJUnit4.class)
public class MemoryProbeTest {

    static final String TAG = "MemoryProbe";
    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;

    static void phase(String name) {
        Log.i(TAG, "phase=" + name + " at=" + SystemClock.uptimeMillis());
    }

    boolean waitFor(long ms, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + ms;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return true;
            SystemClock.sleep(1000);
        }
        return false;
    }

    @Test
    public void heaviestRealUse() {
        Context context = remote.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        prefs.edit().putBoolean("autoUpdates", false).putString(FreeChannels.PREF, "uk").commit();
        phase("launch");
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
        SystemClock.sleep(15_000);

        phase("refresh");
        remote.runOnMainSync(() -> {
            ProviderSettings.enableAll(activity);
            activity.liveTv.live();
        });
        // Let the refresh start, then wait for it to finish (or give up after 15 minutes).
        SystemClock.sleep(5_000);
        boolean finished = waitFor(15 * 60_000, () -> {
            ChannelImporter.Status s = ChannelImporter.status(activity);
            return !s.running && !s.queued;
        });
        int[] count = new int[1];
        try {
            count[0] = ChannelDatabase.get(context).listCount("all", "", java.util.Collections.emptySet());
        } catch (Exception e) {
            Log.w(TAG, "count failed: " + e.getClass().getSimpleName());
        }
        Log.i(TAG, "refreshFinished=" + finished + " channels=" + count[0]);

        phase("guide");
        remote.runOnMainSync(() -> activity.liveTv.live());
        SystemClock.sleep(5_000);
        for (int i = 0; i < 20; i++) {
            remote.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_PAGE_DOWN);
            SystemClock.sleep(1_500);
        }
        SystemClock.sleep(10_000);

        phase("watch");
        boolean played = false;
        try {
            List<ChannelDatabase.Row> rows = ChannelDatabase.get(context).browse(
                "all",
                "",
                java.util.Collections.emptySet(),
                0,
                1
            );
            if (!rows.isEmpty()) {
                ChannelDatabase.Row first = rows.get(0);
                Log.i(TAG, "watching channel number=" + first.number);
                remote.runOnMainSync(() -> activity.liveTv.open(first, 0));
                played = waitFor(60_000, ExoLive::isPlaying);
                SystemClock.sleep(60_000);
            }
        } catch (Exception e) {
            Log.w(TAG, "watch failed: " + e.getClass().getSimpleName());
        }
        Log.i(TAG, "picturePlayed=" + played);

        phase("back-to-guide");
        remote.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(20_000);
        phase("done");

        AtomicBoolean alive = new AtomicBoolean();
        remote.runOnMainSync(() -> alive.set(!activity.isFinishing() && !activity.isDestroyed()));
        assertTrue("MovieHub is still running", alive.get());
        remote.runOnMainSync(() -> activity.finish());
    }
}
