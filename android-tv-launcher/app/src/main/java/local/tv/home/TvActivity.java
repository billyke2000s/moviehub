package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/**
 * Shared building blocks for the TV interface: colours, sizes, focus styling and the
 * standard text, button, row and form widgets used by every screen.
 */
abstract class TvActivity extends Activity {

    SharedPreferences prefs;

    int accent() {
        return prefs.getInt("accent", 0xff8a9eff);
    }

    int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    GradientDrawable box(int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(7));
        return d;
    }

    LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(0xfff8f8fc);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    void styleFocus(View view, boolean focused) {
        GradientDrawable background = box(focused ? 0xfff2f4f8 : view.isSelected() ? 0xff29313e : 0xff171d27);
        if (focused || view.isSelected()) background.setStroke(dp(focused ? 3 : 1), accent());
        view.setBackground(
            "navigation".equals(view.getTag()) && !focused && !view.isSelected()
                ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                : background
        );
        focusText(view, focused ? 0xff10141c : 0xfff2f4f8);
        view.setScaleX(focused ? 1.025f : 1f);
        view.setScaleY(focused ? 1.025f : 1f);
    }

    void focusText(View view, int colour) {
        if (view instanceof TextView) ((TextView) view).setTextColor(colour);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) focusText(group.getChildAt(i), colour);
        }
    }

    void focus(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((v, focused) -> styleFocus(v, focused));
    }

    Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xfff8f8fc);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(box(0xff171d27));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        focus(b);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    Button navigationButton(String label, Runnable action) {
        Button b = button(label, action);
        b.setTag("navigation");
        styleFocus(b, false);
        return b;
    }

    TextView muted(String value, int size) {
        TextView label = compactText(value, size);
        label.setTextColor(0xffa6b2c8);
        return label;
    }

    void section(LinearLayout parent, String title) {
        TextView label = text(title, 20);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(20), 0, dp(10));
        parent.addView(label);
    }

    void emptyState(LinearLayout target, String title, String description, String action, Runnable run) {
        LinearLayout card = vertical();
        card.setPadding(dp(28), dp(24), dp(28), dp(28));
        card.setBackground(box(0xff131c2a));
        card.addView(text(title, 26));
        card.addView(muted(description, 16));
        if (action != null) {
            Button button = button(action, run);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.topMargin = dp(16);
            card.addView(button, lp);
        }
        target.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    Button chip(LinearLayout parent, String label, Runnable action) {
        Button choice = navigationButton(label, action);
        choice.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.rightMargin = dp(8);
        parent.addView(choice, lp);
        return choice;
    }

    void primary(Button button) {
        button.setSelected(true);
        styleFocus(button, button.hasFocus());
    }

    void addRow(LinearLayout parent, View view, int width, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(height));
        p.setMargins(dp(5), dp(5), dp(8), dp(5));
        parent.addView(view, p);
    }

    LinearLayout horizontal(LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row();
        r.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(r);
        parent.addView(scroll);
        return r;
    }

    TextView compactText(String value, int size) {
        TextView label = text(value, size);
        label.setPadding(0, dp(3), 0, dp(3));
        return label;
    }

    EditText field(LinearLayout parent, String hint, boolean password, String value) {
        parent.addView(compactText(hint, 14));
        EditText edit = new EditText(this);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        edit.setId(View.generateViewId());
        edit.setHint("Select to type");
        edit.setTextColor(0xfff8f8fc);
        edit.setHintTextColor(0xff99a6bc);
        edit.setTextSize(18);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setSingleLine(true);
        edit.setInputType(
            password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT
        );
        edit.setTypeface(Typeface.DEFAULT);
        edit.setText(value);
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setShowSoftInputOnFocus(false);
        GradientDrawable idle = box(0xff080e1a);
        idle.setStroke(dp(1), 0xff526079);
        edit.setBackground(idle);
        edit.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = box(0xff080e1a);
            bg.setStroke(dp(focused ? 3 : 1), focused ? accent() : 0xff526079);
            v.setBackground(bg);
            if (!focused) (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).hideSoftInputFromWindow(v.getWindowToken(), 0);
        });
        Runnable keyboard = () -> {
            edit.setShowSoftInputOnFocus(true);
            edit.requestFocus();
            (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        };
        edit.setOnClickListener(v -> keyboard.run());
        edit.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) keyboard.run();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View next = v.focusSearch(
                        key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN
                    );
                    if (next != null) next.requestFocus();
                }
                return true;
            }
            return false;
        });
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
        return edit;
    }

    Dialog form(String title, LinearLayout content) {
        Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout shell = vertical();
        shell.setPadding(dp(24), dp(20), dp(24), dp(24));
        shell.setBackgroundColor(0xff101827);
        shell.addView(text(title, 24));
        shell.addView(content);
        shell.addView(button("Close", d::dismiss));
        scroll.addView(shell);
        d.setContentView(scroll);
        d.show();
        d.getWindow().setLayout(
            Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(48)),
            -1
        );
        d.getWindow().setGravity(Gravity.RIGHT);
        d.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        );
        return d;
    }

    void settingsStyle(View view) {
        if (view instanceof Button || view instanceof Switch) {
            view.setFocusable(true);
            view.setOnFocusChangeListener((v, on) -> {
                GradientDrawable bg = box(on ? 0xff263550 : 0xff101827);
                bg.setStroke(dp(on ? 2 : 0), accent());
                v.setBackground(bg);
            });
            view.setBackground(box(0xff101827));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settingsStyle(group.getChildAt(i));
        }
    }
}
