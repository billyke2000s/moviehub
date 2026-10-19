package local.tv.home;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

/** Native free TV shares the existing channel store, refresh worker, guide and live player. */
final class NativeSources {

    static final String PLUTO = "nativePluto";

    private NativeSources() {}

    static boolean enabled(Context context, boolean providerConnected) {
        return (
            context.getSharedPreferences("ui", 0).getBoolean(PLUTO, true) &&
            !FreeChannels.playlist(context, providerConnected).isEmpty()
        );
    }

    static int refresh(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        NativeHttp http = new NativeHttp();
        cancel.check();
        // Fetch before opening the SQLite write transaction: browsing stays responsive offline.
        List<Playlist.Channel> channels = PlutoSource.catalogue(http);
        cancel.check();
        ChannelDatabase.Writer writer = db.writer("nativepluto", cancel, n -> {});
        try {
            for (Playlist.Channel channel : channels) writer.accept(channel);
            cancel.check();
            writer.commit();
        } finally {
            writer.abort();
        }
        context
            .getSharedPreferences("channelImport", 0)
            .edit()
            .putInt("nativePlutoCount", channels.size())
            .putLong("nativePlutoUpdated", System.currentTimeMillis())
            .apply();
        return channels.size();
    }

    static void guide(ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        NativeHttp http = new NativeHttp();
        List<Playlist.Channel> channels = PlutoSource.catalogue(http);
        java.util.Map<String, String> keys = new java.util.HashMap<>();
        for (Playlist.Channel channel : channels)
            keys.put(PlutoSource.channelId(channel.url), EpgMatch.key(channel.epgId));
        List<Object[]> programmes = new ArrayList<>();
        for (int offset = 0; offset < channels.size(); offset += 20) {
            cancel.check();
            JSONArray batch = PlutoSource.guide(channels, offset, http);
            for (int i = 0; i < batch.length(); i++) {
                JSONObject row = batch.getJSONObject(i);
                String id = row.optString("channelId");
                if (!keys.containsKey(id)) continue;
                JSONArray timelines = row.optJSONArray("timelines");
                if (timelines == null) continue;
                for (int j = 0; j < timelines.length(); j++) {
                    cancel.check();
                    JSONObject item = timelines.getJSONObject(j);
                    long start = java.time.Instant.parse(item.getString("start")).toEpochMilli();
                    long stop = java.time.Instant.parse(item.getString("stop")).toEpochMilli();
                    if (stop <= start) continue;
                    JSONObject episode = item.optJSONObject("episode");
                    programmes.add(new Object[] {
                        "nativepluto:" + id,
                        start,
                        stop,
                        episode == null
                            ? item.optString("title")
                            : episode.optString("name", item.optString("title")),
                        episode == null ? "" : episode.optString("description"),
                        episode == null ? "" : episode.optString("genre"),
                    });
                    if (programmes.size() > 50000) throw new java.io.IOException("Guide too large");
                }
            }
        }
        cancel.check();
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(true);
        try {
            for (java.util.Map.Entry<String, String> key : keys.entrySet())
                writer.match(key.getValue(), "nativepluto:" + key.getKey());
            for (Object[] item : programmes) {
                cancel.check();
                writer.programme(
                    (String) item[0],
                    (Long) item[1],
                    (Long) item[2],
                    (String) item[3],
                    (String) item[4],
                    (String) item[5],
                    ""
                );
            }
            cancel.check();
            if (writer.programmes > 0) writer.commit();
        } finally {
            writer.finish();
        }
    }
}
