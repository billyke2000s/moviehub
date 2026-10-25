package local.tv.home;

import java.util.ArrayList;
import java.util.List;

public final class ProviderOutageTest {

    private static int checks;

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Outage " + checks + ": expected [" + expected + "] got [" + actual + "]"
            );
        }
        checks++;
    }

    public static void main(String[] args) {
        same("geo-locked", ProviderOutage.reason("LG Channels (US)", "Provider returned HTTP 403"));
        same("geo-locked", ProviderOutage.reason("Plex", "Provider returned HTTP 451"));
        same("likely geo-locked", ProviderOutage.reason("Local Now (US)", "Provider error (EcmaError)"));
        same(
            "likely geo-locked",
            ProviderOutage.reason("Samsung TV Plus (GB)", "Provider error (JavaScriptException)")
        );
        same("likely geo-locked", ProviderOutage.reason("LG Channels (UK)", "Provider error (EcmaError)"));
        // A service with no country in its name is not called geo-locked without an HTTP refusal.
        same("error (EcmaError)", ProviderOutage.reason("ZLive", "Provider error (EcmaError)"));
        same("timed out", ProviderOutage.reason("Plex", "Connection timed out"));
        same("timed out", ProviderOutage.reason("Roku Channel", "Provider refresh timed out"));
        same("HTTP 500", ProviderOutage.reason("Roku Channel", "Provider returned HTTP 500"));
        same("DNS lookup failed", ProviderOutage.reason("Plex", "DNS lookup failed"));
        same("failed", ProviderOutage.reason("Plex", ""));
        same("", ProviderOutage.summary(new ArrayList<>()));

        List<String[]> failed = new ArrayList<>();
        failed.add(new String[] { "LG Channels (US)", "Provider returned HTTP 403" });
        failed.add(new String[] { "Local Now (US)", "Provider error (EcmaError)" });
        failed.add(new String[] { "Plex", "Connection timed out" });
        failed.add(new String[] { "Roku Channel", "Provider returned HTTP 403" });
        same(
            "Unavailable now — geo-locked: LG Channels (US), Roku Channel · likely geo-locked: " +
                "Local Now (US) · timed out: Plex (saved channels kept)",
            ProviderOutage.summary(failed)
        );
        System.out.println(checks + " provider outage checks passed");
    }
}
