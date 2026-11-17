package local.tv.home;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

/**
 * The player's side panels: audio and subtitle tracks, video track, playback speed, picture size,
 * subtitle timing and the sleep timer. Split out of {@link VlcPlaybackActivity}.
 */
final class PlayerMenus {

    private final VlcPlaybackActivity a;

    PlayerMenus(VlcPlaybackActivity activity) {
        a = activity;
    }

    private boolean ready() {
        return a.player != null;
    }

    /** Audio and subtitles on one panel, as streaming apps do: both lists, the current ones ticked. */
    void audioAndSubtitles() {
        if (!ready()) return;
        MediaPlayer.TrackDescription[] audio = tracksOrNone(a.player.getAudioTracks());
        MediaPlayer.TrackDescription[] subtitles = tracksOrNone(a.player.getSpuTracks());
        a.dialogOpen = true;
        SidePanel.of(a, "Audio & Subtitles")
            .group(
                "Audio",
                names(audio, 1),
                indexOf(audio, a.player.getAudioTrack()),
                "No audio tracks reported yet",
                which -> {
                    if (ready()) a.player.setAudioTrack(audio[which].id);
                }
            )
            .group(
                "Subtitles",
                names(subtitles, 2),
                indexOf(subtitles, a.player.getSpuTrack()),
                "This video has no subtitles",
                which -> {
                    if (ready()) a.player.setSpuTrack(subtitles[which].id);
                }
            )
            .onDismiss(a::panelClosed)
            .show();
    }

    private static MediaPlayer.TrackDescription[] tracksOrNone(MediaPlayer.TrackDescription[] tracks) {
        return tracks == null ? new MediaPlayer.TrackDescription[0] : tracks;
    }

    private String[] names(MediaPlayer.TrackDescription[] tracks, int type) {
        String[] names = new String[tracks.length];
        for (int i = 0; i < names.length; i++) names[i] = trackName(tracks[i], type);
        return names;
    }

    /** The menu position of the native track {@code id}, or -1. */
    private static int indexOf(MediaPlayer.TrackDescription[] tracks, int id) {
        for (int i = 0; i < tracks.length; i++) if (tracks[i].id == id) return i;
        return -1;
    }

    void resolution() {
        if (!ready()) return;
        Media.VideoTrack track = a.player.getCurrentVideoTrack();
        String size = track == null ? "Resolution unavailable" : track.width + " × " + track.height;
        MediaPlayer.TrackDescription[] choices = a.player.getVideoTracks();
        if (choices == null || choices.length < 2) {
            a.dialogOpen = true;
            SidePanel panel = SidePanel.of(a, "Video · " + size).message(
                "This source has one picture quality. Choose a different source for another resolution."
            );
            if (a.vod != null && a.vod.hasChoices()) panel.action(
                "Choose another source",
                this::chooseVodSource
            );
            panel.action("Close", null).onDismiss(a::panelClosed).show();
            return;
        }
        chooseTracks(choices, a.player.getVideoTrack(), 0, "Video · " + size);
    }

    void chooseTracks(MediaPlayer.TrackDescription[] choices, int current, int type, String title) {
        String[] names = new String[choices.length];
        int selected = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = trackName(choices[i], type);
            if (choices[i].id == current) selected = i;
        }
        a.dialogOpen = true;
        SidePanel.of(a, title)
            .choices(names, selected, which -> {
                if (ready()) {
                    int choice = choices[which].id;
                    if (type == 2) a.player.setSpuTrack(choice);
                    else if (type == 1) a.player.setAudioTrack(choice);
                    else a.player.setVideoTrack(choice);
                }
            })
            .onDismiss(a::panelClosed)
            .show();
    }

    /** Match the native track ID and type, never the menu position or current track. */
    String trackName(MediaPlayer.TrackDescription choice, int type) {
        String name = choice.id < 0 ? "Off" : choice.name;
        if (choice.id < 0 || type == 2 || !ready()) return name;
        IMedia media = a.player.getMedia();
        if (media == null) return name;
        try {
            int nativeType = type == 1 ? IMedia.Track.Type.Audio : IMedia.Track.Type.Video;
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track != null && track.id == choice.id && track.type == nativeType) {
                    return MediaFormats.append(
                        name,
                        choice.id,
                        type == 1,
                        track.fourcc,
                        track.codec,
                        track.originalCodec
                    );
                }
            }
        } catch (RuntimeException error) {
            Diagnostics.recovered("Reading track format", error);
        } finally {
            media.release();
        }
        return name;
    }

    /** The More button: the film's playback options. */
    void moreControls() {
        List<String> labels = new ArrayList<>();
        labels.add("Playback speed · " + a.playbackRate + "×");
        labels.add("Picture size");
        labels.add("Subtitle timing");
        labels.add(
            a.sleepAt > 0
                ? "Sleep timer · " +
                      Math.max(1, (a.sleepAt - SystemClock.elapsedRealtime()) / 60000) +
                      " min left"
                : "Sleep timer"
        );
        if (a.vod != null && a.vod.hasChoices()) labels.add(0, "Sources");
        a.dialogOpen = true;
        SidePanel panel = SidePanel.of(a, "Playback options").onDismiss(a::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Sources")) chooseVodSource();
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
                if (ready()) a.player.setRate(a.playbackRate);
            }
        );
    }

    void choosePicture() {
        String[] aspects = { null, "16:9", "4:3", "21:9" };
        int selected = 0;
        for (int i = 1; i < aspects.length; i++) if (aspects[i].equals(a.aspect)) selected = i;
        options("Picture size", new String[] { "Original · Fit", "16:9", "4:3", "21:9" }, selected, which -> {
            a.aspect = aspects[which];
            if (ready()) {
                a.player.setScale(0);
                a.player.setAspectRatio(a.aspect);
            }
        });
    }

    void subtitleTiming() {
        subtitleTiming(0);
    }

    /** Stays open while adjusting (Back closes it), with the current offset in the title. */
    private void subtitleTiming(int focus) {
        if (!ready()) return;
        long delay = a.player.getSpuDelay();
        long ms = delay / 1000;
        options(
            "Subtitle timing \u00b7 " +
                (ms == 0
                    ? "in sync"
                    : String.format(java.util.Locale.ROOT, "%+d ms", ms) +
                      (ms < 0 ? " (earlier)" : " (later)")),
            new String[] { "Earlier by 250 ms", "Later by 250 ms", "Reset timing" },
            focus,
            which -> {
                if (!ready()) return;
                a.player.setSpuDelay(which == 2 ? 0 : delay + (which == 0 ? -250000 : 250000));
                subtitleTiming(which);
            }
        );
    }

    /** The sleep timer choice last made (0 = Off), so the list ticks it while it runs. */
    private int sleepChoice;

    void chooseSleep() {
        if (a.sleepAt == 0) sleepChoice = 0;
        long left = a.sleepAt == 0 ? 0 : Math.max(1, (a.sleepAt - SystemClock.elapsedRealtime()) / 60000);
        options(
            a.sleepAt == 0 ? "Sleep timer" : "Sleep timer \u00b7 " + left + " min left",
            new String[] { "Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes" },
            sleepChoice,
            which -> {
                a.overlayHandler.removeCallbacks(a.sleep);
                int[] minutes = { 0, 15, 30, 60, 90 };
                sleepChoice = which;
                a.sleepAt = which == 0 ? 0 : SystemClock.elapsedRealtime() + minutes[which] * 60000L;
                if (a.sleepAt > 0) a.overlayHandler.postDelayed(a.sleep, minutes[which] * 60000L);
            }
        );
    }

    /** Automatic is the default; a manual choice stays selected until Auto or another channel. */
    void chooseVodSource() {
        if (a.vod == null) return;
        var entries = a.vod.queue.sources();
        String report = a.vod.providerReport();
        String[] names = new String[entries.size() + (report.isEmpty() ? 1 : 2)];
        if (!report.isEmpty()) names[names.length - 1] = "Provider search details";
        // Picking a source by hand keeps later switches to that provider's links (VodPlayback.select),
        // so say so, and how to go back to switching between every source.
        names[0] = a.vod.automaticSources()
            ? "Automatic (recommended) \u00b7 switches if a source fails"
            : "Manual · only switches between this provider's links · choose to make it Automatic";
        for (int i = 0; i < entries.size(); i++) names[i + 1] =
            entries.get(i).label + (i == a.vod.activeIndex() ? " \u00b7 Playing" : "");
        options("Sources", names, a.vod.automaticSources() ? 0 : a.vod.activeIndex() + 1, which -> {
            if (a.vod == null) return;
            if (which > entries.size()) {
                a.dialogOpen = true;
                SidePanel.of(a, "Provider search details")
                    .message(a.vod.providerReport())
                    .onDismiss(a::panelClosed)
                    .show();
            } else if (which == 0) a.vod.automatic();
            else a.vod.select(which - 1);
        });
    }
}
