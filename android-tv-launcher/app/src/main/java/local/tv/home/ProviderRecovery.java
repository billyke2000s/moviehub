package local.tv.home;

import android.content.Context;
import android.os.SystemClock;

/**
 * Opportunistic repair after a provider-engine playback error. Failover is never
 * held while we check the existing signed update feed. This cannot invent new
 * player code or install an unsigned transform.
 */
final class ProviderRecovery {

    private static final long RETRY_MS = 15 * 60_000L;
    private static long lastAttemptElapsed;
    private static boolean running;

    private ProviderRecovery() {}

    static synchronized boolean reserve(long now) {
        if (running || (lastAttemptElapsed > 0 && now - lastAttemptElapsed < RETRY_MS)) {
            return false;
        }
        running = true;
        lastAttemptElapsed = now;
        return true;
    }

    private static synchronized void finish() {
        running = false;
    }

    static void afterResolveFailure(Context context) {
        Context app = context.getApplicationContext();
        if (
            !ProviderPackages.configured(app) ||
            !app.getSharedPreferences("ui", 0).getBoolean("providerAutoUpdates", true) ||
            !reserve(SystemClock.elapsedRealtime())
        ) return;
        Thread worker = new Thread(
            () -> {
                try {
                    long before = ProviderPackages.current(app).getLong("version");
                    // The existing feed is HTTPS pinned and RSA-signed. Manual skips
                    // the 12-hour importer throttle, but this caller has its own
                    // 15-minute cooldown across *all* provider failures.
                    ProviderUpdates.check(app, true);
                    if (ProviderPackages.current(app).getLong("version") > before) {
                        AdapterSources.invalidate();
                    }
                } catch (Exception error) {
                    Diagnostics.recovered("Signed provider repair unavailable", error);
                } finally {
                    finish();
                }
            },
            "MovieHub signed provider repair"
        );
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            finish();
            Diagnostics.recovered("Scheduling signed provider repair", error);
        }
    }
}
