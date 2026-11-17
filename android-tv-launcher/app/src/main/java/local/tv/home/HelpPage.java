package local.tv.home;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.StatFs;
import android.provider.Settings;
import android.widget.LinearLayout;
import java.util.List;

/**
 * Settings → System → Help: the family's troubleshooting page. It lists only problems the person
 * at the TV can fix themselves, each with a one-press fix (see {@link HelpChecks} for the rules).
 * The technical details are in Settings → Setup → Engineer view ({@link Troubleshooting}).
 */
final class HelpPage {

    static final String TITLE = "Help";

    private final MainActivity activity;
    /** The last speed test's result in Mb/s while MovieHub is open; below 0 when not run. */
    private volatile float testedMbps = -1;
    private volatile boolean testing;

    HelpPage(MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        LinearLayout body = activity.settingsScreens.settingsLayout(
            TITLE,
            activity.settingsScreens::settings,
            "System"
        );
        SettingsScreens settings = activity.settingsScreens;
        body.addView(activity.muted("Problems on this box that you can fix yourself.", 14));
        HelpChecks.Facts facts = facts();
        List<HelpChecks.Problem> problems = HelpChecks.problems(facts);
        if (problems.isEmpty()) settings.settingCard(
            body,
            "No problems found",
            facts.mbps < 0
                ? "This box is online, has space and is up to date. Test the internet speed below if films keep stopping."
                : "This box is online, fast enough, has space and is up to date.",
            null,
            true,
            null,
            null
        );
        for (HelpChecks.Problem problem : problems)
            settings.settingCard(body, problem.title, problem.detail, null, false, problem.action, () ->
                fix(problem.fix, facts)
            );
        settings.settingCard(
            body,
            "Internet speed",
            testing
                ? "Testing… this takes about 10 seconds"
                : "Checks whether your internet is fast enough for films",
            testedMbps < 0 ? null : String.format(java.util.Locale.ROOT, "%.0f Mb/s", testedMbps),
            false,
            testing ? null : "Test",
            this::runSpeedTest
        );
        activity.settingsStyle(body);
    }

    /** What the box knows now, without waiting on the network (the speed test is on request). */
    private HelpChecks.Facts facts() {
        HelpChecks.Facts facts = new HelpChecks.Facts();
        facts.connected = connected();
        facts.mbps = testedMbps >= 0 ? testedMbps : Timings.lastMbps;
        try {
            StatFs space = new StatFs(activity.getFilesDir().getAbsolutePath());
            facts.freeBytes = space.getAvailableBytes();
            facts.totalBytes = space.getTotalBytes();
        } catch (RuntimeException e) {
            Diagnostics.recovered("Reading free space", e);
        }
        facts.preferredQuality = activity.prefs.getInt(AutoPlayback.QUALITY, 1080);
        facts.updateReady = activity.updates.availableUpdate != null;
        return facts;
    }

    /** True when the box has a network that reaches the internet (Wi-Fi or cable). */
    private boolean connected() {
        try {
            ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
            android.net.Network network = manager.getActiveNetwork();
            if (network == null) return false; // no Wi-Fi and no cable
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return (
                capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            );
        } catch (RuntimeException e) {
            // Unknown is not "offline": never tell the family a problem that may not be there.
            Diagnostics.recovered("Reading the network state", e);
            return true;
        }
    }

    private void fix(HelpChecks.Fix fix, HelpChecks.Facts facts) {
        switch (fix) {
            case WIFI_SETTINGS:
                open(Settings.ACTION_WIFI_SETTINGS);
                break;
            case STORAGE_SETTINGS:
                open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                break;
            case LOWER_QUALITY:
                int quality = HelpChecks.lowerQuality(facts);
                activity.prefs.edit().putInt(AutoPlayback.QUALITY, quality).apply();
                activity.toast("Films will now play in " + (quality >= 1080 ? "1080p" : "720p"));
                show();
                break;
            case UPDATE:
                activity.updates.offerUpdate();
                break;
        }
    }

    private void open(String action) {
        try {
            activity.startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception again) {
                activity.toast("This box's settings can't be opened from here");
            }
        }
    }

    private void runSpeedTest() {
        if (testing) return;
        testing = true;
        show();
        activity.async(
            () -> {
                try {
                    SpeedTest.Result result = SpeedTest.run();
                    testedMbps = result.mbps;
                } catch (Exception e) {
                    Diagnostics.recovered("Help speed test", e);
                } finally {
                    testing = false;
                }
                return testedMbps;
            },
            mbps -> show()
        );
    }
}
