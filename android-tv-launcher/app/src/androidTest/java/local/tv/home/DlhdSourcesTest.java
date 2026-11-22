package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DlhdSourcesTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanup() {
        new Secrets(context).remove(DlhdSources.HINTS);
    }

    @Test
    public void primaryAndSeventhPlayerSurviveRestartAndKeepTheirOwnRoutes() throws Exception {
        String module = ProviderPackages.module(context, "dlhd");
        JSONArray rows = new JSONArray();
        for (String prefix : new String[] { "stream", "hub" }) {
            String channel = prefix.equals("stream") ? "38" : "38--player--hub";
            rows.put(
                new JSONObject()
                    .put("channelId", channel)
                    .put("dlhdId", "38")
                    .put("mirror", "https://dlhd.st")
                    .put("playerPath", "/" + prefix + "/stream-38.php")
                    .put("name", "Sky Sports Main Event")
                    .put("programs", new JSONArray().put("not a resolver input"))
            );
        }
        DlhdSources.save(context, module, rows);
        AdapterSources.invalidate();
        JSONObject primary = AdapterSources.resolverRow(context, "dlhd", "38", new NativeHttp());
        JSONObject seventh = AdapterSources.resolverRow(context, "dlhd", "38--player--hub", new NativeHttp());
        assertEquals("/stream/stream-38.php", primary.getString("playerPath"));
        assertEquals("/hub/stream-38.php", seventh.getString("playerPath"));
        assertEquals("https://dlhd.st", seventh.getString("mirror"));
        assertFalse(primary.has("programs"));
        assertNull(DlhdSources.hint(context, module + "changed", "38"));
        String stored = context.getSharedPreferences("accounts", 0).getString(DlhdSources.HINTS, "");
        assertFalse(stored.contains("/hub/stream-38.php"));
    }
}
