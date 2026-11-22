package local.tv.home;

import android.content.Context;
import java.io.*;
import java.net.*;

/**
 * Checks the provider feeds in UpdateFeeds (self-hosted first, then GitHub). Authentication is the
 * detached package signature against the APK-pinned key, so the address is never trusted on its own.
 */
final class ProviderUpdates {

    private ProviderUpdates() {}

    static void check(Context c, boolean manual) throws Exception {
        if (!ProviderPackages.configured(c)) return;
        if (
            !manual &&
            (!c.getSharedPreferences("ui", 0).getBoolean("providerAutoUpdates", true) ||
                System.currentTimeMillis() -
                    c.getSharedPreferences("providerEngine", 0).getLong("checked", 0) <
                    12 * 3600_000L)
        ) return;
        c.getSharedPreferences("providerEngine", 0)
            .edit()
            .putLong("checked", System.currentTimeMillis())
            .apply();
        Exception last = null;
        for (UpdateFeeds.Feed feed : UpdateFeeds.providers(
            BuildConfig.MOVIEHUB_FEED_URL,
            BuildConfig.MOVIEHUB_FEED_KEY
        )) {
            try {
                check(c, feed);
                return;
            } catch (java.security.GeneralSecurityException e) {
                throw e; // A bad signature is never retried elsewhere.
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                last = e;
            }
        }
        if (last != null) throw last;
    }

    private static void check(Context c, UpdateFeeds.Feed feed) throws Exception {
        byte[] payload = download(feed, feed.root + "providers.json", ProviderSignature.MAX_PACKAGE);
        byte[] sig = download(feed, feed.root + "providers.sig", 1024);
        // Authenticate even an identical/stale version before deciding there is nothing to do.
        ProviderSignature.verify(
            payload,
            sig,
            java.util.Base64.getDecoder().decode(ProviderPackages.trust(c).getString("publicKey"))
        );
        long high = Math.max(
            ProviderPackages.current(c).getLong("version"),
            c.getSharedPreferences("providerEngine", 0).getLong("highWater", 1)
        );
        if (ProviderPackages.validate(payload).getLong("version") <= high) return;
        ProviderPackages.install(c, payload, sig);
    }

    /** Bounded HTTPS download that may only be redirected to hosts the feed allows. */
    static byte[] download(UpdateFeeds.Feed feed, String address, int max) throws Exception {
        URI uri = URI.create(address);
        String keyAuthority = uri.getAuthority();
        for (int hop = 0; hop < 5; hop++) {
            if (
                !"https".equals(uri.getScheme()) ||
                uri.getUserInfo() != null ||
                uri.getPort() != -1 ||
                !feed.allows(uri.getHost())
            ) {
                throw new IOException("Provider download host rejected");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (!feed.key.isEmpty() && uri.getAuthority().equalsIgnoreCase(keyAuthority)) {
                connection.setRequestProperty("Authorization", "Bearer " + feed.key);
            }
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing provider redirect");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("Provider download unavailable");
                try (
                    InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (
                            Thread.currentThread().isInterrupted() || out.size() + n > max
                        ) throw new IOException("Provider download limit");
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Provider redirect limit");
    }
}
