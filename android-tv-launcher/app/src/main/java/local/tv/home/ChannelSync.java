package local.tv.home;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Schedules channel imports with WorkManager so they run off the UI thread and survive leaving the screen. */
final class ChannelSync {

    private ChannelSync() {}

    static final long REFRESH_HOURS = 12;
    private static final String PERIODIC = "channel-refresh",
        NOW = "channel-import";

    private static Constraints network() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    /** Keeps an automatic refresh scheduled. Safe to call repeatedly. */
    static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            Job.class,
            REFRESH_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(network())
            .setInitialDelay(REFRESH_HOURS, TimeUnit.HOURS)
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        );
    }

    /** Starts an import now, replacing one that is queued or running. */
    static void refreshNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Job.class)
            .setConstraints(network())
            .build();
        ChannelImporter.markQueued();
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request);
    }

    /**
     * Imports now if channels were never imported or the last success is older than the refresh
     * interval. Free channels alone are imported only when Live TV is opened ({@code liveTvOpened}),
     * so the full public list is not downloaded by people who never use Live TV.
     */
    static void refreshIfStale(Context context, boolean liveTvOpened) {
        Secrets secrets = new Secrets(context);
        boolean provider = secrets.has("m3u") || secrets.has("xtream") || secrets.has("masqueradarr");
        if (!provider && (!liveTvOpened || FreeChannels.playlist(context, false).isEmpty())) return;
        ChannelImporter.Status status = ChannelImporter.status(context);
        long last = status.lastSuccess;
        if (
            !status.running &&
            !status.queued &&
            System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(REFRESH_HOURS)
        ) refreshNow(context);
    }

    public static final class Job extends Worker {

        public Job(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            boolean ok = ChannelImporter.run(getApplicationContext(), () -> {
                if (isStopped()) throw new CancellationException();
            });
            return ok ? Result.success() : getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
