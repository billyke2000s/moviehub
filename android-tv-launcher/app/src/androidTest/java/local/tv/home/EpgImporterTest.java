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
        db.clear();
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
            "\" channel=\"BBC.One.HD.uk\"><title>EastEnders</title><desc>Drama in Walford.</desc><category>Soap</category></programme>" +
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
                    String category
                ) {
                    programmes.add(new Object[] { channel, a, b, title, desc, category });
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
                    (String) p[5]
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
        assertEquals("BBC One has its satellite number", 101, bbc.number);
    }
}
