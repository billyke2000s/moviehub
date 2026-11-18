package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

/** The first-start TV or phone question, and the phone layout: the TV pages, sideways, by touch. */
@RunWith(AndroidJUnit4.class)
public class PhoneModeTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    final Context context = remote.getTargetContext();
    final SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    MainActivity activity;
    String free;
    boolean updates;

    @Before
    public void start() throws Exception {
        free = prefs.getString(FreeChannels.PREF, null);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs
            .edit()
            .remove(DeviceMode.PREF)
            .putString(FreeChannels.PREF, "off")
            .putBoolean("autoUpdates", false)
            .commit();
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("phone-test", () -> {}, n -> {});
        try {
            for (String name : new String[] { "Alpha", "Bravo", "Charlie" })
                writer.accept(
                    new Playlist.Channel(name, "https://example.test/" + name + ".m3u8", "General", "", "")
                );
            writer.commit();
        } finally {
            writer.abort();
        }
    }

    @After
    public void stop() {
        if (activity != null) remote.runOnMainSync(() -> activity.finish());
        SharedPreferences.Editor edit = prefs
            .edit()
            .putString(DeviceMode.PREF, DeviceMode.TV)
            .putBoolean("autoUpdates", updates);
        if (free == null) edit.remove(FreeChannels.PREF);
        else edit.putString(FreeChannels.PREF, free);
        edit.commit();
        LiveZapper.session = null;
        ChannelDatabase.get(context).clear();
    }

    void await(String what, java.util.function.BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            remote.runOnMainSync(() -> ok.set(check.getAsBoolean()));
            if (ok.get()) return;
            SystemClock.sleep(80);
        }
        fail("Timed out: " + what);
    }

    static Button find(View view, String start) {
        if (
            view instanceof Button && ((TextView) view).getText().toString().startsWith(start)
        ) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = find(((ViewGroup) view).getChildAt(i), start);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The MainActivity on screen now (choosing the phone layout rebuilds the screen). Call on the
     * main thread, as {@link #await}'s checks are.
     */
    static MainActivity resumed() {
        MainActivity found = null;
        for (android.app.Activity each : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(
            Stage.RESUMED
        ))
            if (each instanceof MainActivity) found = (MainActivity) each;
        return found;
    }

    static boolean shows(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            if (shows(((ViewGroup) view).getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test
    public void asksOnFirstStartThenShowsTheTvPagesSidewaysForTouch() {
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        AtomicReference<Button> phone = new AtomicReference<>();
        await("the question", () -> {
            phone.set(find(activity.page, "📱"));
            return phone.get() != null && find(activity.page, "📺") != null;
        });
        UiEvidence.capture("layout-question");
        MainActivity first = activity;
        remote.runOnMainSync(() -> phone.get().performClick());

        // The screen is rebuilt with the phone scale and opens Home: the same pages as the TV.
        AtomicReference<MainActivity> phoneScreen = new AtomicReference<>();
        await("phone Home", () -> {
            MainActivity now = resumed();
            if (now == null || now == first || !DeviceMode.phone(context)) return false;
            phoneScreen.set(now);
            return shows(now.page, "Live TV") && shows(now.page, "Settings");
        });
        activity = phoneScreen.get();
        remote.runOnMainSync(() ->
            assertEquals(
                "sideways, either way round",
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                activity.getRequestedOrientation()
            )
        );
        remote.runOnMainSync(() -> {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
            assertEquals(
                "density set by PhoneScale",
                PhoneScale.densityDpi(shortSide),
                activity.getResources().getConfiguration().densityDpi,
                1
            );
        });
        UiEvidence.capture("phone-home");

        // Live TV is the TV guide; a channel goes full screen with the touch buttons on its banner.
        remote.runOnMainSync(() -> activity.liveTv.show("all", ""));
        ChannelDatabase.Row row = ChannelDatabase.get(context)
            .browse("all", "", java.util.Collections.emptySet(), 0)
            .get(1);
        remote.runOnMainSync(() -> activity.liveTv.play(row, 1));
        await(
            "touch controls",
            () ->
                activity.stage != null &&
                activity.stage.full() &&
                activity.stage.zapper() != null &&
                activity.stage.zapper().touch &&
                activity.stage.zapper().position() == 1
        );
        UiEvidence.capture("phone-live-full-screen");
        remote.runOnMainSync(() -> activity.stage.leave());
    }
}
