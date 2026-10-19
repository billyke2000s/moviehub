package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, cancellable HTTPS requests for native provider APIs. Never logs URLs or responses. */
final class NativeHttp implements PlutoSource.Http {

    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
        HttpURLConnection current = connection;
        if (current != null) current.disconnect();
    }

    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException(
            "Cancelled"
        );
    }

    public String get(String url, String bearer) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (bearer != null) headers.put("Authorization", "Bearer " + bearer);
        return getHeaders(url, headers);
    }

    String getHeaders(String url, Map<String, String> headers) throws Exception {
        URI current = URI.create(Playlist.networkUrl(url, false));
        String authority = current.getAuthority();
        for (int redirects = 0; redirects < 4; redirects++) {
            check();
            HttpURLConnection c = (HttpURLConnection) current.toURL().openConnection();
            connection = c;
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", PlutoSource.USER_AGENT);
            if (authority.equalsIgnoreCase(current.getAuthority())) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0) {
                        throw new IOException("Invalid provider header");
                    }
                    c.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            try {
                check();
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    current = URI.create(Playlist.networkUrl(current.resolve(location).toString(), false));
                    continue;
                }
                if (status != 200) throw new IOException("Provider unavailable (" + status + ")");
                try (
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        check();
                        if (out.size() + count > 8 * 1024 * 1024) throw new IOException(
                            "Catalogue too large"
                        );
                        out.write(buffer, 0, count);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                c.disconnect();
                connection = null;
            }
        }
        throw new IOException("Too many redirects");
    }
}
