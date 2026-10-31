package local.tv.home;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Settings → Accounts → Premiumize: Premiumize's own device sign-in. The TV shows a QR code for
 * premiumize.me and a short code; the user approves on their phone (any network) and the TV
 * receives a login token. The token is used exactly where the API key was (a Bearer header), so
 * nothing else changes.
 */
final class PremiumizeLink {

    private PremiumizeLink() {}

    /**
     * ResolveURL's public client ID for Premiumize device sign-in, already shipped in this app's
     * bundled ResolveURL (python/resolveurl/plugins/premiumize_me.py). Device-sign-in client IDs
     * are public by design. Premiumize's approval page may name ResolveURL; replace this with a
     * MovieHub client ID if one is registered.
     */
    static final String CLIENT_ID = "522962560";

    static final String TOKEN_URL = "https://www.premiumize.me/token";

    /** The newest code shown; checks for an older one (after New code) stop quietly. */
    private static volatile Code current;

    /** A started sign-in: what to show, and how long and how often to ask for the result. */
    static final class Code {

        final String device, user, address;
        final long expiresAt, intervalMs;

        Code(String device, String user, String address, long expiresAt, long intervalMs) {
            this.device = device;
            this.user = user;
            this.address = address;
            this.expiresAt = expiresAt;
            this.intervalMs = intervalMs;
        }
    }

    /** POSTs a form to the token address. Returns the JSON reply, also for errors (they carry the reason). */
    private static JSONObject post(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URI(TOKEN_URL).toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(data);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new java.io.IOException("Premiumize returned " + status);
            try (InputStream body = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                for (int n; (n = body.read(b)) != -1; ) {
                    if (out.size() + n > 64 * 1024) throw new java.io.IOException("Response too large");
                    out.write(b, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    static Code start() throws Exception {
        JSONObject reply = post("response_type=device_code&client_id=" + CLIENT_ID);
        String device = reply.getString("device_code"),
            user = reply.optString("user_code"),
            address = reply.optString("verification_uri", "https://www.premiumize.me/device");
        if (!address.startsWith("https://")) address = "https://www.premiumize.me/device";
        long expires = Math.max(60, Math.min(1800, reply.optLong("expires_in", 600)));
        long interval = Math.max(3, Math.min(30, reply.optLong("interval", 5)));
        return new Code(
            device,
            user,
            address,
            SystemClock.elapsedRealtime() + expires * 1000,
            interval * 1000
        );
    }

    /** The token once approved; null while still waiting. Throws when refused or expired. */
    static String poll(Code code) throws Exception {
        JSONObject reply = post(
            "grant_type=device_code&client_id=" + CLIENT_ID + "&code=" + Network.q(code.device)
        );
        String token = reply.optString("access_token");
        if (!token.isEmpty()) return token;
        String error = reply.optString("error");
        if (error.equals("authorization_pending") || error.equals("slow_down")) return null;
        throw new Exception(error.isEmpty() ? "No token" : error);
    }

    /** Adds "Sign in with your phone" to the Premiumize account screen (shown while not connected). */
    static void add(MainActivity activity, LinearLayout parent) {
        parent.addView(activity.text("Or sign in with your phone", 18));
        TextView note = activity.compactText(
            "Approve MovieHub on premiumize.me from your phone. No key to type.",
            13
        );
        note.setTextColor(0xffaab4c4);
        ImageView picture = new ImageView(activity);
        picture.setContentDescription("QR code for Premiumize sign-in");
        Button show = activity.button("Show sign-in code", () -> {});
        show.setOnClickListener(v -> begin(activity, show, picture, note));
        parent.addView(show, new LinearLayout.LayoutParams(-1, activity.dp(44)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(activity.dp(200), activity.dp(200));
        size.topMargin = activity.dp(8);
        size.bottomMargin = activity.dp(8);
        parent.addView(picture, size);
        parent.addView(note);
    }

    private static void begin(MainActivity activity, Button show, ImageView picture, TextView note) {
        show.setEnabled(false);
        note.setText("Asking Premiumize for a code…");
        int request = activity.generation;
        activity.io.execute(() -> {
            Code code;
            try {
                code = start();
            } catch (Exception e) {
                Diagnostics.recovered("Starting Premiumize sign-in", e);
                activity.runOnUiThread(() -> {
                    if (activity.generation != request) return;
                    show.setEnabled(true);
                    note.setText("Could not reach Premiumize. Check the internet connection and try again.");
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || activity.generation != request) return;
                Bitmap qr = PhoneKeyPanel.qr(code.address, activity.dp(200));
                if (qr != null) picture.setImageBitmap(qr);
                note.setText(
                    "Scan the code, or go to " +
                        code.address.replaceFirst("^https://", "") +
                        ", and enter " +
                        code.user +
                        ". This screen updates once you approve."
                );
                show.setText("New code");
                show.setEnabled(true);
                current = code;
                schedule(activity, code, request, note);
            });
        });
    }

    private static void schedule(MainActivity activity, Code code, int request, TextView note) {
        activity.handler.postDelayed(() -> {
            if (activity.isDestroyed() || activity.generation != request || current != code) return;
            if (SystemClock.elapsedRealtime() >= code.expiresAt) {
                note.setText("The code expired. Press New code to try again.");
                return;
            }
            activity.io.execute(() -> check(activity, code, request, note));
        }, code.intervalMs);
    }

    private static void check(MainActivity activity, Code code, int request, TextView note) {
        String token;
        try {
            token = poll(code);
            if (token != null) {
                // Same check as a typed key, then the same encrypted storage.
                activity.media.premiumize("account/info", token);
                activity.secrets.put("premiumize", token);
            }
        } catch (Exception e) {
            Diagnostics.recovered("Premiumize sign-in", e);
            activity.runOnUiThread(() -> {
                if (activity.generation == request && current == code) note.setText(
                    "Premiumize did not approve this sign-in. Press New code to try again."
                );
            });
            return;
        }
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || activity.generation != request || current != code) return;
            if (token == null) {
                schedule(activity, code, request, note);
                return;
            }
            activity.toast("Premiumize connected");
            activity.settingsScreens.accountScreen("Premiumize");
        });
    }
}
