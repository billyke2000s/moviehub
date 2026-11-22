package local.tv.home;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/**
 * Optional Stremio-protocol live-sports source. Nuvio runs on a separate service; the APK only
 * consumes its public catalog/stream API. Never store an expiring playback URL as a channel key.
 */
final class NuvioSports {

    static final String SOURCE = "nuviosports";
    static final String SERVICE = "Nuvio Sports";
    static final String ROOT = "https://nuvio.moviehub.invalid/tv/";
    private static final String LIVE = "nuvio_sports_live";
    private static final String NETWORKS = "nuvio_sports_networks";
    private static final String WORK = "nuvio-sports-refresh";
    private static final long REFRESH_MS = TimeUnit.MINUTES.toMillis(15);
    private static final Map<String, Failed> failed = new HashMap<>();

    private static final class Failed {
        String last;
        long at;
        final Set<String> burnt = new HashSet<>();
    }

    private NuvioSports() {}

    static boolean connected(Context context) {
        return new Secrets(context).has(SOURCE);
    }

    static String service(Context context) throws Exception {
        return new Secrets(context).get(SOURCE);
    }

    static String saved(Context context) {
        try {
            return service(context);
        } catch (Exception e) {
            Diagnostics.recovered("Reading saved Nuvio service", e);
            return "";
        }
    }

    /** A user-configured server base, never a full media/manifest link or an embedded credential. */
    static String base(String supplied, boolean allowHttp) {
        String checked = Playlist.networkUrl(supplied, allowHttp);
        URI uri = URI.create(checked);
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/")) ||
            uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Enter the sports server address, without a path");
        }
        return checked.endsWith("/") ? checked.substring(0, checked.length() - 1) : checked;
    }

    static void verify(String supplied, boolean allowHttp) throws Exception {
        String server = base(supplied, allowHttp);
        JSONObject manifest = new JSONObject(Network.text(server + "/manifest.json", null, allowHttp));
        if (!"community.nuvio.live-sports".equals(manifest.optString("id"))) {
            throw new IOException("Not a Nuvio Live Sports addon");
        }
        JSONArray resources = manifest.optJSONArray("resources");
        if (resources == null || !resources.toString().contains("catalog") ||
            !resources.toString().contains("stream")) {
            throw new IOException("Missing sports catalog or playback API");
        }
    }

    static boolean locator(String url) {
        if (url == null || !url.startsWith(ROOT)) return false;
        try {
            return !id(url).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static String locatorFor(String id) {
        if (id == null || !id.startsWith("nuvio_sport_") || id.length() > 350) {
            throw new IllegalArgumentException("Invalid Nuvio event ID");
        }
        return ROOT + Network.path(id);
    }

    static String id(String locator) throws Exception {
        if (locator == null || !locator.startsWith(ROOT)) throw new IOException("Invalid Nuvio locator");
        URI uri = URI.create(locator);
        if (!"nuvio.moviehub.invalid".equals(uri.getHost()) ||
            uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("Invalid Nuvio locator");
        }
        String result = URLDecoder.decode(uri.getRawPath().substring("/tv/".length()), "UTF-8");
        if (!result.startsWith("nuvio_sport_") || result.length() > 350 ||
            result.indexOf('/') >= 0 || result.indexOf('\\') >= 0) {
            throw new IOException("Invalid Nuvio event");
        }
        return result;
    }

    private static String catalogUrl(String base, String catalog) {
        return base + "/catalog/tv/" + catalog + ".json";
    }

    /** Import only live fixtures and 24/7 networks, keeping a failed import's last good database. */
    static int refresh(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        String server = base(service(context), context.getSharedPreferences("ui", 0).getBoolean("http", false));
        boolean http = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        JSONArray combined = new JSONArray();
        List<String> errors = new ArrayList<>();
        for (String catalog : new String[] { NETWORKS, LIVE }) {
            cancel.check();
            try {
                JSONObject reply = new JSONObject(Network.text(catalogUrl(server, catalog), null, http));
                JSONArray metas = reply.getJSONArray("metas");
                for (int i = 0; i < Math.min(metas.length(), 400); i++) {
                    JSONObject meta = metas.optJSONObject(i);
                    if (meta == null) continue;
                    combined.put(new JSONObject(meta.toString()).put("_moviehubCatalog", catalog));
                }
            } catch (Exception e) {
                cancel.check();
                errors.add(catalog);
            }
        }
        // Do not replace a complete saved sports lineup with half of an upstream response.
        if (!errors.isEmpty()) throw new IOException(
            "Nuvio Sports catalogue unavailable: " + String.join(", ", errors)
        );
        if (combined.length() == 0) throw new IOException("Nuvio Sports has no channels");
        ChannelDatabase.Writer writer = db.writer(SOURCE, cancel, n -> {});
        try {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < combined.length(); i++) {
                cancel.check();
                JSONObject m = combined.getJSONObject(i);
                String eventId = m.optString("id");
                if (!eventId.startsWith("nuvio_sport_") || !seen.add(eventId)) continue;
                String name = m.optString("name", "").trim();
                if (name.isEmpty()) continue;
                String catalog = m.optString("_moviehubCatalog");
                String group = catalog.equals(NETWORKS) ? "Sports · 24/7" : "Sports · Live events";
                String art = m.optString("logo", m.optString("poster", ""));
                if (!art.startsWith("https://")) art = "";
                writer.accept(new Playlist.Channel(
                    name,
                    locatorFor(eventId),
                    group,
                    art,
                    "",
                    catalog.equals(NETWORKS) ? "24/7" : "Live event"
                ));
            }
            if (writer.count() == 0) throw new IOException("Nuvio Sports returned no usable events");
            cancel.check();
            writer.commit();
            context.getSharedPreferences("nuvioSportsSync", 0)
                .edit()
                .putLong("lastSuccess", System.currentTimeMillis())
                .putLong("lastRequested", System.currentTimeMillis())
                .apply();
            return writer.count();
        } finally {
            writer.abort();
        }
    }

    /** A fresh play-time lookup is required: the addon returns short-lived HLS proxy URLs. */
    static String resolve(Context context, String locator, NativeHttp http) throws Exception {
        http.check();
        String server = base(service(context), context.getSharedPreferences("ui", 0).getBoolean("http", false));
        String endpoint = server + "/stream/tv/" + Network.path(id(locator)) + ".json";
        boolean allowHttp = context.getSharedPreferences("ui", 0).getBoolean("http", false);
        JSONArray streams = new JSONObject(Network.text(endpoint, null, allowHttp)).getJSONArray("streams");
        List<String> candidates = candidates(streams, allowHttp);
        if (candidates.isEmpty()) throw new IOException("No supported direct sports stream");
        http.check();
        synchronized (failed) {
            Failed choice = failed.get(locator);
            if (choice == null || System.currentTimeMillis() - choice.at > 600_000) {
                choice = new Failed();
                failed.put(locator, choice);
            }
            for (String candidate : candidates) {
                if (choice.burnt.contains(candidate)) continue;
                choice.last = candidate;
                choice.at = System.currentTimeMillis();
                if (failed.size() > 256) failed.clear();
                return candidate;
            }
            // Everything was rejected. On the next cycle, try the list afresh.
            choice.burnt.clear();
            choice.last = candidates.get(0);
            choice.at = System.currentTimeMillis();
            return choice.last;
        }
    }

    /** Do not hand browser-only embeds, YouTube pages or malformed URLs to ExoPlayer. */
    static List<String> candidates(JSONArray streams, boolean allowHttp) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(50, streams.length()); i++) {
            JSONObject item = streams.optJSONObject(i);
            if (item == null || !item.optString("externalUrl", "").isEmpty() ||
                !item.optString("ytId", "").isEmpty()) continue;
            String url = item.optString("url", "");
            try {
                url = Playlist.networkUrl(url, allowHttp);
                URI uri = URI.create(url);
                String path = uri.getPath().toLowerCase(Locale.ROOT);
                if (!(path.endsWith(".m3u8") || path.endsWith(".ts") ||
                    path.endsWith("/api/manifest") || path.endsWith("/api/manifest.m3u8") ||
                    path.endsWith("/stream") || path.endsWith("/playlist"))) continue;
                if (seen.add(url)) result.add(url);
            } catch (Exception ignored) {
                // Ignore browser-only or unusable stream entries.
            }
        }
        return result;
    }

    /** The Nuvio addon also serves live HLS through extensionless manifest URLs. */
    static boolean hlsProxy(String address) {
        try {
            String path = URI.create(address).getPath().toLowerCase(Locale.ROOT);
            return path.equals("/api/manifest") || path.equals("/api/manifest.m3u8") ||
                path.endsWith("/playlist");
        } catch (Exception e) {
            return false;
        }
    }

    /** Mark the current candidate failed so an ExoPlayer reconnect tries the next one. */
    static void failed(String locator) {
        if (!locator(locator)) return;
        synchronized (failed) {
            Failed item = failed.get(locator);
            if (item != null && item.last != null) {
                item.burnt.add(item.last);
                item.at = System.currentTimeMillis();
            }
        }
    }

    /** Live match listings should not stay unchanged for MovieHub's usual 12-hour EPG interval. */
    static void queueIfStale(Context context) {
        if (!connected(context)) return;
        android.content.SharedPreferences p = context.getSharedPreferences("nuvioSportsSync", 0);
        long now = System.currentTimeMillis();
        long last = p.getLong("lastRequested", 0);
        if (now >= last && now - last < REFRESH_MS) return;
        p.edit().putLong("lastRequested", now).apply();
        Constraints network = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(Job.class)
            .setConstraints(network).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, work);
    }

    public static final class Job extends Worker {
        public Job(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull
        @Override
        public Result doWork() {
            if (!connected(getApplicationContext())) return Result.success();
            try {
                ChannelImporter.refreshNuvio(getApplicationContext(), () -> {
                    if (isStopped()) throw new java.util.concurrent.CancellationException();
                });
                return Result.success();
            } catch (Exception e) {
                Diagnostics.recovered("Refreshing Nuvio sports listings", e);
                return Result.failure();
            }
        }
    }
}
