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

/** Seasons and episodes for a series, including watched-episode tracking. */
final class SeriesScreens {

    private final MainActivity activity;

    SeriesScreens(MainActivity activity) {
        this.activity = activity;
    }

    void seasons(int tmdbId, String title) {
        JSONObject series = activity.playbackTitle;
        activity.screen(title, () -> {
            if (series != null) activity.posters.tmdbTitle(series);
            else activity.libraryScreens.library("Series");
        });
        TextView loading = activity.text("Loading seasons…", 16);
        activity.page.addView(loading);
        activity.async(
            () -> activity.media.tmdb("tv/" + tmdbId + "?append_to_response=external_ids"),
            details -> {
                activity.page.removeView(loading);
                details.put("media_type", "tv");
                activity.playbackTitle = details;
                renderSeasons(details);
            }
        );
    }

    private void renderSeasons(JSONObject details) throws Exception {
        String title = details.optString("name", "Series");
        int id = details.optInt("id");
        String imdb =
            details.optJSONObject("external_ids") == null
                ? ""
                : details.getJSONObject("external_ids").optString("imdb_id");
        activity.page.addView(activity.muted(activity.posters.titleMetadata(details), 15));
        activity.section(activity.page, "Choose a season");
        JSONArray seasons = details.optJSONArray("seasons");
        if (seasons == null || seasons.length() == 0) {
            activity.emptyState(
                activity.page,
                "No seasons yet",
                "Season information is not available for this series.",
                null,
                null
            );
            return;
        }
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.getJSONObject(i);
            int number = season.optInt("season_number");
            LinearLayout card = activity.row();
            card.setPadding(activity.dp(12), activity.dp(12), activity.dp(18), activity.dp(12));
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(poster, new LinearLayout.LayoutParams(activity.dp(64), activity.dp(96)));
            String path = season.optString("poster_path", details.optString("poster_path"));
            if (path.startsWith("/")) activity.posters.loadImage(
                poster,
                "https://image.tmdb.org/t/p/w185" + path
            );
            LinearLayout copy = activity.vertical();
            copy.setPadding(activity.dp(20), 0, 0, 0);
            copy.addView(activity.text(season.optString("name", "Season " + number), 21));
            String date = season.optString("air_date", "");
            copy.addView(
                activity.muted(
                    season.optInt("episode_count") +
                        " episodes" +
                        (date.length() >= 4 ? " · " + date.substring(0, 4) : ""),
                    14
                )
            );
            TextView synopsis = activity.muted(season.optString("overview", ""), 14);
            synopsis.setMaxLines(2);
            synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(synopsis);
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            activity.focus(card);
            activity.styleFocus(card, false);
            card.setOnClickListener(v -> {
                activity.playbackTitle = details;
                episodes(id, imdb, number, title);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(124));
            lp.bottomMargin = activity.dp(10);
            activity.page.addView(card, lp);
        }
    }

    private String episodeKey(int id, int season, int episode) {
        return id + ":" + season + ":" + episode;
    }

    private void episodes(int tmdbId, String imdb, int season, String title) {
        JSONObject series = activity.playbackTitle;
        activity.screen(title + " · Season " + season, () -> {
            activity.playbackTitle = series;
            seasons(tmdbId, title);
        });
        TextView loading = activity.text("Loading episodes…", 18);
        activity.page.addView(loading);
        activity.async(
            () -> activity.media.tmdb("tv/" + tmdbId + "/season/" + season).getJSONArray("episodes"),
            items -> {
                activity.page.removeView(loading);
                renderEpisodes(items, series, tmdbId, imdb, season, title);
            }
        );
    }

    private void renderEpisodes(
        JSONArray episodes,
        JSONObject series,
        int tmdbId,
        String imdb,
        int season,
        String title
    ) throws Exception {
        if (episodes.length() == 0) {
            activity.emptyState(
                activity.page,
                "No episodes yet",
                "Episode information is not available for this season.",
                null,
                null
            );
            return;
        }
        activity.page.addView(
            activity.muted(episodes.length() + " episodes · Hold Select to mark watched", 14)
        );
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.getJSONObject(i);
            int number = episode.optInt("episode_number");
            String key = episodeKey(tmdbId, season, number),
                name = episode.optString("name", "Episode " + number);
            Set<String> watched = activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet());
            LinearLayout card = activity.row();
            card.setPadding(activity.dp(12), activity.dp(12), activity.dp(16), activity.dp(12));
            ImageView still = new ImageView(activity);
            still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(still, new LinearLayout.LayoutParams(activity.dp(170), activity.dp(96)));
            String path = episode.optString("still_path");
            if (path.startsWith("/")) activity.posters.loadImage(
                still,
                "https://image.tmdb.org/t/p/w342" + path
            );
            LinearLayout copy = activity.vertical();
            copy.setPadding(activity.dp(18), 0, 0, 0);
            TextView label = activity.compactText(
                (watched.contains(key) ? "✓  " : "") + number + " · " + name,
                19
            );
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(label);
            String date = episode.optString("air_date", "");
            int runtime = episode.optInt("runtime");
            copy.addView(
                activity.muted(
                    (runtime > 0 ? runtime + " min" : "") + (date.isEmpty() ? "" : "   ·   " + date),
                    13
                )
            );
            TextView synopsis = activity.muted(episode.optString("overview", "No synopsis available."), 14);
            synopsis.setMaxLines(2);
            synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(synopsis);
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            activity.focus(card);
            activity.styleFocus(card, false);
            card.setOnClickListener(v -> {
                try {
                    JSONObject metadata =
                        series == null
                            ? new JSONObject().put("id", tmdbId).put("media_type", "tv")
                            : new JSONObject(series.toString());
                    metadata.put("season", season).put("episode", number).put("episodeKey", key);
                    activity.playbackTitle = metadata;
                } catch (JSONException ignored) {}
                activity.sources.torrentSources(
                    imdb,
                    season,
                    number,
                    title + " · S" + season + " E" + number + " · " + name,
                    "Series"
                );
            });
            card.setOnLongClickListener(v -> {
                Set<String> values = new HashSet<>(
                    activity.prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                );
                if (!values.add(key)) values.remove(key);
                activity.prefs.edit().putStringSet("watchedEpisodes", values).apply();
                label.setText((values.contains(key) ? "✓  " : "") + number + " · " + name);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(124));
            lp.bottomMargin = activity.dp(10);
            activity.page.addView(card, lp);
        }
    }
}
