package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.List;

/**
 * LibVLC engine options. Films and Live TV each have their own list, so a change for one can never
 * reach the other. Today they hold the same values; change them here, one list at a time.
 */
final class PlayerOptions {

    private PlayerOptions() {}

    /** Films and series (VlcPlaybackActivity's own player). */
    static List<String> film(Context context) {
        SharedPreferences prefs = prefs(context);
        return Arrays.asList(
            // A larger buffer delays the start and every jump (see STATUS.md, Decisions).
            "--network-caching=1500",
            "--no-video-title-show",
            // Network files have no subtitle files beside them; do not probe for any.
            "--no-sub-autodetect-file",
            "--audio-language=" + prefs.getString("audioLanguage", "eng,en"),
            "--sub-language=" + prefs.getString("subtitleLanguage", "none")
        );
    }

    /** Live TV (the shared LivePlayer, full screen and in the guide's corner). */
    static List<String> live(Context context) {
        SharedPreferences prefs = prefs(context);
        return Arrays.asList(
            // Short, so changing channel shows the picture quickly.
            "--network-caching=1500",
            "--no-video-title-show",
            "--no-sub-autodetect-file",
            "--audio-language=" + prefs.getString("audioLanguage", "eng,en"),
            "--sub-language=" + prefs.getString("subtitleLanguage", "none")
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }
}
