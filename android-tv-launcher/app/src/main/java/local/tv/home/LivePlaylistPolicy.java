package local.tv.home;

import java.io.IOException;

/** Reject short, finished clips returned in place of the selected live sports channel. */
final class LivePlaylistPolicy {

    private LivePlaylistPolicy() {}

    static void validate(String playlist) throws IOException {
        boolean ended = false;
        double seconds = 0;
        int segments = 0;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.equals("#EXT-X-ENDLIST")) ended = true;
            if (line.startsWith("#EXTINF:")) {
                try {
                    double duration = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                    if (!Double.isFinite(duration) || duration <= 0) throw new NumberFormatException();
                    seconds += duration;
                    segments++;
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid live playlist duration", error);
                }
            }
        }
        // A short sliding window is normal live HLS. Only reject a finished clip.
        // This cannot identify a message encoded into an otherwise continuous live feed.
        if (ended && segments > 0 && seconds <= 120) throw new IOException(
            "Provider returned a short finished clip instead of live TV"
        );
    }
}
