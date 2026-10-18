package local.tv.home;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The colour-key guide along the bottom of the Live TV guide and its menu. The keys are a guide to
 * the remote's coloured buttons; without them, the same jobs are in the guide menu (BACK).
 */
final class KeyBar {

    private final MainActivity activity;
    final LinearLayout view;
    /** The keys, left to right (for tests and focus). */
    final List<View> keys = new ArrayList<>();
    /** Up from the bar: back to the list above it. */
    private Runnable up = () -> {};

    KeyBar(MainActivity activity) {
        this.activity = activity;
        view = activity.row();
        view.setGravity(Gravity.CENTER_VERTICAL);
    }

    KeyBar onUp(Runnable up) {
        this.up = up;
        return this;
    }

    KeyBar add(int colour, String label, Runnable action) {
        LinearLayout key = activity.row();
        key.setGravity(Gravity.CENTER_VERTICAL);
        key.setPadding(activity.dp(6), activity.dp(3), activity.dp(10), activity.dp(3));
        // Not reached with the D-pad: Down pages the guide and BACK opens the guide menu, whose
        // top row (Reminders, Search, Options) and second page (Favourites) do the same jobs.
        // A tap still works on touch screens.
        key.setFocusable(false);
        key.setContentDescription(label);
        View swatch = new View(activity);
        swatch.setBackground(box(colour, 0x55ffffff));
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(
            activity.dp(18),
            activity.dp(18)
        );
        swatchLayout.rightMargin = activity.dp(6);
        key.addView(swatch, swatchLayout);
        TextView text = activity.compactText(label, 17);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        key.addView(text);
        View.OnFocusChangeListener style = (v, focused) -> {
            key.setBackground(focused ? box(LiveTvScreen.YELLOW, 0) : null);
            text.setTextColor(focused ? LiveTvScreen.INK : 0xffffffff);
        };
        key.setOnFocusChangeListener(style);
        style.onFocusChange(key, false);
        key.setOnClickListener(v -> action.run());
        key.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                up.run();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.rightMargin = activity.dp(18);
        view.addView(key, layout);
        keys.add(key);
        return this;
    }

    void focus(int index) {
        if (index >= 0 && index < keys.size()) keys.get(index).requestFocus();
    }

    /** The key that has the focus, or -1. */
    int focused() {
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).hasFocus()) return i;
        return -1;
    }

    private GradientDrawable box(int colour, int edge) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        if (edge != 0) shape.setStroke(activity.dp(1), edge);
        return shape;
    }
}
