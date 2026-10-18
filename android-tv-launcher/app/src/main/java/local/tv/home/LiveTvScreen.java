package local.tv.home;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live TV as a classic satellite-box TV guide: a blue full-screen grid of ten channels per page,
 * channel numbers and names on the left, the half-hour time slots across the top, the selected
 * channel in yellow, and a colour-key footer. Backed by the on-device channel database.
 *
 * <p>Programme titles need a programme guide (EPG) from the provider; the free iptv-org lists have
 * none, so their rows say so.
 */
final class LiveTvScreen {

    /** Channels per guide page, as on the classic guides. */
    static final int ROWS = 10;
    /** The guide shows three half-hour slots. */
    static final long WINDOW_MS = 90L * 60 * 1000;
    /** Guide colours. */
    static final int NAVY = 0xff10175a,
        BLUE = 0xff1f3fa8,
        BLUE_DARK = 0xff17318a,
        YELLOW = 0xffffd83a,
        INK = 0xff0d1a5c;

    private final MainActivity activity;
    /** What the guide shows now: "all", "favourites", "sports", "group:<name>", plus a search. */
    String filter = "all",
        search = "";
    int pageStart;
    /** The guide's channel cells on the current page (for tests and focus). */
    final List<View> cells = new ArrayList<>();

    LiveTvScreen(MainActivity activity) {
        this.activity = activity;
    }

    private ChannelDatabase channelsDb() {
        return ChannelDatabase.get(activity);
    }

    private Set<String> favouriteKeys() {
        return new HashSet<>(activity.prefs.getStringSet("favourites", Collections.emptySet()));
    }

    void live() {
        try {
            ChannelSync.refreshIfStale(activity, true);
        } catch (Exception e) {
            Diagnostics.recovered("Starting channel refresh", e);
        }
        filter = "all";
        search = "";
        guide(0, 0);
    }

    /** Shows the page starting at {@code start}, focusing row {@code focusRow} (-1 for the last). */
    void guide(int start, int focusRow) {
        pageStart = Math.max(0, start);
        activity.fixedScreen(null, activity.homeScreen::home);
        LinearLayout page = activity.page;
        page.setPadding(activity.dp(40), activity.dp(18), activity.dp(40), activity.dp(14));
        page.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
                // Dark bands top and bottom with a lighter blue sweep behind the grid, as on the
                // old satellite-box guide.
                NAVY,
                NAVY,
                0xff2a43a6,
                0xff6a8ce0,
                0xff3654b8,
                0xff1d2f86,
                NAVY,
            })
        );

        // Header: the time and date, then the list name in large capitals.
        LinearLayout header = activity.row();
        LinearLayout titles = activity.vertical();
        TextView when = text(clock(new Date()), 20, false);
        titles.addView(when);
        TextView name = text(listName().toUpperCase(Locale.ROOT), 30, true);
        titles.addView(name);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button options = activity.button("Options", this::options);
        header.addView(options, new LinearLayout.LayoutParams(-2, activity.dp(40)));
        page.addView(header);
        TextView status = text("", 13, false);
        status.setTextColor(0xffc9d3ff);
        page.addView(status);

        // Time slots, like "Today 8.00pm 8.30pm 9.00pm".
        LinearLayout slots = activity.row();
        TextView today = text("Today", 17, true);
        today.setGravity(Gravity.END);
        today.setPadding(0, 0, activity.dp(10), 0);
        slots.addView(today, new LinearLayout.LayoutParams(0, -2, 34));
        Calendar slot = Calendar.getInstance();
        slot.set(Calendar.MINUTE, slot.get(Calendar.MINUTE) < 30 ? 0 : 30);
        slot.set(Calendar.SECOND, 0);
        slot.set(Calendar.MILLISECOND, 0);
        long windowStart = slot.getTimeInMillis();
        LinearLayout times = activity.row();
        for (int i = 0; i < 3; i++) {
            times.addView(text(clockTime(slot.getTime()), 17, true), new LinearLayout.LayoutParams(0, -2, 1));
            slot.add(Calendar.MINUTE, 30);
        }
        slots.addView(times, new LinearLayout.LayoutParams(0, -2, 66));
        page.addView(slots);

        LinearLayout grid = activity.vertical();
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView loading = text("Loading channels…", 18, false);
        grid.addView(loading);

        // Colour keys, as on the remote.
        LinearLayout keys = activity.row();
        keys.setPadding(0, activity.dp(6), 0, 0);
        colourKey(keys, 0xffe53935, "Page up");
        colourKey(keys, 0xff43a047, "Page down");
        colourKey(keys, YELLOW, "Search");
        colourKey(keys, 0xff1e63d6, "Groups");
        page.addView(keys);
        // "Press SELECT to view", with SELECT on a yellow key as the old guide printed it.
        android.text.SpannableString select = new android.text.SpannableString(
            "Press  SELECT  to view  ·  Hold  SELECT  for favourites"
        );
        for (
            int at = select.toString().indexOf(" SELECT ");
            at >= 0;
            at = select.toString().indexOf(" SELECT ", at + 1)
        ) {
            select.setSpan(new android.text.style.BackgroundColorSpan(YELLOW), at, at + 8, 0);
            select.setSpan(new android.text.style.ForegroundColorSpan(INK), at, at + 8, 0);
        }
        TextView press = text("", 16, true);
        press.setText(select);
        page.addView(press);

        String listFilter = filter,
            listSearch = search;
        activity.async(
            () -> {
                List<ChannelDatabase.Row> rows = channelsDb().browse(
                    listFilter,
                    listSearch,
                    favouriteKeys(),
                    pageStart
                );
                // Programmes for the ten rows shown, across the three half-hour slots.
                List<List<ChannelDatabase.Programme>> listings = new ArrayList<>();
                for (int i = 0; i < Math.min(ROWS, rows.size()); i++) listings.add(
                    channelsDb().programmes(
                        rows.get(i),
                        EpgImporter.COUNTRY,
                        windowStart,
                        windowStart + WINDOW_MS
                    )
                );
                return new Object[] { rows, listings };
            },
            result -> {
                @SuppressWarnings("unchecked")
                List<ChannelDatabase.Row> items = (List<ChannelDatabase.Row>) result[0];
                @SuppressWarnings("unchecked")
                List<List<ChannelDatabase.Programme>> listings = (List<
                    List<ChannelDatabase.Programme>
                >) result[1];
                grid.removeAllViews();
                showStatus(status);
                cells.clear();
                int count = Math.min(items.size(), ROWS);
                boolean more = items.size() > ROWS;
                Set<String> favourites = favouriteKeys();
                for (int i = 0; i < count; i++) addRow(
                    grid,
                    items.get(i),
                    listings.get(i),
                    windowStart,
                    pageStart + i,
                    i,
                    count,
                    more,
                    favourites
                );
                for (int i = count; i < ROWS; i++) grid.addView(
                    new View(activity),
                    new LinearLayout.LayoutParams(-1, 0, 1)
                );
                if (count == 0) {
                    grid.removeAllViews();
                    activity.emptyState(
                        grid,
                        filter.equals("favourites") ? "No favourites yet" : "No channels here",
                        filter.equals("favourites")
                            ? "Hold SELECT on a channel to add it to your favourites."
                            : "Channels are still loading, or none match. Free channels and TV providers are in Settings → Accounts.",
                        "Options",
                        this::options
                    );
                    options.requestFocus();
                    return;
                }
                int focus = focusRow < 0 ? count - 1 : Math.min(focusRow, count - 1);
                cells.get(focus).requestFocus();
            }
        );
    }

    private String listName() {
        if (!search.isEmpty()) return "Search: " + search;
        if (filter.equals("favourites")) return "Favourites";
        if (filter.equals("sports")) return "Sports";
        if (filter.startsWith("group:")) return filter.substring(6).isEmpty()
            ? "Ungrouped"
            : filter.substring(6);
        return "All channels";
    }

    private void addRow(
        LinearLayout grid,
        ChannelDatabase.Row channel,
        List<ChannelDatabase.Programme> listing,
        long windowStart,
        int position,
        int row,
        int count,
        boolean more,
        Set<String> favourites
    ) {
        LinearLayout line = activity.row();
        line.setClipChildren(false);
        // Numbers start at 101, as on the classic guides.
        TextView cell = text(
            101 + position + "  " + (favourites.contains(channel.key) ? "★ " : "") + channel.name,
            19,
            true
        );
        cell.setSingleLine(true);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(activity.dp(10), 0, activity.dp(8), 0);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setContentDescription(channel.name);
        List<TextView> programmes = programmeCells(channel, listing, windowStart);
        LinearLayout programme = activity.row();
        for (TextView item : programmes) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                0,
                -1,
                ((LinearLayout.LayoutParams) item.getLayoutParams()).weight
            );
            layout.rightMargin = activity.dp(3);
            programme.addView(item, layout);
        }
        View.OnFocusChangeListener style = (v, focused) -> {
            cell.setBackground(box(focused ? YELLOW : BLUE));
            cell.setTextColor(focused ? INK : 0xffffffff);
            for (TextView item : programmes) {
                item.setBackground(box(focused ? 0xfffff1a6 : BLUE_DARK));
                item.setTextColor(focused ? INK : 0xffdfe6ff);
            }
        };
        cell.setOnFocusChangeListener(style);
        style.onFocusChange(cell, false);
        cell.setOnClickListener(v ->
            activity.async(
                () -> channelsDb().url(channel.id, channel.key),
                url -> {
                    // Up/down and numbers in the player follow this list; coming back shows
                    // the page of whatever channel was watched last.
                    LiveZapper.session = new LiveZapper.Session(filter, search, favouriteKeys(), position);
                    activity.resumeRefresh = () -> {
                        int last = LiveZapper.session == null ? position : LiveZapper.session.position;
                        guide(last - (last % ROWS), last % ROWS);
                    };
                    activity.play(channel.name, url, channel.key, true);
                }
            )
        );
        cell.setOnLongClickListener(v -> {
            Set<String> keys = favouriteKeys();
            boolean added = keys.add(channel.key);
            if (!added) keys.remove(channel.key);
            activity.prefs.edit().putStringSet("favourites", keys).apply();
            cell.setText(101 + position + "  " + (added ? "★ " : "") + channel.name);
            activity.toast(added ? "Added to favourites" : "Removed from favourites");
            return true;
        });
        cell.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return isGuideKey(key);
            if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
                typeDigit(key - KeyEvent.KEYCODE_0);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && row == count - 1 && more) {
                guide(pageStart + ROWS, 0);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && row == 0 && pageStart > 0) {
                guide(pageStart - ROWS, -1);
                return true;
            }
            if (isPageUp(key)) {
                if (pageStart > 0) guide(pageStart - ROWS, row);
                return true;
            }
            if (isPageDown(key)) {
                if (more) guide(pageStart + ROWS, row);
                return true;
            }
            if (colour(key) == 'y') {
                searchPanel();
                return true;
            }
            if (colour(key) == 'b') {
                groups();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                options();
                return true;
            }
            return false;
        });
        cells.add(cell);
        LinearLayout.LayoutParams cellLayout = new LinearLayout.LayoutParams(0, -1, 34);
        cellLayout.rightMargin = activity.dp(8);
        line.addView(cell, cellLayout);
        line.addView(programme, new LinearLayout.LayoutParams(0, -1, 66));
        LinearLayout.LayoutParams lineLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        lineLayout.topMargin = activity.dp(4);
        grid.addView(line, lineLayout);
    }

    private boolean isPageUp(int key) {
        return colour(key) == 'r' || key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP;
    }

    private boolean isPageDown(int key) {
        return (
            colour(key) == 'g' || key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN
        );
    }

    private boolean isGuideKey(int key) {
        return (
            isPageUp(key) ||
            isPageDown(key) ||
            colour(key) != 0 ||
            key == KeyEvent.KEYCODE_MENU ||
            (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9)
        );
    }

    /** Remote colour-key preferences, set by "Set up coloured buttons". */
    static final String[] COLOUR_PREFS = { "remoteRed", "remoteGreen", "remoteYellow", "remoteBlue" };
    static final char[] COLOURS = { 'r', 'g', 'y', 'b' };

    /**
     * Which colour key this is ('r', 'g', 'y', 'b') or 0. Remotes send different codes for the
     * colour keys: the standard ones, F1–F4 on many air-mouse remotes, or whatever was taught in
     * "Set up coloured buttons".
     */
    char colour(int key) {
        for (int i = 0; i < 4; i++) if (activity.prefs.getInt(COLOUR_PREFS[i], -1) == key) return COLOURS[i];
        if (key == KeyEvent.KEYCODE_PROG_RED || key == KeyEvent.KEYCODE_F1) return 'r';
        if (key == KeyEvent.KEYCODE_PROG_GREEN || key == KeyEvent.KEYCODE_F2) return 'g';
        if (key == KeyEvent.KEYCODE_PROG_YELLOW || key == KeyEvent.KEYCODE_F3) return 'y';
        if (key == KeyEvent.KEYCODE_PROG_BLUE || key == KeyEvent.KEYCODE_F4) return 'b';
        return 0;
    }

    /** Teaches the guide this remote's colour keys: press red, green, yellow, then blue. */
    void learnColourKeys(int step) {
        if (step >= 4) {
            activity.toast("Coloured buttons set up");
            guide(pageStart, 0);
            return;
        }
        String[] names = { "RED", "GREEN", "YELLOW", "BLUE" };
        android.app.Dialog panel = SidePanel.of(activity, "Press the " + names[step] + " button")
            .message(
                "Press the " +
                    names[step].toLowerCase(Locale.ROOT) +
                    " button on your remote now. Back cancels."
            )
            .show();
        panel.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK) return false;
            boolean navigation =
                key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER;
            if (navigation) return true;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                activity.prefs.edit().putInt(COLOUR_PREFS[step], key).apply();
                panel.dismiss();
                learnColourKeys(step + 1);
            }
            return true;
        });
    }

    private final StringBuilder typed = new StringBuilder();
    private final Runnable jump = this::jumpToTyped;

    /** Number keys go straight to a channel, as on a satellite box: "151" opens channel 151. */
    private void typeDigit(int digit) {
        if (typed.length() >= 6) typed.setLength(0);
        typed.append(digit);
        activity.toast("Channel " + typed);
        activity.handler.removeCallbacks(jump);
        activity.handler.postDelayed(jump, 1500);
    }

    private void jumpToTyped() {
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        int position = Math.max(0, number - 101);
        guide(position - (position % ROWS), position % ROWS);
    }

    /**
     * The programme cells across the 90-minute window, sized by how much of it each programme
     * fills, with "No information" in any gap; one cell when there is no guide for the channel.
     */
    private List<TextView> programmeCells(
        ChannelDatabase.Row channel,
        List<ChannelDatabase.Programme> listing,
        long windowStart
    ) {
        List<TextView> out = new ArrayList<>();
        long windowEnd = windowStart + WINDOW_MS;
        if (listing.isEmpty()) {
            out.add(
                programmeCell(
                    (channel.group.isEmpty() ? "" : channel.group + "  ·  ") + "No programme information",
                    1
                )
            );
            return out;
        }
        long at = windowStart;
        for (ChannelDatabase.Programme item : listing) {
            long from = Math.max(item.start, windowStart),
                to = Math.min(item.stop, windowEnd);
            if (to <= at) continue;
            if (from > at + 60000) out.add(programmeCell("", (from - at) / 60000f));
            from = Math.max(from, at);
            // Arrows show a programme starting before or running past the slots shown.
            String title =
                (item.start < windowStart ? "‹ " : "") + item.title + (item.stop > windowEnd ? "  ›" : "");
            out.add(programmeCell(title, (to - from) / 60000f));
            at = to;
            if (at >= windowEnd) break;
        }
        if (windowEnd - at > 60000) out.add(programmeCell("", (windowEnd - at) / 60000f));
        return out;
    }

    private TextView programmeCell(String title, float minutes) {
        TextView item = text(title, 17, true);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(activity.dp(8), 0, activity.dp(4), 0);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, -1, Math.max(1f, minutes)));
        return item;
    }

    /** Everything the colour keys do, for remotes without colour keys. */
    void options() {
        SidePanel.of(activity, "Live TV")
            .option("All channels", () -> show("all", ""))
            .option("Favourites", () -> show("favourites", ""))
            .option("Sports", () -> show("sports", ""))
            .option("Groups", this::groups)
            .option("Search", this::searchPanel)
            .option("Set up coloured buttons", () -> learnColourKeys(0))
            .option("Refresh channels", () -> {
                ChannelSync.refreshNow(activity);
                guide(0, 0);
            })
            .show();
    }

    private void show(String newFilter, String newSearch) {
        filter = newFilter;
        search = newSearch;
        guide(0, 0);
    }

    private void groups() {
        activity.async(
            () -> channelsDb().groups(),
            groups -> {
                String[] names = new String[groups.size()];
                for (int i = 0; i < names.length; i++) names[i] =
                    (groups.get(i).name.isEmpty() ? "Ungrouped" : groups.get(i).name) +
                    " · " +
                    groups.get(i).count;
                SidePanel.of(activity, "Channel groups")
                    .choices(names, -1, which -> show("group:" + groups.get(which).name, ""))
                    .show();
            }
        );
    }

    private void searchPanel() {
        LinearLayout form = activity.vertical();
        EditText query = activity.field(form, "Channel or group", false, search);
        android.app.Dialog dialog = activity.form("Search live TV", form);
        form.addView(
            activity.button("Search", () -> {
                dialog.dismiss();
                show(filter.equals("favourites") ? "favourites" : "all", query.getText().toString().trim());
            })
        );
    }

    private void showStatus(TextView status) {
        ChannelImporter.Status sync = ChannelImporter.status(activity);
        status.setText(
            sync.running
                ? String.format(Locale.getDefault(), "Updating channels · %,d imported", sync.progress)
                : sync.queued
                  ? "Waiting for the network to update channels"
                  : sync.message
        );
        if (!sync.running && !sync.queued) return;
        int request = activity.generation;
        activity.handler.postDelayed(
            new Runnable() {
                public void run() {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    ChannelImporter.Status latest = ChannelImporter.status(activity);
                    if (latest.running || latest.queued) {
                        status.setText(
                            latest.running
                                ? String.format(
                                      Locale.getDefault(),
                                      "Updating channels · %,d imported",
                                      latest.progress
                                  )
                                : "Waiting for the network to update channels"
                        );
                        activity.handler.postDelayed(this, 2000);
                    } else guide(pageStart, 0); // the import finished: show the new channels
                }
            },
            2000
        );
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

    private void colourKey(LinearLayout parent, int colour, String label) {
        View swatch = new View(activity);
        swatch.setBackground(box(colour));
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(
            activity.dp(18),
            activity.dp(18)
        );
        swatchLayout.rightMargin = activity.dp(6);
        parent.addView(swatch, swatchLayout);
        TextView text = text(label, 17, true);
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(-2, -2);
        textLayout.rightMargin = activity.dp(34);
        parent.addView(text, textLayout);
    }

    /** "8.00pm Mon 18 Jul", as the classic guides wrote it. */
    static String clock(Date when) {
        return clockTime(when) + " " + new SimpleDateFormat("EEE d MMM", Locale.UK).format(when);
    }

    static String clockTime(Date when) {
        return new SimpleDateFormat("h.mma", Locale.UK).format(when).toLowerCase(Locale.ROOT);
    }

    private static final class FoundChannel extends Exception {}

    /** Reads only until the first playable channel, so connecting a very large playlist stays quick. */
    boolean hasChannel(String url) throws Exception {
        try (java.io.InputStream in = Network.open(url, null, Network.CATALOGUE_MAX, activity.http())) {
            Playlist.parse(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                url,
                activity.http(),
                c -> {
                    throw new FoundChannel();
                }
            );
        } catch (FoundChannel found) {
            return true;
        }
        return false;
    }
}
