package local.tv.home;

import android.content.pm.ActivityInfo;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Asked the first time MovieHub opens: TV with a remote, or phone or tablet by touch. The best
 * guess (see {@link DeviceMode#guess}) has the focus and is marked; some TV boxes report
 * themselves as phones, so the person chooses. The answer is kept and can be changed in Settings.
 */
final class LayoutChooser {

    private LayoutChooser() {}

    static void show(MainActivity activity) {
        activity.fixedScreen(null, activity::finish);
        LinearLayout page = activity.page;
        page.setGravity(Gravity.CENTER);
        page.setPadding(activity.dp(32), activity.dp(24), activity.dp(32), activity.dp(24));
        String guess = DeviceMode.guess(activity);
        TextView title = activity.text("How are you using MovieHub?", 26);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        page.addView(title);
        TextView about = activity.text(
            "Both show the same pages; on a phone, hold it sideways. You can change this later in " +
                "Settings → System.",
            16
        );
        about.setGravity(Gravity.CENTER);
        about.setAlpha(0.75f);
        about.setPadding(0, activity.dp(6), 0, activity.dp(22));
        page.addView(about);
        Button tvButton = choice(activity, "📺  On a TV, with a remote", DeviceMode.TV, guess);
        Button phoneButton = choice(activity, "📱  On a phone or tablet, by touch", DeviceMode.PHONE, guess);
        (DeviceMode.TV.equals(guess) ? tvButton : phoneButton).requestFocus();
    }

    private static Button choice(MainActivity activity, String label, String mode, String guess) {
        Button button = activity.button(label + (mode.equals(guess) ? "   (suggested)" : ""), () -> {
            boolean wasPhone = DeviceMode.phone(activity);
            DeviceMode.choose(activity, mode);
            // The phone layout's scale is set as a screen is created, so a change of layout
            // rebuilds this one; it then opens in the chosen layout (MainActivity.onCreate).
            if (wasPhone != DeviceMode.phone(activity)) activity.recreate();
            else open(activity);
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(activity.dp(420), activity.dp(56));
        layout.bottomMargin = activity.dp(12);
        activity.page.addView(button, layout);
        return button;
    }

    /**
     * Opens MovieHub's Home in the chosen layout. Both use the same pages; a phone may be turned
     * either way round sideways.
     */
    static void open(MainActivity activity) {
        activity.setRequestedOrientation(
            DeviceMode.phone(activity)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );
        activity.homeScreen.home();
    }
}
