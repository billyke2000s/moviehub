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
        try {
            JSONObject info = metadata == null ? new JSONObject() : metadata;
            String searchTitle = info.optString(season < 0 ? "title" : "name", title);
            JSONObject query = new JSONObject()
                .put("imdb", imdb)
                .put("tmdb", info.optInt("tmdbId", info.optInt("id")))
                .put("title", searchTitle)
                .put("year", info.optString(season < 0 ? "release_date" : "first_air_date").split("-")[0])
                .put("season", season)
                .put("episode", episode)
                .put("premiered", info.optString("air_date", info.optString("first_air_date")))
                .put("episodeTitle", info.optString("episodeTitle"))
                .put("http", activity.http());
            SourceRepository repo = new SourceRepository(activity.secrets);
            VodSearch search = new VodSearch(
                activity,
                query,
                repo,
                activity.secrets.has("premiumize"),
                activity.prefs.getInt(AutoPlayback.QUALITY, 1080)
            );
            TextView status = activity.muted(
                "Finding streams… choosing the best available after 3 seconds.",
                16
            );
            activity.page.addView(status);
            LinearLayout results = activity.vertical();
            activity.page.addView(results);
            boolean[] automatic = { AutoPlayback.enabled(activity, season) };
            Button choose = activity.button("Choose a source instead", () -> automatic[0] = false);
            activity.page.addView(choose);
            final int generation = activity.generation;
            Runnable refresh = new Runnable() {
                int count = -1;
                final Set<String> displayed = new HashSet<>();
                boolean handedOff;

                public void run() {
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
                    if (count != found.size()) {
                        count = found.size();

                        for (var source : found) {
                            if (!displayed.add(source.uri)) continue;
                            Button row = activity.button(source.title, () -> {
                                handedOff = true;
                                play(
                                    source,
                                    search,
                                    repo,
                                    metadata,
                                    imdb,
                                    season,
                                    episode,
                                    title,
                                    kind,
                                    true
                                );
                            });
                            results.addView(row);
                        }
                    }
                    boolean done = search.finished();
                    status.setText(
                        count > 0
                            ? count + " streams found" + (done ? "" : " · searching for backups…")
                            : done
                              ? "No playable stream found. Try again later."
                              : "No stream yet · still searching…"
                    );
                    // Even an immediate complete response waits for the original selection window.
                    if (!done || (automatic[0] && best == null && count > 0)) handler.postDelayed(this, 100);
                    else if (count == 0) search.close();
                }
            };
            handler.post(refresh);
        } catch (Exception error) {
            Diagnostics.recovered("Starting source search", error);
            activity.toast("Could not start the source search");
        }
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
