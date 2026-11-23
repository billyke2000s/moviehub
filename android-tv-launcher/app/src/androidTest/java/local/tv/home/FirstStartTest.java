package local.tv.home;

import static org.junit.Assert.*;

import android.app.Dialog;
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

/**
 * The first-start messages: "Connect to the internet" comes first, only while the box is offline,
 * and closes by itself once online; the TMDB question comes last on a box without TMDB. The real
 * network is replaced by {@link ConnectionPrompt#testOnline}, so these do not prove how a real box
 * reports Wi-Fi or a cable.
 */
@RunWith(AndroidJUnit4.class)
public class FirstStartTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    final Context context = remote.getTargetContext();
    final SharedPreferences prefs = context.getSharedPreferences("ui", 0);
    MainActivity activity;
    boolean updates;
    long grace;

    @Before
    public void start() {
        updates = prefs.getBoolean("autoUpdates", true);
        grace = ConnectionPrompt.graceMs;
        ConnectionPrompt.graceMs = 300;
        prefs.edit().putBoolean("autoUpdates", false).commit();
    }

    @After
    public void stop() {
        if (activity != null) {
            remote.runOnMainSync(() -> activity.finish());
            await("activity destroyed", activity::isDestroyed);
        }
        ConnectionPrompt.testOnline = true;
        ConnectionPrompt.graceMs = grace;
        prefs
            .edit()
            .putBoolean(SafetyNotice.PREF, true)
            .putBoolean(FirstStart.TMDB_ASKED, true)
            .putBoolean("autoUpdates", updates)
            .commit();
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

    void open() {
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
    }

    boolean panelShowing() {
        return SidePanel.current != null && SidePanel.current.isShowing();
    }

    void awaitFocus(Dialog panel) {
        await("panel focused", () -> panel.getWindow().getDecorView().hasWindowFocus());
    }

    @Test
    public void offlineAsksToConnectFirstThenClosesItselfWhenOnline() {
        prefs.edit().putBoolean(SafetyNotice.PREF, false).commit();
        ConnectionPrompt.testOnline = false;
        open();
        await("connect panel shown", () -> activity.connection.showing());
        UiEvidence.capture("connect-to-internet");
        Dialog connect = SidePanel.current;
        // The safety notice waits until the box is online.
        assertFalse(SafetyNotice.seen(context));

        ConnectionPrompt.testOnline = true;
        remote.runOnMainSync(activity.connection::check);
        await("connect panel closed", () -> !activity.connection.showing() && !connect.isShowing());
        await("safety notice follows", () -> panelShowing() && SidePanel.current != connect);
        awaitFocus(SidePanel.current);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("notice read", () -> SafetyNotice.seen(context));
    }

    @Test
    public void backOnTheConnectPanelLetsTheOtherMessagesFollow() {
        prefs.edit().putBoolean(SafetyNotice.PREF, false).commit();
        ConnectionPrompt.testOnline = false;
        open();
        await("connect panel shown", () -> activity.connection.showing());
        Dialog connect = SidePanel.current;
        awaitFocus(connect);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("safety notice while still offline", () -> panelShowing() && SidePanel.current != connect);
        // Back is "Not now": the connect panel does not come back while still offline.
        remote.runOnMainSync(activity.connection::check);
        assertFalse(activity.connection.showing());
        awaitFocus(SidePanel.current);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("notice read", () -> SafetyNotice.seen(context));
    }

    @Test
    public void onlineShowsNoConnectPanel() {
        open();
        SystemClock.sleep(ConnectionPrompt.graceMs + 500);
        AtomicBoolean showing = new AtomicBoolean();
        remote.runOnMainSync(() -> showing.set(activity.connection.showing()));
        assertFalse(showing.get());
    }

    @Test
    public void newBoxWithoutTmdbIsAskedOnceAfterTheSafetyNotice() {
        Assume.assumeFalse("This emulator already has TMDB", new Secrets(context).has("tmdb"));
        prefs.edit().putBoolean(FirstStart.TMDB_ASKED, false).commit();
        open();
        await("TMDB question shown", this::panelShowing);
        UiEvidence.capture("tmdb-question");
        Dialog question = SidePanel.current;
        awaitFocus(question);
        // Back leaves it unanswered: asked again next time.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("question closed", () -> !question.isShowing());
        assertFalse(prefs.getBoolean(FirstStart.TMDB_ASKED, false));

        remote.runOnMainSync(() -> FirstStart.askTmdbOnce(activity));
        await("asked again", () -> panelShowing() && SidePanel.current != question);
        awaitFocus(SidePanel.current);
        // "Set up TMDB" has the focus; "Later" is beside it.
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("answered", () -> prefs.getBoolean(FirstStart.TMDB_ASKED, false));
    }
}
