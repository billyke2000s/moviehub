package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The guide menu, laid out like the satellite box's: a numbered list on a light-blue background
 * (1 All Channels, 2 Favourites, 3 Entertainment … 0 More…), the chosen row in yellow, number keys
 * to pick one, and the colour keys underneath. BACK from the TV guide comes here; BACK from here
 * goes Home.
 */
final class GuideMenu {

    /** Menu rows: label and the guide list each opens ("" for More…). */
    static final String[][] MAIN = {
        { "All Channels", "all" },
        { "Favourites", "favourites" },
        { "Entertainment", "section:Entertainment" },
        { "Movies", "section:Movies" },
        { "Sports", "section:Sports" },
        { "News", "section:News" },
        { "Documentaries", "section:Documentaries" },
        { "Kids", "section:Kids" },
        { "Music", "section:Music" },
        { "More…", "" },
    };

    /** More…: the other sections, the countries, the lists' own groups and the options. */
    static final String[][] MORE = {
        { "Religion", "section:Religion" },
        { "Shopping", "section:Shopping" },
        { "Radio", "section:Radio" },
        { "International", "countries" },
        { "Channel groups from your lists", "groups" },
        { "Options", "options" },
    };

    private final MainActivity activity;
    private final LiveTvScreen guide;
    /** The menu's rows (for tests). */
    final List<View> rows = new ArrayList<>();

    GuideMenu(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    void show() {
        build(MAIN, "TV GUIDE", activity.homeScreen::home);
    }

    private void more() {
        build(MORE, "MORE", this::show);
    }

    private void build(String[][] items, String heading, Runnable back) {
        activity.fixedScreen(null, back);
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(80), activity.dp(30), activity.dp(80), activity.dp(24));
        page.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {
                0xffa9cdf6,
                GuideFrame.BACK_TOP,
                0xff8fbdf2,
                GuideFrame.BACK_BOTTOM,
            })
        );

        // The heading row: the guide's name and its parts, the one shown in white.
        LinearLayout top = activity.row();
        top.setGravity(Gravity.BOTTOM);
        TextView name = text("TV Guide", 30, true);
        name.setPadding(0, 0, activity.dp(40), 0);
        top.addView(name);
        String[] parts = { heading, "REMINDERS", "SEARCH", "OPTIONS" };
        for (String part : parts) {
            TextView label = text(part, 18, true);
            label.setTextColor(part.equals(heading) ? 0xffffffff : 0xffe2eeff);
            if (!part.equals(heading)) label.setAlpha(0.75f);
            label.setPadding(0, 0, activity.dp(30), activity.dp(4));
            top.addView(label);
        }
        page.addView(top);

        LinearLayout list = activity.vertical();
        list.setPadding(activity.dp(40), activity.dp(28), activity.dp(40), activity.dp(16));
        rows.clear();
        for (int i = 0; i < items.length; i++) {
            String[] item = items[i];
            int digit = items == MAIN ? (i + 1) % 10 : i + 1;
            View row = row(digit, item[0]);
            row.setOnClickListener(v -> open(item[1]));
            row.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                    int pressed = key - KeyEvent.KEYCODE_0;
                    for (int j = 0; j < items.length; j++) {
                        int number = items == MAIN ? (j + 1) % 10 : j + 1;
                        if (number == pressed) {
                            rows.get(j).requestFocus();
                            open(items[j][1]);
                        }
                    }
                    return true;
                }
                char colour = guide.colour(key);
                if (colour == 'r') guide.panels.options();
                else if (colour == 'g') guide.panels.reminders();
                else if (colour == 'y') guide.panels.searchPanel();
                else if (colour == 'b') guide.show("favourites", "");
                else return false;
                return true;
            });
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, activity.dp(40));
            rowLayout.bottomMargin = activity.dp(3);
            list.addView(row, rowLayout);
            rows.add(row);
        }
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout keys = activity.row();
        keys.setPadding(activity.dp(40), 0, 0, 0);
        colourKey(keys, SkyOverlay.RED, "Options");
        colourKey(keys, SkyOverlay.GREEN, "Reminders");
        colourKey(keys, LiveTvScreen.YELLOW, "Search A–Z");
        colourKey(keys, SkyOverlay.BLUE, "Favourites");
        page.addView(keys);
        rows.get(0).requestFocus();
    }

    private void open(String target) {
        switch (target) {
            case "":
                more();
                break;
            case "countries":
                guide.panels.countries();
                break;
            case "groups":
                guide.panels.groups();
                break;
            case "options":
                guide.panels.options();
                break;
            default:
                guide.show(target, "");
        }
    }

    /** A numbered row: the number on a lighter cell, the name in capitals; yellow when chosen. */
    private View row(int digit, String label) {
        LinearLayout row = activity.row();
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        TextView number = text(String.valueOf(digit), 20, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, new LinearLayout.LayoutParams(activity.dp(52), -1));
        TextView name = text(label.toUpperCase(Locale.UK), 20, true);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(activity.dp(16), 0, activity.dp(12), 0);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -1, 1);
        nameLayout.leftMargin = activity.dp(3);
        row.addView(name, nameLayout);
        View.OnFocusChangeListener style = (v, focused) -> {
            number.setBackground(box(focused ? 0xffe0a400 : 0xff3c74d4));
            number.setTextColor(focused ? LiveTvScreen.INK : 0xffd3e4ff);
            name.setBackground(box(focused ? LiveTvScreen.YELLOW : GuideFrame.CELL));
            name.setTextColor(focused ? LiveTvScreen.INK : 0xffffffff);
        };
        row.setOnFocusChangeListener(style);
        style.onFocusChange(row, false);
        return row;
    }

    private void colourKey(LinearLayout parent, int colour, String label) {
        View swatch = new View(activity);
        swatch.setBackground(box(colour));
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(
            activity.dp(16),
            activity.dp(16)
        );
        swatchLayout.rightMargin = activity.dp(6);
        parent.addView(swatch, swatchLayout);
        TextView text = text(label, 17, true);
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(-2, -2);
        textLayout.rightMargin = activity.dp(34);
        parent.addView(text, textLayout);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        label.setShadowLayer(activity.dp(2), 0, activity.dp(1), 0x55000000);
        SkyFont.apply(label, bold);
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(2));
        return shape;
    }
}
