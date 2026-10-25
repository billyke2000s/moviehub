package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Reading an XMLTV guide and matching it to saved channels, with a fixture guide (no network). */
@RunWith(AndroidJUnit4.class)
public class EpgImporterTest {

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ChannelDatabase db;

    @Before
    public void prepare() throws Exception {
        db = ChannelDatabase.get(context);
        db.clear();
        ChannelDatabase.Writer writer = db.writer("epg-test", () -> {}, n -> {});
        try {
            writer.accept(
                new Playlist.Channel(
                    "BBC One (1080p)",
                    "https://example.test/1.m3u8",
                    "General",
                    "",
                    "BBCOne.uk@HD"
                )
            );
            writer.accept(
                new Playlist.Channel("Gold", "https://example.test/2.m3u8", "General", "", "Gold.it")
            );
            writer.commit();
        } finally {
            writer.abort();
        }
    }

    @After
    public void cleanup() {
        EpgImporter.testGuides = null;
        EpgImporter.testOpener = null;
        db.clear();
    }

    private static String stamp(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    /** A guide with one programme (titled "{title} on {id}") on each channel id/name pair. */
    private static String guide(long now, String title, String... idsAndNames) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><tv>");
        for (int i = 0; i < idsAndNames.length; i += 2) xml.append("<channel id=\"")
            .append(idsAndNames[i])
            .append("\"><display-name>")
            .append(idsAndNames[i + 1])
            .append("</display-name></channel>");
        for (int i = 0; i < idsAndNames.length; i += 2) xml.append("<programme start=\"")
            .append(stamp(now - 600_000))
            .append("\" stop=\"")
            .append(stamp(now + 3_000_000))
            .append("\" channel=\"")
            .append(idsAndNames[i])
            .append("\"><title>")
            .append(title)
            .append(" on ")
            .append(idsAndNames[i])
            .append("</title></programme>");
        return xml.append("</tv>").toString();
    }

    private String nowOn(String key, long now) {
        ChannelDatabase.Row row = db.byKey(ChannelQuery.key(key));
        assertNotNull(key, row);
        List<ChannelDatabase.Programme> on = db.programmes(
            row,
            EpgImporter.COUNTRY,
            now - 60_000,
            now + 60_000
        );
        return on.isEmpty() ? "" : on.get(0).title;
    }

    @Test
    public void guidesInPriorityOrderLondonIsNationalAndCountriesStayApart() throws Exception {
        db.clear();
        ChannelDatabase.Writer writer = db.writer("epg-test", () -> {}, n -> {});
        try {
            String[][] channels = {
                { "BBC One Ⓖ", "https://example.test/a", "BBCOne.uk" },
                { "E4", "https://example.test/b", "E4.uk" },
                { "Dave", "https://example.test/c", "Dave.uk" },
                { "RTÉ One", "https://example.test/d", "RTEOne.ie" },
                { "Eurosport 1", "https://example.test/e", "Eurosport1.de" },
            };
            for (String[] c : channels) writer.accept(new Playlist.Channel(c[0], c[1], "General", "", c[2]));
            writer.commit();
        } finally {
            writer.abort();
        }
        long now = System.currentTimeMillis();
        Map<String, String> files = new HashMap<>();
        files.put(
            "freeview",
            guide(now, "Freeview", "BBCOneLondonHD.uk", "BBC One London HD", "E4.uk", "E4")
        );
        files.put(
            "uk",
            guide(
                now,
                "UK1",
                "BBCOne.uk",
                "BBC One",
                "E4.uk",
                "E4",
                "Dave.uk",
                "Dave",
                "Eurosport1.uk",
                "Eurosport 1"
            )
        );
        files.put("ie", guide(now, "IE1", "RTEOne.ie", "RTÉ One"));
        EpgImporter.testGuides = new EpgImporter.Guide[] {
            new EpgImporter.Guide("https://guides.test/freeview", "uk", true),
            new EpgImporter.Guide("https://guides.test/uk", "uk", false),
            new EpgImporter.Guide("https://guides.test/ie", "ie", false),
        };
        EpgImporter.testOpener = url ->
            new ByteArrayInputStream(
                files.get(url.substring(url.lastIndexOf('/') + 1)).getBytes(StandardCharsets.UTF_8)
            );
        EpgImporter.Result result = EpgImporter.run(context, db, () -> {});
        assertTrue(result.programmes >= 4);
        // The London feed stands for the national BBC One, and Freeview beats the later UK guide.
        assertEquals("Freeview on BBCOneLondonHD.uk", nowOn("https://example.test/a", now));
        assertEquals("Freeview on E4.uk", nowOn("https://example.test/b", now));
        // A channel only the second guide has is filled from it.
        assertEquals("UK1 on Dave.uk", nowOn("https://example.test/c", now));
        // An Irish channel uses the Irish guide.
        assertEquals("IE1 on RTEOne.ie", nowOn("https://example.test/d", now));
        // A channel of a country with no guide still gets none (not the UK Eurosport's listings).
        assertEquals("", nowOn("https://example.test/e", now));
    }

    @Test
    public void oneFailingGuideKeepsTheOthers() throws Exception {
        long now = System.currentTimeMillis();
        EpgImporter.testGuides = new EpgImporter.Guide[] {
            new EpgImporter.Guide("https://guides.test/broken", "uk", true),
            new EpgImporter.Guide("https://guides.test/uk", "uk", false),
        };
        EpgImporter.testOpener = url -> {
            if (url.endsWith("broken")) throw new java.io.UncheckedIOException(
                new java.io.IOException("down")
            );
            return new ByteArrayInputStream(
                guide(now, "UK1", "BBC.One.HD.uk", "BBC One HD").getBytes(StandardCharsets.UTF_8)
            );
        };
        assertEquals(1, EpgImporter.run(context, db, () -> {}).channels);
        assertEquals("UK1 on BBC.One.HD.uk", nowOn("https://example.test/1.m3u8", now));
    }

    static String xmltv(String start, String stop) {
        return (
            "<?xml version=\"1.0\"?><tv>" +
            "<channel id=\"BBC.One.HD.uk\"><display-name>BBC One HD</display-name></channel>" +
            "<channel id=\"Gold.uk\"><display-name>Gold</display-name></channel>" +
            "<programme start=\"" +
            start +
            "\" stop=\"" +
            stop +
            "\" channel=\"BBC.One.HD.uk\"><title>EastEnders</title><desc>Drama in Walford.</desc><category>Soap</category><episode-num system=\"onscreen\">S1 Ep3</episode-num><video><quality>HDTV</quality></video><subtitles type=\"teletext\"/><rating system=\"UK\"><value>15</value></rating><new/></programme>" +
            "<programme start=\"" +
            start +
            "\" stop=\"" +
            stop +
            "\" channel=\"Gold.uk\"><title>Only Fools</title></programme>" +
            "<programme start=\"20000101000000 +0000\" stop=\"20000101003000 +0000\" channel=\"BBC.One.HD.uk\"><title>Old</title></programme>" +
            "</tv>"
        );
    }

    @Test
    public void matchesChannelsAcrossNamingStylesAndKeepsOnlyCurrentProgrammes() throws Exception {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        long now = System.currentTimeMillis();
        String start = format.format(new Date(now - 10 * 60000)),
            stop = format.format(new Date(now + 20 * 60000));
        assertEquals(now - 10 * 60000, EpgImporter.time(start), 1000);

        Set<String> wanted = db.guideKeys(EpgImporter.COUNTRY);
        assertTrue(wanted.contains("bbcone"));
        assertFalse("A channel from another country is not matched to the UK guide", wanted.contains("gold"));
        List<String[]> matches = new ArrayList<>();
        List<Object[]> programmes = new ArrayList<>();
        EpgImporter.Result result = EpgImporter.parse(
            new ByteArrayInputStream(xmltv(start, stop).getBytes(StandardCharsets.UTF_8)),
            wanted,
            now - EpgImporter.BEHIND_MS,
            now + EpgImporter.AHEAD_MS,
            new EpgImporter.Sink() {
                public void match(String key, String channel) {
                    matches.add(new String[] { key, channel });
                }

                public void programme(
                    String channel,
                    long a,
                    long b,
                    String title,
                    String desc,
                    String category,
                    String flags
                ) {
                    programmes.add(new Object[] { channel, a, b, title, desc, category, flags });
                }
            }
        );
        assertEquals(1, result.channels);
        assertEquals("Old and unmatched programmes are skipped", 1, result.programmes);

        ChannelDatabase.GuideWriter writer = db.new GuideWriter();
        try {
            for (String[] m : matches) writer.match(m[0], m[1]);
            for (Object[] p : programmes)
                writer.programme(
                    (String) p[0],
                    (Long) p[1],
                    (Long) p[2],
                    (String) p[3],
                    (String) p[4],
                    (String) p[5],
                    (String) p[6]
                );
            writer.commit();
        } finally {
            writer.finish();
        }
        ChannelDatabase.Row bbc = db.all(0).get(0);
        List<ChannelDatabase.Programme> on = db.programmes(bbc, EpgImporter.COUNTRY, now, now + 90 * 60000);
        assertEquals(1, on.size());
        assertEquals("EastEnders", on.get(0).title);
        assertEquals("Drama in Walford.", on.get(0).description);
        assertEquals("Soap", on.get(0).category);
        assertEquals("S1 Ep3|15|S|HD|New", on.get(0).flags);
        assertEquals("S1 Ep3  [15]  [S]  [HD]  [New]", on.get(0).tags());
        assertEquals("BBC One has its satellite number", 101, bbc.number);
    }
}
