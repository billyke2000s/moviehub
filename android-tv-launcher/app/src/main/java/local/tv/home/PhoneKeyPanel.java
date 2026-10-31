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
 * Settings → Accounts → TMDB: a QR code that opens a page on a phone (same Wi-Fi) where the API
 * key can be pasted instead of typed with the remote ({@link PhoneEntry}). The key lands in the
 * screen's own field and goes through the same Connect check as a typed key.
 */
final class PhoneKeyPanel {

    private PhoneKeyPanel() {}

    static final PhoneEntry.Page TMDB = new PhoneEntry.Page(
        "MovieHub · TMDB API key",
        "Paste your TMDB API Key (v3 auth)",
        "Open your TMDB API settings to copy the key",
        "https://www.themoviedb.org/settings/api",
        "That is not a TMDB API Key (v3 auth). It has 32 letters and numbers."
    );

    /**
     * Adds the QR code to {@code parent}. A key sent from the phone fills {@code field}, then
     * presses {@code connect}, so it is checked exactly like a typed key.
     */
    static void add(MainActivity activity, LinearLayout parent, EditText field, Button connect) {
        TextView heading = activity.text("Or use your phone", 18);
        parent.addView(heading);
        TextView note = activity.compactText("Starting…", 13);
        note.setTextColor(0xffaab4c4);
        ImageView picture = new ImageView(activity);
        picture.setContentDescription("QR code for entering the key on a phone");
        int request = activity.generation;
        activity.async(
            () -> {
                InetAddress address = PhoneEntry.homeAddress();
                if (address == null) return null;
                // A failure here must not replace the account screen with an error page.
                try {
                    return start(activity, address, field, connect, request);
                } catch (Exception e) {
                    Diagnostics.recovered("Starting phone entry", e);
                    return null;
                }
            },
            entry -> {
                if (entry == null) {
                    note.setText(
                        "Phone entry needs the TV on Wi-Fi or Ethernet, and could not start. " +
                            "Type the key instead."
                    );
                    return;
                }
                Bitmap code = qr(entry.url(), activity.dp(200));
                if (code != null) picture.setImageBitmap(code);
                note.setText(
                    "Scan with a phone on the same Wi-Fi, paste your key and press Send. " +
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
        EditText field,
        Button connect,
        int request
    ) throws java.io.IOException {
        return new PhoneEntry(
            address,
            TMDB,
            value -> value.matches("[0-9a-fA-F]{32}"),
            value ->
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    field.setText(value);
                    activity.toast("Key received from your phone");
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
