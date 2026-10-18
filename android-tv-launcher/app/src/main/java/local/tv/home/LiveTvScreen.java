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
                option.substring(0, 1).toUpperCase(Locale.ROOT) + option.substring(1),
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
        TextView status = activity.text("Loading saved channels…", 16);
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
                    LinearLayout card = activity.vertical();
                    card.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
                    card.setBackground(activity.box(0xff182337));
                    LinearLayout identity = activity.row();
                    ImageView logo = new ImageView(activity);
                    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    identity.addView(logo, new LinearLayout.LayoutParams(activity.dp(42), activity.dp(42)));
                    if (channel.logo.startsWith("https://")) activity.posters.loadImage(logo, channel.logo);
                    TextView name = activity.text((favourite ? "★  " : "") + channel.name, 18);
                    name.setMaxLines(1);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
                    nameParams.leftMargin = activity.dp(12);
                    identity.addView(name, nameParams);
                    card.addView(identity);
                    TextView group = activity.text(channel.group.isEmpty() ? "Live TV" : channel.group, 13);
                    group.setMaxLines(1);
                    group.setTextColor(0xffa6b2c8);
                    card.addView(group);
                    activity.focus(card);
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
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, activity.dp(105), 1);
                    lp.setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4));
                    gridRow.addView(card, lp);
                }
                if (count == 0) results.addView(
                    activity.text("No matching channels. Connect an account or refresh your provider.", 18)
                );
                LinearLayout navigation = activity.row();
                if (offset > 0) navigation.addView(
                    activity.button("Previous page", () ->
                        channelBrowser(filter, search, Math.max(0, offset - ChannelDatabase.PAGE))
                    )
                );
                if (items.size() > ChannelDatabase.PAGE) navigation.addView(
                    activity.button("Next page", () ->
                        channelBrowser(filter, search, offset + ChannelDatabase.PAGE)
                    )
                );
                navigation.addView(
                    activity.button("Refresh providers", () -> {
                        ChannelSync.refreshNow(activity);
                        channelBrowser(filter, search, 0);
                    })
                );
                activity.page.addView(navigation);
                activity.page.addView(
                    activity.text("Hold Select on a channel to save or remove a favourite", 13)
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
