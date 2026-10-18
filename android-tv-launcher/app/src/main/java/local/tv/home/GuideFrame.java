package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The fixed parts of the Live TV guide, laid out like the satellite box's TV guide: "TV guide" and
 * the clock across the top; the selected programme's time, title and description with the live
 * picture beside it; a row of tabs (All Channels, Favourites, Entertainment, Movies …); the time
 * slots; the channel grid; and the colour keys with "Press SELECT to view" underneath.
 */
final class GuideFrame {

    /** The satellite guide's light-blue background, its darker cells and the tab colours. */
    static final int BACK_TOP = 0xff6ea8ec,
        BACK_BOTTOM = 0xff3a7bd6,
        CELL = 0xff1b4db4,
        TAB = 0xff2b63c8,
        TAB_ON = 0xff0b2c86,
        SOFT = 0xffd3e4ff;

    /** The tabs: label and the guide list each shows ("" for More…, the guide menu). */
    static final String[][] TABS = {
        { "All Channels", "all" },
        { "Favourites", "favourites" },
        { "Entertainment", "section:Entertainment" },
        { "Movies", "section:Movies" },
        { "Sports", "section:Sports" },
        { "News", "section:News" },
        { "Docs", "section:Documentaries" },
        { "Kids", "section:Kids" },
        { "More…", "" },
    };

    private final MainActivity activity;
    private final LiveTvScreen guide;
    LinearLayout grid;
    FrameLayout pictureBox;
    TextView pictureLabel, status;
    private TextView title, clock, nowLine, tags, about, dayLabel;
    private final List<TextView> slotLabels = new ArrayList<>();
    final List<TextView> tabs = new ArrayList<>();

    GuideFrame(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    void build() {
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(40), activity.dp(16), activity.dp(40), activity.dp(12));
        page.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { BACK_TOP, BACK_BOTTOM })
        );

        // "TV guide" on the left, the time on the right.
        LinearLayout top = activity.row();
        title = text("TV guide", 20, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("", 13, false);
        status.setTextColor(SOFT);
        status.setPadding(0, 0, activity.dp(18), 0);
        top.addView(status);
        clock = text("", 18, true);
        top.addView(clock);
        page.addView(top);

        // The selected programme, with the live picture beside it.
        LinearLayout info = activity.row();
        info.setGravity(Gravity.TOP);
        info.setPadding(0, activity.dp(6), 0, activity.dp(8));
        LinearLayout words = activity.vertical();
        LinearLayout line = activity.row();
        nowLine = text("", 18, true);
        line.addView(nowLine, new LinearLayout.LayoutParams(0, -2, 1));
        tags = text("", 14, true);
        tags.setTextColor(SOFT);
        line.addView(tags);
        words.addView(line);
        about = text("", 15, false);
        about.setSingleLine(false);
        about.setMaxLines(3);
        about.setEllipsize(android.text.TextUtils.TruncateAt.END);
        about.setLineSpacing(0, 1.1f);
        about.setPadding(0, activity.dp(4), 0, 0);
        words.addView(about);
        LinearLayout.LayoutParams wordsLayout = new LinearLayout.LayoutParams(0, -2, 1);
        wordsLayout.rightMargin = activity.dp(20);
        info.addView(words, wordsLayout);
        pictureBox = new FrameLayout(activity);
        pictureBox.setBackground(box(0xff000000, 0x88ffffff));
        pictureLabel = text("", 12, true);
        pictureLabel.setGravity(Gravity.CENTER);
        pictureLabel.setShadowLayer(activity.dp(4), 0, activity.dp(1), 0xcc000000);
        pictureBox.addView(pictureLabel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        info.addView(pictureBox, new LinearLayout.LayoutParams(activity.dp(192), activity.dp(108)));
        page.addView(info);

        // The tabs.
        LinearLayout tabRow = activity.row();
        tabs.clear();
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView tab = text(TABS[i][0], 15, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4));
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            tab.setOnFocusChangeListener((v, focused) -> styleTabs());
            tab.setOnClickListener(v -> openTab(index, false));
            tab.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int next = index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    if (next >= 0 && next < TABS.length - 1) openTab(next, true);
                    else if (next == TABS.length - 1) tabs.get(next).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    guide.tabFocus = false;
                    guide.focusFirstRow();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_UP;
            });
            LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(0, activity.dp(30), 1);
            tabLayout.rightMargin = activity.dp(2);
            tabRow.addView(tab, tabLayout);
            tabs.add(tab);
        }
        page.addView(tabRow);

        // The time slots: the day on the left over the channel names, then three half hours.
        LinearLayout slots = activity.row();
        slots.setPadding(0, activity.dp(4), 0, activity.dp(2));
        dayLabel = text("Today", 16, true);
        dayLabel.setGravity(Gravity.END);
        dayLabel.setPadding(0, 0, activity.dp(10), 0);
        slots.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 34));
        LinearLayout times = activity.row();
        slotLabels.clear();
        for (int i = 0; i < 3; i++) {
            TextView label = text("", 16, true);
            slotLabels.add(label);
            times.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        slots.addView(times, new LinearLayout.LayoutParams(0, -2, 66));
        page.addView(slots);

        grid = activity.vertical();
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        // Colour keys, as round buttons on the remote, and what SELECT does.
        LinearLayout keys = activity.row();
        keys.setPadding(0, activity.dp(6), 0, 0);
        colourKey(keys, SkyOverlay.RED, "Reminders");
        colourKey(keys, SkyOverlay.GREEN, "Search A–Z");
        colourKey(keys, LiveTvScreen.YELLOW, "+24 Hours");
        colourKey(keys, SkyOverlay.BLUE, "−24 Hours");
        page.addView(keys);
        TextView press = text(
            "Press SELECT to view or set a reminder   ·   i for information   ·   BACK for the guide menu",
            14,
            true
        );
        press.setPadding(0, activity.dp(2), 0, 0);
        page.addView(press);
    }

    /** Updates the clock, the day and the time slots for {@code start}, and the tabs. */
    void update(long start) {
        clock.setText(
            LiveTvScreen.clockTime(new Date()) +
                " " +
                new SimpleDateFormat("EEE d/M", Locale.UK).format(new Date())
        );
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.UK)
            .format(new Date(start))
            .equals(new SimpleDateFormat("yyyyMMdd", Locale.UK).format(new Date()));
        dayLabel.setText(today ? "Today" : new SimpleDateFormat("EEE d", Locale.UK).format(new Date(start)));
        for (int i = 0; i < slotLabels.size(); i++) slotLabels
            .get(i)
            .setText(
                (i == 0 && guide.windowOffset > 0 ? "◀ " : "") +
                    LiveTvScreen.clockTime(new Date(start + i * LiveTvScreen.HALF_HOUR)) +
                    (i == slotLabels.size() - 1 ? "  ▶" : "")
            );
        // Lists without a tab of their own (Music, a country, a search) are named in the title.
        title.setText(currentTab() == TABS.length - 1 ? "TV guide  ·  " + guide.listName() : "TV guide");
        styleTabs();
    }

    /** The selected programme at the top: "8.00pm EastEnders", its tags and description. */
    void preview(ChannelDatabase.Row channel, ChannelDatabase.Programme item) {
        if (item == null) {
            nowLine.setText(channel.number + "  " + channel.name);
            tags.setText("");
            about.setText("No programme information for this channel.");
            return;
        }
        nowLine.setText(LiveTvScreen.clockTime(new Date(item.start)) + "  " + item.title);
        tags.setText(item.tags());
        about.setText(item.description.isEmpty() ? channel.number + "  " + channel.name : item.description);
    }

    /** The tab for the list shown; More… for anything without one of its own. */
    int currentTab() {
        String filter = guide.search.isEmpty() ? guide.filter : "";
        for (int i = 0; i < TABS.length - 1; i++) if (TABS[i][1].equals(filter)) return i;
        return TABS.length - 1;
    }

    void focusTab() {
        tabs.get(currentTab()).requestFocus();
    }

    /** Shows a tab's list. More… opens the guide menu. Moving along the tabs keeps the focus there. */
    private void openTab(int index, boolean stayOnTabs) {
        if (TABS[index][1].isEmpty()) {
            guide.menu.show();
            return;
        }
        guide.show(TABS[index][1], "");
        guide.tabFocus = stayOnTabs; // read when the list arrives
    }

    private void styleTabs() {
        int current = currentTab();
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            boolean focused = tab.hasFocus();
            tab.setBackground(box(focused ? LiveTvScreen.YELLOW : i == current ? TAB_ON : TAB, 0x55ffffff));
            tab.setTextColor(focused ? LiveTvScreen.INK : i == current ? 0xffffffff : SOFT);
        }
    }

    private void colourKey(LinearLayout parent, int colour, String label) {
        View dot = new View(activity);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(colour);
        shape.setStroke(activity.dp(1), 0x88ffffff);
        dot.setBackground(shape);
        LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(activity.dp(16), activity.dp(16));
        dotLayout.rightMargin = activity.dp(6);
        parent.addView(dot, dotLayout);
        TextView text = text(label, 16, true);
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(-2, -2);
        textLayout.rightMargin = activity.dp(30);
        parent.addView(text, textLayout);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        label.setShadowLayer(activity.dp(2), 0, activity.dp(1), 0x66000000);
        SkyFont.apply(label, bold);
        return label;
    }

    private GradientDrawable box(int colour, int edge) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        shape.setStroke(activity.dp(1), edge);
        return shape;
    }
}
