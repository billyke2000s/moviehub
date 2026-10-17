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
        String key = accountKey(service);
        boolean connected = activity.secrets.has(key);
        LinearLayout card = activity.row();
        card.setPadding(activity.dp(18), activity.dp(9), activity.dp(14), activity.dp(9));
        card.setBackground(activity.box(0xff171d27));
        LinearLayout copy = activity.vertical();
        TextView name = activity.compactText(service, 19);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);
        TextView detail = activity.compactText(description, 13);
        detail.setTextColor(0xffaab4c4);
        copy.addView(detail);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = activity.compactText(connected ? "Connected" : "Not connected", 13);
        status.setTextColor(connected ? 0xff83ddb4 : 0xffaab4c4);
        card.addView(status);
        Button edit = activity.button(connected ? "Manage" : "Connect", () -> accountScreen(service));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(activity.dp(96), activity.dp(38));
        action.leftMargin = activity.dp(18);
        card.addView(edit, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(78));
        lp.bottomMargin = activity.dp(8);
        body.addView(card, lp);
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
        if (service.equals("Xtream") || service.equals("M3U playlist")) toggle(
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
                new AlertDialog.Builder(activity)
                    .setTitle("Disconnect " + service + "?")
                    .setMessage("Remove this connection? Your other accounts and preferences will be kept.")
                    .setPositiveButton("Disconnect", (d, w) -> {
                        activity.secrets.remove(key);
                        if (
                            service.equals("Xtream") || service.equals("M3U playlist")
                        ) ChannelSync.refreshNow(activity);
                        settings();
                    })
                    .setNegativeButton("Cancel", null)
                    .show()
            )
        );
    }

    private void toggle(LinearLayout parent, String title, String key, boolean fallback) {
        Switch s = new Switch(activity);
        s.setText(title);
        s.setTextColor(0xfff8f8fc);
        s.setChecked(activity.prefs.getBoolean(key, fallback));
        s.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        s.setOnCheckedChangeListener((v, checked) -> activity.prefs.edit().putBoolean(key, checked).apply());
        parent.addView(s);
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
        Button control = activity.button(title + ": " + names[chosen], () -> {});
        control.setOnClickListener(v -> {
            String current = activity.prefs.getString(key, fallback);
            int selected = 0;
            for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) selected = i;
            new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(names, selected, (d, which) -> {
                    activity.prefs.edit().putString(key, codes[which]).apply();
                    control.setText(title + ": " + names[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        parent.addView(control);
    }

    private void settingsTab(LinearLayout body, String tab) {
        body.removeAllViews();
        if (tab.equals("Accounts")) {
            body.addView(activity.compactText("Your connections", 23));
            accountCard(body, "Xtream", "Live channels from your TV provider");
            accountCard(body, "M3U playlist", "Live channels from a playlist link");
            accountCard(body, "TMDB", "Movie and series discovery, details and artwork");
            accountCard(body, "Premiumize", "Playback sources and your cloud library");
        } else if (tab.equals("Appearance")) {
            toggle(body, "Show featured row", "featured", true);
            toggle(body, "24-hour clock", "clock24", true);
            for (int colour : new int[] { 0xff8a9eff, 0xff55aaff, 0xff45c9ac })
                body.addView(
                    activity.button(
                        colour == 0xff8a9eff ? "Violet" : colour == 0xff55aaff ? "Blue" : "Teal",
                        () -> {
                            activity.prefs.edit().putInt("accent", colour).apply();
                            activity.toast("Accent saved");
                        }
                    )
                );
            body.addView(activity.button("Return home", activity.homeScreen::home));
        } else if (tab.equals("Playback")) {
            toggle(body, "Resume playback", "resume", true);
            languagePreference(body, "Subtitles", "subtitleLanguage", "none", true);
            languagePreference(body, "Audio track", "audioLanguage", "eng,en", false);
        } else {
            toggle(body, "Check for updates automatically", "autoUpdates", true);
            body.addView(
                activity.text(
                    android.os.Build.MANUFACTURER +
                        " " +
                        android.os.Build.MODEL +
                        " · Android " +
                        android.os.Build.VERSION.RELEASE,
                    16
                )
            );
            body.addView(
                activity.button("Check for app updates", () -> {
                    activity.updates.lastUpdateCheck = 0;
                    activity.updates.checkForUpdate(true);
                })
            );
            body.addView(
                activity.button("Open Android Settings", () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        activity.toast("Android Settings is unavailable");
                    }
                })
            );
            body.addView(
                activity.text(
                    "TMDB supplies metadata and artwork. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    12
                )
            );
            body.addView(
                activity.button("Metadata attribution", () ->
                    new AlertDialog.Builder(activity)
                        .setTitle("Metadata attribution")
                        .setMessage(
                            "The Movie Database (TMDB)\nhttps://www.themoviedb.org\n\nMetadata is separate from your playback sources."
                        )
                        .setPositiveButton("Close", null)
                        .show()
                )
            );
        }
        activity.settingsStyle(body);
    }
}
