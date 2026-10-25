package local.tv.home;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;

/**
 * Programme listings for Plex channels. Plex's catalogue has no listings, and its own guide is
 * one request per channel per day (about 700 a refresh), so the listings come instead from the
 * public XMLTV files at i.mjh.nz/Plex, rebuilt daily from Plex's guide. Their channel ids are
 * Plex's compound "&lt;server&gt;-&lt;channel&gt;" ids; the part after the last dash is the
 * stable id MovieHub stores, so channels match exactly, never by name.
 */
final class PlexGuide {

    static final String BASE = "https://i.mjh.nz/Plex/";
    /**
     * The UK file first; the US one when the UK file covers under half the channels (a viewer
     * whose Plex lineup is American). Plex chooses the lineup from where the viewer is.
     */
    static final String[] REGIONS = { "gb", "us" };
    /** Compressed download limit; the US file is about 2 MB. */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Tests replace the download; null downloads from {@link #BASE}. */
    static volatile Opener opener;

    interface Opener {
        InputStream open(String region) throws Exception;
    }

    /** One region's matched listings, keyed by MovieHub's channel key ("engineplex:&lt;id&gt;"). */
    static final class Listings {

        final Set<String> channels = new HashSet<>();
        final List<Object[]> programmes = new ArrayList<>();
    }

    private PlexGuide() {}

    /** The stable channel id in a compound Plex id: "abc-123" is "123". */
    static String stableId(String compound) {
        int dash = compound.lastIndexOf('-');
        return dash < 0 ? compound : compound.substring(dash + 1);
    }

    /**
     * Downloads listings for the Plex catalogue {@code rows} and stores them. Returns how many
     * channels got listings. A failed or empty download keeps the previous listings.
     */
    static int refresh(ChannelDatabase db, JSONArray rows, ChannelDatabase.Cancel cancel) throws Exception {
        String prefix = AdapterSources.source("plex") + ":";
        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            wanted.add(prefix + rows.getJSONObject(i).getString("channelId"));
        }
        if (wanted.isEmpty()) return 0;
        Listings best = null;
        Exception failure = null;
        for (String region : REGIONS) {
            cancel.check();
            try (InputStream in = open(region)) {
                Listings found = read(in, wanted, prefix, System.currentTimeMillis(), cancel);
                if (best == null || found.channels.size() > best.channels.size()) best = found;
                if (found.channels.size() * 2 >= wanted.size()) break;
            } catch (Exception e) {
                cancel.check();
                failure = e;
            }
        }
        if (best == null || best.programmes.isEmpty()) {
            if (failure != null) throw failure;
            return 0;
        }
        write(db, prefix, best);
        return best.channels.size();
    }

    private static InputStream open(String region) throws Exception {
        Opener test = opener;
        if (test != null) return test.open(region);
        return Network.open(BASE + region + ".xml.gz", null, MAX_BYTES, false);
    }

    /** Reads one XMLTV file (gzipped or not), keeping programmes from two hours ago to a day ahead. */
    static Listings read(
        InputStream raw,
        Set<String> wanted,
        String prefix,
        long now,
        ChannelDatabase.Cancel cancel
    ) throws Exception {
        InputStream in = new BufferedInputStream(raw, 64 * 1024);
        in.mark(2);
        boolean gzip = in.read() == 0x1f && in.read() == 0x8b;
        in.reset();
        if (gzip) in = new GZIPInputStream(in, 64 * 1024);
        Listings out = new Listings();
        // XMLTV id → MovieHub key, for the programmes that follow the channel list.
        Map<String, String> keys = new LinkedHashMap<>();
        EpgImporter.parse(
            in,
            wanted,
            now - EpgImporter.BEHIND_MS,
            now + EpgImporter.AHEAD_MS,
            new EpgImporter.Sink() {
                public void match(String key, String channel) {
                    keys.put(channel, key);
                    out.channels.add(key);
                }

                public void programme(
                    String channel,
                    long start,
                    long stop,
                    String title,
                    String description,
                    String category,
                    String flags
                ) throws Exception {
                    cancel.check();
                    String key = keys.get(channel);
                    if (key == null) return;
                    out.programmes.add(new Object[] {
                        key,
                        start,
                        stop,
                        title,
                        description,
                        category,
                        flags,
                    });
                }
            },
            id -> prefix + stableId(id)
        );
        return out;
    }

    /** Replaces the Plex listings in one transaction, as the provider's own inline guide does. */
    static void write(ChannelDatabase db, String prefix, Listings listings) throws Exception {
        ChannelDatabase.GuideWriter writer = db.new GuideWriter(prefix);
        try {
            for (String key : listings.channels) writer.match(EpgMatch.key(key), key);
            for (Object[] p : listings.programmes) {
                if (writer.programmes >= 50000) break;
                writer.programme(
                    (String) p[0],
                    (Long) p[1],
                    (Long) p[2],
                    (String) p[3],
                    (String) p[4],
                    (String) p[5],
                    (String) p[6]
                );
            }
            writer.commit();
        } finally {
            writer.finish();
        }
    }
}
