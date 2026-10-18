package local.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
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
 * <p>Channels carry UK satellite numbers where known (see {@link SkyNumbers}). The last channel
 * watched plays in the top corner. Programme titles come from the free UK programme guide (see
 * {@link EpgImporter}); channels it does not cover say so.
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
    /**
     * What the guide shows now: "all", "favourites", "sports", "section:<name>", "group:<name>",
     * plus a search.
     */
    String filter = "all",
        search = "";
    int pageStart;
    /** The guide's channel cells on the current page (for tests and focus). */
    final List<View> cells = new ArrayList<>();

    /** The guide's side panels: options, sections, programme information, lock and reminders. */
    final LiveTvPanels panels;
    /** The live picture in the corner of the guide. */
    final GuidePicture picture = new GuidePicture();
    /** Preference: the last channel watched (its stream key), shown in the guide's picture. */
    static final String LAST_CHANNEL = "lastLiveKey";
    /** Preference: whether the guide plays the last channel in its corner. */
    static final String PICTURE = "guidePicture";

    /** The guide frame (header, picture, footer) stays while paging; only the grid reloads. */
    private int frameGeneration = -1;
    private TextView when, listTitle, status, pictureLabel;
    private final List<TextView> slotLabels = new ArrayList<>();
    private TextView dayLabel;
    private final List<TextView> dayTabs = new ArrayList<>();
    /** The guide rows: channel names and programme cells, and moving along them. */
    final GuideRows rows;
    /** A programme cell has the focus (not a channel name), at this point in time. */
    boolean programmeFocus;
    long cursorTime;
    /** How far the time slots have moved on from now (multiples of half an hour). */
    long windowOffset;
    /** The slots go up to a day ahead, as far as the programme guide reaches. */
    static final long MAX_OFFSET = 24L * 60 * 60 * 1000,
        HALF_HOUR = 30L * 60 * 1000;
    private LinearLayout grid;
    private Button optionsButton;
    private android.widget.FrameLayout pictureBox;
    private int loadTicket;

    LiveTvScreen(MainActivity activity) {
        this.activity = activity;
        panels = new LiveTvPanels(activity, this);
        rows = new GuideRows(activity, this);
    }

    ChannelDatabase channelsDb() {
        return ChannelDatabase.get(activity);
    }

    Set<String> favouriteKeys() {
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
        windowOffset = 0;
        programmeFocus = false;
        guide(0, 0);
    }

    /** Shows the page starting at {@code start}, focusing row {@code focusRow} (-1 for the last). */
    void guide(int start, int focusRow) {
        pageStart = Math.max(0, start);
        if (frameGeneration != activity.generation || grid == null) buildFrame();
        when.setText(clock(new Date()));
        listTitle.setText(listName().toUpperCase(Locale.ROOT));
        long first = windowStart();
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.UK)
            .format(new Date(first))
            .equals(new SimpleDateFormat("yyyyMMdd", Locale.UK).format(new Date()));
        dayLabel.setText(today ? "Today" : new SimpleDateFormat("EEE", Locale.UK).format(new Date(first)));
        for (int i = 0; i < slotLabels.size(); i++) slotLabels
            .get(i)
            .setText(clockTime(new Date(first + i * 30 * 60000L)));
        for (int i = 0; i < dayTabs.size(); i++) {
            long day = System.currentTimeMillis() + i * MAX_OFFSET;
            boolean shown = (i == 0) == today;
            TextView tab = dayTabs.get(i);
            tab.setText(
                (i == 0 ? "Today " : "") + new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(day))
            );
            tab.setBackground(box(shown ? YELLOW : BLUE_DARK));
            tab.setTextColor(shown ? INK : 0xffdfe6ff);
        }
        loadPage(focusRow);
    }

    /** The guide's fixed parts: header with the live picture, time slots, grid area and keys. */
    private void buildFrame() {
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

        // Header: the time and date and the list name on the left; on the right the Options
        // button and the live picture, as the satellite guide kept the channel playing there.
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
        optionsButton = activity.button("Options", panels::options);
        LinearLayout.LayoutParams optionsLayout = new LinearLayout.LayoutParams(-2, activity.dp(40));
        optionsLayout.rightMargin = activity.dp(16);
        header.addView(optionsButton, optionsLayout);
        pictureBox = new android.widget.FrameLayout(activity);
        pictureBox.setBackground(box(0xff000000));
        pictureLabel = text("", 13, true);
        pictureLabel.setGravity(Gravity.CENTER);
        pictureLabel.setShadowLayer(activity.dp(4), 0, activity.dp(1), 0xcc000000);
        pictureBox.addView(pictureLabel, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        header.addView(pictureBox, new LinearLayout.LayoutParams(activity.dp(160), activity.dp(90)));
        activity.page.addView(header);

        // The day bar: today and tomorrow (as far as the programme guide reaches), the day shown
        // in yellow. The yellow key moves between them.
        LinearLayout days = activity.row();
        days.setPadding(0, activity.dp(4), 0, activity.dp(2));
        dayTabs.clear();
        for (int i = 0; i < 2; i++) {
            TextView tab = text("", 15, true);
            tab.setPadding(activity.dp(12), activity.dp(2), activity.dp(12), activity.dp(2));
            LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(-2, -2);
            tabLayout.rightMargin = activity.dp(8);
            days.addView(tab, tabLayout);
            dayTabs.add(tab);
        }
        page.addView(days);

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

        // Colour keys, as on the remote.
        LinearLayout keys = activity.row();
        keys.setPadding(0, activity.dp(6), 0, 0);
        colourKey(keys, 0xffe53935, "Page up");
        colourKey(keys, 0xff43a047, "Page down");
        colourKey(keys, YELLOW, "+24 hours");
        colourKey(keys, 0xff1e63d6, "Sections");
        page.addView(keys);
        // "Press SELECT to view", with SELECT on a yellow key as the old guide printed it.
        android.text.SpannableString select = new android.text.SpannableString(
            "Press  SELECT  to view  ·  ◀ ▶ through the day  ·  i for information  ·  Hold  SELECT  on a name for favourites"
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

        frameGeneration = activity.generation;
        activity.leaving = () -> {
            picture.stop();
            frameGeneration = -1;
        };
        startPicture();
    }

    /** The first time slot shown: now's half hour, moved on by ◀ ▶. */
    long windowStart() {
        return nowSlot() + windowOffset;
    }

    /** The start of the half hour now. */
    private static long nowSlot() {
        Calendar slot = Calendar.getInstance();
        slot.set(Calendar.MINUTE, slot.get(Calendar.MINUTE) < 30 ? 0 : 30);
        slot.set(Calendar.SECOND, 0);
        slot.set(Calendar.MILLISECOND, 0);
        return slot.getTimeInMillis();
    }

    /** Plays the last channel watched in the guide's corner (unless turned off or locked). */
    void startPicture() {
        if (pictureBox != null) picture.show(activity, pictureBox, pictureLabel);
    }

    /** Loads the current page of channels and their programmes into the grid. */
    private void loadPage(int focusRow) {
        int ticket = ++loadTicket;
        grid.removeAllViews();
        cells.clear();
        rows.clear();
        grid.addView(text("Loading channels…", 18, false));
        String listFilter = filter,
            listSearch = search;
        int start = pageStart;
        long windowStart = windowStart();
        activity.async(
            () -> {
                List<ChannelDatabase.Row> rows = channelsDb().browse(
                    listFilter,
                    listSearch,
                    favouriteKeys(),
                    start
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
                if (ticket != loadTicket) return; // a later page was asked for meanwhile
                @SuppressWarnings("unchecked")
                List<ChannelDatabase.Row> items = (List<ChannelDatabase.Row>) result[0];
                @SuppressWarnings("unchecked")
                List<List<ChannelDatabase.Programme>> listings = (List<
                    List<ChannelDatabase.Programme>
                >) result[1];
                grid.removeAllViews();
                showStatus(status);
                cells.clear();
                rows.clear();
                int count = Math.min(items.size(), ROWS);
                boolean more = items.size() > ROWS;
                Set<String> favourites = favouriteKeys(),
                    reminders = new HashSet<>();
                for (Reminders.Reminder item : Reminders.all(activity)) reminders.add(item.id());
                for (int i = 0; i < count; i++) rows.add(
                    grid,
                    items.get(i),
                    listings.get(i),
                    windowStart,
                    pageStart + i,
                    i,
                    count,
                    more,
                    favourites,
                    reminders
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
                        panels::options
                    );
                    optionsButton.requestFocus();
                    return;
                }
                int focus = focusRow < 0 ? count - 1 : Math.min(focusRow, count - 1);
                if (programmeFocus) rows.focusAt(focus, cursorTime);
                else cells.get(focus).requestFocus();
            }
        );
    }

    private String listName() {
        if (!search.isEmpty()) return "Search: " + search;
        if (filter.equals("favourites")) return "Favourites";
        if (filter.equals("sports")) return "Sports";
        if (filter.startsWith("section:")) return filter.substring(8);
        if (filter.startsWith("country:")) return GuideOrder.name(filter.substring(8));
        if (filter.startsWith("group:")) return filter.substring(6).isEmpty()
            ? "Ungrouped"
            : filter.substring(6);
        return "All channels";
    }

    /** The guide label for a channel: its number, a lock, a star for favourites, its name. */
    String label(ChannelDatabase.Row channel, boolean favourite) {
        return (
            channel.number +
            "  " +
            (ParentalLock.locked(activity, channel) ? "🔒 " : "") +
            (favourite ? "★ " : "") +
            channel.name
        );
    }

    /**
     * Opens a channel full screen, asking for the PIN first when it is locked. Up and down in the
     * player follow the list the guide shows; coming back shows the page of the last channel.
     */
    void open(ChannelDatabase.Row channel, int position) {
        if (ParentalLock.locked(activity, channel)) {
            ParentalLock.check(activity, channel.name, () -> play(channel, position), null);
            return;
        }
        play(channel, position);
    }

    private void play(ChannelDatabase.Row channel, int position) {
        String listFilter = filter,
            listSearch = search;
        activity.async(
            () -> channelsDb().url(channel.id, channel.key),
            url -> {
                LiveZapper.session = new LiveZapper.Session(
                    listFilter,
                    listSearch,
                    favouriteKeys(),
                    position
                );
                activity.prefs.edit().putString(LAST_CHANNEL, channel.key).apply();
                // The guide's picture keeps playing: the full screen takes it over, so the same
                // channel carries on without reconnecting.
                activity.resumeRefresh = () -> {
                    int last = LiveZapper.session == null ? position : LiveZapper.session.position;
                    guide(last - (last % ROWS), last % ROWS);
                };
                activity.play(channel.name, url, channel.key, true);
            }
        );
    }

    /** "Watch now" on a reminder: finds the channel in all channels and opens it. */
    void watch(Reminders.Reminder reminder) {
        activity.async(
            () -> {
                ChannelDatabase.Row row = channelsDb().byKey(reminder.key);
                int position =
                    row == null ? -1 : channelsDb().position("all", "", favouriteKeys(), row.number, true);
                return new Object[] { row, position };
            },
            result -> {
                ChannelDatabase.Row row = (ChannelDatabase.Row) result[0];
                if (row == null) {
                    activity.toast(reminder.channel + " is no longer in your channel lists");
                    return;
                }
                filter = "all";
                search = "";
                open(row, Math.max(0, (Integer) result[1]));
            }
        );
    }

    private boolean isPageUp(int key) {
        return colour(key) == 'r' || key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP;
    }

    private boolean isPageDown(int key) {
        return (
            colour(key) == 'g' || key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN
        );
    }

    /** The row that has the focus (its name or a programme), so a refresh keeps the place. */
    int focusedRow() {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).hasFocus()) return i;
            if (i < rows.programmes.size()) for (View item : rows.programmes.get(i))
                if (item.hasFocus()) return i;
        }
        return 0;
    }

    /**
     * ▶ past the last slot: moves the times on until {@code target} is shown, keeping to
     * {@code row}.
     */
    void later(long target, int row) {
        if (windowOffset >= MAX_OFFSET) return;
        while (windowStart() + WINDOW_MS <= target && windowOffset < MAX_OFFSET) windowOffset += HALF_HOUR;
        cursorTime = target;
        programmeFocus = true;
        guide(pageStart, row);
    }

    /** ◀ before the first slot: moves the times back towards now. False when already at now. */
    boolean earlier(long target, int row) {
        if (windowOffset <= 0) return false;
        while (windowStart() > target && windowOffset > 0) windowOffset -= HALF_HOUR;
        cursorTime = Math.max(target, windowStart());
        programmeFocus = true;
        guide(pageStart, row);
        return true;
    }

    /**
     * The yellow key: the same time tomorrow, then back to now, as far as the programme guide
     * reaches.
     */
    void nextDay(int row) {
        if (windowOffset == 0) {
            windowOffset += MAX_OFFSET;
            cursorTime += MAX_OFFSET;
        } else {
            cursorTime = Math.max(nowSlot(), cursorTime - windowOffset);
            windowOffset = 0;
        }
        guide(pageStart, row);
    }

    /** Keys that work the same on a channel name and a programme: numbers, paging, colour keys. */
    boolean sharedKey(int key, int row, boolean more) {
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            typeDigit(key - KeyEvent.KEYCODE_0);
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
            nextDay(row);
            return true;
        }
        if (colour(key) == 'b') {
            panels.sections();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            panels.options();
            return true;
        }
        return false;
    }

    boolean isGuideKey(int key) {
        return (
            isPageUp(key) ||
            isPageDown(key) ||
            colour(key) != 0 ||
            key == KeyEvent.KEYCODE_MENU ||
            key == KeyEvent.KEYCODE_INFO ||
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
        return colour(activity.prefs, key);
    }

    static char colour(android.content.SharedPreferences prefs, int key) {
        for (int i = 0; i < 4; i++) if (prefs.getInt(COLOUR_PREFS[i], -1) == key) return COLOURS[i];
        if (key == KeyEvent.KEYCODE_PROG_RED || key == KeyEvent.KEYCODE_F1) return 'r';
        if (key == KeyEvent.KEYCODE_PROG_GREEN || key == KeyEvent.KEYCODE_F2) return 'g';
        if (key == KeyEvent.KEYCODE_PROG_YELLOW || key == KeyEvent.KEYCODE_F3) return 'y';
        if (key == KeyEvent.KEYCODE_PROG_BLUE || key == KeyEvent.KEYCODE_F4) return 'b';
        return 0;
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

    /**
     * Goes to the typed channel number in this list; a number this list does not have opens it in
     * all channels, and a number no channel has goes to the nearest one above it.
     */
    private void jumpToTyped() {
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        String listFilter = filter,
            listSearch = search;
        activity.async(
            () -> {
                Set<String> keys = favouriteKeys();
                int here = channelsDb().position(listFilter, listSearch, keys, number, false);
                if (here >= 0) return new int[] { here, 0 };
                return new int[] { Math.max(0, channelsDb().position("all", "", keys, number, true)), 1 };
            },
            found -> {
                if (found[1] == 1) {
                    filter = "all";
                    search = "";
                }
                programmeFocus = false;
                guide(found[0] - (found[0] % ROWS), found[0] % ROWS);
            }
        );
    }

    void show(String newFilter, String newSearch) {
        filter = newFilter;
        search = newSearch;
        windowOffset = 0;
        programmeFocus = false;
        guide(0, 0);
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
                    } else guide(pageStart, focusedRow()); // the import finished: show the new channels
                }
            },
            2000
        );
    }

    private TextView text(String value, int size, boolean bold) {
        TextView label = activity.compactText(value, size);
        label.setTextColor(0xffffffff);
        SkyFont.apply(label, bold);
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
