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

/** The full-screen details page for a movie or series. */
final class TitleDetails {

    private final MainActivity activity;

    TitleDetails(MainActivity activity) {
        this.activity = activity;
    }

    void renderDetails(JSONObject item) throws Exception {
        activity.playbackTitle = item;
        String title = item.optString("title", item.optString("name", "Title")),
            type = item.optString("media_type", "movie");
        int tmdbId = item.optInt("id");
        String kind = type.equals("tv") ? "Series" : "Movies";
        FrameLayout hero = new FrameLayout(activity);
        hero.setBackground(activity.box(0xff121c29));
        hero.setClipToOutline(true);
        ImageView background = new ImageView(activity);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(background, new FrameLayout.LayoutParams(-1, -1));
        String backdrop = item.optString("backdrop_path");
        if (backdrop.startsWith("/")) activity.posters.loadImage(
            background,
            "https://image.tmdb.org/t/p/w1280" + backdrop
        );
        View shade = new View(activity);
        shade.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {
                0xfc0d1420,
                0xe60d1420,
                0x770d1420,
            })
        );
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout layout = activity.row();
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20));
        hero.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(poster, new LinearLayout.LayoutParams(activity.dp(156), activity.dp(234)));
        String artwork = item.optString("poster_path");
        if (artwork.startsWith("/")) activity.posters.loadImage(
            poster,
            "https://image.tmdb.org/t/p/w500" + artwork
        );
        LinearLayout info = activity.vertical();
        info.setPadding(activity.dp(26), 0, 0, 0);
        layout.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(activity.muted(type.equals("tv") ? "SERIES" : "MOVIE", 12));
        TextView heading = activity.compactText(title, 30);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(heading);
        info.addView(activity.muted(activity.posters.titleMetadata(item), 14));
        JSONArray genres = item.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        if (genres != null) for (int i = 0; i < genres.length(); i++) names.add(
            genres.getJSONObject(i).optString("name")
        );
        if (!names.isEmpty()) info.addView(activity.muted(String.join(" · ", names), 14));
        TextView synopsis = activity.compactText(item.optString("overview", "No synopsis available."), 16);
        synopsis.setMaxLines(3);
        synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(synopsis);
        LinearLayout actions = activity.row();
        JSONObject recent = activity.posters.recentForTitle(item);
        Button resume =
            recent == null
                ? null
                : activity.chip(actions, "Resume", () ->
                      activity.play(
                          recent.optString("title", title),
                          recent.optString("url"),
                          recent.optString("id"),
                          false,
                          recent
                      )
                  );
        if (resume != null) activity.primary(resume);
        Button play = activity.chip(actions, type.equals("tv") ? "Episodes" : "Find sources", () -> {
            activity.prefs.edit().putInt("seed", tmdbId).putString("seedType", type).apply();
            activity.playbackTitle = item;
            if (type.equals("tv")) activity.seriesScreens.seasons(tmdbId, title);
            else {
                JSONObject external = item.optJSONObject("external_ids");
                String imdb =
                    external == null
                        ? item.optString("imdb_id")
                        : external.optString("imdb_id", item.optString("imdb_id"));
                activity.sources.torrentSources(imdb, -1, -1, title, kind);
            }
        });
        activity.primary(play);
        Button saved = activity.chip(
            actions,
            activity.libraryScreens.isWatchlisted(item) ? "✓ My list" : "+ My list",
            () -> {}
        );
        saved.setOnClickListener(v -> {
            activity.libraryScreens.toggleWatchlist(item);
            saved.setText(activity.libraryScreens.isWatchlisted(item) ? "✓ My list" : "+ My list");
        });
        JSONArray videos =
            item.optJSONObject("videos") == null
                ? null
                : item.getJSONObject("videos").optJSONArray("results");
        String trailer = "";
        if (videos != null) for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.getJSONObject(i);
            if (
                video.optString("site").equals("YouTube") &&
                video.optString("type").equals("Trailer") &&
                video.optString("key").matches("[A-Za-z0-9_-]{11}")
            ) {
                trailer = video.getString("key");
                if (video.optBoolean("official")) break;
            }
        }
        if (!trailer.isEmpty()) {
            final String key = trailer;
            activity.chip(actions, "Trailer", () -> {
                try {
                    activity.startActivity(
                        new Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.youtube.com/watch?v=" + key)
                        )
                    );
                } catch (Exception e) {
                    activity.toast("No app can open this trailer");
                }
            });
        }
        info.addView(actions);
        activity.page.addView(hero, new LinearLayout.LayoutParams(-1, activity.dp(326)));
        activity.section(activity.page, "About " + title);
        activity.page.addView(activity.muted(item.optString("overview", "No synopsis available."), 16));
        LinearLayout related = activity.row();
        activity.chip(related, "More like this", () ->
            activity.catalogues.catalogue(type, type + "/" + tmdbId + "/recommendations", 1)
        );
        JSONObject collection = item.optJSONObject("belongs_to_collection");
        if (collection != null) activity.chip(related, collection.optString("name", "Collection"), () ->
            activity.catalogues.browse(
                kind,
                collection.optString("name", "Collection"),
                "collection/" + collection.optInt("id"),
                1
            )
        );
        activity.page.addView(related);
        JSONObject credits = item.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast != null && cast.length() > 0) {
            activity.section(activity.page, "Cast");
            LinearLayout actors = activity.horizontal(activity.page);
            for (int i = 0; i < Math.min(12, cast.length()); i++) {
                JSONObject person = cast.getJSONObject(i);
                LinearLayout card = activity.vertical();
                card.setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8));
                ImageView photo = new ImageView(activity);
                photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(photo, new LinearLayout.LayoutParams(activity.dp(96), activity.dp(112)));
                String image = person.optString("profile_path");
                if (image.startsWith("/")) activity.posters.loadImage(
                    photo,
                    "https://image.tmdb.org/t/p/w185" + image
                );
                TextView name = activity.compactText(person.optString("name"), 13);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(name);
                TextView character = activity.muted(person.optString("character"), 11);
                character.setMaxLines(1);
                character.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(character);
                activity.focus(card);
                card.setOnClickListener(v ->
                    activity.catalogues.browse(
                        kind,
                        person.optString("name"),
                        "person/" + person.optInt("id") + "/" + type + "_credits",
                        1
                    )
                );
                activity.addRow(actors, card, 122, 172);
            }
        }
        if (resume != null) resume.requestFocus();
        else play.requestFocus();
    }
}
