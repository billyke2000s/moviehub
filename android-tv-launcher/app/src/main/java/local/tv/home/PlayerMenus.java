package local.tv.home;

import android.os.SystemClock;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/**
 * The player's side panels: audio and subtitle tracks, video track, playback speed, picture size,
 * subtitle timing and the sleep timer. Split out of {@link VlcPlaybackActivity}.
 */
final class PlayerMenus {

    private final VlcPlaybackActivity a;

    PlayerMenus(VlcPlaybackActivity activity) {
        a = activity;
    }

    /** One panel for both, as streaming apps do: pick which one to change. */
    void audioAndSubtitles() {
        a.dialogOpen = true;
        SidePanel.of(a, "Audio & Subtitles")
            .option("Audio", () -> tracks(false))
            .option("Subtitles", () -> tracks(true))
            .onDismiss(a::panelClosed)
            .show();
    }

    void resolution() {
        if (a.player == null) return;
        Media.VideoTrack track = a.player.getCurrentVideoTrack();
        String size = track == null ? "Resolution unavailable" : track.width + " × " + track.height;
        MediaPlayer.TrackDescription[] choices = a.player.getVideoTracks();
        if (choices == null || choices.length < 2) {
            a.dialogOpen = true;
            SidePanel.of(a, "Video · " + size)
                .message(
                    "This source has one picture quality. Choose a different source for another resolution."
                )
                .action("Close", null)
                .onDismiss(a::panelClosed)
                .show();
            return;
        }
        chooseTracks(choices, a.player.getVideoTrack(), 0, "Video · " + size);
    }

    void tracks(boolean subtitles) {
        if (a.player == null) return;
        MediaPlayer.TrackDescription[] choices = subtitles
            ? a.player.getSpuTracks()
            : a.player.getAudioTracks();
        if (choices == null || choices.length == 0) {
            Toast.makeText(a, "No tracks available", Toast.LENGTH_SHORT).show();
            return;
        }
        chooseTracks(
            choices,
            subtitles ? a.player.getSpuTrack() : a.player.getAudioTrack(),
            subtitles ? 2 : 1,
            subtitles ? "Subtitles" : "Audio"
        );
    }

    void chooseTracks(MediaPlayer.TrackDescription[] choices, int current, int type, String title) {
        String[] names = new String[choices.length];
        int selected = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = choices[i].id < 0 ? "Off" : choices[i].name;
            if (choices[i].id == current) selected = i;
        }
        a.dialogOpen = true;
        SidePanel.of(a, title)
            .choices(names, selected, which -> {
                if (a.player != null) {
                    int choice = choices[which].id;
                    if (type == 2) a.player.setSpuTrack(choice);
                    else if (type == 1) a.player.setAudioTrack(choice);
                    else a.player.setVideoTrack(choice);
                }
            })
            .onDismiss(a::panelClosed)
            .show();
    }

    void moreControls() {
        options(false);
    }

    /**
     * Live TV's Options (SELECT twice, the blue key or MENU): there are no on-screen controls, so
     * pause and audio and subtitles are here too.
     */
    void liveOptions() {
        options(true);
    }

    private void options(boolean liveTv) {
        List<String> labels = new ArrayList<>();
        if (!a.live) labels.add("Playback speed · " + a.playbackRate + "×");
        labels.add("Picture size");
        labels.add("Subtitle timing");
        labels.add(
            a.sleepAt > 0
                ? "Sleep timer · " +
                      Math.max(1, (a.sleepAt - SystemClock.elapsedRealtime()) / 60000) +
                      " min left"
                : "Sleep timer"
        );
        if (a.zapper != null && a.zapper.copies().size() > 1) labels.add(
            0,
            "Source · " + a.zapper.sourceLabel()
        );
        if (a.live && a.behindLive) labels.add(0, "Back to live");
        if (liveTv) {
            // Everything the coloured buttons do is here too, for remotes without them.
            if (a.zapper != null) labels.add(0, "Programme information");
            labels.add(0, "Audio & subtitles");
            labels.add(0, a.player != null && a.player.isPlaying() ? "Pause" : "Play");
        }
        a.dialogOpen = true;
        SidePanel panel = SidePanel.of(a, liveTv ? "Options" : "Playback options").onDismiss(a::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Pause") || label.equals("Play")) a.toggle();
                else if (label.equals("Audio & subtitles")) audioAndSubtitles();
                else if (label.equals("Programme information")) a.zapper.information();
                else if (label.equals("Back to live")) a.backToLive();
                else if (label.startsWith("Source")) chooseSource();
                else if (label.startsWith("Playback speed")) chooseSpeed();
                else if (label.equals("Picture size")) choosePicture();
                else if (label.equals("Subtitle timing")) subtitleTiming();
                else chooseSleep();
            });
        panel.show();
    }

    void options(String title, String[] labels, int selected, java.util.function.IntConsumer action) {
        a.dialogOpen = true;
        SidePanel.of(a, title).choices(labels, selected, action).onDismiss(a::panelClosed).show();
    }

    void chooseSpeed() {
        float[] rates = { .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f };
        int selected = 2;
        for (int i = 0; i < rates.length; i++) if (rates[i] == a.playbackRate) selected = i;
        options(
            "Playback speed",
            new String[] { "0.5×", "0.75×", "Normal · 1×", "1.25×", "1.5×", "1.75×", "2×" },
            selected,
            which -> {
                a.playbackRate = rates[which];
                if (a.player != null) a.player.setRate(a.playbackRate);
            }
        );
    }

    void choosePicture() {
        String[] aspects = { null, "16:9", "4:3", "21:9" };
        int selected = 0;
        for (int i = 1; i < aspects.length; i++) if (aspects[i].equals(a.aspect)) selected = i;
        options("Picture size", new String[] { "Original · Fit", "16:9", "4:3", "21:9" }, selected, which -> {
            a.aspect = aspects[which];
            if (a.player != null) {
                a.player.setScale(0);
                a.player.setAspectRatio(a.aspect);
            }
        });
    }

    void subtitleTiming() {
        if (a.player == null) return;
        long delay = a.player.getSpuDelay();
        options(
            "Subtitle timing · " + delay / 1000 + " ms",
            new String[] { "Earlier by 250 ms", "Later by 250 ms", "Reset timing" },
            -1,
            which -> {
                if (a.player != null) a.player.setSpuDelay(
                    which == 2 ? 0 : delay + (which == 0 ? -250000 : 250000)
                );
            }
        );
    }

    void chooseSleep() {
        options(
            "Sleep timer",
            new String[] { "Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes" },
            a.sleepAt == 0 ? 0 : -1,
            which -> {
                a.overlayHandler.removeCallbacks(a.sleep);
                int[] minutes = { 0, 15, 30, 60, 90 };
                a.sleepAt = which == 0 ? 0 : SystemClock.elapsedRealtime() + minutes[which] * 60000L;
                if (a.sleepAt > 0) a.overlayHandler.postDelayed(a.sleep, minutes[which] * 60000L);
            }
        );
    }

    /** The copies of this Live TV channel from the free lists; the current one is ticked. */
    void chooseSource() {
        java.util.List<ChannelDatabase.Row> copies = a.zapper.copies();
        String[] names = new String[copies.size()];
        int current = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = "Source " + (i + 1) + "  ·  " + copies.get(i).name;
            if (a.zapper.sourceLabel().startsWith(i + 1 + " ")) current = i;
        }
        options("Source", names, current, which -> a.zapper.useCopy(which, null));
    }
}
