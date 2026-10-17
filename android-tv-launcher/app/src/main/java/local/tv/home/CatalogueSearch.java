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

/** Title, person and company search with recent-search history. */
final class CatalogueSearch {

    private final MainActivity activity;

    CatalogueSearch(MainActivity activity) {
        this.activity = activity;
    }

    void entitySearch(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            entity = section.equals(":people")
                ? "person"
                : section.equals(":companies")
                  ? "company"
                  : section.equals(":keywords")
                    ? "keyword"
                    : "collection";
        EditText query = activity.field(
            target,
            section.equals(":people")
                ? "Actor, director or creator"
                : section.equals(":companies")
                  ? "Studio name"
                  : section.equals(":keywords")
                    ? "Keyword or theme"
                    : "Collection name",
            false,
            ""
        );
        LinearLayout found = activity.vertical();
        target.addView(
            activity.button("Search", () -> {
                String value = query.getText().toString().trim();
                if (value.isEmpty()) {
                    query.setError("Enter a search term");
                    return;
                }
                if (!activity.secrets.has("tmdb")) {
                    activity.settingsScreens.accountScreen("TMDB");
                    return;
                }
                entityResults(found, kind, entity, value, 1);
            })
        );
        target.addView(found);
    }

    private void entityResults(LinearLayout target, String kind, String entity, String query, int number) {
        activity.generation++;
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        TextView loading = activity.text("Loading matches…", 15);
        target.addView(loading);
        activity.async(
            () ->
                activity.media.tmdb(
                    "search/" +
                        entity +
                        "?query=" +
                        Network.q(query) +
                        "&page=" +
                        number +
                        (entity.equals("person") || entity.equals("collection") ? "&include_adult=false" : "")
                ),
            response -> {
                target.removeAllViews();
                JSONArray items = response.getJSONArray("results");
                List<TmdbCategories.Entry> choices = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item.optBoolean("adult", false)) continue;
                    int id = item.getInt("id");
                    String route = entity.equals("collection")
                        ? "collection/" + id
                        : entity.equals("person")
                          ? "person/" + id + "/" + type + "_credits"
                          : TmdbCategories.discover(
                                type,
                                (entity.equals("company") ? "with_companies=" : "with_keywords=") + id
                            );
                    choices.add(new TmdbCategories.Entry(item.getString("name"), route));
                }
                activity.catalogues.categoryChoices(target, kind, choices);
                if (choices.isEmpty()) target.addView(activity.text("No matches found", 16));
                LinearLayout pages = activity.row();
                if (number > 1) pages.addView(
                    activity.button("Previous", () -> entityResults(target, kind, entity, query, number - 1))
                );
                if (number < Math.min(response.optInt("total_pages", 1), 500)) pages.addView(
                    activity.button("Next", () -> entityResults(target, kind, entity, query, number + 1))
                );
                target.addView(pages);
            }
        );
    }

    private JSONArray recentSearches() {
        try {
            return new JSONArray(activity.prefs.getString("searchHistory", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    void rememberSearch(String value) {
        JSONArray next = new JSONArray();
        next.put(value);
        JSONArray old = recentSearches();
        for (int i = 0; i < old.length() && next.length() < 12; i++) if (
            !value.equalsIgnoreCase(old.optString(i))
        ) next.put(old.optString(i));
        activity.prefs.edit().putString("searchHistory", next.toString()).apply();
    }

    void searchCatalogue(String kind) {
        activity.screen("Search", () -> activity.libraryScreens.library(kind));
        LinearLayout typeRow = activity.horizontal(activity.page);
        for (String scope : new String[] { "Movies", "Series" }) {
            Button tab = activity.chip(typeRow, scope, () -> searchCatalogue(scope));
            if (scope.equals(kind)) activity.primary(tab);
        }
        activity.section(activity.page, "What would you like to watch?");
        LinearLayout input = activity.vertical();
        EditText query = activity.field(input, "Search " + kind.toLowerCase(Locale.ROOT), false, "");
        activity.page.addView(input);
        query.setHint("Titles, actors, directors…");
        LinearLayout actions = activity.row();
        Runnable submit = () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) {
                query.setError("Enter a title");
                return;
            }
            rememberSearch(value);
            String type = kind.equals("Movies") ? "movie" : "tv";
            activity.catalogues.browse(
                kind,
                "Search: " + value,
                "search/" + type + "?query=" + Network.q(value) + "&include_adult=false",
                1
            );
        };
        Button search = activity.chip(actions, "Search titles", submit);
        activity.primary(search);
        activity.chip(actions, "Search people", () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) {
                query.setError("Enter a name");
                return;
            }
            rememberSearch(value);
            activity.catalogues.browse(kind, "People", ":people", 1);
            if (activity.overlayContent != null) entityResults(
                activity.overlayContent,
                kind,
                "person",
                value,
                1
            );
        });
        activity.page.addView(actions);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                submit.run();
                return true;
            }
            return false;
        });
        JSONArray history = recentSearches();
        if (history.length() > 0) {
            activity.section(activity.page, "Recent searches");
            LinearLayout recent = activity.horizontal(activity.page);
            for (int i = 0; i < history.length(); i++) {
                String value = history.optString(i);
                activity.chip(recent, value, () -> {
                    query.setText(value);
                    submit.run();
                });
            }
            activity.page.addView(
                activity.button("Clear search history", () -> {
                    activity.prefs.edit().remove("searchHistory").apply();
                    searchCatalogue(kind);
                }),
                new LinearLayout.LayoutParams(activity.dp(200), activity.dp(40))
            );
        } else activity.page.addView(activity.muted("Your recent searches will appear here.", 15));
        query.requestFocus();
    }
}
