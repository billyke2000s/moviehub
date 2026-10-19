package local.tv.home;

import android.widget.LinearLayout;

/** Uses the existing settings sidebar/cards. Experimental providers are opt-in. */
final class ProviderSettings {

    private final MainActivity activity;

    ProviderSettings(MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        SettingsScreens settings = activity.settingsScreens;
        LinearLayout body = settings.settingsLayout("Provider engine", settings::settings, "Accounts");
        settings.settingCard(
            body,
            "Local provider engine",
            "Isolated JavaScript runtime · no separate server",
            "Runtime 2",
            true,
            "Check version",
            () -> {
                activity.async(
                    () -> ProviderPackages.current(activity).getLong("version"),
                    version -> activity.toast("Provider package " + version)
                );
            }
        );
        boolean on = activity.prefs.getBoolean(EngineSources.PREF, false);
        settings.settingCard(
            body,
            "FreeLiveSports",
            "Experimental · regional availability and TV playback need verification",
            on ? "On" : "Off",
            on,
            on ? "Turn off" : "Turn on",
            () -> {
                activity.prefs.edit().putBoolean(EngineSources.PREF, !on).apply();
                ChannelSync.refreshNow(activity);
                show();
            }
        );
        android.content.SharedPreferences status = activity.getSharedPreferences("providerEngine", 0);
        long last = status.getLong("flsRefresh", 0);
        settings.settingCard(
            body,
            "Provider status",
            "Last successful FreeLiveSports catalogue: " + status.getInt("flsCount", 0) + " channels",
            last == 0
                ? "No successful refresh"
                : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(last)),
            last > 0,
            "Refresh Providers",
            () -> {
                ChannelSync.refreshNow(activity);
                activity.toast("Provider refresh queued");
            }
        );
        for (int i = 0; i < ProviderBroker.IDS.length; i++) {
            String id = ProviderBroker.IDS[i],
                label = AdapterSources.LABELS[i];
            boolean enabled = activity.prefs.getBoolean(AdapterSources.pref(id), false);
            long refreshed = status.getLong(id + "Refresh", 0);
            String detail =
                refreshed == 0
                    ? "No successful refresh"
                    : status.getInt(id + "Count", 0) +
                      " channels · " +
                      java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(refreshed));
            if (status.contains(id + "Error")) detail += " · Refresh failed; cached channels kept";
            settings.settingCard(
                body,
                label,
                "Experimental local upstream adapter · " + detail,
                enabled ? "On" : "Off",
                enabled,
                enabled ? "Turn off" : "Turn on",
                () -> {
                    activity.prefs.edit().putBoolean(AdapterSources.pref(id), !enabled).apply();
                    AdapterSources.invalidate();
                    ChannelSync.refreshNow(activity);
                    show();
                }
            );
        }
        String domain = activity.prefs.getString("engineZliveDomain", "");
        settings.settingCard(
            body,
            "ZLive domain",
            "Use the provider's official domain; leave empty to follow upstream. Existing cooldowns remain active.",
            domain.isEmpty() ? "Upstream default" : domain,
            true,
            "Change",
            () -> {
                LinearLayout form = activity.vertical();
                android.widget.EditText field = activity.field(
                    form,
                    "Official domain (optional)",
                    false,
                    domain
                );
                android.app.Dialog dialog = activity.form("ZLive domain", form);
                form.addView(
                    activity.button("Save", () -> {
                        try {
                            String value = field
                                .getText()
                                .toString()
                                .trim()
                                .toLowerCase(java.util.Locale.ROOT);
                            if (!value.isEmpty()) {
                                java.net.URI u = java.net.URI.create(
                                    ProviderUrls.playback(value.contains("://") ? value : "https://" + value)
                                );
                                if (
                                    u.getUserInfo() != null ||
                                    u.getPort() != -1 ||
                                    u.getQuery() != null ||
                                    u.getFragment() != null ||
                                    !(u.getPath().isEmpty() || u.getPath().equals("/"))
                                ) throw new IllegalArgumentException();
                                value = u.getHost();
                                if (
                                    !value.matches(
                                        "(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]+"
                                    ) ||
                                    value.endsWith(".local") ||
                                    value.endsWith(".localhost")
                                ) throw new IllegalArgumentException();
                            }
                            activity.prefs.edit().putString("engineZliveDomain", value).apply();
                            AdapterSources.invalidate();
                            ChannelSync.refreshNow(activity);
                            dialog.dismiss();
                            show();
                        } catch (Exception e) {
                            activity.toast("Enter an HTTPS provider domain without an account, port or path");
                        }
                    })
                );
                form.addView(activity.button("Cancel", dialog::dismiss));
            }
        );
        boolean trust = ProviderPackages.configured(activity);
        boolean auto = activity.prefs.getBoolean("providerAutoUpdates", true);
        settings.settingCard(
            body,
            "Automatic provider updates",
            trust
                ? "Verified packages checked during channel refresh"
                : "Unavailable until a trusted update signing key is configured",
            trust ? (auto ? "On" : "Off") : "Not configured",
            trust && auto,
            auto ? "Turn off" : "Turn on",
            () -> {
                activity.prefs.edit().putBoolean("providerAutoUpdates", !auto).apply();
                show();
            }
        );
        settings.settingCard(
            body,
            "Provider packages",
            "Check signed updates or restore the previous local package",
            null,
            false,
            "Manage",
            () ->
                SidePanel.of(activity, "Provider packages")
                    .option("Check for updates", () -> {
                        if (!ProviderPackages.configured(activity)) {
                            activity.toast("Trusted signing key is not configured");
                            return;
                        }
                        activity.async(() -> {
                            ProviderUpdates.check(activity, true);
                            return "Provider check completed";
                        }, activity::toast);
                    })
                    .option("Roll back", () ->
                        activity.async(
                            () -> {
                                ProviderPackages.rollback(activity);
                                return "Previous package restored; automatic updates paused";
                            },
                            message -> {
                                activity.toast(message);
                                show();
                            }
                        )
                    )
                    .message(
                        "Pluto TV keeps its native adapter. Runtime 2 executes additional historical upstream adapters locally. Availability depends on the provider and region; current upstream source remains inaccessible. A package rollback keeps channels and favourites."
                    )
                    .show()
        );
        activity.settingsStyle(body);
    }
}
