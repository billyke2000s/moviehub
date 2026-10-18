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

/** My List, Continue Watching and saved provider libraries. */
final class LibraryScreens {

    private final MainActivity activity;

    LibraryScreens(MainActivity activity) {
        this.activity = activity;
    }

    private JSONArray watchlistItems() {
        try {
            return new JSONArray(activity.prefs.getString("watchlist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    boolean isWatchlisted(JSONObject item) {
        JSONArray saved = watchlistItems();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (
                row != null &&
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) return true;
        }
        return false;
    }

    void toggleWatchlist(JSONObject item) {
        JSONArray saved = watchlistItems(),
            next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (row == null) continue;
            if (
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) {
                found = true;
                continue;
            }
            next.put(row);
        }
        if (!found) {
            JSONObject entry = new JSONObject();
            try {
                for (String key : new String[] {
                    "id",
                    "title",
                    "name",
                    "media_type",
                    "poster_path",
                    "backdrop_path",
                    "overview",
                })
                    if (item.has(key)) entry.put(key, item.get(key));
                next.put(entry);
            } catch (Exception e) {
                Diagnostics.recovered("Copying a My List entry", e);
            }
        }
        activity.prefs.edit().putString("watchlist", next.toString()).apply();
    }

    void watchlist() {
        watchlist("All", activity.prefs.getString("watchlistSort", "Recently added"));
    }

    void watchlist(String filter, String sort) {
        activity.destination = () -> watchlist(filter, sort);
        activity.screen("My list", activity.homeScreen::home);
        activity.myListRefresh = () -> watchlist(filter, sort);
        LinearLayout tools = activity.horizontal(activity.page);
        for (String kind : new String[] { "All", "Movies", "Series" }) {
            Button tab = activity.chip(tools, kind, () -> watchlist(kind, sort));
            if (kind.equals(filter)) activity.primary(tab);
        }
        activity.chip(tools, "Sort: " + sort, () ->
            new AlertDialog.Builder(activity)
                .setTitle("Sort My list")
                .setItems(new String[] { "Recently added", "Title A–Z", "Title Z–A" }, (d, which) -> {
                    String value = new String[] { "Recently added", "Title A–Z", "Title Z–A" }[which];
                    activity.prefs.edit().putString("watchlistSort", value).apply();
                    watchlist(filter, value);
                })
                .show()
        );
        List<JSONObject> rows = new ArrayList<>();
        JSONArray saved = watchlistItems();
        for (int i = saved.length() - 1; i >= 0; i--) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
            if (filter.equals("All") || filter.equals(type.equals("tv") ? "Series" : "Movies")) rows.add(
                item
            );
        }
        if (sort.startsWith("Title")) {
            rows.sort(
                Comparator.comparing(item ->
                    item.optString("title", item.optString("name", "")).toLowerCase(Locale.ROOT)
                )
            );
            if (sort.endsWith("Z–A")) Collections.reverse(rows);
        }
        activity.page.addView(
            activity.muted(
                rows.size() + (rows.size() == 1 ? " title" : " titles") + " · Hold Select for options",
                13
            )
        );
        if (rows.isEmpty()) {
            activity.emptyState(
                activity.page,
                "Your next watch starts here",
                filter.equals("All")
                    ? "Save movies and series from their details page."
                    : "No saved " + filter.toLowerCase(Locale.ROOT) + " yet.",
                "Browse " + (filter.equals("Series") ? "series" : "movies"),
                () -> library(filter.equals("Series") ? "Series" : "Movies")
            );
            return;
        }
        LinearLayout grid = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 6 == 0) {
                grid = activity.row();
                grid.setGravity(Gravity.TOP);
                activity.page.addView(grid);
            }
            View card = activity.posters.posterCard(false);
            activity.posters.bindPoster(card, rows.get(i), false, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(activity.dp(4), activity.dp(8), activity.dp(4), activity.dp(8));
            grid.addView(card, lp);
        }
        if (grid != null) while (grid.getChildCount() < 6)
            grid.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
    }

    void continueWatching() {
        activity.screen("Continue watching", activity.homeScreen::home);
        activity.resumeRefresh = this::continueWatching;
        LinearLayout controls = activity.horizontal(activity.page);
        activity.chip(controls, "My list", this::watchlist);
        activity.chip(controls, "Clear history", () ->
            new AlertDialog.Builder(activity)
                .setTitle("Clear viewing history?")
                .setMessage("This removes saved playback progress on this device.")
                .setPositiveButton("Clear", (d, w) -> {
                    activity.getSharedPreferences("positions", Context.MODE_PRIVATE).edit().clear().apply();
                    try {
                        activity.secrets.put("recentPlayback", "[]");
                    } catch (Exception e) {
                        activity.toast("Could not clear history");
                    }
                    continueWatching();
                })
                .setNegativeButton("Cancel", null)
                .show()
        );
        try {
            String stored = activity.secrets.get("recentPlayback");
            JSONArray recent = new JSONArray(stored.isEmpty() ? "[]" : stored);
            int count = 0;
            SharedPreferences positions = activity.getSharedPreferences("positions", Context.MODE_PRIVATE);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject item = recent.getJSONObject(i);
                String key = item.getString("id");
                Object saved = positions.getAll().get(key);
                if (!(saved instanceof Number) || ((Number) saved).longValue() <= 0) continue;
                long position = ((Number) saved).longValue(),
                    length = positions.getLong(key + ":duration", 0);
                count++;
                String title = item.optString("title", "Title"),
                    url = item.getString("url");
                LinearLayout card = activity.row();
                card.setPadding(activity.dp(12), activity.dp(12), activity.dp(16), activity.dp(12));
                card.setBackground(activity.box(0xff141e2c));
                ImageView artwork = new ImageView(activity);
                artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(artwork, new LinearLayout.LayoutParams(activity.dp(168), activity.dp(94)));
                String path = item.optString("backdrop_path", item.optString("poster_path"));
                if (path.startsWith("/")) activity.posters.loadImage(
                    artwork,
                    "https://image.tmdb.org/t/p/w342" + path
                );
                LinearLayout copy = activity.vertical();
                copy.setPadding(activity.dp(18), 0, activity.dp(18), 0);
                TextView name = activity.compactText(title, 19);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                copy.addView(name);
                copy.addView(
                    activity.muted(
                        length > position
                            ? Math.max(1, (length - position) / 60000) + " min left"
                            : position / 60000 + " min watched",
                        14
                    )
                );
                if (length > 0) {
                    ProgressBar progress = new ProgressBar(
                        activity,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                    );
                    progress.setMax(1000);
                    progress.setProgress((int) Math.min(1000, (position * 1000) / length));
                    progress.setProgressTintList(
                        android.content.res.ColorStateList.valueOf(activity.accent())
                    );
                    copy.addView(progress, new LinearLayout.LayoutParams(-1, activity.dp(5)));
                }
                card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                Button resume = activity.button("Resume", () -> activity.play(title, url, key, false, item));
                card.addView(resume, new LinearLayout.LayoutParams(activity.dp(108), activity.dp(44)));
                Button more = activity.button("Options", () -> {});
                card.addView(more, new LinearLayout.LayoutParams(activity.dp(100), activity.dp(44)));
                more.setOnClickListener(v ->
                    new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setItems(
                            new String[] { "Start over", "Remove from Continue watching" },
                            (d, which) -> {
                                positions.edit().remove(key).apply();
                                if (which == 0) activity.play(title, url, key, false, item);
                                else continueWatching();
                            }
                        )
                        .show()
                );
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = activity.dp(10);
                activity.page.addView(card, lp);
            }
            if (count == 0) activity.emptyState(
                activity.page,
                "Pick up where you left off",
                "Movies and episodes you start will appear here.",
                "Browse movies",
                () -> library("Movies")
            );
        } catch (Exception e) {
            activity.emptyState(
                activity.page,
                "History unavailable",
                "Try opening this screen again.",
                "Retry",
                this::continueWatching
            );
        }
    }

    void library(String kind) {
        activity.catalogues.rows(kind);
    }

    void mediaRow(
        LinearLayout target,
        String title,
        String description,
        String action,
        Runnable run,
        Runnable more
    ) {
        LinearLayout card = activity.row();
        card.setPadding(activity.dp(18), activity.dp(12), activity.dp(12), activity.dp(12));
        card.setBackground(activity.box(0xff141e2c));
        LinearLayout copy = activity.vertical();
        TextView name = activity.compactText(title, 18);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        if (description != null && !description.isEmpty()) {
            TextView detail = activity.muted(description, 13);
            detail.setMaxLines(1);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(detail);
        }
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = activity.button(action, run);
        card.addView(open, new LinearLayout.LayoutParams(activity.dp(100), activity.dp(44)));
        if (more != null) {
            Button options = activity.button("Options", more);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dp(100), activity.dp(44));
            lp.leftMargin = activity.dp(8);
            card.addView(options, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(88));
        lp.bottomMargin = activity.dp(8);
        target.addView(card, lp);
    }

    void savedLibrary(String kind) {
        activity.screen("My " + kind, () -> library(kind));
        LinearLayout tools = activity.horizontal(activity.page);
        activity.chip(tools, "Browse catalogue", () -> activity.catalogues.catalogueMenu(kind));
        activity.chip(tools, "Add source", () -> activity.settingsScreens.addSource(kind, "", ""));
        activity.chip(tools, "Cloud", () -> activity.cloudScreens.cloud("", kind));
        activity.chip(tools, "Transfers", () -> activity.cloudScreens.transfers(kind));
        activity.section(activity.page, "Saved sources");
        try {
            JSONArray rows = activity.media.library();
            int found = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                if (!kind.equals(item.getString("kind"))) continue;
                found++;
                String title = item.getString("title"),
                    url = item.optString("url"),
                    key = item.getString("id");
                mediaRow(
                    activity.page,
                    title,
                    "premiumize".equals(item.optString("source")) ? "Premiumize cloud" : "Saved media",
                    "Play",
                    () -> {
                        if ("premiumize".equals(item.optString("source"))) {
                            activity.async(
                                () ->
                                    activity.media.premiumize(
                                        "item/details?id=" + Network.q(item.getString("sourceId")),
                                        activity.secrets.get("premiumize")
                                    ),
                                detail -> activity.play(title, detail.getString("link"), key, false)
                            );
                        } else activity.play(title, url, key, false);
                    },
                    () ->
                        new AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setItems(new String[] { "Remove from library" }, (dialog, which) ->
                                new AlertDialog.Builder(activity)
                                    .setTitle("Remove " + title + "?")
                                    .setPositiveButton("Remove", (d, w) -> {
                                        try {
                                            activity.media.remove(key);
                                            savedLibrary(kind);
                                        } catch (Exception e) {
                                            activity.toast("Could not remove this source");
                                        }
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            )
                            .show()
                );
            }
            if (found == 0) activity.emptyState(
                activity.page,
                "Build your library",
                "Add a media link or save a video from your cloud.",
                "Open cloud",
                () -> activity.cloudScreens.cloud("", kind)
            );
        } catch (Exception e) {
            activity.emptyState(
                activity.page,
                "Library unavailable",
                "Try opening this screen again.",
                "Retry",
                () -> savedLibrary(kind)
            );
        }
    }
}
