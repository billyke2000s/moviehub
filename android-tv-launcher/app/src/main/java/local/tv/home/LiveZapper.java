package local.tv.home;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Satellite-box behaviour while watching Live TV: channel up and down, typing a channel number,
 * the last-channel key, the now-and-next banner on every change, and backup copies of a channel
 * (the same channel from another free list) with automatic switching when a stream fails or keeps
 * buffering.
 */
final class LiveZapper {

    /** What the guide was showing when a channel was chosen, so up and down follow the same list. */
    static final class Session {

        final String filter, search;
        final Set<String> favourites;
        int position;

        Session(String filter, String search, Set<String> favourites, int position) {
            this.filter = filter;
            this.search = search;
            this.favourites = new HashSet<>(favourites);
            this.position = position;
        }
    }

    /** Set by the guide when a channel is chosen; read by the player. */
    static volatile Session session;

    /** Buffering this long, or this many pauses in a minute, moves to the next backup. */
    static final long STALL_MS = 8000;
    static final int STALLS_PER_MINUTE = 3;

    private final VlcPlaybackActivity player;
    /** This player's own copy, so a later guide visit or test clearing the shared one is safe. */
    private final Session own;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ChannelDatabase db;
    private List<ChannelDatabase.Row> copies = new ArrayList<>();
    private int copy,
        previousPosition = -1,
        request;
    private final List<Long> stalls = new ArrayList<>();
    private LinearLayout banner;
    private TextView bannerNumber, bannerName, bannerNow, bannerNext, bannerSource;
    private final StringBuilder typed = new StringBuilder();
    private final Runnable jump = this::jumpToTyped;
    private final Runnable hideBanner = () -> {
        if (banner != null) banner.setVisibility(View.GONE);
    };
    private final Runnable stalledTooLong = () -> failover("The stream kept buffering");

    LiveZapper(VlcPlaybackActivity player, Session session) {
        this.player = player;
        own = session;
        db = ChannelDatabase.get(player);
    }

    /** Builds the banner and loads the current channel's backups and listings. */
    void attach(FrameLayout root) {
        buildBanner(root);
        load(own.position, false, true);
    }

    int position() {
        return own.position;
    }

    String sourceLabel() {
        return copies.size() <= 1 ? "1 of 1" : copy + 1 + " of " + copies.size();
    }

    List<ChannelDatabase.Row> copies() {
        return copies;
    }

    // ---- Changing channel -----------------------------------------------------------------

    void channel(int delta) {
        tune(Math.max(0, own.position + delta));
    }

    void lastChannel() {
        if (previousPosition >= 0) tune(previousPosition);
    }

    void typeDigit(int digit) {
        if (typed.length() >= 6) typed.setLength(0);
        typed.append(digit);
        showBannerText(typed.toString(), "", "", "");
        player.overlayHandler.removeCallbacks(jump);
        player.overlayHandler.postDelayed(jump, 1500);
    }

    private void jumpToTyped() {
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        tune(Math.max(0, number - 101));
    }

    private void tune(int position) {
        if (position == own.position) {
            showBanner();
            return;
        }
        load(position, true, false);
    }

    /**
     * Finds the channel at {@code position} in the guide's list, its backups and what is on, then
     * plays it ({@code play}) and shows the banner. Positions past the end stay on the last channel.
     */
    private void load(int position, boolean play, boolean current) {
        int ticket = ++request;
        Session s = own;
        worker.execute(() -> {
            try {
                List<ChannelDatabase.Row> rows = db.browse(s.filter, s.search, s.favourites, position);
                if (rows.isEmpty()) return;
                ChannelDatabase.Row row = rows.get(0);
                List<ChannelDatabase.Row> found = db.copies(row);
                String url = play ? db.url(found.get(0).id, found.get(0).key) : null;
                long now = System.currentTimeMillis();
                List<ChannelDatabase.Programme> on = db.programmes(
                    row,
                    EpgImporter.COUNTRY,
                    now,
                    now + 4 * 3600000L
                );
                player.runOnUiThread(() -> {
                    if (ticket != request || player.isFinishing()) return;
                    if (!current) previousPosition = s.position;
                    s.position = position;
                    copies = found;
                    copy = 0;
                    stalls.clear();
                    if (play) player.switchTo(row.name, url, found.get(0).key);
                    showBanner(row, on);
                });
            } catch (Exception e) {
                Diagnostics.recovered("Changing channel", e);
            }
        });
    }

    // ---- Backups ----------------------------------------------------------------------------

    /** Plays backup copy {@code index} (0 is the main one). */
    void useCopy(int index, String reason) {
        if (index < 0 || index >= copies.size()) return;
        ChannelDatabase.Row row = copies.get(index);
        int ticket = ++request;
        worker.execute(() -> {
            try {
                String url = db.url(row.id, row.key);
                player.runOnUiThread(() -> {
                    if (ticket != request || player.isFinishing()) return;
                    copy = index;
                    stalls.clear();
                    player.switchTo(row.name, url, row.key);
                    if (reason != null) Toast.makeText(
                        player,
                        reason + ": trying source " + (index + 1) + " of " + copies.size(),
                        Toast.LENGTH_LONG
                    ).show();
                    showBanner();
                });
            } catch (Exception e) {
                Diagnostics.recovered("Switching to a backup", e);
            }
        });
    }

    /** Moves to the next backup. Returns false when there is none left to try. */
    boolean failover(String reason) {
        player.overlayHandler.removeCallbacks(stalledTooLong);
        if (copy + 1 >= copies.size()) return false;
        Timings.record("Live TV backup used", Timings.start());
        useCopy(copy + 1, reason);
        return true;
    }

    /** Buffering started while watching: a long pause, or frequent ones, try the next backup. */
    void bufferingStarted() {
        long now = System.currentTimeMillis();
        stalls.add(now);
        stalls.removeIf(at -> now - at > 60000);
        if (stalls.size() >= STALLS_PER_MINUTE) {
            failover("The stream keeps buffering");
            return;
        }
        player.overlayHandler.removeCallbacks(stalledTooLong);
        player.overlayHandler.postDelayed(stalledTooLong, STALL_MS);
    }

    void bufferingEnded() {
        player.overlayHandler.removeCallbacks(stalledTooLong);
    }

    void close() {
        player.overlayHandler.removeCallbacks(stalledTooLong);
        player.overlayHandler.removeCallbacks(jump);
        player.overlayHandler.removeCallbacks(hideBanner);
        worker.shutdownNow();
    }

    // ---- Banner -----------------------------------------------------------------------------

    private ChannelDatabase.Row bannerRow;
    private List<ChannelDatabase.Programme> bannerListing = new ArrayList<>();

    /** Shows the banner for the current channel again (the Info key, OK when hidden). */
    void showBanner() {
        if (bannerRow != null) showBanner(bannerRow, bannerListing);
    }

    private void showBanner(ChannelDatabase.Row row, List<ChannelDatabase.Programme> listing) {
        bannerRow = row;
        bannerListing = listing;
        long now = System.currentTimeMillis();
        ChannelDatabase.Programme current = null,
            next = null;
        for (ChannelDatabase.Programme item : listing) {
            if (current == null && item.start <= now && item.stop > now) current = item;
            else if (item.start >= now && next == null) next = item;
        }
        showBannerText(
            String.valueOf(101 + own.position),
            row.name,
            current == null
                ? "No programme information"
                : LiveTvScreen.clockTime(new Date(current.start)) + "   " + current.title,
            next == null ? "" : LiveTvScreen.clockTime(new Date(next.start)) + "   " + next.title
        );
    }

    private void showBannerText(String number, String name, String now, String next) {
        if (banner == null) return;
        bannerNumber.setText(number);
        bannerName.setText(name);
        bannerNow.setText(now.isEmpty() ? "" : "Now    " + now);
        bannerNext.setText(next.isEmpty() ? "" : "Next   " + next);
        bannerSource.setText(copies.size() > 1 ? "Source " + sourceLabel() : "");
        banner.setVisibility(View.VISIBLE);
        player.overlayHandler.removeCallbacks(hideBanner);
        player.overlayHandler.postDelayed(hideBanner, 5000);
    }

    /**
     * The classic satellite-box banner: a blue bar across the bottom with the channel number in
     * yellow, the channel name, and what is on now and next.
     */
    private void buildBanner(FrameLayout root) {
        banner = new LinearLayout(player);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(player.dp(18), player.dp(12), player.dp(22), player.dp(12));
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            0xf02a43a6,
            0xf0182a7a,
        });
        shape.setCornerRadius(player.dp(6));
        shape.setStroke(player.dp(2), 0xaaffffff);
        banner.setBackground(shape);
        bannerNumber = label(30, true);
        bannerNumber.setTextColor(LiveTvScreen.YELLOW);
        bannerNumber.setMinWidth(player.dp(90));
        banner.addView(bannerNumber);
        LinearLayout middle = new LinearLayout(player);
        middle.setOrientation(LinearLayout.VERTICAL);
        bannerName = label(24, true);
        middle.addView(bannerName);
        bannerNow = label(18, true);
        middle.addView(bannerNow);
        bannerNext = label(18, false);
        bannerNext.setTextColor(0xffc9d6ff);
        middle.addView(bannerNext);
        LinearLayout.LayoutParams middleLayout = new LinearLayout.LayoutParams(0, -2, 1);
        middleLayout.leftMargin = player.dp(14);
        banner.addView(middle, middleLayout);
        bannerSource = label(15, false);
        bannerSource.setTextColor(0xffc9d6ff);
        banner.addView(bannerSource);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        layout.setMargins(player.dp(40), 0, player.dp(40), player.dp(30));
        root.addView(banner, layout);
    }

    private TextView label(int size, boolean bold) {
        TextView text = new TextView(player);
        text.setTextColor(Color.WHITE);
        text.setTextSize(size);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
}
