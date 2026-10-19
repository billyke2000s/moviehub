package local.tv.home;

import android.content.Context;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * The one Live TV player, shared by the full-screen player and the picture in the guide's corner,
 * as a satellite box keeps one tuner: going between them moves the picture to another view
 * instead of stopping and reconnecting. Movies and series keep their own player. Main thread only.
 */
final class LivePlayer {

    private static LibVLC engine;
    private static String engineOptions;
    private static MediaPlayer player;
    private static String url;
    /** A stream has been started and has not failed, ended or been stopped. */
    private static boolean alive;
    /** Who shows the picture now (the full-screen player or the guide picture), and where. */
    private static Object owner;
    private static VLCVideoLayout layout;
    private static MediaPlayer.EventListener listener;
    /** How many streams were opened, so tests can tell a handover from a restart. */
    static int opened;

    private LivePlayer() {}

    static MediaPlayer player() {
        return player;
    }

    /** A stream is playing or opening, so a new view can take it over without reconnecting. */
    static boolean active() {
        return player != null && (alive || player.isPlaying());
    }

    /**
     * Plays {@code streamUrl}. When that stream is already playing (and {@code restart} is false)
     * it carries on untouched and this returns true. Otherwise the player opens it (keeping the
     * engine) and this returns false.
     */
    static boolean play(Context context, String streamUrl, List<String> options, boolean restart) {
        String optionKey = String.valueOf(options);
        if (engine == null || !optionKey.equals(engineOptions)) {
            stop();
            engine = new LibVLC(context.getApplicationContext(), new ArrayList<>(options));
            engineOptions = optionKey;
            player = new MediaPlayer(engine);
            player.setEventListener(LivePlayer::event);
        }
        if (!restart && streamUrl.equals(url) && (alive || player.isPlaying())) {
            if (!player.isPlaying()) player.play();
            return true;
        }
        player.stop();
        Media media = new Media(engine, Uri.parse(streamUrl));
        media.setHWDecoderEnabled(true, false);
        player.setMedia(media);
        media.release();
        url = streamUrl;
        alive = true;
        opened++;
        player.play();
        return false;
    }

    private static void event(MediaPlayer.Event event) {
        // Not "Stopped": that comes late from our own stop() when opening a new stream, and would
        // mark the new stream as finished.
        if (
            event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached
        ) alive = false;
        if (listener != null) listener.onEvent(event);
    }

    /**
     * Shows the picture in {@code view} for {@code who}, moving it from wherever it was. Events
     * now go to {@code events}.
     */
    static void attach(Object who, VLCVideoLayout view, MediaPlayer.EventListener events) {
        if (player == null) return;
        if (layout != view) {
            boolean moving = layout != null;
            if (moving) player.detachViews();
            player.attachViews(view, null, false, false);
            layout = view;
            // Do not restart the video decoder during a view handover. LibVLC
            // binds its existing decoder to the new video output when the
            // destination surface becomes available.
        }
        owner = who;
        listener = events;
    }

    static boolean owns(Object who) {
        return player != null && owner == who;
    }

    /** Stops the player when {@code who} still shows it (nobody took it over). */
    static void stopIfOwner(Object who) {
        if (owns(who)) stop();
    }

    /** Stops and releases everything. */
    static void stop() {
        if (player != null) {
            player.setEventListener(null);
            player.stop();
            if (layout != null) player.detachViews();
            player.release();
            player = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
        engineOptions = null;
        url = null;
        alive = false;
        owner = null;
        layout = null;
        listener = null;
    }
}
