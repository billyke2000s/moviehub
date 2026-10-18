package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Settings tabs, account setup and playback/language preferences. */
final class SettingsScreens {

    private final MainActivity activity;

    SettingsScreens(MainActivity activity) {
        this.activity = activity;
    }

    void addSource(String kind, String name, String link) {
        LinearLayout content = activity.vertical();
        EditText title = activity.field(content, "Title", false, name),
            url = activity.field(content, "Authorized media URL", false, link);
        CheckBox permission = new CheckBox(activity);
        permission.setText("I have permission to play this source");
        permission.setTextColor(0xfff8f8fc);
        content.addView(permission);
        Dialog dialog = activity.form("Add to " + kind, content);
        content.addView(
            activity.button("Save source", () -> {
                if (!permission.isChecked()) {
                    activity.toast("Confirm permission for this source");
                    return;
                }
                try {
                    activity.media.add(
                        title.getText().toString(),
                        url.getText().toString(),
                        kind,
                        activity.http()
                    );
                    dialog.dismiss();
                    activity.libraryScreens.library(kind);
                } catch (Exception e) {
                    activity.toast("Enter a title and valid media URL");
                }
            })
        );
    }

    void settings() {
        activity.fixedScreen("Settings", activity.homeScreen::home);
        LinearLayout layout = activity.row();
        layout.setGravity(Gravity.TOP);
        activity.page.addView(layout, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navigation = activity.vertical();
        navigation.setPadding(0, activity.dp(8), activity.dp(22), 0);
        layout.addView(navigation, new LinearLayout.LayoutParams(activity.dp(190), -1));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = activity.vertical();
        body.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8));
        scroll.addView(body);
        layout.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        List<Button> choices = new ArrayList<>();
        for (String tab : new String[] { "Accounts", "Appearance", "Playback", "System" }) {
            Button choice = activity.navigationButton(tab, () -> {});
            choices.add(choice);
            LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, activity.dp(44));
            navParams.bottomMargin = activity.dp(4);
            navigation.addView(choice, navParams);
            choice.setOnClickListener(v -> {
                for (Button other : choices) {
                    other.setSelected(other == v);
                    activity.styleFocus(other, other.hasFocus());
                }
                settingsTab(body, tab);
            });
        }
        choices.get(0).setSelected(true);
        activity.styleFocus(choices.get(0), false);
        settingsTab(body, "Accounts");
        choices.get(0).requestFocus();
    }

    private String versionName() {
        try {
            return (
                "Version " +
                activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName
            );
        } catch (Exception e) {
            return "";
        }
    }

    private String accountKey(String service) {
        return service.equals("Xtream")
            ? "xtream"
            : service.equals("M3U playlist")
              ? "m3u"
              : service.equals("TMDB")
                ? "tmdb"
                : "premiumize";
    }

    private void accountCard(LinearLayout body, String service, String description) {
        boolean connected = activity.secrets.has(accountKey(service));
        settingCard(
            body,
            service,
            description,
            connected ? "Connected" : "Not connected",
            connected,
            connected ? "Manage" : "Connect",
            () -> accountScreen(service)
        );
    }

    /**
     * One setting, in the style shared by every Settings tab: a bold title and short description on
     * the left, the current value on the right (green when on or connected), then one action button.
     * Returns the value label so callers can update it. {@code status} and {@code action} may be null.
     */
    private TextView settingCard(
        LinearLayout body,
        String title,
        String description,
        String status,
        boolean positive,
        String action,
        Runnable onAction
    ) {
        LinearLayout card = activity.row();
        card.setPadding(activity.dp(18), activity.dp(9), activity.dp(14), activity.dp(9));
        card.setBackground(activity.box(0xff171d27));
        LinearLayout copy = activity.vertical();
        TextView name = activity.compactText(title, 19);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);
        TextView detail = activity.compactText(description, 13);
        detail.setTextColor(0xffaab4c4);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(detail);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = activity.compactText(status == null ? "" : status, 13);
        statusColour(value, positive);
        if (status != null) card.addView(value);
        if (action != null) {
            Button button = activity.button(action, onAction);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dp(118), activity.dp(38));
            lp.leftMargin = activity.dp(18);
            card.addView(button, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(78));
        lp.bottomMargin = activity.dp(8);
        body.addView(card, lp);
        return value;
    }

    private void statusColour(TextView value, boolean positive) {
        value.setTextColor(positive ? 0xff83ddb4 : 0xffaab4c4);
    }

    /** Section heading at the top of a Settings tab. */
    private void heading(LinearLayout body, String text) {
        body.addView(activity.compactText(text, 23));
    }

    /** A single-purpose form keeps credentials separate from settings and other providers. */
    void accountScreen(String service) {
        String key = accountKey(service);
        boolean connected = activity.secrets.has(key);
        activity.screen(service, this::settings);
        LinearLayout layout = activity.row();
        layout.setGravity(Gravity.TOP);
        activity.page.addView(layout);
        LinearLayout explanation = activity.vertical();
        explanation.setPadding(0, activity.dp(10), activity.dp(40), 0);
        layout.addView(explanation, new LinearLayout.LayoutParams(activity.dp(290), -2));
        explanation.addView(activity.text(connected ? "Manage connection" : "Connect " + service, 26));
        String description = service.equals("Xtream")
            ? "Live TV\nEnter the server address, username and password supplied by your TV provider."
            : service.equals("M3U playlist")
              ? "Live TV\nPaste the playlist link supplied by your TV provider. Channels refresh automatically."
              : service.equals("TMDB")
                ? "Movies, series & artwork\nUse the API Key (v3 auth) from your TMDB account’s API settings."
                : "Playback & cloud library\nUse the API key from your Premiumize account.";
        explanation.addView(activity.text(description, 16));
        if (service.equals("TMDB")) explanation.addView(activity.text("themoviedb.org/settings/api", 14));
        if (service.equals("Premiumize")) explanation.addView(activity.text("premiumize.me/account", 14));
        LinearLayout form = activity.vertical();
        form.setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(20));
        form.setBackground(activity.box(0xff131b27));
        layout.addView(form, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = activity.compactText(
            connected
                ? "Connected · saved credentials are kept until a replacement is verified"
                : "Not connected",
            14
        );
        status.setTextColor(0xffaab4c4);
        form.addView(status);
        List<EditText> inputs = new ArrayList<>();
        if (service.equals("Xtream")) {
            String server = "",
                user = "";
            try {
                if (connected) {
                    JSONObject saved = new JSONObject(activity.secrets.get(key));
                    server = saved.optString("server");
                    user = saved.optString("username");
                }
            } catch (Exception e) {
                Diagnostics.recovered("Reading saved Xtream account", e);
            }
            inputs.add(activity.field(form, "Server address", false, server));
            inputs.get(0).setHint("https://provider.example:port");
            inputs.add(activity.field(form, "Username", false, user));
            inputs.add(activity.field(form, "Password", true, ""));
        } else {
            String label = service.equals("M3U playlist")
                ? "Playlist URL"
                : service.equals("TMDB")
                  ? "API Key (v3 auth)"
                  : "API key";
            inputs.add(activity.field(form, label, !service.equals("M3U playlist"), ""));
            inputs
                .get(0)
                .setHint(
                    service.equals("M3U playlist")
                        ? "https://provider.example/playlist.m3u"
                        : "Enter your key"
                );
        }
        if (service.equals("Xtream") || service.equals("M3U playlist")) formSwitch(
            form,
            "Allow HTTP provider connections",
            "http",
            false
        );
        if (!service.equals("M3U playlist")) {
            CheckBox reveal = new CheckBox(activity);
            reveal.setText("Show " + (service.equals("Xtream") ? "password" : "key"));
            reveal.setTextColor(0xffaab4c4);
            EditText secret = inputs.get(inputs.size() - 1);
            reveal.setOnCheckedChangeListener((v, on) -> {
                int cursor = secret.getSelectionStart();
                secret.setTransformationMethod(
                    on ? null : android.text.method.PasswordTransformationMethod.getInstance()
                );
                if (cursor >= 0) secret.setSelection(Math.min(cursor, secret.length()));
            });
            form.addView(reveal);
        }
        Button save = activity.button(connected ? "Verify & save" : "Connect", () -> {});
        form.addView(save, new LinearLayout.LayoutParams(-1, activity.dp(44)));
        save.setOnClickListener(v -> {
            List<String> values = new ArrayList<>();
            for (EditText input : inputs) {
                String value = input.getText().toString();
                if (!(service.equals("Xtream") && inputs.indexOf(input) == 2)) value = value.trim();
                if (value.isEmpty()) {
                    input.setError("Required");
                    input.requestFocus();
                    return;
                }
                values.add(value);
            }
            if (service.equals("TMDB") && !values.get(0).matches("[0-9a-fA-F]{32}")) {
                inputs.get(0).setError("Use the 32-character API Key (v3 auth)");
                inputs.get(0).requestFocus();
                return;
            }
            save.setEnabled(false);
            save.setText("Connecting…");
            status.setText("Checking your connection…");
            int request = activity.generation;
            boolean allowHttp = activity.http();
            activity.io.execute(() -> {
                String result;
                boolean success = false;
                try {
                    String credential = values.get(0);
                    if (service.equals("Xtream")) credential = MediaRepository.connectXtream(
                        values.get(0),
                        values.get(1),
                        values.get(2),
                        allowHttp
                    ).toString();
                    else if (service.equals("M3U playlist")) {
                        credential = Playlist.networkUrl(credential, allowHttp);
                        if (!activity.liveTv.hasChannel(credential)) throw new Exception();
                    } else if (service.equals("TMDB")) new JSONObject(
                        Network.text(MediaRepository.tmdbUrl("trending/all/week", credential), null, false)
                    ).getJSONArray("results");
                    else activity.media.premiumize("account/info", credential);
                    activity.secrets.put(key, credential);
                    success = true;
                    result = "Connected";
                } catch (Exception error) {
                    result = service.equals("Xtream")
                        ? "Could not connect. Check the server address, username, password and HTTP setting."
                        : service.equals("M3U playlist")
                          ? "Could not read this playlist. Check the URL, provider access and HTTP setting."
                          : "Could not verify this key. Check the key and your internet connection.";
                }
                final boolean ok = success;
                final String message = result;
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    status.setText(message);
                    status.setTextColor(ok ? 0xff83ddb4 : 0xffffb4aa);
                    save.setEnabled(true);
                    save.setText("Verify & save");
                    if (ok) {
                        if (
                            service.equals("Xtream") || service.equals("M3U playlist")
                        ) ChannelSync.refreshNow(activity);
                        accountScreen(service);
                        activity.toast(
                            "Connected" +
                                (service.equals("Xtream") || service.equals("M3U playlist")
                                    ? " · importing channels"
                                    : "")
                        );
                    }
                });
            });
        });
        if (connected) form.addView(
            activity.button("Disconnect", () ->
                SidePanel.of(activity, "Disconnect " + service + "?")
                    .message("Remove this connection? Your other accounts and preferences will be kept.")
                    .action("Disconnect", () -> {
                        activity.secrets.remove(key);
                        if (
                            service.equals("Xtream") || service.equals("M3U playlist")
                        ) ChannelSync.refreshNow(activity);
                        settings();
                    })
                    .action("Cancel", null)
                    .show()
            )
        );
    }

    /** A plain on/off switch for use inside an account form. */
    private void formSwitch(LinearLayout parent, String title, String key, boolean fallback) {
        Switch s = new Switch(activity);
        s.setText(title);
        s.setTextColor(0xfff8f8fc);
        s.setChecked(activity.prefs.getBoolean(key, fallback));
        s.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        s.setOnCheckedChangeListener((v, checked) -> activity.prefs.edit().putBoolean(key, checked).apply());
        parent.addView(s);
    }

    private void toggle(LinearLayout parent, String title, String description, String key, boolean fallback) {
        boolean[] on = { activity.prefs.getBoolean(key, fallback) };
        TextView[] value = new TextView[1];
        Button[] button = new Button[1];
        value[0] = settingCard(
            parent,
            title,
            description,
            on[0] ? "On" : "Off",
            on[0],
            on[0] ? "Turn off" : "Turn on",
            () -> {
                on[0] = !on[0];
                activity.prefs.edit().putBoolean(key, on[0]).apply();
                value[0].setText(on[0] ? "On" : "Off");
                statusColour(value[0], on[0]);
                button[0].setText(on[0] ? "Turn off" : "Turn on");
            }
        );
        LinearLayout card = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
        button[0] = (Button) card.getChildAt(card.getChildCount() - 1);
    }

    private void languagePreference(
        LinearLayout parent,
        String title,
        String key,
        String fallback,
        boolean subtitles
    ) {
        String[] names = subtitles
            ? new String[] {
                  "Off",
                  "English",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              }
            : new String[] {
                  "English",
                  "Original / default",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              };
        String[] codes = subtitles
            ? new String[] {
                  "none",
                  "eng,en",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              }
            : new String[] {
                  "eng,en",
                  "",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              };
        String value = activity.prefs.getString(key, fallback);
        int chosen = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(value)) chosen = i;
        TextView[] shown = new TextView[1];
        shown[0] = settingCard(
            parent,
            title,
            subtitles
                ? "Subtitle language chosen automatically when a video starts"
                : "Audio language chosen automatically when a video starts",
            names[chosen],
            !names[chosen].equals("Off"),
            "Change",
            () -> {
                String current = activity.prefs.getString(key, fallback);
                int selected = 0;
                for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) selected = i;
                SidePanel.of(activity, title)
                    .choices(names, selected, which -> {
                        activity.prefs.edit().putString(key, codes[which]).apply();
                        shown[0].setText(names[which]);
                        statusColour(shown[0], !names[which].equals("Off"));
                    })
                    .show();
            }
        );
    }

    private void settingsTab(LinearLayout body, String tab) {
        body.removeAllViews();
        if (tab.equals("Accounts")) {
            heading(body, "Your connections");
            accountCard(body, "Xtream", "Live channels from your TV provider");
            accountCard(body, "M3U playlist", "Live channels from a playlist link");
            freeChannelsCard(body);
            accountCard(body, "TMDB", "Movie and series discovery, details and artwork");
            accountCard(body, "Premiumize", "Playback sources and your cloud library");
        } else if (tab.equals("Appearance")) {
            heading(body, "Look and feel");
            toggle(body, "Featured row", "Artwork and a poster shelf on Home", "featured", true);
            toggle(body, "24-hour clock", "Show the time as 21:30 instead of 9:30 PM", "clock24", true);
            String[] accentNames = { "Violet", "Blue", "Teal" };
            int[] accents = { 0xff8a9eff, 0xff55aaff, 0xff45c9ac };
            int current = 0;
            for (int i = 0; i < accents.length; i++) if (accents[i] == activity.accent()) current = i;
            TextView[] accentValue = new TextView[1];
            accentValue[0] = settingCard(
                body,
                "Accent colour",
                "Outline colour around the selected item",
                accentNames[current],
                true,
                "Change",
                () -> {
                    int selected = 0;
                    for (int i = 0; i < accents.length; i++) if (accents[i] == activity.accent()) selected =
                        i;
                    SidePanel.of(activity, "Accent colour")
                        .choices(accentNames, selected, which -> {
                            activity.prefs.edit().putInt("accent", accents[which]).apply();
                            accentValue[0].setText(accentNames[which]);
                            accentValue[0].setTextColor(accents[which]);
                        })
                        .show();
                }
            );
            accentValue[0].setTextColor(activity.accent());
        } else if (tab.equals("Playback")) {
            heading(body, "Playback preferences");
            toggle(body, "Resume playback", "Continue from where you stopped", "resume", true);
            languagePreference(body, "Subtitles", "subtitleLanguage", "none", true);
            languagePreference(body, "Audio track", "audioLanguage", "eng,en", false);
        } else {
            heading(body, "System");
            toggle(
                body,
                "Automatic updates",
                "Offer new MovieHub versions when they are published",
                "autoUpdates",
                true
            );
            settingCard(
                body,
                "MovieHub",
                "Check now for a newer version of MovieHub",
                versionName(),
                true,
                "Check",
                () -> {
                    activity.updates.lastUpdateCheck = 0;
                    activity.updates.checkForUpdate(true);
                }
            );
            settingCard(
                body,
                SafetyNotice.TITLE,
                "MovieHub never asks you to leave the app, ring a number or pay during a stream",
                null,
                false,
                "Read",
                () -> SafetyNotice.show(activity)
            );
            settingCard(
                body,
                "Diagnostics",
                "How long loading, sources and playback take on this box",
                null,
                false,
                "Open",
                this::diagnostics
            );
            settingCard(
                body,
                "Android settings",
                android.os.Build.MANUFACTURER +
                    " " +
                    android.os.Build.MODEL +
                    " · Android " +
                    android.os.Build.VERSION.RELEASE,
                null,
                false,
                "Open",
                () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        activity.toast("Android Settings is unavailable");
                    }
                }
            );
            settingCard(
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
        }
        activity.settingsStyle(body);
    }

    /** Free public channels from iptv-org for Live TV, by country. */
    private void freeChannelsCard(LinearLayout body) {
        String current = FreeChannels.choice(activity);
        TextView[] shown = new TextView[1];
        shown[0] = settingCard(
            body,
            "Free channels",
            "Public channels from iptv-org, for Live TV without a TV provider",
            FreeChannels.label(current),
            !current.equals("off"),
            "Change",
            () -> {
                String[] names = new String[FreeChannels.CHOICES.length];
                int selected = 0;
                for (int i = 0; i < names.length; i++) {
                    names[i] = FreeChannels.CHOICES[i][1];
                    if (FreeChannels.CHOICES[i][0].equals(FreeChannels.choice(activity))) selected = i;
                }
                SidePanel.of(activity, "Free channels")
                    .message(
                        "Channels come from the public iptv-org lists of freely available streams. Some streams may not work, and some need Allow HTTP in System."
                    )
                    .choices(names, selected, which -> {
                        String value = FreeChannels.CHOICES[which][0];
                        activity.prefs.edit().putString(FreeChannels.PREF, value).apply();
                        shown[0].setText(FreeChannels.label(value));
                        statusColour(shown[0], !value.equals("off"));
                        ChannelSync.refreshNow(activity);
                    })
                    .show();
            }
        );
    }

    /**
     * Timings measured since MovieHub started (see {@link Timings}): what is slow on this box.
     * Shows only names, durations and counts; no addresses or account details.
     */
    void diagnostics() {
        activity.screen("Diagnostics", this::settings);
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
        activity.chip(actions, "Refresh", this::diagnostics);
        activity.chip(actions, "Reset", () -> {
            Timings.clear();
            diagnostics();
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
