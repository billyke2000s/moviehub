package local.tv.home;

import android.content.Context;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps advertised player identities with the stored channels between process restarts. */
final class DlhdSources {

    static final String HINTS = "engineDlhdPlayerHints";

    private DlhdSources() {}

    static void save(Context context, String module, JSONArray rows) throws Exception {
        JSONObject hints = new JSONObject();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!row.has("playerPath")) continue;
            JSONObject hint = new JSONObject();
            for (String field : new String[] { "channelId", "dlhdId", "playerPath", "mirror" })
                hint.put(field, row.getString(field));
            hints.put(row.getString("channelId"), hint);
        }
        String value = new JSONObject()
            .put("module", ProviderCodec.call("sha256", module))
            .put("rows", hints)
            .toString();
        if (value.length() > 256 * 1024) throw new IOException("DaddyLive player hint limit");
        new Secrets(context).put(HINTS, value);
    }

    static JSONObject hint(Context context, String module, String channel) {
        try {
            String saved = new Secrets(context).get(HINTS);
            if (saved.isEmpty()) return null;
            JSONObject value = new JSONObject(saved);
            if (!ProviderCodec.call("sha256", module).equals(value.getString("module"))) return null;
            // The resolver checks the current watch page again before using this route.
            return value.getJSONObject("rows").optJSONObject(channel);
        } catch (Exception failure) {
            Diagnostics.recovered("Reading DaddyLive player hints", failure);
            return null;
        }
    }
}
