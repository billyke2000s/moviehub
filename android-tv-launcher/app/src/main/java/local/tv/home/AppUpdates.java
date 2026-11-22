package local.tv.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/**
 * App update feeds (see UpdateFeeds): the self-hosted address first, then GitHub. Account details are
 * never sent; only the feed access key, and only to the self-hosted host.
 */
final class AppUpdates {

    /** A separate test build (-Pexperimental) has its own feeds, so it never offers or installs the normal app. */
    static List<UpdateFeeds.Feed> feeds() {
        return UpdateFeeds.app(
            BuildConfig.MOVIEHUB_FEED_URL,
            BuildConfig.MOVIEHUB_FEED_KEY,
            BuildConfig.EXPERIMENTAL
        );
    }

    static final long MAX_BYTES = 300L * 1024 * 1024;

    static final class Release {

        final long version;
        final String name, sha;
        /** "What's new" text from the feed, plain text; empty for feeds published before it existed. */
        final String notes;
        /** The feed that offered this release; the APK is downloaded from the same place. */
        final UpdateFeeds.Feed feed;

        Release(long version, String name, String sha) {
            this(version, name, sha, "");
        }

        Release(long version, String name, String sha, String notes) {
            this(version, name, sha, notes, feeds().get(feeds().size() - 1));
        }

        Release(long version, String name, String sha, String notes, UpdateFeeds.Feed feed) {
            this.version = version;
            this.name = name;
            this.sha = sha;
            this.notes = notes;
            this.feed = feed;
        }
    }

    /** Asks each feed in turn; the first one that answers decides. */
    static Release check(Context context) throws Exception {
        Exception last = null;
        for (UpdateFeeds.Feed feed : feeds()) {
            try {
                return check(context, feed);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                last = e;
            }
        }
        throw last != null ? last : new IOException("No update feed");
    }

    private static Release check(Context context, UpdateFeeds.Feed feed) throws Exception {
        JSONObject json = new JSONObject(Network.text(feed.root + "update.json", feed.key, false));
        if (!context.getPackageName().equals(json.getString("packageName"))) throw new IOException(
            "Wrong package"
        );
        long version = json.getLong("versionCode");
        String sha = json.getString("sha256");
        if (!sha.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid checksum");
        long installed = context
            .getPackageManager()
            .getPackageInfo(context.getPackageName(), 0)
            .getLongVersionCode();
        String notes = json.optString("notes", "");
        if (notes.length() > 2000) notes = notes.substring(0, 2000) + "…";
        return version > installed
            ? new Release(version, json.getString("versionName"), sha, notes, feed)
            : null;
    }

    static File download(Context context, Release release) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare update");
        File part = new File(directory, "update.part"),
            apk = new File(directory, "update.apk");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            URI current = URI.create(release.feed.root + "home.apk");
            String keyAuthority = current.getAuthority();
            boolean downloaded = false;
            for (int redirects = 0; redirects < 6; redirects++) {
                if (
                    !"https".equalsIgnoreCase(current.getScheme()) || current.getUserInfo() != null
                ) throw new IOException("Invalid update URL");
                HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                // The feed key goes only to the feed's own host, never to a redirect target.
                if (
                    !release.feed.key.isEmpty() && current.getAuthority().equalsIgnoreCase(keyAuthority)
                ) connection.setRequestProperty("Authorization", "Bearer " + release.feed.key);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Invalid redirect");
                        current = current.resolve(location);
                        continue;
                    }
                    if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IOException(
                        "Update download failed"
                    );
                    try (
                        InputStream input = connection.getInputStream();
                        OutputStream output = new FileOutputStream(part)
                    ) {
                        byte[] buffer = new byte[65536];
                        long size = 0;
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                            size += n;
                            if (size > MAX_BYTES) throw new IOException("Update too large");
                            digest.update(buffer, 0, n);
                            output.write(buffer, 0, n);
                        }
                    }
                    downloaded = true;
                    break;
                } finally {
                    connection.disconnect();
                }
            }
            if (!downloaded || !hex(digest.digest()).equalsIgnoreCase(release.sha)) throw new IOException(
                "Checksum mismatch"
            );
            PackageManager manager = context.getPackageManager();
            PackageInfo candidate = manager.getPackageArchiveInfo(
                    part.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES
                ),
                installed = manager.getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES
                );
            if (
                candidate == null ||
                !context.getPackageName().equals(candidate.packageName) ||
                candidate.getLongVersionCode() != release.version ||
                candidate.getLongVersionCode() <= installed.getLongVersionCode()
            ) throw new IOException("Invalid update version");
            if (
                candidate.signingInfo == null ||
                installed.signingInfo == null ||
                !certificates(candidate).equals(certificates(installed))
            ) throw new IOException("Update signing key does not match this installation");
            if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace cached update");
            if (!part.renameTo(apk)) throw new IOException("Cannot prepare APK");
            return apk;
        } finally {
            if (part.exists()) part.delete();
        }
    }

    private static Set<String> certificates(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        for (Signature signature : info.signingInfo.getApkContentsSigners())
            result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
