package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/**
 * Free live channels from the public iptv-org playlists (https://github.com/iptv-org/iptv), used
 * for Live TV when no Xtream or M3U provider is connected. iptv-org lists publicly available
 * streams submitted by its users; it hosts no video itself.
 */
final class FreeChannels {

    static final String ROOT = "https://iptv-org.github.io/iptv/";
    /** Preference: "auto" (on only without a provider), "off", "all", or an iptv-org country code. */
    static final String PREF = "freeChannels";

    /** Choices offered in Settings: preference value and label. */
    static final String[][] CHOICES = {
        { "auto", "Automatic (only without a TV provider)" },
        { "off", "Off" },
        { "uk", "United Kingdom" },
        { "ie", "Ireland" },
        { "us", "United States" },
        { "ca", "Canada" },
        { "au", "Australia" },
        { "all", "All countries (very large)" },
    };

    private FreeChannels() {}

    static String choice(Context context) {
        return prefs(context).getString(PREF, "auto");
    }

    static String label(String value) {
        for (String[] item : CHOICES) if (item[0].equals(value)) return item[1];
        return value.toUpperCase(Locale.ROOT);
    }

    /**
     * The playlist to import, or "" when free channels are off. "auto" uses this box's country
     * (iptv-org calls the United Kingdom "uk", not "gb") and only when no provider is connected.
     */
    static String playlist(Context context, boolean providerConnected) {
        String choice = choice(context);
        if (choice.equals("off") || (choice.equals("auto") && providerConnected)) return "";
        if (choice.equals("all")) return ROOT + "index.m3u";
        String country = choice.equals("auto") ? Locale.getDefault().getCountry() : choice;
        country = country.toLowerCase(Locale.ROOT);
        if (country.equals("gb")) country = "uk";
        if (!country.matches("[a-z]{2}")) country = "uk";
        return ROOT + "countries/" + country + ".m3u";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE);
    }
}
