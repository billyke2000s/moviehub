package local.tv.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guide's one line about providers that failed to refresh, grouped by why, for example
 * "Unavailable now — geo-locked: LG Channels (US), Local Now (US) · timed out: Plex (saved
 * channels kept)". Reasons come only from {@link ProviderFailure}'s fixed summaries, never from
 * provider text.
 */
final class ProviderOutage {

    private ProviderOutage() {}

    /**
     * Why {@code label} failed, from its {@link ProviderFailure#describe} summary. HTTP 403 and 451
     * are how these services refuse a viewer outside their country. A region-tagged service (its
     * label names a country) that gave up without an HTTP error usually got the service's
     * "not in your country" page instead of a catalogue, so that is called likely, not certain.
     */
    static String reason(String label, String failure) {
        String f = failure == null ? "" : failure;
        if (f.equals("Provider returned HTTP 403") || f.equals("Provider returned HTTP 451")) {
            return "geo-locked";
        }
        if (f.startsWith("Provider error (") && regional(label)) return "likely geo-locked";
        if (
            f.equals("Connection timed out") ||
            f.equals("Provider refresh timed out") ||
            f.equals("Provider processing timed out")
        ) return "timed out";
        if (f.startsWith("Provider returned HTTP ")) return f.substring("Provider returned ".length());
        if (f.isEmpty()) return "failed";
        String text = f.startsWith("Provider ") ? f.substring("Provider ".length()) : f;
        // "Connection failed" reads as "connection failed"; an initialism such as DNS stays.
        boolean word = text.length() > 1 && Character.isLowerCase(text.charAt(1));
        return word ? Character.toLowerCase(text.charAt(0)) + text.substring(1) : text;
    }

    /** True when the label names the country the service is for, as "LG Channels (US)" does. */
    static boolean regional(String label) {
        return (
            label != null &&
            (label.contains("(US") ||
                label.contains("(UK)") ||
                label.contains("(GB)") ||
                label.contains("Canada)"))
        );
    }

    /** {@code failed} holds {label, failure summary} pairs in refresh order; "" when empty. */
    static String summary(List<String[]> failed) {
        if (failed.isEmpty()) return "";
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (String[] item : failed) {
            byReason.computeIfAbsent(reason(item[0], item[1]), k -> new ArrayList<>()).add(item[0]);
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : byReason.entrySet()) {
            groups.add(group.getKey() + ": " + String.join(", ", group.getValue()));
        }
        return "Unavailable now — " + String.join(" · ", groups) + " (saved channels kept)";
    }
}
