package local.tv.home;

import android.content.Context;
import android.view.SurfaceView;
import java.util.Collections;
import java.util.List;

/**
 * Normal builds: no ExoPlayer. Live TV uses {@link LivePlayer} (LibVLC). This stub keeps the same
 * members as the experimental engine (src/experimental) so shared code compiles in both builds;
 * {@link #available()} is false, so none of the other members is ever called.
 */
final class ExoLive {

    interface Listener {
        void playing();

        void buffering(boolean buffering);

        void failed(String reason);
    }

    private ExoLive() {}

    static boolean available() {
        return false;
    }

    static boolean play(Context context, String locator, boolean restart) {
        return false;
    }

    static void attach(Object owner, SurfaceView view, Listener listener) {}

    static void stopIfOwner(Object owner) {}

    static void stop() {}

    static boolean active() {
        return false;
    }

    static boolean isPlaying() {
        return false;
    }

    static void pause() {}

    static void resume() {}

    static String currentUrl() {
        return null;
    }

    static String status() {
        return "ExoPlayer is not in this build";
    }

    static List<String> audioTracks() {
        return Collections.emptyList();
    }

    static int selectedAudioTrack() {
        return -1;
    }

    static void selectAudioTrack(int index) {}
}
