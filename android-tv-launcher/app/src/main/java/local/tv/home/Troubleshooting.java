package local.tv.home;

import android.widget.LinearLayout;

/** Settings → System → Diagnostics: what is slow on this box. */
final class Troubleshooting {

    private final MainActivity activity;

    Troubleshooting(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * Timings measured since MovieHub started (see {@link Timings}): what is slow on this box.
     * Shows only names, durations and counts; no addresses or account details.
     */
    void show() {
        activity.screen("Diagnostics", activity.settingsScreens::settings);
        activity.page.addView(
            activity.muted(
                android.os.Build.MANUFACTURER +
                    " " +
                    android.os.Build.MODEL +
                    " · Android " +
                    android.os.Build.VERSION.RELEASE +
                    " · " +
                    Runtime.getRuntime().maxMemory() / (1024 * 1024) +
                    " MB for MovieHub",
                14
            )
        );
        activity.page.addView(
            activity.muted(
                "Measured since MovieHub started. Browse, open a title and play something, then come back here.",
                14
            )
        );
        float mbps = Timings.lastMbps;
        diagnosticRow(
            "Last download speed while playing",
            mbps < 0 ? "Nothing played yet" : String.format(java.util.Locale.ROOT, "%.1f Mb/s", mbps)
        );
        java.util.List<Timings.Stat> stats = Timings.snapshot();
        if (stats.isEmpty()) diagnosticRow("Nothing measured yet", "Use the app, then open this page again");
        for (Timings.Stat stat : stats)
            diagnosticRow(
                stat.name,
                seconds(stat.last) +
                    " last  ·  " +
                    seconds(stat.average()) +
                    " average  ·  " +
                    seconds(stat.max) +
                    " slowest  ·  " +
                    stat.count +
                    (stat.count == 1 ? " time" : " times")
            );
        LinearLayout actions = activity.row();
        actions.setPadding(0, activity.dp(12), 0, 0);
        activity.chip(actions, "Refresh", this::show);
        activity.chip(actions, "Reset", () -> {
            Timings.clear();
            show();
        });
        activity.page.addView(actions);
    }

    private void diagnosticRow(String name, String value) {
        LinearLayout row = activity.listRow();
        LinearLayout copy = activity.vertical();
        copy.addView(activity.rowText(name, 17, false, 1));
        copy.addView(activity.rowText(value, 14, true, 2));
        row.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        activity.styleListRow(row, false);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = activity.dp(8);
        activity.page.addView(row, layout);
    }

    private static String seconds(long millis) {
        return millis < 1000
            ? millis + " ms"
            : String.format(java.util.Locale.ROOT, "%.1f s", millis / 1000f);
    }
}
