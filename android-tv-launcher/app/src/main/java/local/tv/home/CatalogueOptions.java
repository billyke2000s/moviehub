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

/**
 * The Browse sheet of the Movies/Series screen (Browse and Filters were two sheets): a short "find
 * something" form (genre, year, rating, sort), popular and new lists, genres, and languages and
 * people. Region and streaming-service availability were removed. Split out of
 * {@link CatalogueScreens}; no behaviour of its own beyond the sheet.
 */
final class CatalogueOptions {

    private final MainActivity activity;
    private final CatalogueScreens screens;

    CatalogueOptions(MainActivity activity, CatalogueScreens screens) {
        this.activity = activity;
        this.screens = screens;
    }

    String catalogueRegion() {
        String fallback = Locale.getDefault().getCountry();
        return activity.prefs.getString("catalogueRegion", fallback.matches("[A-Z]{2}") ? fallback : "GB");
    }

    void categoryChoices(LinearLayout target, String kind, List<TmdbCategories.Entry> entries) {
        LinearLayout line = null;
        for (int i = 0; i < entries.size(); i++) {
            TmdbCategories.Entry entry = entries.get(i);
            if (i % 2 == 0) {
                line = activity.row();
                target.addView(line);
            }
            Button choice = activity.button(entry.name, () ->
                screens.browse(kind, entry.name, entry.path, 1)
            );
            choice.setTextSize(14);
            choice.setTag(entry.path);
            choice.setContentDescription(entry.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, activity.dp(42), 1);
            lp.setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
            line.addView(choice, lp);
        }
        if (line != null) while (line.getChildCount() < 2)
            line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
    }

    void renderCategoryHub(LinearLayout target, String kind, String section) {
        if (section.equals(":categories") || section.equals(":filters")) {
            renderBrowse(target, kind);
            return;
        }
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        if (!section.equals(":filters")) {
            target.addView(activity.compactText("Genres", 18));
            categoryChoices(target, kind, screens.catalogueGenres(type));
        }
        if (section.equals(":categories")) {
            target.addView(activity.text("Popular & new", 18));
            categoryChoices(target, kind, TmdbCategories.lists(type));
        }
        if (!section.equals(":genres")) {
            target.addView(activity.text("Browse by", 18));
            categoryChoices(target, kind, TmdbCategories.groups(type));
        }
    }

    void refreshCatalogueGenres(LinearLayout target, String kind, String section) {
        if (!activity.secrets.has("tmdb")) return;
        int request = activity.generation;
        String type = kind.equals("Movies") ? "movie" : "tv";
        activity.io.execute(() -> {
            try {
                JSONArray result = activity.media.tmdb("genre/" + type + "/list").getJSONArray("genres");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject item = result.getJSONObject(i);
                    TmdbCategories.genre(type, item.getInt("id"), item.getString("name"));
                }
                String data = result.toString();
                if (data.equals(activity.prefs.getString("catalogueGenres_" + type, "[]"))) return;
                activity.prefs
                    .edit()
                    .putString("catalogueGenres_" + type, data)
                    .apply();
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    View focused = activity.getCurrentFocus();
                    Object route = focused == null ? null : focused.getTag();
                    boolean inside = false;
                    for (
                        android.view.ViewParent parent = focused == null ? null : focused.getParent();
                        parent != null;
                        parent = parent.getParent()
                    ) if (parent == target) {
                        inside = true;
                        break;
                    }
                    renderCategoryHub(target, kind, section);
                    if (inside && route != null) {
                        View replacement = target.findViewWithTag(route);
                        if (replacement != null) replacement.requestFocus();
                    }
                });
            } catch (Exception e) {
                Diagnostics.recovered("Refreshing catalogue genres", e);
                /* Official built-in and previously fetched genres remain usable offline. */
            }
        });
    }

    void renderCatalogueOptions(LinearLayout grid, String kind, String path) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        if (path.equals(":categories") || path.equals(":filters") || path.equals(":genres")) {
            renderCategoryHub(grid, kind, path);
            refreshCatalogueGenres(grid, kind, path);
            return;
        }
        if (path.equals(":years")) {
            grid.addView(
                activity.button("Choose a year", () -> {
                    LinearLayout content = activity.vertical();
                    EditText input = activity.field(
                        content,
                        "Year",
                        false,
                        String.valueOf(java.time.LocalDate.now().getYear())
                    );
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    Dialog dialog = activity.form("Browse by year", content);
                    content.addView(
                        activity.button("Browse", () -> {
                            try {
                                TmdbCategories.Entry entry = TmdbCategories.year(
                                    type,
                                    Integer.parseInt(input.getText().toString().trim())
                                );
                                dialog.dismiss();
                                screens.browse(kind, entry.name, entry.path, 1);
                            } catch (Exception e) {
                                input.setError("Enter a year from 1874 to 9999");
                            }
                        })
                    );
                })
            );
        }
        if (
            Arrays.asList(
                ":years",
                ":ratings",
                ":runtime",
                ":sort",
                ":types",
                ":status",
                ":releases",
                ":availability"
            ).contains(path)
        ) {
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, path, catalogueRegion(), java.time.LocalDate.now())
            );
            return;
        }
        if (
            path.equals(":people") ||
            path.equals(":companies") ||
            path.equals(":keywords") ||
            path.equals(":collections")
        ) {
            activity.catalogueSearch.entitySearch(grid, kind, path);
            return;
        }
        if (!activity.secrets.has("tmdb")) {
            // Connecting TMDB is in Settings, for whoever looks after the box.
            grid.addView(activity.text(CatalogueScreens.NOT_SET_UP, 20));
            return;
        }
        TextView loading = activity.text("Loading titles…", 15);
        grid.addView(loading);
        if (path.equals(":languages") || path.equals(":countries")) {
            String endpoint = path.equals(":languages")
                ? "configuration/languages"
                : "configuration/countries";
            activity.async(
                () ->
                    new JSONArray(
                        Network.text(
                            MediaRepository.tmdbUrl(endpoint, activity.secrets.get("tmdb")),
                            null,
                            false
                        )
                    ),
                items -> {
                    grid.removeAllViews();
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        boolean language = path.equals(":languages");
                        String code = item.optString(language ? "iso_639_1" : "iso_3166_1"),
                            name = item.optString("english_name", item.optString("name", code));
                        if (code.isEmpty()) continue;
                        choices.add(
                            new TmdbCategories.Entry(
                                name,
                                TmdbCategories.discover(
                                    type,
                                    (language ? "with_original_language=" : "with_origin_country=") +
                                        Network.q(code)
                                )
                            )
                        );
                    }
                    choices.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
    }

    /** The groups offered under "More ways to browse": languages and people. */
    static List<TmdbCategories.Entry> moreWays(String type) {
        List<TmdbCategories.Entry> out = new ArrayList<>();
        for (TmdbCategories.Entry entry : TmdbCategories.groups(type))
            if (entry.path.equals(":languages") || entry.path.equals(":people")) out.add(entry);
        return out;
    }

    /** The one Browse sheet: find something, popular and new, genres, more ways to browse. */
    void renderBrowse(LinearLayout target, String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        sheetHeading(target, "Find something");
        refineControls(target, kind);
        sheetHeading(target, "Popular & new");
        categoryChoices(target, kind, TmdbCategories.lists(type));
        sheetHeading(target, "Genres");
        categoryChoices(target, kind, screens.catalogueGenres(type));
        sheetHeading(target, "More ways to browse");
        categoryChoices(target, kind, moreWays(type));
    }

    private void sheetHeading(LinearLayout target, String text) {
        TextView label = activity.compactText(text, 18);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(activity.dp(3), activity.dp(14), 0, activity.dp(4));
        target.addView(label);
    }

    static final String[] ORDERS = { "Most popular", "Highest rated", "Newest releases", "Title A–Z" },
        ORDER_ROUTES = { "popularity.desc", "vote_average.desc", "date.desc", "title.asc" };

    /**
     * Genre, year, rating and sort as four buttons in the sheet itself (they were a separate
     * "Refine results" form in another style), then Show results and Reset. Each choice is saved
     * at once under the keys the old form used, so the sheet reopens with the last choices.
     */
    void refineControls(LinearLayout target, String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            prefix = "refine_" + type + "_";
        SharedPreferences prefs = activity.prefs;
        List<TmdbCategories.Entry> genres = screens.catalogueGenres(type);
        int genre = prefs.getInt(prefix + "genre", 0),
            year = prefs.getInt(prefix + "year", 0),
            rating = prefs.getInt(prefix + "rating", 0),
            sort = Math.max(0, Math.min(ORDERS.length - 1, prefs.getInt(prefix + "sort", 0)));
        String genreName = "Any";
        for (TmdbCategories.Entry entry : genres)
            if (entry.path.endsWith("=" + genre)) genreName = entry.name;
        LinearLayout line = activity.row();
        target.addView(line);
        refineButton(line, "Genre · " + genreName, "refine:genre", () -> {
            String[] names = new String[genres.size() + 1];
            names[0] = "Any genre";
            int current = 0;
            for (int i = 0; i < genres.size(); i++) {
                names[i + 1] = genres.get(i).name;
                if (genres.get(i).path.endsWith("=" + prefs.getInt(prefix + "genre", 0))) current = i + 1;
            }
            SidePanel.of(activity, "Genre")
                .choices(names, current, which -> {
                    String path = which == 0 ? "" : genres.get(which - 1).path;
                    prefs
                        .edit()
                        .putInt(
                            prefix + "genre",
                            which == 0 ? 0 : Integer.parseInt(path.substring(path.lastIndexOf('=') + 1))
                        )
                        .apply();
                    again(target, kind, "refine:genre");
                })
                .show();
        });
        refineButton(line, "Year · " + (year == 0 ? "Any" : String.valueOf(year)), "refine:year", () -> {
            int now = java.time.LocalDate.now().getYear();
            String[] names = new String[now - 1950 + 2];
            names[0] = "Any year";
            int current = 0;
            for (int i = 1; i < names.length; i++) {
                names[i] = String.valueOf(now - i + 1);
                if (now - i + 1 == prefs.getInt(prefix + "year", 0)) current = i;
            }
            SidePanel.of(activity, "Year")
                .choices(names, current, which -> {
                    prefs
                        .edit()
                        .putInt(prefix + "year", which == 0 ? 0 : now - which + 1)
                        .apply();
                    again(target, kind, "refine:year");
                })
                .show();
        });
        line = activity.row();
        target.addView(line);
        refineButton(line, "Rating · " + (rating == 0 ? "Any" : rating + "+"), "refine:rating", () ->
            SidePanel.of(activity, "Minimum rating")
                .choices(
                    new String[] { "Any rating", "5+", "6+", "7+", "8+", "9+" },
                    prefs.getInt(prefix + "rating", 0) == 0 ? 0 : prefs.getInt(prefix + "rating", 0) - 4,
                    which -> {
                        prefs
                            .edit()
                            .putInt(prefix + "rating", which == 0 ? 0 : which + 4)
                            .apply();
                        again(target, kind, "refine:rating");
                    }
                )
                .show()
        );
        refineButton(line, "Sort · " + ORDERS[sort], "refine:sort", () ->
            SidePanel.of(activity, "Sort order")
                .choices(
                    ORDERS,
                    Math.max(0, Math.min(ORDERS.length - 1, prefs.getInt(prefix + "sort", 0))),
                    which -> {
                        prefs
                            .edit()
                            .putInt(prefix + "sort", which)
                            .apply();
                        again(target, kind, "refine:sort");
                    }
                )
                .show()
        );
        line = activity.row();
        target.addView(line);
        Button results = refineButton(line, "Show results", "refine:show", () -> {
            String route = TmdbCategories.refine(
                type,
                prefs.getInt(prefix + "genre", 0),
                prefs.getInt(prefix + "year", 0),
                prefs.getInt(prefix + "rating", 0),
                ORDER_ROUTES[Math.max(0, Math.min(ORDERS.length - 1, prefs.getInt(prefix + "sort", 0)))]
            );
            screens.browse(kind, "Your choices", route, 1);
        });
        activity.primary(results);
        refineButton(line, "Reset", "refine:reset", () -> {
            prefs
                .edit()
                .remove(prefix + "genre")
                .remove(prefix + "year")
                .remove(prefix + "rating")
                .remove(prefix + "sort")
                .apply();
            again(target, kind, "refine:reset");
        });
    }

    /** Rebuilds the sheet with the new choice and puts the remote back on the same button. */
    private void again(LinearLayout target, String kind, String tag) {
        renderBrowse(target, kind);
        View button = target.findViewWithTag(tag);
        if (button != null) button.requestFocus();
    }

    /** A half-width button in the sheet; its tag lets focus come back to it after a rebuild. */
    private Button refineButton(LinearLayout line, String label, String tag, Runnable action) {
        Button button = activity.button(label, action);
        button.setTextSize(14);
        button.setTag(tag);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, activity.dp(42), 1);
        lp.setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
        line.addView(button, lp);
        return button;
    }
}
