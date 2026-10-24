package local.tv.home;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

/**
 * A full-screen Live TV player screen: what the satellite banner ({@link SkyOverlay}), channel
 * changing ({@link LiveZapper}) and player side panels need from it, independent of the playback
 * engine behind it.
 */
abstract class LiveHost extends Activity {

    final Handler overlayHandler = new Handler(Looper.getMainLooper());
    /** Where the picture is drawn; focusable on Live TV so remote keys never land on hidden controls. */
    FrameLayout video;
    /** Channel changing and backups while watching Live TV from the guide; null otherwise. */
    LiveZapper zapper;
    /** A side panel is open over the picture. */
    boolean dialogOpen;
    /** Live TV: this channel failed; the message is in the picture and SELECT tries again. */
    boolean liveFailed;

    int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    abstract LiveMenus liveMenus();

    /** Plays another stream in place: the next channel, or a backup copy of this one. */
    abstract void switchTo(String title, String newUrl, String newId);

    abstract String playbackUrl();

    /** A side panel over the video closed: show the controls again. */
    abstract void panelClosed();

    /** The current copy could not be opened; move on to a backup or say so. */
    abstract void liveSourceFailed();

    /** Pause or carry on (phone layout's touch button). */
    abstract void toggle();
}
