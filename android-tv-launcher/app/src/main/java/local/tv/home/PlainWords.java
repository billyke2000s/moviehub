package local.tv.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-English wording for the family, where the app's own words are technical. The technical
 * versions stay in Settings → Setup → Engineer view. Plain Java, so it is checked without a device.
 */
final class PlainWords {

    private PlainWords() {}

    /** What the Live TV player is doing, from {@code ExoLive}'s status ("Playing", "Failed: …"). */
    static String liveStatus(String status) {
        String s = status == null ? "" : status.trim();
        if (s.startsWith("Playing")) return "Playing normally.";
        if (
            s.startsWith("Buffering")
        ) return "Waiting for the picture. The internet or the channel is slow at the moment.";
        if (s.startsWith("Opening") || s.startsWith("Finding")) return "Starting the channel.";
        if (s.startsWith("Reopening")) {
            String tries = between(s, "(", ")");
            return (
                "The channel stopped, so MovieHub is reconnecting it" +
                (tries.isEmpty() ? "." : " (try " + tries + ").")
            );
        }
        if (
            s.startsWith("Failed")
        ) return "This channel isn't working right now. Choose Source to try another copy, or pick another channel.";
        return "Nothing is playing.";
    }

    /** Live TV player events in plain words, by how each starts; the rest are technical. */
    private static final String[][] LIVE_EVENTS = {
        { "New channel", "Channel started" },
        { "Link found", "Found the channel's stream" },
        { "Link not found", "Couldn't find the channel's stream" },
        { "Buffering", "Waited for the picture" },
        { "Stream ended", "The channel's stream ended" },
        { "Error ", "The channel stopped with an error" },
        { "Moving to another source", "Switched to another copy of the channel" },
        { "Reopening the channel", "Reconnected the channel" },
        { "Provider link renewed", "Refreshed the channel's link" },
        { "Link renewal failed", "Couldn't refresh the channel's link; still playing" },
        { "Better source kept", "Switched to a better copy of the channel" },
        { "Poor picture", "The picture kept pausing" },
        { "Moving to smoother source", "Switched to a smoother copy of the channel" },
        { "Smoother source kept", "Kept the smoother copy of the channel" },
        { "Other source did no better", "The other copy was no better, so went back" },
        { "Every source failed", "No copy of the channel worked; trying again shortly" },
        { "Trying the last working source", "Trying the copy that worked last" },
        { "Back to live", "Back to live" },
        { "Picture stopped advancing", "The picture froze while the sound played on" },
        { "Picture moving again", "The picture is moving again" },
        { "Sound ran short", "The sound skipped" },
    };

    /** A Live TV player event in plain words, or null when it is only of technical interest. */
    static String liveEvent(String event) {
        if (event == null) return null;
        for (String[] known : LIVE_EVENTS) if (event.startsWith(known[0])) return known[1];
        return null;
    }

    /** The text between the first {@code open} and the next {@code close}, or "". */
    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        int end = start < 0 ? -1 : text.indexOf(close, start + 1);
        return start < 0 || end < 0 ? "" : text.substring(start + open.length(), end);
    }

    /**
     * Source names as shown in a list: a name that appears more than once gets "copy 2", "copy 3"…
     * on its later appearances, so separate sources with the same name can be told apart. No source
     * is merged or dropped: two with the same name can need different settings to play.
     */
    static List<String> numberRepeats(List<String> names) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (String name : names) {
            int count = seen.merge(name, 1, Integer::sum);
            out.add(count == 1 ? name : name + " · copy " + count);
        }
        return out;
    }

    /** Shown when no website had a stream for a title. */
    static String noStream(int websitesChecked) {
        return (
            "No working copy found" +
            (websitesChecked > 0 ? " on the " + websitesChecked + " websites checked" : "") +
            ". Try again later, or choose another title."
        );
    }
}
