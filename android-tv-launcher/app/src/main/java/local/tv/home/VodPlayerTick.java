package local.tv.home;

import java.util.Locale;

/** The film player's one-second controls and progress update. Main thread only. */
final class VodPlayerTick implements Runnable {

    private final VlcPlaybackActivity activity;

    VodPlayerTick(VlcPlaybackActivity activity) {
        this.activity = activity;
    }

    public void run() {
        if (activity.vod != null) activity.vod.tick();
        if (activity.player != null) {
            // While a skip is waiting, show where it will land.
            long length = activity.player.getLength(),
                time = activity.skips.pending()
                    ? activity.skips.target()
                    : Math.max(0, activity.player.getTime());
            activity.furthestMs = Math.max(activity.furthestMs, activity.player.getTime());
            activity.timeLeft.setText(length > 0 ? PlayerOverlay.timeLeft(Math.max(0, length - time)) : "—");
            if (
                (activity.vod == null || !activity.vod.rejectedMedia()) &&
                !activity.nextPromptShown &&
                length >= PlaybackEnd.MIN_LENGTH_MS &&
                time > 0 &&
                length - time <= 10000 &&
                length - time > 0 &&
                activity.getIntent().getStringExtra("episodeKey") != null &&
                SourcesScreen.Binge.imdb != null
            ) {
                activity.nextPromptShown = true;
                SidePanel.of(activity, "Up next")
                    .message("Ready for the next episode?")
                    .action("Play next episode", () -> activity.launchNextEpisode())
                    .action("Keep watching", () -> activity.nextCancelled = true)
                    .show();
            }
            // A programme reminder that is due shows over the picture (checked every 15 s).
            if (++activity.reminderTicks % 15 == 0 && !activity.dialogOpen) activity.showDueReminder();
            activity.downloadMbps =
                activity.vod == null
                    ? PlayerOverlay.downloadSpeed(activity.player)
                    : activity.vod.incomingMbps();
            if (activity.downloadMbps >= 0) Timings.lastMbps = activity.downloadMbps;
            String traffic =
                activity.downloadMbps < 0
                    ? "Receiving stream…"
                    : String.format(Locale.ROOT, "Downloading at %.1f Mb/s", activity.downloadMbps);
            if (activity.vod != null) {
                long bytes = activity.vod.incomingBytes();
                traffic =
                    "Source: " +
                    activity.vod.sourceLabel() +
                    "\n" +
                    traffic +
                    (bytes < 0 ? "" : String.format(Locale.ROOT, " · %.2f MB received", bytes / 1000000d));
            }
            activity.speed.setText(traffic);
            activity.timeline.setEnabled(activity.player.isSeekable() && length > 0);
            if (!activity.dragging && length > 0) activity.timeline.setProgress(
                (int) Math.min(1000, (time * 1000) / length)
            );
        }
        if (activity.foreground) activity.overlayHandler.postDelayed(this, 1000);
    }
}
