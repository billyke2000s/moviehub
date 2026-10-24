package local.tv.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.videolan.libvlc.MediaPlayer;

/**
 * The live picture in the corner of the TV guide, as on a satellite box: the channel you were
 * last watching keeps playing while you browse. It shares the one live player with the full
 * screen ({@link LivePlayer}), so going between them moves the picture without reconnecting.
 * Stopped when the guide closes, unless the full screen has taken the player over.
 */
final class GuidePicture {

    private FrameLayout video;
    /** Playing, for tests. */
    volatile boolean playing;

    /** The box the picture is shown in; a newer guide frame replaces it. */
    private FrameLayout box;

    /**
     * Shows the live picture in {@code holder} with the channel's number and name in
     * {@code label}. A stream already playing (coming back from the full screen) carries on;
     * otherwise the last channel watched starts, unless the picture is turned off or the channel
     * is locked or gone. Never shows an error on the guide.
     */
    void show(MainActivity activity, FrameLayout holder, TextView label) {
        box = holder;
        boolean on = activity.prefs.getBoolean(LiveTvScreen.PICTURE, true);
        holder.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            stop();
            label.setText("");
            return;
        }
        // Take over at once (before the full-screen player stops), so the stream never pauses.
        boolean carryOn = ExoLive.available() ? ExoLive.active() : LivePlayer.active();
        if (carryOn) {
            attach(activity, holder);
            if (ExoLive.available()) ExoLive.resume();
            else LivePlayer.resume();
            playing = carryOn;
        } else removeVideo();
        String key = activity.prefs.getString(LiveTvScreen.LAST_CHANNEL, "");
        if (key.isEmpty()) {
            label.setText(carryOn ? "" : "No channel yet");
            return;
        }
        ChannelDatabase db = ChannelDatabase.get(activity);
        activity.async(
            () -> {
                try {
                    ChannelDatabase.Row row = db.byKey(key);
                    if (row != null) return new Object[] {
                        row,
                        carryOn ? null : db.url(row.id, row.key),
                        db.copies(row),
                    };
                } catch (Exception e) {
                    Diagnostics.recovered("Finding the guide picture's channel", e);
                }
                return new Object[] { null, null };
            },
            result -> {
                ChannelDatabase.Row row = (ChannelDatabase.Row) result[0];
                if (holder != box) return;
                if (row == null) {
                    if (!carryOn) label.setText("No channel yet");
                    return;
                }
                String name = row.number + " " + row.name;
                label.setText(name);
                if (carryOn) {
                    configureBackups(activity, result);
                    return;
                }
                if (ParentalLock.locked(activity, row)) {
                    label.setText(name + "  ·  Locked");
                    return;
                }
                try {
                    start(activity, holder, Playlist.networkUrl((String) result[1], activity.http()));
                    configureBackups(activity, result);
                } catch (Exception e) {
                    label.setText(name + "  ·  No picture");
                }
            }
        );
    }

    @SuppressWarnings("unchecked")
    private void configureBackups(Context context, Object[] result) {
        if (ExoLive.available()) return; // the experimental engine prepares no standby player
        LivePlayer.backups(context, (java.util.List<ChannelDatabase.Row>) result[2]);
    }

    /** Plays {@code url} in {@code holder} on the shared live player. */
    void start(Context context, FrameLayout holder, String url) {
        try {
            if (ExoLive.available()) ExoLive.play(context, url, false);
            else LivePlayer.play(context, url, PlayerOptions.live(context), false);
            attach(context, holder);
        } catch (RuntimeException e) {
            Diagnostics.recovered("Starting the guide picture", e);
            stop();
        }
    }

    private void attach(Context context, FrameLayout holder) {
        if (video == null || video.getParent() != holder) {
            removeVideo();
            video = new FrameLayout(context);
            holder.addView(video, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        if (ExoLive.available()) {
            attachExo(context);
            return;
        }
        LivePlayer.attach(this, video, this::event, () -> playing = false, null, () -> playing = true);
    }

    private void event(MediaPlayer.Event event) {
        if (event.type == MediaPlayer.Event.Playing) playing = true;
        else if (
            event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.Stopped
        ) playing = false;
    }

    /** Experimental builds: the shared ExoPlayer draws on a surface in this box. */
    private void attachExo(Context context) {
        android.view.SurfaceView surface = null;
        if (video.getChildCount() > 0 && video.getChildAt(0) instanceof android.view.SurfaceView) {
            surface = (android.view.SurfaceView) video.getChildAt(0);
        } else {
            surface = new android.view.SurfaceView(context);
            video.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        }
        ExoLive.attach(
            this,
            surface,
            new ExoLive.Listener() {
                @Override
                public void playing() {
                    playing = true;
                }

                @Override
                public void buffering(boolean buffering) {}

                @Override
                public void failed(String reason) {
                    playing = false;
                }
            }
        );
    }

    /** The guide closed: stops the live player unless the full screen has taken it over. */
    void stop() {
        playing = false;
        if (ExoLive.available()) ExoLive.stopIfOwner(this);
        else LivePlayer.stopIfOwner(this);
        removeVideo();
    }

    private void removeVideo() {
        if (video != null && video.getParent() instanceof ViewGroup) {
            ((ViewGroup) video.getParent()).removeView(video);
        }
        video = null;
    }
}
