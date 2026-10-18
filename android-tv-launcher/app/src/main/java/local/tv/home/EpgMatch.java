package local.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches channels to programme-guide channels. Free channel lists and free guides name channels
 * differently ("BBC One (1080p)" with id "BBCOne.uk@HD" against "BBC.One.HD.uk" / "BBC One HD"), so
 * both sides are reduced to a plain key ("bbcone"): lower case, without bracketed notes, quality
 * words, the country suffix and punctuation.
 */
final class EpgMatch {

    private static final Set<String> QUALITY = new HashSet<>(
        Arrays.asList("hd", "uhd", "fhd", "sd", "4k", "hq", "sdi", "1080p", "720p", "576p", "480p", "360p")
    );

    private EpgMatch() {}

    /** The plain key for a channel name or guide id, or "" when nothing is left. */
    static String key(String value) {
        if (value == null) return "";
        String text = value.toLowerCase(Locale.ROOT);
        int at = text.indexOf('@');
        if (at >= 0) text = text.substring(0, at); // iptv-org feed suffix: "bbcone.uk@hd"
        text = text.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        text = text.replaceAll("\\.[a-z]{2}$", ""); // country suffix: ".uk"
        StringBuilder out = new StringBuilder();
        for (String token : text.split("[^a-z0-9+]+"))
            if (!token.isEmpty() && !QUALITY.contains(token)) out.append(token);
        return out.toString();
    }

    /** The country of an id like "BBCOne.uk@HD" ("uk"), or "" when it has none. */
    static String country(String id) {
        if (id == null) return "";
        String text = id.toLowerCase(Locale.ROOT);
        int at = text.indexOf('@');
        if (at >= 0) text = text.substring(0, at);
        int dot = text.lastIndexOf('.');
        return dot >= 0 && text.length() - dot == 3 ? text.substring(dot + 1) : "";
    }

    /**
     * Keys a channel can be found under in a guide for {@code guideCountry}, or none when the
     * channel's id names another country (so "Gold" in Italy does not get UK Gold's listings).
     */
    static List<String> keys(String epgId, String name, String guideCountry) {
        List<String> out = new ArrayList<>();
        String country = country(epgId);
        if (!country.isEmpty() && !sameCountry(country, guideCountry)) return out;
        for (String candidate : new String[] { key(epgId), key(name) })
            if (!candidate.isEmpty() && !out.contains(candidate)) out.add(candidate);
        return out;
    }

    private static boolean sameCountry(String a, String b) {
        String x = a.equals("gb") ? "uk" : a,
            y = b.equals("gb") ? "uk" : b;
        return x.equals(y);
    }
}
