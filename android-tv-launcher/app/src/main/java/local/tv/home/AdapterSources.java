package local.tv.home;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Android SQLite/session adapter for upstream async provider modules. */
final class AdapterSources {

    static final String ROOT = "https://native.moviehub.invalid/engine/";
    private static final Map<String, JSONArray> cache = new HashMap<>();
    private static final Map<String, Long> expiry = new HashMap<>();
    private static final Map<String, String> versions = new HashMap<>();
    static final String[] LABELS = {
        "STIRR",
        "TCL TV+",
        "Xumo Play",
        "LG Channels",
        "Whale TV+",
        "DistroTV",
        "Vizio WatchFree+",
        "Vidaa",
        "Roku Channel",
        "Plex",
        "DaddyLive",
        "Tubi",
        "Samsung TV Plus (GB)",
    };

    private AdapterSources() {}

    static String source(String id) {
        return "engine" + id;
    }

    static String pref(String id) {
        return "engineProvider_" + id;
    }

    static boolean enabled(Context c, String id, boolean connected) {
        return (
            c.getSharedPreferences("ui", 0).getBoolean(pref(id), false) &&
            !FreeChannels.playlist(c, connected).isEmpty()
        );
    }

    static synchronized void invalidate() {
        expiry.clear();
    }

    static boolean locator(String url) {
        if (url == null || !url.startsWith(ROOT)) return false;
        String tail = url.substring(ROOT.length());
        int slash = tail.indexOf('/');
        return slash > 0 && ProviderBroker.supported(tail.substring(0, slash));
    }

    static String provider(String url) throws IOException {
        if (!locator(url)) throw new IOException("Invalid provider locator");
        return url.substring(ROOT.length(), url.indexOf('/', ROOT.length()));
    }

    static String channel(String url) throws IOException {
        String id = provider(url);
        String tail = url.substring((ROOT + id + "/").length());
        if (!tail.matches("[A-Za-z0-9_-]{1,700}")) throw new IOException("Invalid provider channel");
        try {
            return new String(Base64.getUrlDecoder().decode(tail), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Invalid provider identity");
        }
    }

    static String locator(String id, String channel) {
        return (
            ROOT +
            id +
            "/" +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(channel.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    private static JSONArray catalogue(Context c, String id, NativeHttp http) throws Exception {
        String module = ProviderPackages.module(c, id);
        synchronized (AdapterSources.class) {
            if (
                cache.containsKey(id) &&
                System.currentTimeMillis() < expiry.getOrDefault(id, 0L) &&
                module.equals(versions.get(id))
            ) return cache.get(id);
        }
        Object value = ProviderBroker.call(c, id, "catalogue", null, http);
        if (!(value instanceof JSONArray)) throw new IOException("Invalid catalogue result");
        JSONArray rows = (JSONArray) value;
        if (rows.length() == 0 || rows.length() > 5000) throw new IOException("Invalid catalogue size");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String key = row.getString("channelId");
            if (
                key.isEmpty() ||
                key.length() > 400 ||
                !seen.add(key) ||
                row.getString("name").trim().isEmpty()
            ) throw new IOException("Invalid provider row");
        }
        synchronized (AdapterSources.class) {
            cache.put(id, rows);
            expiry.put(id, System.currentTimeMillis() + 600_000);
            versions.put(id, module);
        }
        return rows;
    }

    static EngineSources.Stream resolve(Context c, String url, NativeHttp http) throws Exception {
        String id = provider(url),
            wanted = channel(url);
        JSONArray rows = catalogue(c, id, http);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            http.check();
            if (wanted.equals(row.getString("channelId"))) return new EngineSources.Stream(
                (JSONObject) ProviderBroker.call(c, id, "resolve", row, http)
            );
        }
        throw new IOException("Provider channel unavailable");
    }

    static int refresh(Context c, String id, ChannelDatabase db, ChannelDatabase.Cancel cancel)
        throws Exception {
        synchronized (AdapterSources.class) {
            expiry.remove(id);
        }
        NativeHttp http = new NativeHttp();
        http.externalCancel = cancel;
        cancel.check();
        JSONArray rows = catalogue(c, id, http);
        cancel.check();
        ChannelDatabase.Writer writer = db.writer(source(id), cancel, n -> {});
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String channel = row.getString("channelId");
                String logo = "";
                try {
                    logo = Playlist.networkUrl(row.optString("logo"), false);
                } catch (Exception ignored) {}
                writer.accept(
                    new Playlist.Channel(
                        row.getString("name"),
                        locator(id, channel),
                        LABELS[Arrays.asList(ProviderBroker.IDS).indexOf(id)] +
                            " · " +
                            row.optString("category", "Live TV"),
                        logo,
                        source(id) + ":" + channel
                    )
                );
            }
            cancel.check();
            writer.commit();
        } finally {
            writer.abort();
        }
        try {
            guide(id, db, rows, cancel);
        } catch (Exception e) {
            cancel.check();
            Diagnostics.recovered("Provider inline guide", e);
        }
        c.getSharedPreferences("providerEngine", 0)
            .edit()
            .putInt(id + "Count", rows.length())
            .putLong(id + "Refresh", System.currentTimeMillis())
            .remove(id + "Error")
            .apply();
        return rows.length();
    }

    private static void guide(String id, ChannelDatabase db, JSONArray rows, ChannelDatabase.Cancel cancel)
        throws Exception {
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(source(id) + ":");
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String key = source(id) + ":" + row.getString("channelId");
                writer.match(EpgMatch.key(key), key);
                JSONArray programs = row.optJSONArray("programs");
                if (programs == null) continue;
                for (int j = 0; j < programs.length(); j++) {
                    cancel.check();
                    JSONObject p = programs.getJSONObject(j);
                    long start, end;
                    try {
                        start = java.time.Instant.parse(p.getString("start")).toEpochMilli();
                        end = java.time.Instant.parse(p.getString("end")).toEpochMilli();
                    } catch (Exception e) {
                        continue;
                    }
                    if (end <= start) continue;
                    if (writer.programmes >= 50000) throw new IOException("Guide limit");
                    writer.programme(
                        key,
                        start,
                        end,
                        p.optString("title"),
                        p.optString("desc"),
                        p.optString("genre"),
                        ""
                    );
                }
            }
            if (writer.programmes > 0) writer.commit();
        } finally {
            writer.finish();
        }
    }
}
