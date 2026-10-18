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

/** Movie and series catalogues: category hubs, poster grids and filter sheets. */
final class CatalogueScreens {

    private final MainActivity activity;

    CatalogueScreens(MainActivity activity) {
        this.activity = activity;
    }

    CatalogueScreen catalogue;
    /** Remembered rows pages ("Movies", "Series"), restored when coming back from a title. */
    final Map<String, CatalogueRows.Saved> rowStates = new HashMap<>();

    /** Opens the Movies or Series start page: Netflix-style rows of categories. */
    void rows(String kind) {
        if (catalogue == null || !catalogue.kind.equals(kind)) {
            catalogue = new CatalogueScreen(kind);
            catalogue.refreshGenres();
        }
        catalogue.showRows();
    }

    private String catalogueRegion() {
        String fallback = Locale.getDefault().getCountry();
        return activity.prefs.getString("catalogueRegion", fallback.matches("[A-Z]{2}") ? fallback : "GB");
    }

    List<TmdbCategories.Entry> catalogueGenres(String type) {
        LinkedHashMap<Integer, TmdbCategories.Entry> rows = new LinkedHashMap<>();
        for (TmdbCategories.Entry entry : TmdbCategories.genres(type)) {
            String raw = entry.path.substring(entry.path.lastIndexOf('=') + 1);
            rows.put(Integer.parseInt(raw), entry);
        }
        try {
            JSONArray cached = new JSONArray(activity.prefs.getString("catalogueGenres_" + type, "[]"));
            for (int i = 0; i < cached.length(); i++) {
                JSONObject genre = cached.getJSONObject(i);
                int id = genre.getInt("id");
                rows.put(id, TmdbCategories.genre(type, id, genre.getString("name")));
            }
        } catch (Exception e) {
            Diagnostics.recovered("Reading cached genres", e);
        }
        return new ArrayList<>(rows.values());
    }

    void categoryChoices(LinearLayout target, String kind, List<TmdbCategories.Entry> entries) {
        LinearLayout line = null;
        for (int i = 0; i < entries.size(); i++) {
            TmdbCategories.Entry entry = entries.get(i);
            if (i % 2 == 0) {
                line = activity.row();
                target.addView(line);
            }
            Button choice = activity.button(entry.name, () -> browse(kind, entry.name, entry.path, 1));
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

    private void renderCategoryHub(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        if (!section.equals(":filters")) {
            target.addView(activity.compactText("Genres", 18));
            categoryChoices(target, kind, catalogueGenres(type));
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

    private void refreshCatalogueGenres(LinearLayout target, String kind, String section) {
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

    void browse(String kind, String selected, String path, int number) {
        if (catalogue == null || !catalogue.kind.equals(kind)) {
            catalogue = new CatalogueScreen(kind);
            if (path.startsWith(":")) catalogue.load("Trending today", "trending/" + catalogue.type + "/day");
            catalogue.refreshGenres();
        }
        if (path.startsWith(":")) {
            catalogue.showOverlay(selected, path);
            return;
        }
        catalogue.load(selected, path);
    }

    final class CatalogueScreen {

        final String kind, type;
        final LinearLayout content, controls;
        final ImageView backdrop;
        final TextView collection, previewTitle, previewMeta, previewSummary;
        final LinearLayout hero;
        final Button backButton;
        /** The header button focused when the screen opens. */
        final View initialFocus;
        /** The rows page, while it is showing. */
        CatalogueRows rowsView;
        /** True when a grid was opened from the rows page, so Back returns to the rows. */
        boolean fromRows;
        final ArrayDeque<TmdbCategories.Entry> overlayPages = new ArrayDeque<>();
        PosterFeed feed;
        boolean closed;
        Runnable pendingPreview;
        String selected = "Trending today",
            path;

        CatalogueScreen(String kind) {
            this.kind = kind;
            type = kind.equals("Movies") ? "movie" : "tv";
            activity.fixedScreen(kind, activity.homeScreen::home);
            LinearLayout header = (LinearLayout) activity.page.getChildAt(0);
            controls = header;
            backButton = (Button) header.getChildAt(1);
            header.removeView(backButton);
            header.addView(
                activity.navigationButton("Search", () -> activity.catalogueSearch.searchCatalogue(kind)),
                new LinearLayout.LayoutParams(activity.dp(88), activity.dp(36))
            );
            header.addView(
                activity.navigationButton("Browse", () -> showOverlay("Browse categories", ":categories")),
                new LinearLayout.LayoutParams(activity.dp(100), activity.dp(36))
            );
            header.addView(
                activity.navigationButton("Filters", () -> showOverlay("Filters", ":filters")),
                new LinearLayout.LayoutParams(activity.dp(88), activity.dp(36))
            );
            Button library = activity.navigationButton("Library", () -> {});
            library.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                    .setTitle("Your library")
                    .setItems(new String[] { "My library", "Premiumize cloud", "Transfers" }, (d, which) -> {
                        if (which == 0) activity.libraryScreens.savedLibrary(kind);
                        else if (which == 1) activity.cloudScreens.cloud("", kind);
                        else activity.cloudScreens.transfers(kind);
                    })
                    .show()
            );
            header.addView(library, new LinearLayout.LayoutParams(activity.dp(88), activity.dp(36)));
            header.addView(backButton, new LinearLayout.LayoutParams(activity.dp(72), activity.dp(36)));
            ViewGroup parent = (ViewGroup) activity.page.getParent();
            parent.removeView(activity.page);
            FrameLayout root = new FrameLayout(activity);
            root.setBackgroundColor(0xff090d14);
            backdrop = new ImageView(activity);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setAlpha(.6f);
            root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
            // Darken the left (behind the text) and the lower half (behind the posters), leaving the
            // artwork visible at the top right, as streaming apps do.
            View sideShade = new View(activity);
            sideShade.setBackground(
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {
                    0xf2090d14,
                    0xb3090d14,
                    0x33090d14,
                    0x00090d14,
                })
            );
            root.addView(sideShade, new FrameLayout.LayoutParams(-1, -1));
            View lowerShade = new View(activity);
            lowerShade.setBackground(
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
                    0x66090d14,
                    0x00090d14,
                    0xaa090d14,
                    0xff090d14,
                    0xff090d14,
                })
            );
            root.addView(lowerShade, new FrameLayout.LayoutParams(-1, -1));
            activity.page.setBackgroundColor(Color.TRANSPARENT);
            root.addView(activity.page, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(root);
            LinearLayout preview = activity.vertical();
            hero = preview;
            preview.setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(6));
            previewTitle = activity.compactText("Discover your next watch", 24);
            previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            previewTitle.setSingleLine(true);
            previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            previewMeta = activity.muted("", 13);
            previewSummary = activity.muted("Browse full posters. Hold Select for quick actions.", 14);
            previewSummary.setMaxLines(2);
            previewSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            preview.addView(previewTitle);
            preview.addView(previewMeta);
            preview.addView(previewSummary);
            activity.page.addView(preview, new LinearLayout.LayoutParams(-1, -2));
            collection = activity.compactText("Trending today", 16);
            collection.setTextColor(0xffaab7cb);
            activity.page.addView(collection, new LinearLayout.LayoutParams(-1, activity.dp(28)));
            content = activity.vertical();
            activity.page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            initialFocus = header.getChildAt(1);
            initialFocus.requestFocus();
        }

        /** Shows the rows page in this screen. Browse and Filters still open over it. */
        void showRows() {
            if (closed) return;
            if (feed != null && path != null) activity.rememberCatalogue(type + ":" + path, feed.snapshot());
            if (activity.catalogueDialog != null) activity.catalogueDialog.dismiss();
            activity.generation++;
            if (feed != null) {
                feed.close();
                activity.feeds.remove(feed);
                feed = null;
            }
            path = null;
            selected = null;
            fromRows = false;
            setBack(activity.homeScreen::home);
            activity.destination = () -> rows(kind);
            collection.setText("");
            collection.setVisibility(View.GONE);
            hero.setPadding(activity.dp(8), activity.dp(28), activity.dp(8), activity.dp(10));
            previewTitle.setTextSize(30);
            previewSummary.setMaxLines(3);
            previewSummary.setText("Hold Select on a poster for quick actions.");
            if (pendingPreview != null) activity.handler.removeCallbacks(pendingPreview);
            content.removeAllViews();
            if (rowsView != null) rowsView.close();
            rowsView = new CatalogueRows(activity, this);
            content.addView(rowsView.build(rowStates.remove(kind)), new LinearLayout.LayoutParams(-1, -1));
        }

        /** Sets what both the Back key and the on-screen Back button do. */
        private void setBack(Runnable action) {
            activity.back = action;
            backButton.setOnClickListener(v -> action.run());
        }

        private void leaveRows() {
            if (rowsView == null) return;
            CatalogueRows.Saved saved = rowsView.close();
            if (saved != null) rowStates.put(kind, saved);
            rowsView = null;
            fromRows = true;
            setBack(this::showRows);
        }

        void close() {
            if (rowsView != null) {
                CatalogueRows.Saved saved = rowsView.close();
                if (saved != null) rowStates.put(kind, saved);
                rowsView = null;
            }
            closed = true;
            if (pendingPreview != null) activity.handler.removeCallbacks(pendingPreview);
        }

        void refreshGenres() {
            if (!activity.secrets.has("tmdb")) return;
            activity.io.execute(() -> {
                try {
                    JSONArray result = activity.media.tmdb("genre/" + type + "/list").getJSONArray("genres");
                    String data = result.toString();
                    if (data.equals(activity.prefs.getString("catalogueGenres_" + type, "[]"))) return;
                    activity.prefs
                        .edit()
                        .putString("catalogueGenres_" + type, data)
                        .apply();
                } catch (Exception e) {
                    Diagnostics.recovered("Caching catalogue genres", e);
                }
            });
        }

        void load(String name, String route) {
            if (closed) return;
            if (feed != null && path != null) activity.rememberCatalogue(type + ":" + path, feed.snapshot());
            if (activity.catalogueDialog != null) activity.catalogueDialog.dismiss();
            activity.generation++;
            leaveRows();
            selected = name;
            path = route;
            activity.destination = fromRows
                ? () -> {
                      rows(kind);
                      browse(kind, name, route, 1);
                  }
                : () -> browse(kind, name, route, 1);
            collection.setText(name);
            collection.setVisibility(View.VISIBLE);
            hero.setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(6));
            previewTitle.setTextSize(24);
            previewSummary.setMaxLines(2);
            if (pendingPreview != null) activity.handler.removeCallbacks(pendingPreview);
            backdrop.setTag(null);
            backdrop.setImageDrawable(null);
            if (feed != null) {
                feed.close();
                activity.feeds.remove(feed);
                feed = null;
            }
            content.removeAllViews();
            TextView loading = activity.compactText("Loading titles…", 15);
            content.addView(loading);
            if (!activity.secrets.has("tmdb")) {
                loading.setText("Connect TMDB to browse this catalogue");
                content.addView(
                    activity.button("Connect TMDB", () -> activity.settingsScreens.accountScreen("TMDB"))
                );
            } else {
                feed = activity.posters.catalogueFeed(
                    content,
                    loading,
                    controls,
                    type,
                    n -> cataloguePage(type, route, n),
                    activity.catalogueStates.get(type + ":" + route)
                );
                final PosterFeed current = feed;
                final View focusBefore = activity.getCurrentFocus();
                current.onFirstItems = () -> {
                    if (
                        closed || feed != current || current.items.isEmpty() || !current.isAttachedToWindow()
                    ) return;
                    preview(current.items.get(0));
                    if (focusBefore == null || !focusBefore.isAttachedToWindow() || focusBefore == loading) {
                        View first = current.getLayoutManager().findViewByPosition(0);
                        if (first != null) first.requestFocus();
                    }
                };
            }
            // Keep focus on the posters when the catalogue is already being navigated.
        }

        void preview(JSONObject item) {
            previewTitle.setText(item.optString("title", item.optString("name", "Title")));
            previewMeta.setText(activity.posters.titleMetadata(item));
            previewSummary.setText(item.optString("overview", "No synopsis available."));
            if (pendingPreview != null) activity.handler.removeCallbacks(pendingPreview);
            String art = item.optString("backdrop_path");
            pendingPreview = () -> {
                if (closed) return;
                backdrop.setTag(null);
                backdrop.setImageDrawable(null);
                if (art.startsWith("/")) activity.posters.loadImage(
                    backdrop,
                    "https://image.tmdb.org/t/p/w1280" + art
                );
            };
            activity.handler.postDelayed(pendingPreview, 350);
        }

        void showOverlay(String title, String route) {
            if (activity.catalogueDialog == null) {
                overlayPages.clear();
                Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
                activity.catalogueDialog = dialog;
                dialog.setOnDismissListener(d -> {
                    if (activity.catalogueDialog == dialog) {
                        activity.catalogueDialog = null;
                        activity.overlayContent = null;
                        activity.generation++;
                    }
                });
                dialog.setOnKeyListener((d, key, event) -> {
                    if (key != KeyEvent.KEYCODE_BACK) return activity.dpadRepeats.consume(event);
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        if (overlayPages.size() > 1) {
                            overlayPages.pop();
                            renderOverlay();
                        } else dialog.dismiss();
                    }
                    return true;
                });
            }
            overlayPages.push(new TmdbCategories.Entry(title, route));
            renderOverlay();
        }

        void renderOverlay() {
            activity.generation++;
            TmdbCategories.Entry entry = overlayPages.peek();
            LinearLayout body = activity.vertical();
            body.setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(18));
            LinearLayout head = activity.row();
            TextView title = activity.compactText(entry.name, 22);
            head.addView(title, new LinearLayout.LayoutParams(0, activity.dp(46), 1));
            head.addView(
                activity.button("Close", () -> activity.catalogueDialog.dismiss()),
                new LinearLayout.LayoutParams(activity.dp(76), activity.dp(36))
            );
            body.addView(head);
            ScrollView scroll = new ScrollView(activity);
            LinearLayout choices = activity.vertical();
            activity.overlayContent = choices;
            scroll.addView(choices);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            activity.catalogueDialog.setContentView(body);
            activity.catalogueDialog.show();
            Window window = activity.catalogueDialog.getWindow();
            window
                .getDecorView()
                .setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
            window.setBackgroundDrawable(activity.box(0xff101722));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.55f);
            window.setLayout(
                Math.min(
                    activity.dp(520),
                    activity.getResources().getDisplayMetrics().widthPixels - activity.dp(48)
                ),
                activity.getResources().getDisplayMetrics().heightPixels - activity.dp(48)
            );
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            attributes.x = activity.dp(24);
            window.setAttributes(attributes);
            renderCatalogueOptions(choices, kind, entry.path);
        }
    }

    JSONObject cataloguePage(String type, String path, int number) throws Exception {
        if (path.startsWith("collection/")) {
            JSONArray parts = activity.media.tmdb(path).getJSONArray("parts");
            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < parts.length(); i++) sorted.add(parts.getJSONObject(i));
            sorted.sort(Comparator.comparing(item -> item.optString("release_date", "9999")));
            return new JSONObject().put("results", new JSONArray(sorted)).put("total_pages", 1);
        }
        if (path.startsWith("person/")) {
            JSONObject response = activity.media.tmdb(path);
            JSONArray items = new JSONArray();
            Set<Integer> seen = new HashSet<>();
            for (String key : new String[] { "cast", "crew" }) {
                JSONArray credits = response.optJSONArray(key);
                if (credits != null) for (int i = 0; i < credits.length(); i++) {
                    JSONObject item = credits.getJSONObject(i);
                    if (seen.add(item.optInt("id"))) items.put(item);
                }
            }
            return new JSONObject().put("results", items).put("total_pages", 1);
        }
        return activity.media.tmdb(path + (path.contains("?") ? "&" : "?") + "page=" + number);
    }

    private void renderCatalogueOptions(LinearLayout grid, String kind, String path) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        if (path.equals(":filters")) {
            grid.addView(activity.button("Refine results", () -> refineCatalogue(kind)));
            categoryChoices(grid, kind, TmdbCategories.groups(type));
            return;
        }
        if (path.equals(":categories") || path.equals(":genres")) {
            renderCategoryHub(grid, kind, path);
            refreshCatalogueGenres(grid, kind, path);
            return;
        }
        if (path.equals(":region")) {
            grid.addView(activity.text("Loading countries…", 16));
            activity.async(
                () ->
                    new JSONArray(
                        Network.text(
                            MediaRepository.tmdbUrl("configuration/countries", activity.secrets.get("tmdb")),
                            null,
                            false
                        )
                    ),
                countries -> {
                    grid.removeAllViews();
                    for (int i = 0; i < countries.length(); i++) {
                        JSONObject country = countries.getJSONObject(i);
                        String code = country.getString("iso_3166_1");
                        grid.addView(
                            activity.button(country.optString("english_name", code), () -> {
                                activity.prefs.edit().putString("catalogueRegion", code).apply();
                                if (catalogue != null) {
                                    catalogue.overlayPages.pop();
                                    catalogue.renderOverlay();
                                }
                            })
                        );
                    }
                }
            );
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
                                browse(kind, entry.name, entry.path, 1);
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
            grid.addView(activity.text("Connect TMDB to browse this catalogue", 20));
            grid.addView(
                activity.button("Connect TMDB", () -> activity.settingsScreens.accountScreen("TMDB"))
            );
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
        if (path.equals(":services")) {
            grid.removeAllViews();
            grid.addView(activity.button("Region: " + catalogueRegion(), () -> chooseCatalogueRegion(kind)));
            grid.addView(activity.compactText("Availability data: JustWatch", 12));
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, ":availability", catalogueRegion(), java.time.LocalDate.now())
            );
            grid.addView(loading);
            activity.async(
                () ->
                    activity.media
                        .tmdb("watch/providers/" + type + "?watch_region=" + catalogueRegion())
                        .getJSONArray("results"),
                items -> {
                    grid.removeView(loading);
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject provider = items.getJSONObject(i);
                        choices.add(
                            new TmdbCategories.Entry(
                                provider.getString("provider_name"),
                                TmdbCategories.discover(
                                    type,
                                    "watch_region=" +
                                        catalogueRegion() +
                                        "&with_watch_providers=" +
                                        provider.getInt("provider_id")
                                )
                            )
                        );
                    }
                    if (choices.isEmpty()) grid.addView(
                        activity.text("No services listed in this region", 16)
                    );
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
    }

    private void refineCatalogue(String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            prefix = "refine_" + type + "_";
        LinearLayout content = activity.vertical();
        List<TmdbCategories.Entry> genres = catalogueGenres(type);
        int[] genre = { activity.prefs.getInt(prefix + "genre", 0) },
            rating = { activity.prefs.getInt(prefix + "rating", 0) },
            sort = { activity.prefs.getInt(prefix + "sort", 0) };
        String[] orders = { "Most popular", "Highest rated", "Newest releases", "Title A–Z" },
            routes = { "popularity.desc", "vote_average.desc", "date.desc", "title.asc" };
        sort[0] = Math.max(0, Math.min(orders.length - 1, sort[0]));
        String genreName = "Any genre";
        for (TmdbCategories.Entry entry : genres)
            if (entry.path.endsWith("=" + genre[0])) genreName = entry.name;
        Button genreButton = activity.button(genreName, () -> {});
        content.addView(activity.muted("Genre", 14));
        content.addView(genreButton);
        genreButton.setOnClickListener(v -> {
            String[] choices = new String[genres.size() + 1];
            choices[0] = "Any genre";
            for (int i = 0; i < genres.size(); i++) choices[i + 1] = genres.get(i).name;
            new AlertDialog.Builder(activity)
                .setTitle("Genre")
                .setItems(choices, (d, which) -> {
                    genre[0] =
                        which == 0
                            ? 0
                            : Integer.parseInt(
                                  genres
                                      .get(which - 1)
                                      .path.substring(genres.get(which - 1).path.lastIndexOf('=') + 1)
                              );
                    genreButton.setText(choices[which]);
                })
                .show();
        });
        EditText year = activity.field(
            content,
            "Release year · leave blank for any",
            false,
            activity.prefs.getInt(prefix + "year", 0) == 0
                ? ""
                : String.valueOf(activity.prefs.getInt(prefix + "year", 0))
        );
        year.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(activity.muted("Minimum rating", 14));
        Button ratingButton = activity.button(rating[0] == 0 ? "Any rating" : rating[0] + "+", () -> {});
        content.addView(ratingButton);
        ratingButton.setOnClickListener(v ->
            new AlertDialog.Builder(activity)
                .setTitle("Minimum rating")
                .setItems(new String[] { "Any rating", "5+", "6+", "7+", "8+", "9+" }, (d, which) -> {
                    rating[0] = which == 0 ? 0 : which + 4;
                    ratingButton.setText(rating[0] == 0 ? "Any rating" : rating[0] + "+");
                })
                .show()
        );
        content.addView(activity.muted("Sort order", 14));
        Button sortButton = activity.button(orders[sort[0]], () -> {});
        content.addView(sortButton);
        sortButton.setOnClickListener(v ->
            new AlertDialog.Builder(activity)
                .setTitle("Sort order")
                .setSingleChoiceItems(orders, sort[0], (d, which) -> {
                    sort[0] = which;
                    sortButton.setText(orders[which]);
                    d.dismiss();
                })
                .show()
        );
        Dialog dialog = activity.form("Refine " + kind.toLowerCase(Locale.ROOT), content);
        LinearLayout actions = activity.row();
        Button apply = activity.chip(actions, "Apply filters", () -> {
            try {
                int value = year.getText().toString().trim().isEmpty()
                    ? 0
                    : Integer.parseInt(year.getText().toString().trim());
                String route = TmdbCategories.refine(type, genre[0], value, rating[0], routes[sort[0]]);
                activity.prefs
                    .edit()
                    .putInt(prefix + "genre", genre[0])
                    .putInt(prefix + "year", value)
                    .putInt(prefix + "rating", rating[0])
                    .putInt(prefix + "sort", sort[0])
                    .apply();
                dialog.dismiss();
                browse(kind, "Filtered results", route, 1);
            } catch (Exception e) {
                year.setError("Enter a year from 1874 to 9999");
            }
        });
        activity.primary(apply);
        activity.chip(actions, "Reset", () -> {
            genre[0] = 0;
            rating[0] = 0;
            sort[0] = 0;
            genreButton.setText("Any genre");
            ratingButton.setText("Any rating");
            sortButton.setText(orders[0]);
            year.setText("");
        });
        content.addView(actions);
    }

    private void chooseCatalogueRegion(String kind) {
        browse(kind, "Region", ":region", 1);
    }

    void catalogueMenu(String kind) {
        activity.libraryScreens.library(kind);
    }

    void catalogue(String type, String path, int number) {
        browse(type.equals("movie") ? "Movies" : "Series", "Browse", path, number);
    }

    private void advancedCatalogue(String type) {
        browse(type.equals("movie") ? "Movies" : "Series", "More filters", ":filters", 1);
    }
}
