package local.tv.home;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Encrypted event replay retains ZLive's original cache/refusal/decoy state across disposable VMs. */
final class ProviderSessions {

    static final String KEY = "engineZliveReplayState";
    private static final Set<String> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean storageFailed;

    private ProviderSessions() {}

    static void failed(String channel) {
        if (fresh.size() < 128) fresh.add(channel);
    }

    static synchronized JSONObject resolve(
        Context c,
        String module,
        JSONObject row,
        ProviderBroker.Executor engine,
        ProviderBroker.Transport transport,
        NativeHttp http
    ) throws Exception {
        http.check();
        if (storageFailed) throw new IOException("Provider state storage unavailable");
        long now = System.currentTimeMillis();
        String hash = ProviderCodec.call("sha256", module + "\n" + row.optString("domain"));
        JSONObject saved;
        String stored = new Secrets(c).get(KEY);
        if (stored.length() > 2 * 1024 * 1024) throw new IOException("Provider state limit");
        saved = stored.isEmpty() ? new JSONObject() : new JSONObject(stored);
        if (now < saved.optLong("pauseUntil")) throw new IOException("Provider contact cooldown");
        JSONArray previous = saved.optJSONArray("history"),
            history = new JSONArray();
        if (previous != null) {
            if (previous.length() > 128) throw new IOException("Provider history limit");
            for (int i = 0; i < previous.length(); i++) {
                JSONObject event = previous.getJSONObject(i);
                long age = now - event.getLong("at");
                if (age >= 0 && age < 3L * 3600_000) history.put(event);
            }
        }
        if (!hash.equals(saved.optString("module")) && history.length() > 0) throw new IOException(
            "Provider update cooldown: previous contact history retained"
        );
        JSONObject journal = new JSONObject().put("history", history),
            input = new JSONObject(row.toString());
        input.put("fresh", fresh.remove(row.getString("channelId")));
        JSONObject envelope = new JSONObject().put("module", hash).put("history", history);
        boolean[] capped = { false },
            contacted = { false };
        Object value;
        try {
            value = ProviderBroker.run(
                module,
                "resolve",
                input,
                engine,
                request -> {
                    if (history.length() >= 128) {
                        capped[0] = true;
                        throw new IOException("Provider contact budget reached");
                    }
                    if (!request.getString("url").startsWith("https://provider-dns.invalid/")) contacted[0] =
                        true;
                    return transport.request(request);
                },
                http,
                journal
            );
            JSONArray responses = journal.getJSONArray("responses");
            if (responses.length() > 0 && !capped[0]) history.put(
                new JSONObject()
                    .put("slug", row.getString("channelId"))
                    .put("fresh", input.getBoolean("fresh"))
                    .put("at", journal.getLong("at"))
                    .put("responses", responses)
            );
            persist(c, envelope);
        } catch (Exception error) {
            boolean cancelled =
                error instanceof java.io.InterruptedIOException || Thread.currentThread().isInterrupted();
            if (cancelled && contacted[0] && history.length() < 128) history.put(
                new JSONObject()
                    .put("slug", row.getString("channelId"))
                    .put("fresh", input.getBoolean("fresh"))
                    .put("at", journal.getLong("at"))
                    .put("responses", journal.getJSONArray("responses"))
                    .put("cancelled", true)
            );
            if (!cancelled) envelope.put("pauseUntil", now + 60_000);
            persist(c, envelope);
            throw error;
        }
        JSONObject result = (JSONObject) value;
        if (result.has("failure")) throw new IOException(
            "ZLive resolution refused: " + result.getString("failure")
        );
        return result;
    }

    private static void persist(Context c, JSONObject state) throws Exception {
        String bytes = state.toString();
        if (bytes.length() > 2 * 1024 * 1024) {
            storageFailed = true;
            throw new IOException("Provider history limit");
        }
        boolean interrupted = Thread.interrupted();
        try {
            new Secrets(c).put(KEY, bytes);
        } catch (Exception e) {
            storageFailed = true;
            throw e;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
