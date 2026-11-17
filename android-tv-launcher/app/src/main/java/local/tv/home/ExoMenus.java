package local.tv.home;

import java.util.List;

/** The Live TV side panels when the {@link ExoLive} player is playing. */
final class ExoMenus implements LiveMenus {

    private final LiveScreen screen;
    private final Runnable reconnect, backToLive;

    ExoMenus(LiveScreen screen, Runnable reconnect, Runnable backToLive) {
        this.screen = screen;
        this.reconnect = reconnect;
        this.backToLive = backToLive;
    }

    /** Audio and subtitles on one panel, each list under its own heading, the current one ticked. */
    @Override
    public void audioAndSubtitles() {
        List<String> audio = ExoLiveTracks.audioTracks();
        List<String> subtitles = ExoLiveTracks.subtitleTracks();
        String[] subtitleNames = new String[subtitles.isEmpty() ? 0 : subtitles.size() + 1];
        if (subtitleNames.length > 0) {
            subtitleNames[0] = "Off";
            for (int i = 0; i < subtitles.size(); i++) subtitleNames[i + 1] = subtitles.get(i);
        }
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "Audio & Subtitles")
            .group(
                "Audio",
                audio.toArray(new String[0]),
                ExoLiveTracks.selectedAudioTrack(),
                "No audio tracks reported yet",
                ExoLiveTracks::selectAudioTrack
            )
            .group(
                "Subtitles",
                subtitleNames,
                ExoLiveTracks.selectedSubtitleTrack() + 1,
                "This channel has no subtitles",
                which -> ExoLiveTracks.selectSubtitleTrack(which - 1)
            )
            .onDismiss(screen::panelClosed)
            .show();
    }

    @Override
    public void chooseSource() {
        LiveZapper zapper = screen.zapper();
        if (zapper == null) return;
        List<ChannelDatabase.Row> copies = zapper.copies();
        String[] names = new String[copies.size() + 1];
        String playing = zapper.sourceProvider();
        // Automatic first and recommended; then each copy by provider and name, the one on screen
        // marked Playing.
        names[0] =
            "Automatic (recommended)" +
            (zapper.automaticSources() && !playing.isEmpty() ? " \u00b7 now " + playing : "") +
            " \u00b7 switches if a source fails";
        for (int i = 0; i < copies.size(); i++) {
            String provider = AdapterSources.sourceName(copies.get(i));
            names[i + 1] =
                (provider.isEmpty() ? "Source " + (i + 1) : provider) +
                " \u00b7 " +
                copies.get(i).name +
                (i == zapper.selectedCopy() ? " \u00b7 Playing" : "");
        }
        int current = zapper.automaticSources() ? 0 : zapper.selectedCopy() + 1;
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "Sources")
            .choices(names, current, which -> {
                if (which == 0) zapper.automaticSources(true);
                else zapper.useCopy(which - 1, false);
            })
            .onDismiss(screen::panelClosed)
            .show();
    }

    @Override
    public void liveOptions() {
        LiveZapper zapper = screen.zapper();
        if (zapper == null) return;
        // Only what the banner's buttons do not: Audio & subtitles, Source and Information are each
        // one button on the banner. Pause stays: the TV banner has no pause button and many remotes
        // have no play/pause key. The technical status and log are in Settings → Setup → Engineer view.
        List<String> labels = new java.util.ArrayList<>();
        if (ExoLive.behindLive()) labels.add("Back to live");
        java.util.Collections.addAll(
            labels,
            ExoLive.isPlaying() ? "Pause" : "Play",
            "Reconnect channel",
            "What's happening"
        );
        screen.dialogOpen(true);
        SidePanel panel = SidePanel.of(screen.activity(), "Options").onDismiss(screen::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Back to live")) backToLive.run();
                else if (label.equals("Pause") || label.equals("Play")) screen.toggle();
                else if (label.equals("Reconnect channel")) reconnect.run();
                else if (label.equals("What's happening")) whatsHappening();
            });
        panel.show();
    }

    /** The player's state in plain words, and its recent events (no decoder or format names). */
    private void whatsHappening() {
        String events = ExoLiveLog.plainEvents(6);
        screen.dialogOpen(true);
        SidePanel.of(screen.activity(), "What's happening")
            .message(
                PlainWords.liveStatus(ExoLive.status()) +
                    (events.isEmpty() ? "" : "\n\nRecently:\n" + events) +
                    "\n\nIf the picture keeps stopping: choose Source on the banner to try another copy, " +
                    "or Reconnect channel."
            )
            .action("Close", null)
            .onDismiss(screen::panelClosed)
            .show();
    }
}
