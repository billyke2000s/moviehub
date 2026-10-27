package local.tv.home;

import android.os.SystemClock;
import java.util.List;
import org.videolan.libvlc.interfaces.IMedia;

/**
 * Checks a playing film's real picture against its label, and mentions a better source when one
 * whose quality was read from the file or stream turns up later. It never switches by itself:
 * the viewer chooses in More → Sources, which prepares the new source in the background at the
 * current position before replacing the picture. Main thread only.
 */
final class VodQuality {

    /**
     * Long enough for a streaming playlist to settle on its picture size after starting. Device
     * tests shorten it.
     */
    static long settleMs = 15_000;

    private final VlcPlaybackActivity activity;
    private final List<VodSourceFailover.Source> sources;
    private final VodSourceFailover queue;
    private final boolean free;
    private final int preferred;
    private int timedIndex = -1,
        measuredIndex = -1,
        announced;
    private long playingSince;
    private String mismatch;

    VodQuality(
        VlcPlaybackActivity activity,
        List<VodSourceFailover.Source> sources,
        VodSourceFailover queue,
        boolean free
    ) {
        this.activity = activity;
        this.sources = sources;
        this.queue = queue;
        this.free = free;
        preferred = activity
            .getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            .getInt(AutoPlayback.QUALITY, 1080);
    }

    /** Called every second with the source on screen and whether it is playing. */
    void tick(int active, boolean playing) {
        if (active < 0 || active >= sources.size()) return;
        for (VodSourceFailover.Source source : sources) {
            MediaFacts.Value facts = source.facts.value;
            if (
                facts.origin == MediaFacts.Origin.UNKNOWN || facts.origin == MediaFacts.Origin.PLAYING
            ) continue;
            if (facts.tier() > 0) {
                // A new Java header check replaces the old playback-only judgement, not the
                // website's quality-trust history. Existing Python checks already judged it.
                if (free && source.confirmed == 0 && facts.origin == MediaFacts.Origin.FILE) {
                    FreeVodProviders.judge(activity, source.site, source.claimed, facts.tier());
                }
                source.confirmed = facts.tier();
            }
            source.label = facts.describe() + " · " + source.provider;
        }
        if (playing) playingFacts(active);
        if (active != timedIndex) {
            timedIndex = active;
            playingSince = 0;
        }
        if (!playing) {
            playingSince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (playingSince == 0) playingSince = now;
        if (now - playingSince >= settleMs) measure(active);
        if (measuredIndex == active) announce(active);
    }

    private void measure(int active) {
        VodSourceFailover.Source source = sources.get(active);
        MediaFacts.Value track = source.facts.value;
        // Use the selected-track snapshot, not LibVLC's convenience getter (which returns
        // the first video track even when another track is selected in a multi-track file).
        if (track.origin != MediaFacts.Origin.PLAYING || track.tier() == 0) return;
        boolean first = measuredIndex != active;
        measuredIndex = active;
        int tier = track.tier();
        source.actual = tier;
        String picture = QualityClaims.playingLabel(
            source.provider,
            tier,
            source.confirmed,
            source.claimed,
            source.playlist,
            ""
        );
        source.label =
            picture.replace(" · " + source.provider, "") +
            " · " +
            track.width +
            "×" +
            track.height +
            " · " +
            source.facts.value.audio() +
            " · " +
            source.facts.value.bitrate() +
            " · " +
            source.provider;
        // Only a file's real size judges the website: a playlist may play below what it offers
        // because of the connection, and a size already read from the file was judged then.
        if (first && free && source.confirmed == 0 && !source.playlist) {
            FreeVodProviders.judge(activity, source.site, source.claimed, tier);
        }
        mismatch =
            first &&
            source.confirmed == 0 &&
            !source.playlist &&
            QualityClaims.verdict(source.claimed, tier) == QualityClaims.OVERSTATED
                ? "Playing " +
                  QualityClaims.name(tier) +
                  " · it was labelled " +
                  QualityClaims.name(SourceRanking.resolution(source.claimed))
                : null;
    }

    private void playingFacts(int active) {
        IMedia media = activity.player.getMedia();
        if (media == null) return;
        try {
            IMedia.VideoTrack video = null;
            IMedia.AudioTrack audio = null;
            int videoId = activity.player.getVideoTrack(),
                audioId = activity.player.getAudioTrack();
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track instanceof IMedia.VideoTrack && track.id == videoId) video =
                    (IMedia.VideoTrack) track;
                if (track instanceof IMedia.AudioTrack && track.id == audioId) audio =
                    (IMedia.AudioTrack) track;
            }
            if (video == null) video = activity.player.getCurrentVideoTrack();
            if (video == null || video.width <= 0 || video.height <= 0) return;
            VodSourceFailover.Source source = sources.get(active);
            source.facts.value = new MediaFacts.Value(
                MediaFacts.Origin.PLAYING,
                video.width,
                video.height,
                video.codec,
                audio == null ? "" : audio.codec,
                audio == null ? 0 : audio.channels,
                audio == null ? "" : audio.language,
                video.bitrate,
                audio == null ? 0 : audio.bitrate,
                activity.player.getLength()
            );
            // Correct facts as soon as tracks exist; quality-claim notices retain their settling delay.
            source.label = "Playing " + source.facts.value.describe() + " · " + source.provider;
        } finally {
            media.release();
        }
    }

    /** The best confirmed quality already pointed out, 0 when none. */
    int announced() {
        return announced;
    }

    private void announce(int active) {
        int count = sources.size();
        int[] confirmed = new int[count];
        boolean[] skipped = new boolean[count];
        for (int i = 0; i < count; i++) {
            confirmed[i] = sources.get(i).confirmed;
            skipped[i] = i == active || queue.failed(i);
        }
        int better = QualityClaims.better(
            confirmed,
            skipped,
            sources.get(active).knownQuality(),
            preferred,
            announced
        );
        String found =
            better > 0 ? QualityClaims.name(better) + " source found · More → Sources to switch" : null;
        if (better > 0) announced = better;
        String message = mismatch == null ? found : found == null ? mismatch : mismatch + "\n" + found;
        mismatch = null;
        if (message != null) android.widget.Toast.makeText(
            activity,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show();
    }
}
