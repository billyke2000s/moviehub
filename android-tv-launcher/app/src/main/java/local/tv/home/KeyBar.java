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
 * The colour keys along the bottom of the Live TV guide and its menu. They are always drawn as
 * buttons, for remotes without coloured buttons: BACK (or ▼ where nothing is below) highlights
 * them, ◀ ▶ move along and OK presses one. A remote's own coloured buttons still work as before.
 */
final class KeyBar {

    private final MainActivity activity;
    final LinearLayout view;
    /** The keys, left to right (for tests and focus). */
    final List<View> keys = new ArrayList<>();
    /** Up from the bar: back to the list above it. */
    private Runnable up = () -> {};
    /** DOWN from a key; null keeps the focus on the bar. */
    private Runnable down;
    private final List<Runnable> actions = new ArrayList<>();
    /** The first BACK on a screen highlights the buttons; after that BACK goes back. */
    private boolean backHighlighted;

    KeyBar(MainActivity activity) {
        this.activity = activity;
        activity.activeKeyBar = this;
        view = activity.row();
        view.setGravity(Gravity.CENTER_VERTICAL);
    }

    KeyBar onUp(Runnable up) {
        this.up = up;
        return this;
    }

    KeyBar onDown(Runnable down) {
        this.down = down;
        return this;
    }

    KeyBar add(int colour, String label, Runnable action) {
        LinearLayout key = activity.row();
        key.setGravity(Gravity.CENTER_VERTICAL);
        key.setPadding(activity.dp(10), activity.dp(3), activity.dp(10), activity.dp(3));
        // Buttons for remotes without colour keys: focusable from the start (D-pad and touch).
        key.setFocusable(true);
        key.setFocusableInTouchMode(true);
        key.setContentDescription(label);
        // No colour swatch: the owner's remotes have coloured buttons that are programmable shortcuts,
        // not colour keys, so a coloured square suggested a button that does nothing here. The
        // colour still decides which physical colour key (on remotes that have them) presses it.
        TextView text = activity.compactText(label, 17);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        key.addView(text);
        View.OnFocusChangeListener style = (v, focused) -> {
            key.setBackground(focused ? box(LiveTvScreen.YELLOW, 0) : box(0x22182260, 0x88ffffff));
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
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down != null) down.run();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.rightMargin = activity.dp(18);
        view.addView(key, layout);
        keys.add(key);
        actions.add(action);
        return this;
    }

    void focus(int index) {
        if (index >= 0 && index < keys.size()) keys.get(index).requestFocus();
    }

    /** Route hardware colours independently of which guide widget currently owns focus. */
    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        // The event form also recognises standard and learned scan codes, as the player does.
        char colour = LiveTvScreen.colour(activity.prefs, event);
        int at = colour == 'r' ? 0 : colour == 'g' ? 1 : colour == 'y' ? 2 : colour == 'b' ? 3 : -1;
        if (at >= 0 && at < actions.size()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) actions
                .get(at)
                .run();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                // The first BACK highlights the buttons (the way to them on a remote without
                // colour keys); after that BACK goes back.
                if (!backHighlighted && focused() < 0) {
                    backHighlighted = true;
                    focus(0);
                } else if (activity.back != null) activity.back.run();
            }
            return true;
        }
        int focused = focused();
        if (focused >= 0 && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) focus(
                Math.max(
                    0,
                    Math.min(keys.size() - 1, focused + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1))
                )
            );
            return true;
        }
        return false;
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
