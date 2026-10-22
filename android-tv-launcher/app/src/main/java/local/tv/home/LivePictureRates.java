package local.tv.home;

import java.util.Locale;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

/** One-second VLC counters; decoded frames are not a measurement of frames drawn by SurfaceView. */
final class LivePictureRates {

    static volatile String fullScreen = "Not measured yet",
        guide = "Not measured yet";
    private static Object previousOwner;
    private static MediaPlayer previousPlayer;
    private static long at, decoded, displayed, lost;

    private LivePictureRates() {}

    static void sample(MediaPlayer player, LiveVideoOutput output, Object owner, long now) {
        if (player == null || output == null || owner == null || !player.isPlaying() || !output.isShown()) {
            previousOwner = null;
            return;
        }
        IMedia media = player.getMedia();
        if (media == null) return;
        try {
            IMedia.Stats stats = media.getStats();
            if (stats == null) return;
            long elapsed = now - at;
            if (
                previousPlayer == player &&
                previousOwner == owner &&
                elapsed >= 900 &&
                elapsed < 5000 &&
                stats.decodedVideo >= decoded &&
                stats.displayedPictures >= displayed
            ) {
                var surface = output.surfaceView();
                String text = String.format(
                    Locale.ROOT,
                    "%.0f fps decoded · %.0f fps reported displayed · %d dropped · %dx%d · SurfaceView",
                    (1000.0 * (stats.decodedVideo - decoded)) / elapsed,
                    (1000.0 * (stats.displayedPictures - displayed)) / elapsed,
                    Math.max(0, stats.lostPictures - lost),
                    surface == null ? 0 : surface.getWidth(),
                    surface == null ? 0 : surface.getHeight()
                );
                if (owner instanceof GuidePicture) guide = text;
                else fullScreen = text;
            }
            previousOwner = owner;
            previousPlayer = player;
            at = now;
            decoded = stats.decodedVideo;
            displayed = stats.displayedPictures;
            lost = stats.lostPictures;
        } catch (RuntimeException error) {
            previousOwner = null;
        } finally {
            media.release();
        }
    }
}
