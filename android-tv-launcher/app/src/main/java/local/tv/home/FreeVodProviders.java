package local.tv.home;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.*;

/**
 * The signed APK contains the Python modules. A newer signed scraper package (ScraperPackages) can
 * replace them over the air; moviehub_loader falls back to the APK's copy if a package fails to load.
 */
public final class FreeVodProviders {

    private FreeVodProviders() {}

    /** Package folder currently in use ("" = the APK's own scrapers); null before the first call. */
    private static String activated;
    /**
     * Long Python calls in progress. The scrapers import site modules lazily during a search, so a
     * newly downloaded package is switched in only when none is running, never mid-search.
     */
    private static int inFlight;

    private static synchronized PyObject enter(Context context) {
        PyObject module = runtime(context);
        inFlight++;
        return module;
    }

    private static synchronized void leave() {
        inFlight--;
    }

    private static synchronized PyObject runtime(Context context) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        Python python = Python.getInstance();
        String wanted = ScraperPackages.activePath(context);
        if (!wanted.equals(activated) && (activated == null || inFlight == 0)) {
            String result = python.getModule("moviehub_loader").callAttr("activate", wanted).toString();
            if (!wanted.isEmpty() && !result.equals("overlay")) {
                ScraperPackages.reject(context, wanted, result);
                activated = "";
            } else activated = wanted;
        }
        return python.getModule("moviehub_vod");
    }

    static List<String> ids(Context context) throws Exception {
        JSONArray rows = new JSONArray(runtime(context).callAttr("search_providers").toString());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) result.add(rows.getJSONObject(i).getString("id"));
        return result;
    }

    static List<String> ids(Context context, boolean series) throws Exception {
        JSONArray rows = new JSONArray(runtime(context).callAttr("search_providers").toString());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.optBoolean(series ? "series" : "movies")) ids.add(row.getString("id"));
        }
        return ids;
    }

    static int validate(Context context) {
        return runtime(context).callAttr("validate").toInt();
    }

    public interface Result {
        void ready(String json);
    }

    static String search(
        Context context,
        String id,
        JSONObject query,
        AtomicBoolean cancelled,
        Consumer<SourceRepository.Source> found
    ) throws Exception {
        Result callback = json -> {
            if (cancelled.get()) return;
            try {
                parse(context, id, query, new JSONObject(json), found);
            } catch (Exception error) {
                Diagnostics.recovered("Free source result", error);
            }
        };
        PyObject module = enter(context);
        try {
            return module.callAttr("query_site", id, query.toString(), cancelled, callback).toString();
        } finally {
            leave();
        }
    }

    public static final class RefreshCancellation {

        public boolean get() {
            return Thread.currentThread().isInterrupted();
        }
    }

    private static void parse(
        Context context,
        String id,
        JSONObject query,
        JSONObject item,
        Consumer<SourceRepository.Source> found
    ) throws Exception {
        String link = Playlist.networkUrl(item.getString("url"), query.optBoolean("http"));
        JSONObject headers = item.getJSONObject("headers");
        String label = item.optString("quality", "SD");
        String path = item.optString("filename", "video.mp4");
        JSONArray files = new JSONArray().put(
            new JSONObject()
                .put("path", path)
                .put("link", link)
                .put("lookupSeason", query.getInt("season"))
                .put("lookupEpisode", query.getInt("episode"))
                .put("identityEvidence", item.optString("identityEvidence", "provider_lookup"))
        );
        SourceRepository.Source source = new SourceRepository.Source(
            item.optString("providerName", id).replaceFirst("^www\\.", "") +
                "\n" +
                label +
                " · " +
                item.optString("info"),
            link,
            files,
            headers
        );
        source.formatInfo = item.optString("formatInfo");
        source.confirmed = item.optBoolean("qualityVerified", false);
        source.playlist = item.optBoolean("playlist", false);
        source.site = item.optString("website", id);
        source.claimed = item.optString("claimedQuality", label);
        // A playlist's label is capped at the preferred quality, so only a file's own size
        // can show that a website overstated it.
        if (source.confirmed && !source.playlist) judge(
            context,
            source.site,
            source.claimed,
            SourceRanking.resolution(label)
        );
        source.ranking =
            QualityClaims.effective(label, source.confirmed, history(context, source.site)) +
            (SourceRanking.camera(item.optString("info")) ? " CAM" : "");
        source.probeMbps = item.optDouble("probeMbps", 0);
        source.responseMs = item.optLong("responseMs", Long.MAX_VALUE);
        // Preserve native DASH base-URL handling; the relay rewrites HLS, not MPD templates.
        if (!"dash".equals(item.optString("format")) || headers.length() > 0) source.stream =
            new EngineSources.Stream(
                new JSONObject()
                    .put("url", link)
                    .put("headers", headers)
                    .put("segmentContainers", item.optBoolean("segmentContainers", false))
                    .put("boundedRanges", item.optBoolean("boundedRanges", false))
            );
        source.expiresAt = item.optLong("expiresAtMs");
        if (item.has("refresh")) {
            JSONObject recipe = item.getJSONObject("refresh");
            JSONObject snapshot = new JSONObject(query.toString());
            source.refresh = () -> {
                List<SourceRepository.Source> renewed = new ArrayList<>();
                PyObject module = enter(context);
                JSONObject replacement;
                try {
                    replacement = new JSONObject(
                        module
                            .callAttr(
                                "refresh_source",
                                recipe.toString(),
                                snapshot.toString(),
                                new RefreshCancellation()
                            )
                            .toString()
                    );
                } finally {
                    leave();
                }
                parse(context, id, snapshot, replacement, renewed::add);
                return renewed.isEmpty() ? null : renewed.get(0);
            };
        }
        found.accept(source);
    }

    private static final String TRUST = "quality_claims.";

    /** This website's recent label checks, as {@link QualityClaims#record} keeps them. */
    static String history(Context context, String site) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(TRUST + site, "");
    }

    /**
     * Remembers whether a website's claimed quality matched the real picture ({@code actualTier},
     * see {@link VideoDetails#tier}). Search workers and the player both report, so this is locked.
     */
    static synchronized void judge(Context context, String site, String claimed, int actualTier) {
        if (site == null || site.isEmpty()) return;
        int verdict = QualityClaims.verdict(claimed, actualTier);
        if (verdict == QualityClaims.NO_CLAIM) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        prefs
            .edit()
            .putString(TRUST + site, QualityClaims.record(prefs.getString(TRUST + site, ""), verdict))
            .apply();
    }
}
