package local.tv.home;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Settings → Setup: the last tab of Settings, for whoever looks after the box. Everything set up
 * once, or only for fixing problems, lives here so the other tabs hold only what the family uses:
 * Premiumize and its library, the free channel services, extra guide sections, the parental lock,
 * clearing watch history, the engineer view and the TMDB credit. No PIN: nobody needs to come here.
 *
 * <p>Only where these are shown changed; every preference, account and saved list is kept.
 */
final class SetupScreens {

    /** The Settings sidebar tab this class fills. */
    static final String TAB = "Setup";

    private final MainActivity activity;
    private final SettingsScreens settings;

    SetupScreens(MainActivity activity) {
        this.activity = activity;
        this.settings = activity.settingsScreens;
    }

    /** The Setup tab's cards, in {@code body} (the Settings tab area). */
    void fill(LinearLayout body) {
        body.removeAllViews();
        settings.heading(body, "Setup");
        body.addView(
            activity.muted(
                "Set once by whoever looks after this box. Nothing here is needed to watch TV or films.",
                14
            )
        );
        Runnable back = settings::setup;

        settings.heading(body, "Accounts");
        settings.accountCard(body, "Premiumize", "Playback sources and your cloud library");
        // Both hosted services are independent options, even before first connection.
        settings.accountCard(
            body,
            "Masqueradarr",
            "Connect the M3U export from your own Masqueradarr server"
        );

        settings.heading(body, "Live TV");
        settings.accountCard(
            body,
            NuvioSports.SERVICE,
            "Optional live sports matches and 24/7 channels from a Nuvio Live Sports server"
        );
        settings.settingCard(
            body,
            "Free channels",
            "Which free services and countries' channel lists Live TV uses",
            FreeChannelsPage.servicesOn(activity.prefs) +
                " of " +
                FreeChannelsPage.servicesTotal() +
                " services on",
            FreeChannelsPage.servicesOn(activity.prefs) > 0,
            "Open",
            () -> new FreeChannelsPage(activity).show()
        );
        settings.settingCard(
            body,
            "More channel sections",
            "International channels by country, and the groups from your own channel lists",
            null,
            false,
            "Open",
            () ->
                SidePanel.of(activity, "More channel sections")
                    .option("International", activity.liveTv.panels::countries)
                    .option("Channel groups from your lists", activity.liveTv.panels::groups)
                    .show()
        );
        boolean lockOn = ParentalLock.hasPin(activity);
        settings.settingCard(
            body,
            "Parental lock",
            "A PIN for chosen Live TV channels and guide sections, or for all films and series",
            !lockOn ? "Off" : ParentalLock.filmsLocked(activity) ? "On · films and series locked" : "On",
            lockOn,
            "Manage",
            () -> activity.liveTv.panels.parentalLock(back)
        );

        settings.heading(body, "Films and series");
        settings.settingCard(
            body,
            "Links you added",
            "Your own video links, for films and for series",
            null,
            false,
            "Open",
            () ->
                SidePanel.of(activity, "Links you added")
                    .option("Films", () -> activity.libraryScreens.savedLibrary("Movies"))
                    .option("Series", () -> activity.libraryScreens.savedLibrary("Series"))
                    .show()
        );
        if (activity.secrets.has("premiumize")) settings.settingCard(
            body,
            "Premiumize cloud",
            "Your Premiumize files and downloads",
            null,
            false,
            "Open",
            () ->
                SidePanel.of(activity, "Premiumize cloud")
                    .option("Cloud files", () -> activity.cloudScreens.cloud("", "Movies"))
                    .option("Transfers", () -> activity.cloudScreens.transfers("Movies"))
                    .show()
        );
        settings.settingCard(
            body,
            "Clear watch history",
            "Empties Continue watching on this box. My list is kept",
            null,
            false,
            "Clear",
            () -> clearHistory(activity, back)
        );

        settings.heading(body, "Problems");
        settings.settingCard(
            body,
            "Engineer view",
            "Speed test, connection checks, player and provider logs, recent errors. Technical",
            null,
            false,
            "Open",
            activity.troubleshooting::show
        );

        settings.heading(body, "About");
        settings.settingCard(
            body,
            "Metadata",
            "Uses the TMDB API but is not endorsed or certified by TMDB",
            null,
            false,
            "Details",
            () ->
                SidePanel.of(activity, "Metadata attribution")
                    .message(
                        "The Movie Database (TMDB)\nhttps://www.themoviedb.org\n\nMetadata is separate from your playback sources."
                    )
                    .action("Close", null)
                    .show()
        );
        activity.settingsStyle(body);
    }

    /** Asks first, then removes saved playback progress on this box; My List is kept. */
    static void clearHistory(MainActivity activity, Runnable done) {
        SidePanel.of(activity, "Clear viewing history?")
            .message("This removes saved playback progress on this device. My List is kept.")
            .action("Clear history", () -> {
                activity.getSharedPreferences("positions", Context.MODE_PRIVATE).edit().clear().apply();
                try {
                    activity.secrets.put("recentPlayback", "[]");
                    activity.toast("Watch history cleared");
                } catch (Exception e) {
                    activity.toast("Could not clear history");
                }
                if (done != null) done.run();
            })
            .action("Cancel", null)
            .show();
    }
}
