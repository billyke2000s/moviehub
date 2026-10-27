package local.tv.home;

import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Progressive movie/episode results share the existing source cards and VOD recovery. */
final class FreeVodScreen {

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    FreeVodScreen(MainActivity activity) {
        this.activity = activity;
    }

    void start(String imdb, int season, int episode, String title, String kind, JSONObject metadata) {
        start(imdb, season, episode, title, kind, metadata, null);
    }

    void start(
        String imdb,
        int season,
        int episode,
        String title,
        String kind,
        JSONObject metadata,
        VodNextEpisode prepared
    ) {
        try {
            if (prepared != null) prepared.claim();
            SourceRepository repo = prepared == null ? new SourceRepository(activity.secrets) : prepared.repo;
            VodSearch search =
                prepared == null
                    ? new VodSearch(
                          activity,
                          query(imdb, season, episode, title, metadata, activity.http()),
                          repo,
                          activity.secrets.has("premiumize"),
                          activity.prefs.getInt(AutoPlayback.QUALITY, 1080)
                      )
                    : prepared.search;
            TextView status = activity.muted(
                "Finding streams… choosing the best available in 3 to 8 seconds.",
                16
            );
            activity.page.addView(status);
            LinearLayout results = activity.vertical();
            activity.page.addView(results);
            boolean[] automatic = { AutoPlayback.enabled(activity, season) };
            Button choose = activity.button("Choose a source instead", () -> automatic[0] = false);
            activity.page.addView(choose);
            activity.page.addView(
                activity.button("Provider search details", () ->
                    SidePanel.of(activity, "Provider search details").message(search.report()).show()
                )
            );
            final int generation = activity.generation;
            Runnable refresh = new Runnable() {
                int count = -1;
                boolean handedOff;
                final FreeVodRows rows = new FreeVodRows(activity, results, source -> {
                    automatic[0] = false;
                    handedOff = true;
                    play(source, search, repo, metadata, imdb, season, episode, title, kind, true);
                });

                public void run() {
                    if (handedOff) return;
                    if (
                        activity.isDestroyed() || activity.isFinishing() || activity.generation != generation
                    ) {
                        if (!handedOff) search.close();
                        return;
                    }
                    SourceRepository.Source best = search.selected();
                    if (automatic[0] && best != null) {
                        handedOff = true;
                        play(best, search, repo, metadata, imdb, season, episode, title, kind, false);
                        return;
                    }
                    List<SourceRepository.Source> found = search.snapshot();
                    count = found.size();
                    // Metadata can finish without the number of links changing.
                    rows.render(found);
                    boolean done = search.finished();
                    status.setText(
                        count > 0
                            ? count +
                                  (count == 1 ? " link found · " : " links found · ") +
                                  search.progress() +
                                  (done ? "" : " · searching for backups…")
                            : done
                              ? "No playable stream found · " +
                                search.progress() +
                                ". See provider search details."
                              : "No stream yet · " + search.progress() + " · still searching…"
                    );
                    // Even an immediate complete response waits for the original selection window.
                    if (done && count == 0) search.close();
                    // Keep a light ownership check after a manual list finishes too, so leaving
                    // cancels any metadata work and releases this search's worker resources.
                    else handler.postDelayed(this, done ? 1000 : 100);
                }
            };
            handler.post(refresh);
        } catch (Exception error) {
            if (prepared != null && prepared.search != null) prepared.search.close();
            Diagnostics.recovered("Starting source search", error);
            activity.toast("Could not start the source search");
        }
    }

    static JSONObject query(
        String imdb,
        int season,
        int episode,
        String title,
        JSONObject metadata,
        boolean http
    ) throws JSONException {
        JSONObject info = metadata == null ? new JSONObject() : metadata;
        String searchTitle = info.optString(season < 0 ? "title" : "name", title);
        return new JSONObject()
            .put("imdb", imdb)
            .put("tmdb", info.optInt("tmdbId", info.optInt("id")))
            .put("title", searchTitle)
            .put("localTitle", searchTitle)
            .put("aliases", aliases(info, season < 0))
            .put("year", info.optString(season < 0 ? "release_date" : "first_air_date").split("-")[0])
            .put("season", season)
            .put("episode", episode)
            .put("premiered", info.optString("air_date", info.optString("first_air_date")))
            .put("episodeTitle", info.optString("episodeTitle"))
            .put("runtime", info.optInt("runtime", 0))
            .put("http", http);
    }

    static JSONArray aliases(JSONObject metadata, boolean movie) {
        Set<String> titles = new LinkedHashSet<>();
        String original = metadata.optString(movie ? "original_title" : "original_name");
        if (!original.trim().isEmpty()) titles.add(original);
        JSONObject alternatives = metadata.optJSONObject("alternative_titles");
        if (alternatives != null) {
            JSONArray rows = alternatives.optJSONArray(movie ? "titles" : "results");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String name = row.optString("title").trim();
                if (!name.isEmpty()) titles.add(name);
            }
        }
        return new JSONArray(titles);
    }

    private void play(
        SourceRepository.Source selected,
        VodSearch search,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind,
        boolean pinned
    ) {
        try {
            int pick = AutoPlayback.pick(selected.files, season, episode, title);
            if (pick < 0) throw new IllegalArgumentException("No exact playable file");
            String link = selected.files.getJSONObject(pick).getString("link");
            SourcesScreen.Binge.remember(imdb, season, episode, title, kind, metadata, selected.files);
            VodSources.Session session = VodSources.progressive(
                selected,
                link,
                search,
                repo,
                season,
                episode,
                title,
                pinned
            );
            activity.play(title, link, activity.id(selected.uri), false, metadata, session);
        } catch (Exception error) {
            Diagnostics.recovered("Opening free source", error);
            activity.toast("Could not open that source");
            search.close();
        }
    }
}
