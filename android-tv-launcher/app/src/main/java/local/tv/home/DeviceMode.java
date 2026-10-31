package local.tv.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * Whether MovieHub shows its TV layout (remote, 10-foot screens) or its phone layout (the same
 * pages, held sideways and scaled to fit, by touch). Asked when MovieHub first opens, because some TV boxes report themselves as phones;
 * the question starts on the best guess. Changeable later in Settings and from the phone menu.
 */
final class DeviceMode {

    static final String PREF = "layoutMode",
        TV = "tv",
        PHONE = "phone";

    private DeviceMode() {}

    /** "tv", "phone", or "" when not chosen yet. */
    static String chosen(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(PREF, "");
    }

    static boolean phone(Context context) {
        return PHONE.equals(chosen(context));
    }

    /**
     * Phone layout: before the screen is built, scales it so the TV pages fit the phone held
     * sideways ({@link PhoneScale}). Call from an activity's attachBaseContext. Does nothing for
     * the TV layout, which keeps the device's own density.
     */
    static void scaleForPhone(android.app.Activity activity, Context base) {
        if (!phone(base)) return;
        android.util.DisplayMetrics metrics = base.getResources().getDisplayMetrics();
        int dpi = PhoneScale.densityDpi(Math.min(metrics.widthPixels, metrics.heightPixels));
        if (dpi <= 0) return;
        Configuration override = new Configuration();
        override.densityDpi = dpi;
        activity.applyOverrideConfiguration(override);
    }

    static void choose(Context context, String mode) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString(PREF, mode).apply();
    }

    /**
     * The best guess: a TV when Android says so, when there is no touchscreen, or when the device
     * supports the TV interface; otherwise a phone or tablet.
     */
    static String guess(Context context) {
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return TV;
        PackageManager packages = context.getPackageManager();
        if (!packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return TV;
        if (packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return TV;
        return PHONE;
    }
}
