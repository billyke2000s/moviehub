package local.tv.home;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Connect to the internet": shown instead of empty, never-loading pages when the box has no
 * network (no Wi-Fi and no cable). It has a moving picture ({@link ConnectionAnimation}) and opens
 * the box's own Wi-Fi or network settings. It closes by itself as soon as the box is online, and
 * Home then loads again.
 *
 * <p>It also holds back the first-start messages ({@link FirstStart}) until the box is online or
 * the person chooses "Not now", so the first thing a new box shows without internet is how to
 * connect it.
 *
 * <p>Rules: a box that has only just started gets {@link #graceMs} to join its Wi-Fi before the
 * panel appears, and so does a short drop. "Not now" (or Back) hides it until the connection comes
 * back and is lost again, or MovieHub is opened again. It is shown only while MovieHub is on
 * screen, never over another app, the player or Android's settings, and never over another panel.
 */
final class ConnectionPrompt {

    static final String TITLE = "Connect to the internet";
    static final String TEXT =
        "MovieHub needs the internet for TV, films and series. Connect to your Wi-Fi, or plug an " +
        "internet cable from your router into the box. This closes by itself once you're online.";

    /** How long the box may be offline before the panel appears. Tests shorten it. */
    static long graceMs = 8000;
    /** Test hook: when set, used instead of the box's real network state. */
    static Boolean testOnline;

    private final MainActivity activity;
    private final Runnable check = this::check;
    /** The first-start messages, run once the box is online or the person says "Not now". */
    private Runnable firstStart;
    private Dialog panel;
    /** True after "Not now" or Back, until the connection changes. */
    private boolean snoozed;
    /** Set when the panel closes because of a button, so its dismissal is not taken as "Not now". */
    private boolean handled;
    /** True once the panel has been shown and the box has not been online since. */
    private boolean wasOffline;
    private boolean resumed;
    private ConnectivityManager.NetworkCallback callback;
    private Application.ActivityLifecycleCallbacks lifecycle;

    private ConnectionPrompt(MainActivity activity, Runnable firstStart) {
        this.activity = activity;
        this.firstStart = firstStart;
    }

    /** Starts watching the connection while {@code activity} is on screen. */
    static ConnectionPrompt start(MainActivity activity, Runnable firstStart) {
        ConnectionPrompt prompt = new ConnectionPrompt(activity, firstStart);
        prompt.lifecycle = new Lifecycle() {
            @Override
            public void onActivityResumed(Activity resumedActivity) {
                if (resumedActivity == activity) prompt.resumed();
            }

            @Override
            public void onActivityPaused(Activity paused) {
                if (paused == activity) prompt.paused();
            }

            @Override
            public void onActivityDestroyed(Activity destroyed) {
                if (destroyed != activity) return;
                prompt.paused();
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(prompt.lifecycle);
        return prompt;
    }

    /** True when the box has a network that reaches the internet (Wi-Fi or cable). */
    static boolean online(Context context) {
        if (testOnline != null) return testOnline;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            android.net.Network network = manager.getActiveNetwork();
            if (network == null) return false; // no Wi-Fi and no cable
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return (
                capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            );
        } catch (RuntimeException e) {
            // Unknown is not "offline": never tell the family a problem that may not be there.
            Diagnostics.recovered("Reading the network state", e);
            return true;
        }
    }

    /** True while the panel is on screen. */
    boolean showing() {
        return panel != null && panel.isShowing();
    }

    private void resumed() {
        resumed = true;
        listen();
        // Straight away when online (the first-start messages follow), or back from Android's
        // settings still offline; otherwise give a box that has just started time to connect.
        schedule(online(activity) || wasOffline ? 0 : graceMs);
    }

    private void paused() {
        resumed = false;
        activity.handler.removeCallbacks(check);
        if (callback == null) return;
        try {
            activity.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            Diagnostics.recovered("Stopping the network watch", e);
        }
        callback = null;
    }

    private void listen() {
        if (callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                changed();
            }

            @Override
            public void onCapabilitiesChanged(android.net.Network network, NetworkCapabilities caps) {
                changed();
            }

            @Override
            public void onLost(android.net.Network network) {
                changed();
            }
        };
        try {
            activity.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) {
            callback = null;
            Diagnostics.recovered("Watching the network", e);
        }
    }

    /** Called on a background thread by Android when the network changes. */
    private void changed() {
        activity.handler.post(() -> {
            if (!resumed) return;
            schedule(online(activity) ? 0 : graceMs);
        });
    }

    private void schedule(long delay) {
        activity.handler.removeCallbacks(check);
        activity.handler.postDelayed(check, delay);
    }

    /** Shows or closes the panel to match the connection now. Package-private for tests. */
    void check() {
        if (!resumed || activity.isFinishing() || activity.isDestroyed()) return;
        if (online(activity)) {
            snoozed = false;
            if (showing()) {
                handled = true;
                panel.dismiss();
            }
            if (wasOffline) backOnline();
            wasOffline = false;
            runFirstStart();
            return;
        }
        if (snoozed || showing()) return;
        // Never on top of another panel (such as a question the person is answering): try later.
        if (SidePanel.current != null && SidePanel.current.isShowing()) {
            schedule(2000);
            return;
        }
        show();
    }

    private void show() {
        wasOffline = true;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int accent = activity.prefs.getInt("accent", 0xff8a9eff);
        content.addView(new ConnectionAnimation(activity, accent));
        TextView text = new TextView(activity);
        text.setText(TEXT);
        text.setTextSize(16);
        text.setTextColor(0xffb4bfd2);
        text.setLineSpacing(0, 1.15f);
        text.setGravity(Gravity.START);
        text.setPadding(0, activity.dp(10), 0, activity.dp(12));
        content.addView(text);
        panel = SidePanel.of(activity, TITLE)
            .content(content)
            .option("Wi-Fi settings", () -> {
                handled = true;
                openSettings(Settings.ACTION_WIFI_SETTINGS);
            })
            .option("Cable and other network settings", () -> {
                handled = true;
                openSettings(Settings.ACTION_WIRELESS_SETTINGS);
            })
            .option("Not now", () -> {
                handled = true;
                notNow();
            })
            .onDismiss(() ->
                // A chosen button runs straight after this; Back or a tap outside does not.
                activity.handler.post(() -> {
                    if (!handled) notNow();
                    handled = false;
                })
            )
            .show();
    }

    private void notNow() {
        snoozed = true;
        runFirstStart();
    }

    private void runFirstStart() {
        Runnable next = firstStart;
        firstStart = null;
        if (next != null) next.run();
    }

    /** The box came back online after the panel was shown: reload Home, which was left empty. */
    private void backOnline() {
        activity.toast("Connected to the internet");
        // Only on Home (it has no Back); any other page stays as the person left it.
        if (activity.back == null && activity.destination != null) activity.destination.run();
    }

    /** Opens one of the box's settings pages; the box's main settings if it lacks that page. */
    private void openSettings(String action) {
        try {
            activity.startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception again) {
                activity.toast("This box's settings can't be opened from here");
            }
        }
    }

    /** Lifecycle callbacks with nothing to do by default. */
    private abstract static class Lifecycle implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityResumed(Activity activity) {}

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }
}
