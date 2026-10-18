package local.tv.home;

import android.content.Context;
import android.net.Uri;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Arrays;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * The live picture in the corner of the TV guide, as on a satellite box: the channel you were
 * last watching keeps playing while you browse. Stopped whenever the guide closes, so the full
 * screen player never competes with it.
 */
final class GuidePicture {

    private LibVLC engine;
    private MediaPlayer player;
    private VLCVideoLayout video;
    /** Playing, for tests. */
    volatile boolean playing;

    /** The box the picture is shown in; a newer guide frame replaces it. */
    private FrameLayout box;

    /**
     * Shows the last channel watched (the guide's preference) in {@code holder}, with its number
     * and name in {@code label}; nothing plays when the picture is turned off or the channel is
     * locked or gone. Never shows an error on the guide.
     */
    void show(MainActivity activity, FrameLayout holder, android.widget.TextView label) {
        stop();
        box = holder;
        boolean on = activity.prefs.getBoolean(LiveTvScreen.PICTURE, true);
        holder.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        String key = activity.prefs.getString(LiveTvScreen.LAST_CHANNEL, "");
        if (!on || key.isEmpty()) {
            label.setText(on ? "No channel yet" : "");
            return;
        }
        ChannelDatabase db = ChannelDatabase.get(activity);
        activity.async(
            () -> {
                try {
                    ChannelDatabase.Row row = db.byKey(key);
                    if (row != null) return new Object[] { row, db.url(row.id, row.key) };
                } catch (Exception e) {
                    Diagnostics.recovered("Finding the guide picture's channel", e);
                }
                return new Object[] { null, null };
            },
            result -> {
                ChannelDatabase.Row row = (ChannelDatabase.Row) result[0];
                if (row == null || holder != box) {
                    label.setText("No channel yet");
                    return;
                }
                String name = row.number + " " + row.name;
                label.setText(name);
                if (ParentalLock.locked(activity, row)) {
                    label.setText(name + "  ·  Locked");
                    return;
                }
                try {
                    start(activity, holder, Playlist.networkUrl((String) result[1], activity.http()));
                } catch (Exception e) {
                    label.setText(name + "  ·  No picture");
                }
            }
        );
    }

    /** Plays {@code url} in {@code holder}; any earlier picture stops first. */
    void start(Context context, FrameLayout holder, String url) {
        stop();
        try {
            engine = new LibVLC(
                context,
                new ArrayList<>(
                    Arrays.asList(
                        "--network-caching=1500",
                        "--no-video-title-show",
                        "--no-sub-autodetect-file"
                    )
                )
            );
            player = new MediaPlayer(engine);
            video = new VLCVideoLayout(context);
            holder.addView(video, 0, new FrameLayout.LayoutParams(-1, -1));
            player.attachViews(video, null, false, false);
            player.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.Playing) playing = true;
                else if (
                    event.type == MediaPlayer.Event.EncounteredError ||
                    event.type == MediaPlayer.Event.Stopped
                ) playing = false;
            });
            Media media = new Media(engine, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            player.setMedia(media);
            media.release();
            player.play();
        } catch (RuntimeException e) {
            Diagnostics.recovered("Starting the guide picture", e);
            stop();
        }
    }

    void stop() {
        playing = false;
        if (player != null) {
            player.setEventListener(null);
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (video != null && video.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) video.getParent()).removeView(video);
        }
        video = null;
        if (engine != null) {
            engine.release();
            engine = null;
        }
    }
}
