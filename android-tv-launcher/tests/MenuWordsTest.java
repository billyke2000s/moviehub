package local.tv.home;

import java.util.Arrays;
import java.util.List;

/** The family Help page's problems and the plain-English player wording. */
public final class MenuWordsTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("Menu words check " + checks + ": " + what);
    }

    private static void same(Object expected, Object actual) {
        check(expected.equals(actual), "expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    private static HelpChecks.Facts healthy() {
        HelpChecks.Facts facts = new HelpChecks.Facts();
        facts.connected = true;
        facts.mbps = 50;
        facts.freeBytes = 8L * 1024 * 1024 * 1024;
        facts.totalBytes = 32L * 1024 * 1024 * 1024;
        facts.preferredQuality = 1080;
        return facts;
    }

    public static void main(String[] args) {
        // A healthy box shows nothing: never a vague "something is wrong".
        check(HelpChecks.problems(healthy()).isEmpty(), "healthy box has no problems");
        HelpChecks.Facts unknown = new HelpChecks.Facts();
        check(HelpChecks.problems(unknown).isEmpty(), "nothing known shows nothing");

        HelpChecks.Facts offline = healthy();
        offline.connected = false;
        List<HelpChecks.Problem> off = HelpChecks.problems(offline);
        same(1, off.size());
        same(HelpChecks.Fix.WIFI_SETTINGS, off.get(0).fix);
        same("Open Wi-Fi settings", off.get(0).action);
        offline.mbps = 1;
        same(1, HelpChecks.problems(offline).size());

        HelpChecks.Facts slow = healthy();
        slow.mbps = 4.6f;
        List<HelpChecks.Problem> slowProblems = HelpChecks.problems(slow);
        same(1, slowProblems.size());
        same("Your internet is too slow for HD (4 Mb/s)", slowProblems.get(0).title);
        same(HelpChecks.Fix.LOWER_QUALITY, slowProblems.get(0).fix);
        same("Use 720p instead", slowProblems.get(0).action);
        same(720, HelpChecks.lowerQuality(slow));
        slow.preferredQuality = 720;
        check(HelpChecks.problems(slow).isEmpty(), "720p already chosen: nothing to lower");
        slow.preferredQuality = 2160;
        slow.mbps = 12;
        List<HelpChecks.Problem> uhd = HelpChecks.problems(slow);
        same("Your internet is too slow for 4K (12 Mb/s)", uhd.get(0).title);
        same("Use 1080p instead", uhd.get(0).action);
        same(1080, HelpChecks.lowerQuality(slow));
        slow.mbps = 30;
        check(HelpChecks.problems(slow).isEmpty(), "fast enough for 4K");
        HelpChecks.Facts unmeasured = healthy();
        unmeasured.mbps = -1;
        check(HelpChecks.problems(unmeasured).isEmpty(), "no speed known: no speed problem");

        HelpChecks.Facts full = healthy();
        full.freeBytes = 300L * 1024 * 1024;
        List<HelpChecks.Problem> fullProblems = HelpChecks.problems(full);
        same(1, fullProblems.size());
        same("Storage is full", fullProblems.get(0).title);
        same(HelpChecks.Fix.STORAGE_SETTINGS, fullProblems.get(0).fix);
        check(fullProblems.get(0).detail.contains("300 MB"), "says how much is left");
        full.freeBytes = 900L * 1024 * 1024;
        full.totalBytes = 64L * 1024 * 1024 * 1024;
        same(1, HelpChecks.problems(full).size());
        full.totalBytes = 8L * 1024 * 1024 * 1024;
        check(HelpChecks.problems(full).isEmpty(), "900 MB of 8 GB is not full");
        same("1.5 GB", HelpChecks.megabytes(1536L * 1024 * 1024));

        HelpChecks.Facts update = healthy();
        update.updateReady = true;
        same(HelpChecks.Fix.UPDATE, HelpChecks.problems(update).get(0).fix);

        HelpChecks.Facts many = healthy();
        many.connected = false;
        many.freeBytes = 1;
        many.updateReady = true;
        same(3, HelpChecks.problems(many).size());
        for (HelpChecks.Problem problem : HelpChecks.problems(many)) {
            check(!problem.title.toLowerCase().contains("something"), "specific titles only");
            check(!problem.action.isEmpty(), "every problem has a fix");
        }

        same("Playing normally.", PlainWords.liveStatus("Playing · c2.android.avc.decoder"));
        check(PlainWords.liveStatus("Buffering").startsWith("Waiting"), "buffering");
        same("Starting the channel.", PlainWords.liveStatus("Opening"));
        same("Starting the channel.", PlainWords.liveStatus("Finding the stream"));
        same(
            "The channel stopped, so MovieHub is reconnecting it (try 2 of 3).",
            PlainWords.liveStatus("Reopening (2 of 3)")
        );
        check(PlainWords.liveStatus("Failed: HTTP 403").contains("Source"), "failure names the fix");
        check(!PlainWords.liveStatus("Failed: HTTP 403").contains("403"), "no technical code");
        same("Nothing is playing.", PlainWords.liveStatus("Idle"));
        same("Nothing is playing.", PlainWords.liveStatus(null));

        same(
            Arrays.asList(
                "Site A · 1080p",
                "Site B · 720p",
                "Site A · 1080p · copy 2",
                "Site A · 1080p · copy 3"
            ),
            PlainWords.numberRepeats(
                Arrays.asList("Site A · 1080p", "Site B · 720p", "Site A · 1080p", "Site A · 1080p")
            )
        );
        same(4, PlainWords.numberRepeats(Arrays.asList("a", "a", "a", "b")).size());
        check(PlainWords.noStream(12).contains("12 websites"), "no stream says how many were checked");
        check(!PlainWords.noStream(0).contains("0 websites"), "no count when none known");

        same("Channel started", PlainWords.liveEvent("New channel"));
        same("Found the channel's stream", PlainWords.liveEvent("Link found in 3 s"));
        same("Couldn't find the channel's stream", PlainWords.liveEvent("Link not found after 9 s"));
        same("Reconnected the channel", PlainWords.liveEvent("Reopening the channel (HTTP 403)"));
        check(PlainWords.liveEvent("Video decoder c2.android.avc.decoder") == null, "decoder is technical");
        check(PlainWords.liveEvent("Dropped 12 frames in 1000 ms") == null, "frames are technical");
        check(PlainWords.liveEvent("Picture size 1920×1080") == null, "size is technical");
        check(PlainWords.liveEvent(null) == null, "null event");

        System.out.println(checks + " menu words checks passed");
    }
}
