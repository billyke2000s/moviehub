package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Over-the-air film and series scraper updates. A signed package (ScraperBundle) is downloaded from
 * the self-hosted feed, checked against the APK-pinned key in providers/trust.json, unpacked into the
 * app's private storage and used by FreeVodProviders in place of the APK's own python/ folder.
 *
 * Safety rules:
 * - nothing is used unless the signature over the exact zip bytes is valid;
 * - versions only go up (a high-water mark), so an old package cannot be replayed;
 * - a package that fails to import on the box is rejected and the previous one (or the APK's own
 *   scrapers) is used instead, automatically;
 * - a package built from older app source than the installed APK is ignored.
 */
final class ScraperPackages {

    private static final Object LOCK = new Object();
    static final long CHECK_HOURS = 6;
    private static final String PREFS = "scraperPackages";

    private ScraperPackages() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, 0);
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), "scrapers");
    }

    private static File folder(Context c, long version) {
        return new File(root(c), Long.toString(version));
    }

    private static Set<String> rejected(Context c) {
        return new HashSet<>(prefs(c).getStringSet("rejected", Collections.emptySet()));
    }

    /**
     * The python folder FreeVodProviders should put first on the import path, or "" for the APK's
     * own scrapers. Falls back to the previous package when the active one was rejected.
     */
    static String activePath(Context c) {
        synchronized (LOCK) {
            Set<String> rejected = rejected(c);
            for (String key : new String[] { "active", "previous" }) {
                long version = prefs(c).getLong(key, 0);
                if (version <= 0 || rejected.contains(Long.toString(version))) continue;
                File dir = folder(c, version);
                long[] manifest = ScraperBundle.installedManifest(dir);
                if (
                    manifest != null &&
                    manifest[0] == version &&
                    manifest[1] == ScraperBundle.API &&
                    manifest[2] >= BuildConfig.VERSION_CODE &&
                    new File(dir, "python/moviehub_vod.py").isFile()
                ) return new File(dir, "python").getAbsolutePath();
            }
            return "";
        }
    }

    /** Marks the package at path as broken on this box; it will not be used again. */
    static void reject(Context c, String path, String reason) {
        synchronized (LOCK) {
            String version = new File(path).getParentFile().getName();
            Set<String> rejected = rejected(c);
            rejected.add(version);
            prefs(c)
                .edit()
                .putStringSet("rejected", rejected)
                .putString("lastProblem", version + ": " + reason)
                .apply();
        }
        Diagnostics.recovered(
            "Scraper package " + new File(path).getParentFile().getName(),
            new IOException(reason)
        );
    }

    /** One-line state for diagnostics: active version or "built-in", plus the last problem. */
    static String describe(Context c) {
        String path = activePath(c);
        String state = path.isEmpty() ? "built-in" : new File(path).getParentFile().getName();
        String problem = prefs(c).getString("lastProblem", "");
        return problem.isEmpty() ? state : state + " (last problem: " + problem + ")";
    }

    static void check(Context c, boolean manual) throws Exception {
        if (!ProviderPackages.configured(c)) return; // No signing key in this APK.
        List<UpdateFeeds.Feed> feeds = UpdateFeeds.scrapers(
            BuildConfig.MOVIEHUB_FEED_URL,
            BuildConfig.MOVIEHUB_FEED_KEY,
            BuildConfig.EXPERIMENTAL
        );
        if (feeds.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (
            !manual && now - prefs(c).getLong("checked", 0) < TimeUnit.HOURS.toMillis(CHECK_HOURS) - 60_000
        ) return;
        prefs(c).edit().putLong("checked", now).apply();
        UpdateFeeds.Feed feed = feeds.get(0);
        byte[] zip = ProviderUpdates.download(feed, feed.root + "scrapers.zip", ScraperBundle.MAX_ZIP);
        byte[] sig = ProviderUpdates.download(feed, feed.root + "scrapers.sig", 1024);
        ProviderSignature.verify(
            zip,
            sig,
            Base64.getDecoder().decode(ProviderPackages.trust(c).getString("publicKey")),
            ScraperBundle.MAX_ZIP
        );
        ScraperBundle bundle = ScraperBundle.read(zip);
        if (bundle.version <= prefs(c).getLong("highWater", 0)) return;
        if (!bundle.compatible(BuildConfig.VERSION_CODE)) return;
        install(c, bundle);
    }

    static void install(Context c, ScraperBundle bundle) throws IOException {
        synchronized (LOCK) {
            if (bundle.version <= prefs(c).getLong("highWater", 0)) throw new IOException(
                "Scraper downgrade rejected"
            );
            File root = root(c);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create scraper storage");
            File temp = new File(root, bundle.version + ".tmp");
            delete(temp);
            bundle.extractTo(temp);
            File target = folder(c, bundle.version);
            delete(target);
            if (!temp.renameTo(target)) {
                delete(temp);
                throw new IOException("Cannot activate scraper package");
            }
            long active = prefs(c).getLong("active", 0);
            boolean activeUsable = active > 0 && !rejected(c).contains(Long.toString(active));
            if (
                !prefs(c)
                    .edit()
                    .putLong("previous", activeUsable ? active : prefs(c).getLong("previous", 0))
                    .putLong("active", bundle.version)
                    .putLong("highWater", bundle.version)
                    .commit()
            ) throw new IOException("Scraper package state failed");
            prune(c);
        }
    }

    /** Goes back to the previous package (or the APK's own scrapers) and stops using the current one. */
    static void rollback(Context c) {
        synchronized (LOCK) {
            long active = prefs(c).getLong("active", 0);
            if (active > 0) {
                Set<String> rejected = rejected(c);
                rejected.add(Long.toString(active));
                prefs(c).edit().putStringSet("rejected", rejected).apply();
            }
        }
    }

    /** Keeps only the active and previous folders. */
    private static void prune(Context c) {
        Set<String> keep = new HashSet<>(
            Arrays.asList(
                Long.toString(prefs(c).getLong("active", 0)),
                Long.toString(prefs(c).getLong("previous", 0))
            )
        );
        File[] children = root(c).listFiles();
        if (children == null) return;
        for (File child : children) if (!keep.contains(child.getName())) delete(child);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    /** Background check every few hours, whether or not Live TV is used. */
    static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(Job.class, CHECK_HOURS, TimeUnit.HOURS)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "scraper-updates",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        );
    }

    public static final class Job extends Worker {

        public Job(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            try {
                check(getApplicationContext(), false);
            } catch (Exception e) {
                Diagnostics.recovered("Scraper package update", e);
            }
            return Result.success();
        }
    }
}
