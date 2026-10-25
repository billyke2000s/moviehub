package local.tv.home;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.xmlpull.v1.XmlPullParser;

/**
 * Downloads a free XMLTV programme guide and keeps the next day of programmes for the channels
 * MovieHub has, so the Live TV guide can show what is on. The guide is read as a stream, so a
 * large file never has to fit in memory.
 */
final class EpgImporter {

    /** Free UK guide (epgshare01, rebuilt daily). Matching is by plain channel key; see EpgMatch. */
    static final String UK_GUIDE = "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz";
    static final String COUNTRY = "uk";
    /** The guide downloaded; tests set "" to skip the download. */
    static volatile String guideUrl = UK_GUIDE;
    /** Programmes kept: from two hours ago to this far ahead. */
    static final long AHEAD_MS = 26L * 60 * 60 * 1000,
        BEHIND_MS = 2L * 60 * 60 * 1000;
    static final long MAX_BYTES = 300L * 1024 * 1024;

    /** Where parsed guide data goes. */
    interface Sink {
        void match(String key, String channel) throws Exception;

        void programme(
            String channel,
            long start,
            long stop,
            String title,
            String description,
            String category,
            String flags
        ) throws Exception;
    }

    static final class Result {

        int channels, programmes;
    }

    private EpgImporter() {}

    /** Downloads and stores the guide. Returns how many channels and programmes matched. */
    static Result run(Context context, ChannelDatabase db, ChannelDatabase.Cancel cancel) throws Exception {
        Set<String> wanted = db.guideKeys(COUNTRY);
        Result result = new Result();
        if (wanted.isEmpty() || guideUrl.isEmpty()) return result;
        long started = Timings.start();
        // Collected in memory first (a day of listings is small), then written in one short
        // transaction, so the database is never held while the guide downloads.
        List<String[]> matches = new ArrayList<>();
        List<Object[]> programmes = new ArrayList<>();
        try (InputStream raw = Network.open(guideUrl, null, MAX_BYTES, false)) {
            InputStream in = new BufferedInputStream(raw, 64 * 1024);
            in.mark(2);
            boolean gzip = in.read() == 0x1f && in.read() == 0x8b;
            in.reset();
            if (gzip) in = new GZIPInputStream(in, 64 * 1024);
            long now = System.currentTimeMillis();
            result = parse(
                in,
                wanted,
                now - BEHIND_MS,
                now + AHEAD_MS,
                new Sink() {
                    public void match(String key, String channel) {
                        matches.add(new String[] { key, channel });
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
                        programmes.add(new Object[] {
                            channel,
                            start,
                            stop,
                            title,
                            description,
                            category,
                            flags,
                        });
                    }
                }
            );
        }
        // An empty or unreadable guide keeps the previous listings.
        if (result.programmes > 0) {
            ChannelDatabase.GuideWriter writer = db.new GuideWriter();
            try {
                for (String[] match : matches) writer.match(match[0], match[1]);
                for (Object[] item : programmes)
                    writer.programme(
                        (String) item[0],
                        (Long) item[1],
                        (Long) item[2],
                        (String) item[3],
                        (String) item[4],
                        (String) item[5],
                        (String) item[6]
                    );
                writer.commit();
            } finally {
                writer.finish();
            }
        }
        Timings.record("Programme guide download", started);
        return result;
    }

    /** Programme descriptions are cut to this many characters (two or three sentences). */
    static final int DESCRIPTION_MAX = 400;

    /**
     * Reads XMLTV: {@code <channel id>} with {@code <display-name>}s, then {@code <programme start
     * stop channel>} with {@code <title>}, {@code <desc>} and {@code <category>}. Keeps channels whose id or a display name matches a key
     * in {@code wanted}, and their programmes overlapping [from, to).
     */
    static Result parse(InputStream in, Set<String> wanted, long from, long to, Sink sink) throws Exception {
        return parse(in, wanted, from, to, sink, null);
    }

    /**
     * As above, but when {@code idKey} is given a channel matches only by {@code idKey(channel id)}
     * (no display names): for a provider's own guide, whose ids identify its channels exactly.
     */
    static Result parse(
        InputStream in,
        Set<String> wanted,
        long from,
        long to,
        Sink sink,
        java.util.function.UnaryOperator<String> idKey
    ) throws Exception {
        XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, null);
        Map<String, Boolean> kept = new HashMap<>();
        Result result = new Result();
        String channelId = null,
            programmeChannel = null,
            element = null;
        List<String> names = new ArrayList<>();
        long start = 0,
            stop = 0;
        String title = null,
            description = null,
            category = null;
        // Details the satellite guide showed as icons: subtitles, HD, age rating, new, episode.
        boolean subtitles = false,
            hd = false,
            fresh = false,
            inRating = false,
            onscreenEpisode = false;
        String rating = null,
            episode = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                element = parser.getName();
                if ("channel".equals(element)) {
                    channelId = parser.getAttributeValue(null, "id");
                    names.clear();
                } else if ("programme".equals(element)) {
                    programmeChannel = parser.getAttributeValue(null, "channel");
                    // Most of the guide is channels not saved here: skip their programmes without
                    // reading times or text. (Channels come before programmes in XMLTV.)
                    if (!kept.containsKey(programmeChannel)) programmeChannel = null;
                    start = programmeChannel == null ? 0 : time(parser.getAttributeValue(null, "start"));
                    stop = programmeChannel == null ? 0 : time(parser.getAttributeValue(null, "stop"));
                    title = null;
                    description = null;
                    category = null;
                    subtitles = hd = fresh = false;
                    rating = episode = null;
                } else if (programmeChannel != null) {
                    if ("subtitles".equals(element)) subtitles = true;
                    else if ("new".equals(element) || "premiere".equals(element)) fresh = true;
                    else if ("rating".equals(element)) inRating = true;
                    else if ("episode-num".equals(element)) onscreenEpisode = "onscreen".equals(
                        parser.getAttributeValue(null, "system")
                    );
                }
            } else if (event == XmlPullParser.TEXT && element != null) {
                if (channelId != null && "display-name".equals(element)) names.add(parser.getText());
                else if (programmeChannel != null && "title".equals(element) && title == null) title =
                    parser.getText();
                else if (
                    programmeChannel != null && "desc".equals(element) && description == null
                ) description = parser.getText();
                else if (
                    programmeChannel != null && "category".equals(element) && category == null
                ) category = parser.getText();
                else if (programmeChannel != null && "quality".equals(element)) hd |= parser
                    .getText()
                    .toUpperCase(java.util.Locale.ROOT)
                    .contains("HD");
                else if (
                    programmeChannel != null && inRating && "value".equals(element) && rating == null
                ) rating = parser.getText().trim();
                else if (
                    programmeChannel != null &&
                    "episode-num".equals(element) &&
                    onscreenEpisode &&
                    episode == null
                ) episode = parser.getText().trim();
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("channel".equals(name) && channelId != null) {
                    List<String> keys = new ArrayList<>();
                    if (idKey != null) addKey(keys, idKey.apply(channelId), wanted);
                    else {
                        for (String candidate : names) addKey(keys, EpgMatch.key(candidate), wanted);
                        addKey(keys, EpgMatch.key(channelId), wanted);
                    }
                    for (String key : keys) sink.match(key, channelId);
                    if (!keys.isEmpty()) {
                        kept.put(channelId, true);
                        result.channels++;
                    }
                    channelId = null;
                } else if ("programme".equals(name) && programmeChannel != null) {
                    if (
                        kept.containsKey(programmeChannel) &&
                        title != null &&
                        stop > from &&
                        start < to &&
                        stop > start
                    ) {
                        sink.programme(
                            programmeChannel,
                            start,
                            stop,
                            title.trim(),
                            shorten(description),
                            category == null ? "" : category.trim(),
                            flags(episode, rating, subtitles, hd, fresh)
                        );
                        result.programmes++;
                    }
                    programmeChannel = null;
                }
                if ("rating".equals(name)) inRating = false;
                element = null;
            }
        }
        return result;
    }

    /**
     * A programme's details for the guide's tags, separated by "|": the on-screen episode
     * ("S1 Ep3"), the age rating, "S" (subtitles), "HD" and "New", in that order when present.
     */
    static String flags(String episode, String rating, boolean subtitles, boolean hd, boolean fresh) {
        List<String> out = new ArrayList<>();
        if (episode != null && !episode.isEmpty() && episode.length() <= 20) out.add(episode);
        if (rating != null && !rating.isEmpty() && rating.length() <= 6) out.add(rating);
        if (subtitles) out.add("S");
        if (hd) out.add("HD");
        if (fresh) out.add("New");
        return String.join("|", out);
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String value = text.trim();
        return value.length() <= DESCRIPTION_MAX
            ? value
            : value.substring(0, DESCRIPTION_MAX - 1).trim() + "…";
    }

    private static void addKey(List<String> keys, String key, Set<String> wanted) {
        if (!key.isEmpty() && wanted.contains(key) && !keys.contains(key)) keys.add(key);
    }

    /** XMLTV time: "20261018200000 +0100" (the zone may be missing, then UTC). */
    static long time(String value) {
        if (value == null || value.length() < 14) return 0;
        long quick = EpgMatch.time(value);
        if (quick != Long.MIN_VALUE) return quick;
        try {
            String text = value.trim();
            if (text.length() == 14) text += " +0000";
            return new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).parse(text).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
