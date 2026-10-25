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
abstract class LiveHost extends Activity implements LiveScreen, LiveScreen.Owner {

    final Handler overlayHandler = new Handler(Looper.getMainLooper());
    /** Where the picture is drawn; focusable on Live TV so remote keys never land on hidden controls. */
    FrameLayout video;
    /** Channel changing and backups while watching Live TV from the guide; null otherwise. */
    LiveZapper zapper;
    /** A side panel is open over the picture. */
    boolean dialogOpen;
    /** Live TV: this channel failed; the message is in the picture and SELECT tries again. */
    boolean liveFailed;

    @Override
    public int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public LiveScreen liveScreen() {
        return this;
    }

    @Override
    public Handler overlayHandler() {
        return overlayHandler;
    }

    @Override
    public FrameLayout video() {
        return video;
    }

    @Override
    public LiveZapper zapper() {
        return zapper;
    }

    @Override
    public boolean dialogOpen() {
        return dialogOpen;
    }

    @Override
    public void dialogOpen(boolean open) {
        dialogOpen = open;
    }

    @Override
    public boolean liveFailed() {
        return liveFailed;
    }

    /** A separate player screen leaves by closing; the guide below it is already there. */
    @Override
    public void leave() {
        finish();
    }

    @Override
    public String title() {
        return getIntent().getStringExtra("title");
    }

    @Override
    public String videoDetails() {
        return LivePlayer.videoDetails();
    }
}
