package local.tv.home;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The fixed parts of the Live TV guide, in the dark-blue classic look the owner chose (as in
 * version 0.26): the time and date, the list's name in capitals and the channel status on the
 * left; the Options button and the live picture on the right; the time slots; the channel grid;
 * and the colour keys with "Press SELECT to view" underneath. Sections are on the blue key (the
 * guide menu).
 */
final class GuideFrame {

    /** The guide's background: dark bands top and bottom, a lighter blue sweep in the middle. */
    static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            LiveTvScreen.NAVY,
            LiveTvScreen.NAVY,
            0xff2a43a6,
            0xff6a8ce0,
            0xff3654b8,
            0xff1d2f86,
            LiveTvScreen.NAVY,
        });
    }

    private final MainActivity activity;
    private final LiveTvScreen guide;
    LinearLayout grid;
    FrameLayout pictureBox;
    TextView pictureLabel, status;
    Button optionsButton;
    /** The colour keys along the bottom, which can be chosen with the D-pad. */
    KeyBar bar;
    /** The colour keys' colours, as on the remote. */
    static final int RED = 0xffe53935,
        GREEN = 0xff43a047,
        BLUE = 0xff1e63d6;
    private TextView when, listTitle, dayLabel;
    private final List<TextView> slotLabels = new ArrayList<>();

    GuideFrame(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    void build() {
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(40), activity.dp(18), activity.dp(40), activity.dp(14));
        page.setBackground(background());

        // Header: the time and date and the list name on the left; on the right the Options
        // button and the live picture.
        LinearLayout header = activity.row();
        header.setGravity(Gravity.TOP);
        LinearLayout titles = activity.vertical();
        when = text("", 20, false);
        titles.addView(when);
        listTitle = text("", 30, true);
        titles.addView(listTitle);
        status = text("", 13, false);
        status.setTextColor(0xffc9d3ff);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        optionsButton = activity.button("Options", guide.panels::options);
        optionsButton.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && !guide.cells.isEmpty()) {
                guide.focusFirstRow();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams optionsLayout = new LinearLayout.LayoutParams(-2, activity.dp(40));
        optionsLayout.rightMargin = activity.dp(16);
        header.addView(optionsButton, optionsLayout);
        pictureBox = new FrameLayout(activity);
        pictureBox.setBackground(box(0xff000000));
        pictureLabel = text("", 13, true);
        pictureLabel.setGravity(Gravity.CENTER);
        pictureLabel.setShadowLayer(activity.dp(4), 0, activity.dp(1), 0xcc000000);
        pictureBox.addView(pictureLabel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        header.addView(pictureBox, new LinearLayout.LayoutParams(activity.dp(160), activity.dp(90)));
        page.addView(header);

        // Time slots, like "Today 8.00pm 8.30pm 9.00pm".
        LinearLayout slots = activity.row();
        dayLabel = text("Today", 17, true);
        dayLabel.setGravity(Gravity.END);
        dayLabel.setPadding(0, 0, activity.dp(10), 0);
        slots.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 34));
        LinearLayout times = activity.row();
        slotLabels.clear();
        for (int i = 0; i < 3; i++) {
            TextView label = text("", 17, true);
            slotLabels.add(label);
            times.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        slots.addView(times, new LinearLayout.LayoutParams(0, -2, 66));
        page.addView(slots);

        grid = activity.vertical();
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        // Colour keys, as on the remote. Each is a button too (Down from the bottom row), for
        // remotes without coloured buttons.
        bar = new KeyBar(activity)
            .onUp(guide::focusLastRow)
            .add(RED, "Page up", () -> guide.pageFromBar(-1, 0))
            .add(GREEN, "Page down", () -> guide.pageFromBar(1, 1))
            .add(LiveTvScreen.YELLOW, "Search", guide.panels::searchPanel)
            .add(BLUE, "Sections", guide.menu::show);
        bar.view.setPadding(0, activity.dp(4), 0, 0);
        page.addView(bar.view);
        // "Press SELECT to view", with SELECT on a yellow key as the old guide printed it.
        android.text.SpannableString select = new android.text.SpannableString(
            "Press  SELECT  to view  ·  ◀ ▶ through the day  ·  Hold  SELECT  for favourites"
        );
        for (
            int at = select.toString().indexOf(" SELECT ");
            at >= 0;
            at = select.toString().indexOf(" SELECT ", at + 1)
        ) {
            select.setSpan(new android.text.style.BackgroundColorSpan(LiveTvScreen.YELLOW), at, at + 8, 0);
            select.setSpan(new android.text.style.ForegroundColorSpan(LiveTvScreen.INK), at, at + 8, 0);
        }
        TextView press = text("", 16, true);
        press.setText(select);
        page.addView(press);
    }

    /** Updates the clock, the list's name, the day and the time slots for {@code start}. */
    void update(long start) {
        when.setText(LiveTvScreen.clock(new Date()));
        listTitle.setText(guide.listName().toUpperCase(Locale.ROOT));
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.UK)
            .format(new Date(start))
            .equals(new SimpleDateFormat("yyyyMMdd", Locale.UK).format(new Date()));
        dayLabel.setText(today ? "Today" : new SimpleDateFormat("EEE", Locale.UK).format(new Date(start)));
        for (int i = 0; i < slotLabels.size(); i++) slotLabels
            .get(i)
            .setText(LiveTvScreen.clockTime(new Date(start + i * LiveTvScreen.HALF_HOUR)));
    }

    /** Up from the top row, or nothing in the grid: the Options button. */
    void focusTop() {
        if (optionsButton != null) optionsButton.requestFocus();
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable box(int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(activity.dp(3));
        shape.setStroke(activity.dp(1), 0x55ffffff);
        return shape;
    }
}
