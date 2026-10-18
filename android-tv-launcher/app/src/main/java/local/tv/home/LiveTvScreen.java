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

/** Live TV channel browser backed by the on-device channel database. */
final class LiveTvScreen {

    private final MainActivity activity;

    LiveTvScreen(MainActivity activity) {
        this.activity = activity;
    }

    private ChannelDatabase channelsDb() {
        return ChannelDatabase.get(activity);
    }

    interface ChannelPage {
        List<ChannelDatabase.Row> load(int offset) throws Exception;
    }

    private Set<String> favouriteKeys() {
        return new HashSet<>(activity.prefs.getStringSet("favourites", Collections.emptySet()));
    }

    void live() {
        channelBrowser("all", "", 0);
    }

    private void channelBrowser(String filter, String search, int offset) {
        activity.screen("Live TV", activity.homeScreen::home);
        LinearLayout filters = activity.horizontal(activity.page);
        for (String option : new String[] { "all", "sports", "favourites" }) {
            Button tab = activity.chip(
                filters,
                option.equals("all")
                    ? "All channels"
                    : option.substring(0, 1).toUpperCase(Locale.ROOT) + option.substring(1),
                () -> channelBrowser(option, search, 0)
            );
            if (option.equals(filter)) activity.primary(tab);
        }
        activity.chip(filters, "Groups", () ->
            activity.async(
                () -> channelsDb().groups(),
                groups -> {
                    String[] names = new String[groups.size()];
                    for (int i = 0; i < names.length; i++) names[i] =
                        (groups.get(i).name.isEmpty() ? "Ungrouped" : groups.get(i).name) +
                        " · " +
                        groups.get(i).count;
                    SidePanel.of(activity, "Channel groups")
                        .choices(names, -1, which ->
                            channelBrowser("group:" + groups.get(which).name, search, 0)
                        )
                        .show();
                }
            )
        );
        activity.chip(filters, "Search", () -> {
            LinearLayout form = activity.vertical();
            EditText query = activity.field(form, "Channel or group", false, search);
            Dialog dialog = activity.form("Search live TV", form);
            form.addView(
                activity.button("Search", () -> {
                    dialog.dismiss();
                    channelBrowser(filter, query.getText().toString().trim(), 0);
                })
            );
        });
        activity.chip(filters, "Refresh", () -> {
            ChannelSync.refreshNow(activity);
            channelBrowser(filter, search, 0);
        });
        if (!search.isEmpty() || filter.startsWith("group:")) {
            LinearLayout active = activity.row();
            active.addView(
                activity.muted(
                    (filter.startsWith("group:") ? filter.substring(6) : "") +
                        (!search.isEmpty() ? "   Search: " + search : ""),
                    15
                ),
                new LinearLayout.LayoutParams(0, -2, 1)
            );
            activity.chip(active, "Clear", () -> channelBrowser("all", "", 0));
            activity.page.addView(active);
        }
        TextView status = activity.muted("Loading saved channels…", 14);
        activity.page.addView(status);
        LinearLayout results = activity.vertical();
        activity.page.addView(results);
        activity.async(
            () -> {
                ChannelDatabase db = channelsDb();
                return db.browse(filter, search, favouriteKeys(), offset);
            },
            items -> {
                ChannelImporter.Status sync = ChannelImporter.status(activity);
                status.setText(
                    sync.running
                        ? String.format(
                              Locale.getDefault(),
                              "Updating · %,d channels imported",
                              sync.progress
                          )
                        : sync.queued
                          ? "Waiting for network to refresh"
                          : sync.message.isEmpty()
                            ? "Saved channels"
                            : "Saved channels · " + sync.message
                );
                int count = Math.min(items.size(), ChannelDatabase.PAGE);
                Set<String> visibleFavourites = favouriteKeys();
                LinearLayout gridRow = null;
                for (int i = 0; i < count; i++) {
                    ChannelDatabase.Row channel = items.get(i);
                    if (i % 3 == 0) {
                        gridRow = activity.row();
                        results.addView(gridRow);
                    }
                    boolean favourite = visibleFavourites.contains(channel.key);
                    LinearLayout card = activity.listRow();
                    card.setPadding(activity.dp(10), activity.dp(10), activity.dp(14), activity.dp(10));
                    ImageView logo = new ImageView(activity);
                    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    int pad = activity.dp(5);
                    logo.setPadding(pad, pad, pad, pad);
                    // Logos stay on a dark tile even when the row turns white, so light logos stay visible.
                    logo.setBackground(activity.box(0xff0d1320));
                    card.addView(logo, new LinearLayout.LayoutParams(activity.dp(78), activity.dp(52)));
                    if (channel.logo.startsWith("https://")) activity.posters.loadImage(logo, channel.logo);
                    LinearLayout copy = activity.vertical();
                    copy.setPadding(activity.dp(12), 0, 0, 0);
                    TextView name = activity.rowText((favourite ? "★  " : "") + channel.name, 17, false, 1);
                    name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                    copy.addView(name);
                    copy.addView(
                        activity.rowText(channel.group.isEmpty() ? "Live TV" : channel.group, 13, true, 1)
                    );
                    card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                    activity.styleListRow(card, false);
                    card.setOnClickListener(v ->
                        activity.async(
                            () -> channelsDb().url(channel.id, channel.key),
                            url -> activity.play(channel.name, url, channel.key, true)
                        )
                    );
                    card.setOnLongClickListener(v -> {
                        Set<String> keys = favouriteKeys();
                        boolean added = keys.add(channel.key);
                        if (!added) keys.remove(channel.key);
                        activity.prefs.edit().putStringSet("favourites", keys).apply();
                        name.setText((added ? "★  " : "") + channel.name);
                        activity.toast(added ? "Added to favourites" : "Removed from favourites");
                        return true;
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, activity.dp(76), 1);
                    lp.setMargins(activity.dp(4), activity.dp(5), activity.dp(6), activity.dp(5));
                    gridRow.addView(card, lp);
                }
                if (gridRow != null) while (gridRow.getChildCount() < 3)
                    gridRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
                if (count == 0) activity.emptyState(
                    results,
                    filter.equals("favourites") ? "No favourites yet" : "No channels here",
                    filter.equals("favourites")
                        ? "Hold Select on a channel to add it to your favourites."
                        : "Connect Xtream or an M3U playlist in Settings, or refresh your provider.",
                    "Open Settings",
                    activity.settingsScreens::settings
                );
                LinearLayout navigation = activity.row();
                navigation.setPadding(0, activity.dp(14), 0, activity.dp(4));
                if (offset > 0) pagePill(navigation, "‹  Previous", () ->
                    channelBrowser(filter, search, Math.max(0, offset - ChannelDatabase.PAGE))
                );
                if (items.size() > ChannelDatabase.PAGE) pagePill(navigation, "Next  ›", () ->
                    channelBrowser(filter, search, offset + ChannelDatabase.PAGE)
                );
                activity.page.addView(navigation);
                activity.page.addView(
                    activity.muted("Hold Select on a channel to save or remove a favourite", 13)
                );
                int request = activity.generation;
                if (sync.running || sync.queued) activity.handler.postDelayed(
                    new Runnable() {
                        public void run() {
                            if (
                                activity.isFinishing() ||
                                activity.isDestroyed() ||
                                activity.generation != request
                            ) return;
                            ChannelImporter.Status latest = ChannelImporter.status(activity);
                            if (latest.running || latest.queued) {
                                status.setText(
                                    latest.running
                                        ? String.format(
                                              Locale.getDefault(),
                                              "Updating · %,d channels imported",
                                              latest.progress
                                          )
                                        : "Waiting for network to refresh"
                                );
                                activity.handler.postDelayed(this, 2000);
                            } else {
                                status.setText(latest.message);
                                activity.page.addView(
                                    activity.button("Reload channels", () ->
                                        channelBrowser(filter, search, 0)
                                    )
                                );
                            }
                        }
                    },
                    2000
                );
            }
        );
    }

    private void pagePill(LinearLayout parent, String label, Runnable run) {
        Button button = activity.button(label, run);
        activity.pill(button, false);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, activity.dp(42));
        layout.rightMargin = activity.dp(12);
        parent.addView(button, layout);
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
