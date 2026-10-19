package local.tv.home;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Replays pure JS until completion, fulfilling only APK-audited provider HTTP capabilities. */
final class ProviderBroker {

    static final String[] IDS = {
        "stirr",
        "tcl",
        "xumo",
        "lg",
        "whale",
        "distro",
        "vizio",
        "vidaa",
        "roku",
        "plex",
        "dlhd",
        "tubi",
        "samsung",
    };

    private ProviderBroker() {}

    static boolean supported(String id) {
        return Arrays.asList(IDS).contains(id);
    }

    static boolean allowed(String provider, String url) {
        try {
            URI u = URI.create(url);
            if (
                !"https".equals(u.getScheme()) ||
                u.getUserInfo() != null ||
                (u.getPort() != -1 && u.getPort() != 443)
            ) return false;
            String host = u.getHost();
            if (host == null) return false;
            switch (provider) {
                case "samsung":
                    return (
                        host.equals("i.mjh.nz") ||
                        host.equals("jmp2.uk") ||
                        host.endsWith(".samsungtvplus.com") ||
                        host.endsWith(".cloudfront.net") ||
                        host.endsWith(".samsung.com")
                    );
                case "tubi":
                    return host.equals("tubi.io") || host.endsWith(".tubi.io");
                case "dlhd":
                    return true; // Public HTTPS GET only; no app credentials, LAN or POST capability.
                case "stirr":
                    return host.equals("stirr.com") && u.getPath().startsWith("/api/");
                case "tcl":
                    return host.equals("gateway-prod.ideonow.com") && u.getPath().startsWith("/api/");
                case "xumo":
                    return host.equals("valencia-app-mds.xumo.com") && u.getPath().startsWith("/v2/");
                case "lg":
                    return host.equals("api.lgchannels.com") && u.getPath().startsWith("/api/v1.0/");
                case "whale":
                    return host.equals("rlaxx.zeasn.tv") && u.getPath().startsWith("/livetv/api/");
                case "distro":
                    return (
                        host.equals("tv.jsrdn.com") &&
                        (u.getPath().startsWith("/tv_v5/") || u.getPath().startsWith("/epg/"))
                    );
                case "vizio":
                    return (
                        host.equals("watchfreeplus-epg-prod.smartcasttv.com") &&
                        u.getPath().startsWith("/api/")
                    );
                case "vidaa":
                    return host.equals("vtvapp-ovp.vidaahub.com") || host.endsWith(".vidaahub.com");
                case "roku":
                    return host.equals("therokuchannel.roku.com") || host.equals("content.sr.roku.com");
                case "plex":
                    return (
                        host.equals("watch.plex.tv") ||
                        host.equals("plex.tv") ||
                        host.equals("epg.provider.plex.tv")
                    );
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static void publicHost(String host) throws Exception {
        if (
            host == null || host.equalsIgnoreCase("localhost") || host.endsWith(".local")
        ) throw new IOException("Local target rejected");
        for (InetAddress ip : InetAddress.getAllByName(host)) {
            if (
                ip.isAnyLocalAddress() ||
                ip.isLoopbackAddress() ||
                ip.isLinkLocalAddress() ||
                ip.isSiteLocalAddress() ||
                ip.isMulticastAddress() ||
                (ip.getAddress().length == 16 && (ip.getAddress()[0] & 0xfe) == 0xfc)
            ) throw new IOException("Private target rejected");
        }
    }

    interface Executor {
        String call(String module, String operation, String payload) throws Exception;
    }

    interface Transport {
        JSONObject request(JSONObject request) throws Exception;
    }

    static Object run(
        String module,
        String operation,
        JSONObject row,
        Executor engine,
        Transport transport,
        NativeHttp http
    ) throws Exception {
        JSONArray responses = new JSONArray();
        JSONObject input = new JSONObject()
            .put("now", System.currentTimeMillis())
            .put("seed", UUID.randomUUID().toString())
            .put("row", row == null ? new JSONObject() : row)
            .put("responses", responses);
        long deadline =
            System.nanoTime() + (operation.equals("catalogue") ? 240_000_000_000L : 45_000_000_000L);
        int requests = 0,
            bytes = 0;
        for (int round = 0; round < 64; round++) {
            http.check();
            if (System.nanoTime() > deadline) throw new IOException("Provider operation timeout");
            JSONObject step = new JSONObject(engine.call(module, operation, input.toString()));
            if (step.optBoolean("done")) return step.get("value");
            JSONArray pending = step.getJSONArray("requests");
            if (pending.length() == 0) throw new IOException("Provider stalled");
            for (int i = 0; i < pending.length(); i++) {
                if (++requests > 64) throw new IOException("Provider request limit");
                http.check();
                JSONObject request = pending.getJSONObject(i),
                    record = new JSONObject().put("request", request);
                try {
                    JSONObject response = transport.request(request);
                    record.put("response", response);
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (Exception e) {
                    http.check();
                    record.put("error", true);
                }
                if (record.has("response")) bytes += record.getJSONObject("response").toString().length();
                if (bytes > 4 * 1024 * 1024) throw new IOException("Provider aggregate limit");
                record.put("completedAt", System.currentTimeMillis());
                responses.put(record);
            }
        }
        throw new IOException("Provider continuation limit");
    }

    static Object call(Context c, String id, String operation, JSONObject row, NativeHttp http)
        throws Exception {
        if (!supported(id)) throw new IOException("Unsupported provider");
        String module = ProviderPackages.module(c, id);
        return run(
            module,
            operation,
            row,
            (script, op, payload) -> ProviderBridge.call(c, script, op, payload),
            request -> {
                String target = request.getString("url");
                if (target.startsWith("https://provider-timer.invalid/")) {
                    int ms = Integer.parseInt(target.substring("https://provider-timer.invalid/".length()));
                    if (ms < 0 || ms > 3000) throw new IOException("Timer capability rejected");
                    Thread.sleep(ms);
                    http.check();
                    return new JSONObject()
                        .put("status", 200)
                        .put("url", target)
                        .put("headers", new JSONObject())
                        .put("text", "");
                }
                Map<String, String> headers = new LinkedHashMap<>();
                JSONObject h = request.getJSONObject("headers");
                for (Iterator<String> keys = h.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    headers.put(key, h.getString(key));
                }
                if (id.equals("dlhd") && !request.getString("method").equals("GET")) throw new IOException(
                    "Read-only provider capability"
                );
                return http.exchange(
                    request.getString("url"),
                    request.getString("method"),
                    headers,
                    request.getString("body"),
                    url -> allowed(id, url)
                );
            },
            http
        );
    }
}
