package local.tv.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/** Opt-in for optional third-party free services. */
final class FreeServicesConsent {
    static final String DECIDED = "freeServicesChoiceMade";
    static final String ENABLED = "freeServicesEnabled";

    private FreeServicesConsent() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false);
    }

    static void showOnce(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("ui", Context.MODE_PRIVATE);
        if (prefs.getBoolean(DECIDED, false)) {
            SafetyNotice.showOnce(activity);
            return;
        }
        SidePanel.of(activity, "Enable free services?")
            .message("Enable optional third-party free TV, movie and series sources? They are disabled by default.")
            .action("Not now", () -> {
                prefs.edit().putBoolean(DECIDED, true).putBoolean(ENABLED, false).apply();
                SafetyNotice.showOnce(activity);
            })
            .action("Enable", () -> confirm(activity))
            .show();
    }

    private static void confirm(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("ui", Context.MODE_PRIVATE);
        SidePanel.of(activity, SafetyNotice.TITLE)
            .message(SafetyNotice.TEXT)
            .action("Cancel", () -> {
                prefs.edit().putBoolean(DECIDED, true).putBoolean(ENABLED, false).apply();
            })
            .action("I understand & enable", () -> {
                prefs.edit().putBoolean(DECIDED, true)
                    .putBoolean(ENABLED, true)
                    .putBoolean(SafetyNotice.PREF, true).apply();
            })
            .show();
    }
}
