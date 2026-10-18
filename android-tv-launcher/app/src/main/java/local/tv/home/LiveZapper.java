package local.tv.home;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
 * the last-channel key, the now-and-next banner with a progress bar and description, browsing
 * other channels in the banner without leaving this one, backup copies of a channel (the same
 * channel from another list) with automatic switching when a stream fails or keeps buffering,
 * and the parental lock.
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
    /** The banner stays this long; browsing in it a little longer. */
    static final long BANNER_MS = 5000,
        BROWSE_MS = 8000;

    private final VlcPlaybackActivity player;
    /**
     * This player's own copy of the guide's list, so a later guide visit or test clearing the
     * shared one is safe. Replaced by all channels when a typed number is not in the list.
     */
    private Session own;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ChannelDatabase db;
    private List<ChannelDatabase.Row> copies = new ArrayList<>();
    private int copy,
        previousPosition = -1,
        request;
    private final List<Long> stalls = new ArrayList<>();
    private LinearLayout banner;
    private TextView bannerNumber, bannerName, bannerNow, bannerNext, bannerAbout, bannerSource, bannerHint;
    private ProgressBar bannerProgress;
    private final StringBuilder typed = new StringBuilder();
    private final Runnable jump = this::jumpToTyped;
    private final Runnable hideBanner = () -> {
        browsing = false;
        if (banner != null) banner.setVisibility(View.GONE);
    };
    private final Runnable stalledTooLong = () -> failover("The stream kept buffering");

    /** Browsing other channels in the banner: which channel and which of its programmes. */
    private boolean browsing;
    /** The channel shown while browsing (package-private for tests). */
    int browsePosition;
    private int browseProgramme;
    private ChannelDatabase.Row browseRow;

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
        endBrowse();
        tune(Math.max(0, own.position + delta));
    }

    void lastChannel() {
        endBrowse();
        if (previousPosition >= 0) tune(previousPosition);
    }

    void typeDigit(int digit) {
        endBrowse();
        if (typed.length() >= 6) typed.setLength(0);
        typed.append(digit);
        showBannerText(typed.toString(), "", "", "", "", -1);
        player.overlayHandler.removeCallbacks(jump);
        player.overlayHandler.postDelayed(jump, 1500);
    }

    /**
     * Tunes to the typed channel number: in this list, else in all channels (a number no channel
     * has goes to the nearest one above it).
     */
    private void jumpToTyped() {
        if (typed.length() == 0) return;
        int number = Integer.parseInt(typed.toString());
        typed.setLength(0);
        Session s = own;
        worker.execute(() -> {
            int here = db.position(s.filter, s.search, s.favourites, number, false);
            Session target = s;
            if (here < 0) {
                here = Math.max(0, db.position("all", "", s.favourites, number, true));
                target = new Session("all", "", s.favourites, s.position);
            }
            int found = here;
            Session list = target;
            player.runOnUiThread(() -> {
                if (player.isFinishing()) return;
                if (list != own) {
                    previousPosition = -1; // positions in the old list mean nothing in the new one
                    own = list;
                }
                tune(found);
            });
        });
    }

    /** Tunes to this channel (a reminder's "Watch now"), switching to all channels. */
    void tuneKey(String key) {
        endBrowse();
        Session s = own;
        worker.execute(() -> {
            ChannelDatabase.Row row = db.byKey(key);
            if (row == null) return;
            int position = Math.max(0, db.position("all", "", s.favourites, row.number, true));
            player.runOnUiThread(() -> {
                if (player.isFinishing()) return;
                own = new Session("all", "", s.favourites, own.position);
                previousPosition = -1;
                load(position, true, false);
            });
        });
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
     * A locked channel asks for the PIN first and stays on this one if it is not given.
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
                List<ChannelDatabase.Programme> on = listing(row);
                player.runOnUiThread(() -> {
                    if (ticket != request || player.isFinishing()) return;
                    Runnable apply = () -> {
                        if (!current) previousPosition = s.position;
                        s.position = position;
                        copies = found;
                        copy = 0;
                        stalls.clear();
                        if (play) {
                            player.switchTo(row.name, url, found.get(0).key);
                            remember(row);
                        }
                        showBanner(row, on);
                    };
                    if (play && ParentalLock.locked(player, row)) {
                        player.dialogOpen = true;
                        ParentalLock.check(
                            player,
                            row.number + " " + row.name,
                            () -> {
                                player.panelClosed();
                                apply.run();
                            },
                            player::panelClosed
                        );
                    } else apply.run();
                });
            } catch (Exception e) {
                Diagnostics.recovered("Changing channel", e);
            }
        });
    }

    /** The guide's picture shows the last channel watched. */
    private void remember(ChannelDatabase.Row row) {
        player
            .getSharedPreferences("ui", VlcPlaybackActivity.MODE_PRIVATE)
            .edit()
            .putString(LiveTvScreen.LAST_CHANNEL, row.key)
            .apply();
    }

    private List<ChannelDatabase.Programme> listing(ChannelDatabase.Row row) {
        long now = System.currentTimeMillis();
        return db.programmes(row, EpgImporter.COUNTRY, now, now + 6 * 3600000L, 8);
    }

    // ---- Browsing in the banner ---------------------------------------------------------------

    boolean browsing() {
        return browsing;
    }

    /**
     * Browses without changing channel, as the satellite banner did: {@code channelDelta} moves to
     * another channel's now and next, {@code programmeDelta} to later programmes on it.
     */
    void browse(int channelDelta, int programmeDelta) {
        if (!browsing) {
            browsing = true;
            browsePosition = own.position;
            browseProgramme = 0;
            browseRow = null;
        }
        int position = Math.max(0, browsePosition + channelDelta);
        if (channelDelta != 0) browseProgramme = 0;
        browseProgramme = Math.max(0, browseProgramme + programmeDelta);
        int ticket = ++request;
        Session s = own;
        int programme = browseProgramme;
        worker.execute(() -> {
            try {
                List<ChannelDatabase.Row> rows = db.browse(s.filter, s.search, s.favourites, position);
                if (rows.isEmpty()) return;
                ChannelDatabase.Row row = rows.get(0);
                List<ChannelDatabase.Programme> on = listing(row);
                player.runOnUiThread(() -> {
                    if (ticket != request || player.isFinishing() || !browsing) return;
                    browsePosition = position;
                    browseRow = row;
                    browseProgramme = Math.min(programme, Math.max(0, on.size() - 1));
                    showProgramme(row, on, browseProgramme, true);
                });
            } catch (Exception e) {
                Diagnostics.recovered("Browsing channels", e);
            }
        });
    }

    /** SELECT while browsing: watch the channel shown (or just close the banner on this one). */
    void browseSelect() {
        int position = browsePosition;
        boolean other = browseRow != null && position != own.position;
        endBrowse();
        if (other) tune(position);
    }

    void endBrowse() {
        if (!browsing) return;
        browsing = false;
        hideBanner.run();
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

    /** Shows the banner for the current channel again (the Info key). */
    void showBanner() {
        endBrowse();
        if (bannerRow != null) showBanner(bannerRow, bannerListing);
    }

    private void showBanner(ChannelDatabase.Row row, List<ChannelDatabase.Programme> listing) {
        bannerRow = row;
        bannerListing = listing;
        showProgramme(row, listing, 0, false);
    }

    /**
     * The banner for {@code row}: programme {@code index} of its listing (0 is the one on now),
     * the one after it, the description, and how far through the programme on now is.
     */
    private void showProgramme(
        ChannelDatabase.Row row,
        List<ChannelDatabase.Programme> listing,
        int index,
        boolean browse
    ) {
        long now = System.currentTimeMillis();
        ChannelDatabase.Programme shown = index < listing.size() ? listing.get(index) : null,
            next = index + 1 < listing.size() ? listing.get(index + 1) : null;
        boolean onNow = shown != null && shown.start <= now && shown.stop > now;
        int progress = onNow
            ? (int) (((now - shown.start) * 1000) / Math.max(1, shown.stop - shown.start))
            : -1;
        showBannerText(
            String.valueOf(row.number),
            row.name,
            shown == null
                ? "No programme information"
                : (onNow ? "Now    " : "") +
                      LiveTvScreen.clockTime(new Date(shown.start)) +
                      "   " +
                      shown.title,
            next == null ? "" : "Next   " + LiveTvScreen.clockTime(new Date(next.start)) + "   " + next.title,
            shown == null ? "" : shown.description,
            progress
        );
        bannerHint.setVisibility(browse ? View.VISIBLE : View.GONE);
        if (browse) {
            player.overlayHandler.removeCallbacks(hideBanner);
            player.overlayHandler.postDelayed(hideBanner, BROWSE_MS);
        }
    }

    private void showBannerText(
        String number,
        String name,
        String now,
        String next,
        String about,
        int progress
    ) {
        if (banner == null) return;
        bannerNumber.setText(number);
        bannerName.setText(name);
        bannerNow.setText(now);
        bannerNext.setText(next);
        bannerAbout.setText(about);
        bannerAbout.setVisibility(about.isEmpty() ? View.GONE : View.VISIBLE);
        bannerProgress.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
        if (progress >= 0) bannerProgress.setProgress(progress);
        bannerSource.setText(copies.size() > 1 ? "Source " + sourceLabel() : "");
        bannerHint.setVisibility(View.GONE);
        banner.setVisibility(View.VISIBLE);
        player.overlayHandler.removeCallbacks(hideBanner);
        player.overlayHandler.postDelayed(hideBanner, BANNER_MS);
    }

    /**
     * The classic satellite-box banner: a blue bar across the bottom with the channel number in
     * yellow, the channel name, what is on now and next, how far through it is and what it is
     * about. While browsing, a line says how to watch the channel shown.
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
        bannerProgress = new ProgressBar(player, null, android.R.attr.progressBarStyleHorizontal);
        bannerProgress.setMax(1000);
        bannerProgress.setProgressTintList(ColorStateList.valueOf(LiveTvScreen.YELLOW));
        bannerProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x66ffffff));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(
            player.dp(260),
            player.dp(6)
        );
        progressLayout.topMargin = player.dp(3);
        progressLayout.bottomMargin = player.dp(3);
        middle.addView(bannerProgress, progressLayout);
        bannerNext = label(18, false);
        bannerNext.setTextColor(0xffc9d6ff);
        middle.addView(bannerNext);
        bannerAbout = label(15, false);
        bannerAbout.setTextColor(0xffdfe6ff);
        bannerAbout.setSingleLine(false);
        bannerAbout.setMaxLines(2);
        middle.addView(bannerAbout);
        bannerHint = label(15, true);
        bannerHint.setTextColor(LiveTvScreen.YELLOW);
        bannerHint.setText("SELECT to watch  ·  ◀ ▶ later programmes  ·  BACK to close");
        middle.addView(bannerHint);
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
