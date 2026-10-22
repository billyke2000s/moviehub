package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.List;

/**
 * LibVLC engine options. Films and Live TV each have their own list, so a change for one can never
 * reach the other. VOD has more buffering headroom; live channels keep a shorter startup profile.
 */
final class PlayerOptions {

    private PlayerOptions() {}

    /** Films and series (VlcPlaybackActivity's own player). */
    static List<String> film(Context context) {
        SharedPreferences prefs = prefs(context);
        return Arrays.asList(
            // VOD favours a little more headroom; this can add time to startup and seeks.
            "--network-caching=" + PlaybackBuffer.VOD_MS,
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
            "--network-caching=" + PlaybackBuffer.LIVE_MS,
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
