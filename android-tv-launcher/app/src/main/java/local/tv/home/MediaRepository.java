package local.tv.home;

import java.util.*;
import org.json.*;

final class MediaRepository {

    final Secrets secrets;
    private final CatalogueCache catalogue = new CatalogueCache();

    MediaRepository(Secrets secrets) {
        this.secrets = secrets;
    }

    static JSONObject connectXtream(String server, String username, String password, boolean http)
        throws Exception {
        String safe = Playlist.networkUrl(server, http).replaceAll("/+$", "");
        JSONObject response = new JSONObject(
            Network.text(
                safe + "/player_api.php?username=" + Network.q(username) + "&password=" + Network.q(password),
                null,
                http
            )
        );
        JSONObject account = response.optJSONObject("user_info");
        if (account == null || account.optInt("auth") != 1) throw new IllegalArgumentException(
            "Provider rejected the account"
        );
        String status = account.optString("status", "");
        if (!status.isEmpty() && !status.equalsIgnoreCase("Active")) throw new IllegalArgumentException(
            "Provider account is inactive"
        );
        String format = "ts";
        JSONArray formats = account.optJSONArray("allowed_output_formats");
        if (formats != null && formats.length() > 0) {
            boolean ts = false,
                hls = false;
            for (int i = 0; i < formats.length(); i++) {
                ts |= "ts".equalsIgnoreCase(formats.optString(i));
                hls |=
                    "m3u8".equalsIgnoreCase(formats.optString(i)) ||
                    "hls".equalsIgnoreCase(formats.optString(i));
            }
            if (!ts && hls) format = "m3u8";
            else if (!ts) throw new IllegalArgumentException("Provider has no supported live format");
        }
        return new JSONObject()
            .put("server", safe)
            .put("username", username)
            .put("password", password)
            .put("format", format);
    }

    JSONObject premiumize(String path, String token) throws Exception {
        JSONObject result = new JSONObject(
            Network.text("https://www.premiumize.me/api/" + path, token, false)
        );
        if (!"success".equals(result.optString("status"))) throw new Exception(
            "Premiumize request was not accepted"
        );
        return result;
    }

    static String tmdbUrl(String path, String key) throws Exception {
        if (!key.matches("(?i)[0-9a-f]{32}")) throw new IllegalArgumentException(
            "Enter the 32-character TMDB API key (v3), not the Read Access Token"
        );
        return (
            "https://api.themoviedb.org/3/" +
            path +
            (path.contains("?") ? "&" : "?") +
            "api_key=" +
            Network.q(key)
        );
    }

    JSONObject tmdb(String path) throws Exception {
        String key = secrets.get("tmdb");
        if (key.isEmpty()) throw new Exception("Connect TMDB first");
        String request = tmdbUrl(path + (path.contains("?") ? "&" : "?") + "language=en-US", key);
        String body = catalogue.get(request, () -> {
            long started = Timings.start();
            String response = Network.text(request, null, false);
            // Parse before caching: a malformed response must not make repeated visits fail.
            new JSONObject(response);
            Timings.record("TMDB request", started);
            return response;
        });
        // Callers receive independent objects: episode/detail decoration must not mutate the cache.
        return new JSONObject(body);
    }

    void trimMemory() {
        catalogue.clear();
    }
}
