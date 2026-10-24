package local.tv.home;

import android.widget.Toast;
import java.util.List;

/** The Live TV side panels when the experimental {@link ExoLive} player is playing. */
final class ExoMenus implements LiveMenus {

    private final LiveScreen screen;
    private final Runnable reconnect;

    ExoMenus(LiveScreen screen, Runnable reconnect) {
        this.screen = screen;
        this.reconnect = reconnect;
    }

    @Override
    public void audioAndSubtitles() {
        List<String> tracks = ExoLive.audioTracks();
        screen.dialogOpen(true);
        if (tracks.size() < 2) {
            SidePanel.of(screen.activity(), "Audio")
                .message(
                    tracks.isEmpty()
                        ? "No audio tracks reported yet."
                        : "This channel has one audio track: " + tracks.get(0)
                )
                .action("Close", null)
                .onDismiss(screen::panelClosed)
                .show();
            return;
        }
        SidePanel.of(screen.activity(), "Audio")
            .choices(tracks.toArray(new String[0]), ExoLive.selectedAudioTrack(), ExoLive::selectAudioTrack)
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
        names[0] =
            "Automatic · " +
            (zapper.automaticSources() && !playing.isEmpty() ? "now " + playing + " · " : "") +
            "switch if a source fails";
        for (int i = 0; i < copies.size(); i++) {
            String provider = AdapterSources.sourceName(copies.get(i).source);
            names[i + 1] =
                (i == zapper.selectedCopy() ? "▶ " : "") +
                "Source " +
                (i + 1) +
                "  ·  " +
                (provider.isEmpty() ? "" : provider + "  ·  ") +
                copies.get(i).name;
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
        String[] labels = {
            ExoLive.isPlaying() ? "Pause" : "Play",
            "Audio",
            "Programme information",
            zapper.sourceText(),
            "Reconnect channel",
            "Player status · ExoPlayer · " + ExoLive.status(),
        };
        screen.dialogOpen(true);
        SidePanel panel = SidePanel.of(screen.activity(), "Options").onDismiss(screen::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.equals("Pause") || label.equals("Play")) screen.toggle();
                else if (label.equals("Audio")) audioAndSubtitles();
                else if (label.equals("Programme information")) zapper.information();
                else if (label.startsWith("Sources")) chooseSource();
                else if (label.equals("Reconnect channel")) reconnect.run();
                else Toast.makeText(screen.activity(), ExoLive.status(), Toast.LENGTH_LONG).show();
            });
        panel.show();
    }
}
