package local.tv.home;

import android.graphics.Bitmap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.InetAddress;
import java.util.EnumMap;
import java.util.Map;

/**
 * Settings → Accounts: a QR code that opens a page on a phone (same Wi-Fi) for typing the account
 * details there instead of with the remote ({@link PhoneEntry}). TMDB, Xtream, M3U playlist and
 * Masqueradarr use it; Premiumize has its own sign-in ({@link PremiumizeLink}). What arrives fills
 * the screen's own boxes and goes through the same Connect check as typed details.
 */
final class PhoneKeyPanel {

    private PhoneKeyPanel() {}

    static final PhoneEntry.Page TMDB = new PhoneEntry.Page(
        "MovieHub \u00b7 TMDB API key",
        new String[] { "Paste your TMDB API Key (v3 auth)" },
        new boolean[] { false },
        "Open your TMDB API settings to copy the key",
        "https://www.themoviedb.org/settings/api",
        "",
        "That is not a TMDB API Key (v3 auth). It has 32 letters and numbers."
    );

    static final PhoneEntry.Page XTREAM = new PhoneEntry.Page(
        "MovieHub \u00b7 Xtream login",
        new String[] { "Server address", "Username", "Password" },
        new boolean[] { false, false, true },
        "",
        "",
        "Use the details from your TV provider. The server address starts with http:// or https:// " +
            "and may end with a port, such as :8080.",
        "Fill in all three. The server address must start with http:// or https://."
    );

    static PhoneEntry.Page playlist(String service) {
        return new PhoneEntry.Page(
            "MovieHub \u00b7 " + service,
            new String[] { "Playlist link" },
            new boolean[] { false },
            "",
            "",
            "Paste the full link, starting with http:// or https://.",
            "That is not a link. It must start with http:// or https://."
        );
    }

    /** The phone page for an account screen, or null when that account signs in another way. */
    static PhoneEntry.Page page(String service) {
        switch (service) {
            case "TMDB":
                return TMDB;
            case "Xtream":
                return XTREAM;
            case "M3U playlist":
            case "Masqueradarr":
                return playlist(service);
            default:
                return null;
        }
    }

    /** What each page accepts: the same rules the TV form applies, so a bad value stays on the phone. */
    static boolean valid(String service, String[] values) {
        for (String value : values)
            if (value == null || value.isEmpty() || value.length() > 2048) return false;
        switch (service) {
            case "TMDB":
                return values[0].matches("[0-9a-fA-F]{32}");
            case "Xtream":
                return link(values[0]) && values[1].length() <= 256 && values[2].length() <= 256;
            default:
                return link(values[0]);
        }
    }

    private static boolean link(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://")) && !value.contains(" ");
    }

    /**
     * Adds the QR code to {@code parent}. Details sent from the phone fill {@code inputs} in order,
     * then press {@code connect}, so they are checked exactly like typed details.
     */
    static void add(
        MainActivity activity,
        LinearLayout parent,
        String service,
        java.util.List<EditText> inputs,
        Button connect
    ) {
        PhoneEntry.Page page = page(service);
        if (page == null || page.labels.length != inputs.size()) return;
        parent.addView(activity.text("Or use your phone", 18));
        TextView note = activity.compactText("Starting\u2026", 13);
        note.setTextColor(0xffaab4c4);
        ImageView picture = new ImageView(activity);
        picture.setContentDescription("QR code for typing these details on a phone");
        int request = activity.generation;
        activity.async(
            () -> {
                InetAddress address = PhoneEntry.homeAddress();
                if (address == null) return null;
                // A failure here must not replace the account screen with an error page.
                try {
                    return start(activity, address, service, page, inputs, connect, request);
                } catch (Exception e) {
                    Diagnostics.recovered("Starting phone entry", e);
                    return null;
                }
            },
            entry -> {
                if (entry == null) {
                    note.setText(
                        "Phone entry needs the TV on Wi-Fi or Ethernet, and could not start. " +
                            "Type the details instead."
                    );
                    return;
                }
                Bitmap code = qr(entry.url(), activity.dp(200));
                if (code != null) picture.setImageBitmap(code);
                note.setText(
                    "Scan with a phone on the same Wi-Fi, fill in the page and press Send. " +
                        "Works for 5 minutes while this screen is open."
                );
            }
        );
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(activity.dp(200), activity.dp(200));
        size.topMargin = activity.dp(8);
        size.bottomMargin = activity.dp(8);
        parent.addView(picture, size);
        parent.addView(note);
    }

    private static PhoneEntry start(
        MainActivity activity,
        InetAddress address,
        String service,
        PhoneEntry.Page page,
        java.util.List<EditText> inputs,
        Button connect,
        int request
    ) throws java.io.IOException {
        return new PhoneEntry(
            address,
            page,
            values -> valid(service, values),
            values ->
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    for (int i = 0; i < values.length; i++) inputs.get(i).setText(values[i]);
                    activity.toast("Details received from your phone");
                    connect.performClick();
                }),
            () -> !activity.isDestroyed() && activity.generation == request,
            PhoneEntry.LIFETIME_MS,
            false
        );
    }

    /** Black modules on white with the standard quiet zone; null if the text cannot be encoded. */
    static Bitmap qr(String text, int pixels) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixels, pixels, hints);
            int width = matrix.getWidth(),
                height = matrix.getHeight();
            int[] colours = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) colours[y * width + x] =
                matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            return Bitmap.createBitmap(colours, width, height, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Diagnostics.recovered("Drawing the phone QR code", e);
            return null;
        }
    }
}
