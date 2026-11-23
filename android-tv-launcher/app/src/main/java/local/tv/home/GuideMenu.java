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
 * The guide menu, laid out like the satellite box's: a numbered list in the guide's dark blue
 * (1 All Channels, 2 Favourites, 3 Entertainment … 0 More…), the chosen row in yellow, number keys
 * to pick one, and the colour keys underneath (Options, Reminders, Search, Favourites; always shown
 * as buttons). Live TV opens here, like the box's TV Guide button. BACK from the channel grid comes
 * here; BACK from here goes Home.
 */
final class GuideMenu {

    /**
     * The menu: label and the guide list each opens. The sections keep the satellite box's order;
     * Favourites (also on the blue key) and Options are among them. As on the satellite box, 0 is
     * More…, which holds Music, International and the channel groups from the viewer's own lists.
     * "menu:" rows open a further numbered list in the same style rather than a side panel.
     */
    static final String[][] ITEMS = {
        { "All Channels", "all" },
        { "Favourites", "favourites" },
        { "Entertainment", "section:Entertainment" },
        { "Movies", "section:Movies" },
        { "Sports", "section:Sports" },
        { "News", "section:News" },
        { "Documentaries", "section:Documentaries" },
        { "Kids", "section:Kids" },
        { "Options", "options" },
        { "Music", "section:Music" },
        { "International", "menu:countries" },
        { "Channel Groups", "menu:groups" },
    };

    /** Rows on a page, More… included. */
    static final int PAGE = 10;

    /** The page shown of the list shown (0 is the first). */
    int page;
    /** The list shown: the menu itself, or the International or channel-group list. */
    private String[][] shown = ITEMS;
    private String heading = "";
    /** Where BACK goes from the first page of the list shown. */
    private Runnable leave;

    private final MainActivity activity;
    private final LiveTvScreen guide;
    /** The menu's rows (for tests). */
    final List<View> rows = new ArrayList<>();
    /** The colour keys along the bottom, which can be chosen with the D-pad. */
    KeyBar bar;

    GuideMenu(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /** Live TV opens here first, as the satellite box's TV Guide button did. */
    void show() {
        list(ITEMS, "", activity.homeScreen::home, 0);
    }

    /**
     * BACK from the channel grid: the list and page it was opened from, so a country's channels go
     * back to International. The menu's first page until something has been shown.
     */
    void reopen() {
        if (leave == null) show();
        else page(page);
    }

    /** The items on page {@code index} of the menu. */
    static String[][] pageItems(int index) {
        return pageItems(ITEMS, index);
    }

    /** The items on page {@code index} of {@code all}: nine and More… while more follow, else the rest. */
    static String[][] pageItems(String[][] all, int index) {
        List<String[]> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < index; i++) from += PAGE - 1;
        boolean last = all.length - from <= PAGE;
        int to = last ? all.length : from + PAGE - 1;
        for (int i = from; i < to; i++) out.add(all[i]);
        if (!last) out.add(new String[] { "More…", "" });
        return out.toArray(new String[0][]);
    }

    /** Shows page {@code index} of a list; BACK goes up a page, then to {@code leave}. */
    private void list(String[][] items, String title, Runnable leave, int index) {
        shown = items;
        heading = title;
        this.leave = leave;
        page(index);
    }

    private void page(int index) {
        page = index;
        String sub = heading.isEmpty() ? (index == 0 ? "" : "MORE") : heading + (index == 0 ? "" : "  ·  MORE");
        build(pageItems(shown, index), sub, index == 0 ? leave : () -> page(index - 1));
    }

    /** International: all of it first, then each country, in the menu's numbered style. */
    private void countries(Runnable back) {
        activity.async(
            () -> guide.channelsDb().countries(),
            countries -> {
                String[][] items = new String[countries.size() + 1][];
                items[0] = new String[] { "All International", "section:" + GuideOrder.INTERNATIONAL };
                for (int i = 0; i < countries.size(); i++) items[i + 1] = new String[] {
                    GuideOrder.name(countries.get(i).name) + "  (" + countries.get(i).count + ")",
                    "country:" + countries.get(i).name,
                };
                list(items, "INTERNATIONAL", back, 0);
            }
        );
    }

    /** The groups from the viewer's own channel lists, in the menu's numbered style. */
    private void groups(Runnable back) {
        activity.async(
            () -> guide.channelsDb().groups(),
            groups -> {
                if (groups.isEmpty()) {
                    activity.toast("Your channel lists have no groups");
                    return;
                }
                String[][] items = new String[groups.size()][];
                for (int i = 0; i < items.length; i++) items[i] = new String[] {
                    (groups.get(i).name.isEmpty() ? "Ungrouped" : groups.get(i).name) +
                    "  (" +
                    groups.get(i).count +
                    ")",
                    "group:" + groups.get(i).name,
                };
                list(items, "CHANNEL GROUPS", back, 0);
            }
        );
    }

    private void build(String[][] items, String heading, Runnable back) {
        activity.fixedScreen(null, back, true);
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(80), activity.dp(24), activity.dp(80), activity.dp(18));
        page.setBackground(GuideFrame.background());

        // The heading: the guide's name and the page. Options, Reminders and Search are on the
        // colour buttons at the bottom (they were also along the top, in another style).
        LinearLayout top = activity.row();
        top.setGravity(Gravity.BOTTOM);
        TextView name = text("TV Guide", 30, true);
        name.setPadding(0, 0, activity.dp(30), 0);
        top.addView(name);
        if (!heading.isEmpty()) top.addView(text(heading, 18, true)); // "MORE", "INTERNATIONAL" …
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
                    rows.get(rows.size() - 1).requestFocus(); // round to the bottom
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && index == items.length - 1) {
                    rows.get(0).requestFocus(); // round to the top, as on the satellite box
                    return true;
                }
                return shortcut(items, key);
            });
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, 0, 1);
            rowLayout.bottomMargin = activity.dp(3);
            list.addView(row, rowLayout);
            rows.add(row);
        }
        // A short list (More…) keeps rows the usual height instead of stretching them.
        int spare = PAGE - items.length;
        if (spare > 0) list.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, spare));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        bar = new KeyBar(activity)
            .onUp(() -> rows.get(rows.size() - 1).requestFocus())
            .add(GuideFrame.RED, "Options", guide.panels::options)
            .add(GuideFrame.GREEN, "Reminders", guide.panels::reminders)
            .add(GuideFrame.YELLOW, "Search A–Z", guide.panels::searchPanel)
            .add(GuideFrame.BLUE, "Favourites", () -> guide.show("favourites", ""));
        bar.view.setPadding(activity.dp(34), 0, 0, 0);
        page.addView(bar.view);
        rows.get(0).requestFocus();
    }

    /** The number shown beside a row: 1 to 9, then 0 for the tenth. */
    private static int digit(String[][] items, int index) {
        return (index + 1) % 10;
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
        // A further list returns to this page of this list.
        String[][] from = shown;
        String fromHeading = heading;
        Runnable fromLeave = leave;
        int fromPage = page;
        Runnable back = () -> list(from, fromHeading, fromLeave, fromPage);
        switch (target) {
            case "":
                page(page + 1); // More…
                break;
            case "options":
                guide.panels.options();
                break;
            case "menu:countries":
                countries(back);
                break;
            case "menu:groups":
                groups(back);
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
            number.setBackground(box(focused ? LiveTvScreen.YELLOW_DARK : LiveTvScreen.BLUE_DARK));
            number.setTextColor(focused ? LiveTvScreen.INK : LiveTvScreen.NUMBER_TEXT);
            name.setBackground(box(focused ? LiveTvScreen.YELLOW : LiveTvScreen.BLUE));
            name.setTextColor(focused ? LiveTvScreen.INK : 0xffffffff);
        };
        row.setOnFocusChangeListener(style);
        style.onFocusChange(row, false);
        return row;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(2));
        return shape;
    }
}
