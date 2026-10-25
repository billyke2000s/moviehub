package local.tv.home;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.*;

/** The signed APK contains the Python modules; no Kodi install, server or downloaded code. */
public final class FreeVodProviders {

    private FreeVodProviders() {}

    private static synchronized PyObject runtime(Context context) {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        return Python.getInstance().getModule("moviehub_vod");
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
                JSONObject item = new JSONObject(json);
                String link = Playlist.networkUrl(item.getString("url"), query.optBoolean("http"));
                JSONObject headers = item.getJSONObject("headers");
                String label = item.optString("quality", "SD");
                String path =
                    query.getString("title") +
                    (query.getInt("season") < 0
                        ? ""
                        : String.format(
                              Locale.ROOT,
                              " S%02dE%02d",
                              query.getInt("season"),
                              query.getInt("episode")
                          )) +
                    ".mp4";
                JSONArray files = new JSONArray().put(new JSONObject().put("path", path).put("link", link));
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
                source.ranking = label + (SourceRanking.camera(item.optString("info")) ? " CAM" : "");
                source.probeMbps = item.optDouble("probeMbps", 0);
                source.responseMs = item.optLong("responseMs", Long.MAX_VALUE);
                // Preserve native DASH base-URL handling; the relay rewrites HLS, not MPD templates.
                if (!"dash".equals(item.optString("format")) || headers.length() > 0) source.stream =
                    new EngineSources.Stream(new JSONObject().put("url", link).put("headers", headers));
                found.accept(source);
            } catch (Exception error) {
                Diagnostics.recovered("Free source result", error);
            }
        };
        return runtime(context).callAttr("query_site", id, query.toString(), cancelled, callback).toString();
    }
}
