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
 * to pick one, and the colour keys underneath. Live TV opens here, like the box's TV Guide button;
 * the parts along the top (Reminders, Search, Options) can be chosen too. BACK from the channel
 * grid comes here; BACK from here goes Home.
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
    /** The menu's rows, and the parts along the top (for tests). */
    final List<View> rows = new ArrayList<>(),
        tabs = new ArrayList<>();

    GuideMenu(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /** Live TV opens here first, as the satellite box's TV Guide button did. */
    void show() {
        build(MAIN, "TV GUIDE", activity.homeScreen::home);
    }

    private void more() {
        build(MORE, "MORE", this::show);
    }

    private void build(String[][] items, String heading, Runnable back) {
        activity.fixedScreen(null, back);
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(80), activity.dp(24), activity.dp(80), activity.dp(18));
        page.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {
                0xffa9cdf6,
                GuideFrame.BACK_TOP,
                0xff8fbdf2,
                GuideFrame.BACK_BOTTOM,
            })
        );

        // The heading row: the guide's name, then its parts. Each part can be chosen: this list,
        // reminders, search and options.
        LinearLayout top = activity.row();
        top.setGravity(Gravity.BOTTOM);
        TextView name = text("TV Guide", 30, true);
        name.setPadding(0, 0, activity.dp(30), 0);
        top.addView(name);
        String[] parts = { heading, "REMINDERS", "SEARCH", "OPTIONS" };
        Runnable[] actions = {
            () -> rows.get(0).requestFocus(),
            guide.panels::reminders,
            guide.panels::searchPanel,
            guide.panels::options,
        };
        tabs.clear();
        for (int i = 0; i < parts.length; i++) {
            Runnable action = actions[i];
            boolean current = i == 0;
            TextView tab = text(parts[i], 18, true);
            tab.setPadding(activity.dp(10), activity.dp(3), activity.dp(10), activity.dp(3));
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            tab.setOnClickListener(v -> action.run());
            View.OnFocusChangeListener style = (v, focused) -> {
                tab.setBackground(focused ? box(LiveTvScreen.YELLOW) : null);
                tab.setTextColor(focused ? LiveTvScreen.INK : current ? 0xffffffff : 0xffe2eeff);
                tab.setAlpha(focused || current ? 1f : 0.8f);
            };
            tab.setOnFocusChangeListener(style);
            style.onFocusChange(tab, false);
            tab.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    rows.get(0).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                return shortcut(items, key);
            });
            LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(-2, -2);
            tabLayout.rightMargin = activity.dp(8);
            top.addView(tab, tabLayout);
            tabs.add(tab);
        }
        page.addView(top);

        // The rows share the height, so every one fits on the screen with nothing to scroll.
        LinearLayout list = activity.vertical();
        list.setPadding(activity.dp(40), activity.dp(18), activity.dp(40), activity.dp(12));
        rows.clear();
        for (int i = 0; i < items.length; i++) {
            String[] item = items[i];
            int index = i;
            View row = row(digit(items, i), item[0]);
            row.setOnClickListener(v -> open(item[1]));
            row.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP && index == 0) {
                    tabs.get(0).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && index == items.length - 1) return true;
                return shortcut(items, key);
            });
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, 0, 1);
            rowLayout.bottomMargin = activity.dp(3);
            list.addView(row, rowLayout);
            rows.add(row);
        }
        // A short list (More…) keeps rows the usual height instead of stretching them.
        int spare = MAIN.length - items.length;
        if (spare > 0) list.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, spare));
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

    /** The number shown beside a row: 1 to 9 then 0 on the main list, 1 upwards on More…. */
    private static int digit(String[][] items, int index) {
        return items == MAIN ? (index + 1) % 10 : index + 1;
    }

    /** Number keys open a row; the colour keys do what the key guide at the bottom says. */
    private boolean shortcut(String[][] items, int key) {
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            int pressed = key - KeyEvent.KEYCODE_0;
            for (int j = 0; j < items.length; j++) {
                if (digit(items, j) == pressed) {
                    rows.get(j).requestFocus();
                    open(items[j][1]);
                    return true;
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
        TextView number = text(String.valueOf(digit), 19, true);
        number.setGravity(Gravity.CENTER);
        row.addView(number, new LinearLayout.LayoutParams(activity.dp(52), -1));
        TextView name = text(label.toUpperCase(Locale.UK), 19, true);
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
