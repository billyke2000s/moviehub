package local.tv.home;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Diagnostic JVM host for the production relay; never packaged in the app. */
public final class ProviderRelayProbe {

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try (ProviderRelay relay = new ProviderRelay(new EngineSources.Stream(new JSONObject(input.readLine())))) {
            System.out.println(new JSONObject().put("relay", relay.url));
            System.out.flush();
            input.readLine();
        }
    }
}

// Only Android-dependent seams are replaced. HTTP transport, URL validation, playlist sessions,
// manifest rewriting and PNG decoding above run their actual production implementations.
final class EngineSources {

    static final class Stream {

        final String url;
        final JSONObject headers;
        final JSONObject playlistSession;

        Stream(JSONObject value) {
            url = value.getString("url");
            headers = value.optJSONObject("headers") == null ? new JSONObject() : value.getJSONObject("headers");
            playlistSession = value.optJSONObject("playlistSession");
        }
    }
}

final class ProviderBroker {

    static void publicHost(String host) throws Exception {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new java.io.IOException("Private upstream rejected");
            }
        }
    }
}

final class Diagnostics {

    static void recovered(String where, Exception error) {
        System.err.println(where + ": " + error.getClass().getSimpleName());
    }
}
