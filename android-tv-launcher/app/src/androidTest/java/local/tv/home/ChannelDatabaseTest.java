package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real Android SQLite and Keystore checks. */
@RunWith(AndroidJUnit4.class)
public class ChannelDatabaseTest {

    private ChannelDatabase db;

    @Before
    public void prepare() {
        db = ChannelDatabase.get(ApplicationProvider.getApplicationContext());
        db.clear();
    }

    @After
    public void cleanup() {
        db.clear();
    }

    private Playlist.Channel channel(int i) {
        return new Playlist.Channel(
            "Channel " + i,
            "https://example.test/live/private-user/private-password/" + i + ".ts",
            i % 2 == 0 ? "Sports" : "News",
            "",
            ""
        );
    }

    private void importRows(int count) throws Exception {
        ChannelDatabase.Writer writer = db.writer("test", () -> {}, n -> {});
        try {
            for (int i = 0; i < count; i++) writer.accept(channel(i));
            writer.commit();
        } finally {
            writer.abort();
        }
    }

    @Test
    public void paginationFavouritesAndSearchHaveNoSilentCap() throws Exception {
        importRows(1301);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1301; i++) keys.add(ChannelQuery.key(channel(i).url));
        Set<Long> seen = new HashSet<>();
        int offset = 0;
        while (true) {
            List<ChannelDatabase.Row> page = db.browse("favourites", "", keys, offset);
            int shown = Math.min(ChannelDatabase.PAGE, page.size());
            for (int i = 0; i < shown; i++) assertTrue("No duplicated row", seen.add(page.get(i).id));
            if (page.size() <= ChannelDatabase.PAGE) break;
            offset += ChannelDatabase.PAGE;
        }
        assertEquals(1301, seen.size());
        assertEquals(1, db.browse("all", "Channel 1300", keys, 0).size());
        assertEquals(0, db.browse("sports", "Channel 1299", keys, 0).size());
        assertTrue(db.browse("favourites", "\" OR *", Collections.emptySet(), 0).isEmpty());
        assertEquals(1, db.browse("group:News", "Channel 1299", keys, 0).size());
        assertEquals(0, db.browse("group:Sports", "Channel 1299", keys, 0).size());
        assertTrue(db.browse("group:News OR 1=1", "", keys, 0).isEmpty());
    }

    @Test
    public void failedRefreshKeepsPreviousRowsAndUrlsAreEncrypted() throws Exception {
        importRows(3);
        assertEquals(channel(0).url, db.url(db.all(0).get(0).id, ChannelQuery.key(channel(0).url)));
        ChannelDatabase.Writer writer = db.writer("test", () -> {}, n -> {});
        try {
            writer.accept(channel(99));
        } finally {
            writer.abort();
        }
        assertEquals(3, db.count());
        assertEquals(channel(0).url, db.url(db.all(0).get(0).id, ChannelQuery.key(channel(0).url)));
        try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT url FROM channels LIMIT 1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(0));
            assertFalse(
                new String(cursor.getBlob(0), java.nio.charset.StandardCharsets.UTF_8).contains(
                    "private-password"
                )
            );
        }
    }

    @Test
    public void schemaUpgradePreservesCatalogueAndKey() throws Exception {
        importRows(3);
        db.close();
        Context context = ApplicationProvider.getApplicationContext();
        try (
            SQLiteDatabase old = SQLiteDatabase.openDatabase(
                context.getDatabasePath("channels-v2.db").getPath(),
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
        ) {
            old.execSQL("DROP TABLE favourite_keys");
            old.setVersion(1);
        }
        assertEquals(3, db.count());
        assertEquals(channel(0).url, db.url(db.all(0).get(0).id, ChannelQuery.key(channel(0).url)));
        assertEquals(
            1,
            db.browse("favourites", "", Collections.singleton(ChannelQuery.key(channel(2).url)), 0).size()
        );
    }

    @Test
    public void theSameChannelFromTwoListsIsShownOnceWithABackup() throws Exception {
        for (String source : new String[] { "free", "fta" }) {
            ChannelDatabase.Writer writer = db.writer(source, () -> {}, n -> {});
            try {
                writer.accept(
                    new Playlist.Channel(
                        source.equals("fta") ? "BBC One Ⓖ" : "BBC One (1080p)",
                        "https://example.test/" + source + "/bbc1.m3u8",
                        "General",
                        "",
                        source.equals("fta") ? "BBCOne.uk" : "BBCOne.uk@HD"
                    )
                );
                writer.accept(
                    new Playlist.Channel(
                        "Only in " + source,
                        "https://example.test/" + source + "/x.m3u8",
                        "General",
                        "",
                        ""
                    )
                );
                writer.commit();
            } finally {
                writer.abort();
            }
        }
        List<ChannelDatabase.Row> rows = db.all(0);
        assertEquals("BBC One once, plus one channel only in each list", 3, rows.size());
        assertEquals("Free-TV comes first and its name is cleaned", "BBC One", rows.get(0).name);
        List<ChannelDatabase.Row> copies = db.copies(rows.get(0));
        assertEquals(2, copies.size());
        assertTrue(db.url(copies.get(1).id, copies.get(1).key).contains("/free/"));
    }

    @Test
    public void channelsGetSatelliteNumbersAndSections() throws Exception {
        ChannelDatabase.Writer writer = db.writer("fta", () -> {}, n -> {});
        try {
            writer.accept(
                new Playlist.Channel("Film4", "https://example.test/film4.m3u8", "UK", "", "Film4.uk")
            );
            writer.accept(
                new Playlist.Channel("BBC One", "https://example.test/bbc1.m3u8", "UK", "", "BBCOne.uk")
            );
            writer.accept(
                new Playlist.Channel("Local Now", "https://example.test/local.m3u8", "News", "", "")
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        Set<String> none = Collections.emptySet();
        List<ChannelDatabase.Row> all = db.all(0);
        assertEquals("BBC One", all.get(0).name);
        assertEquals(101, all.get(0).number);
        assertEquals("Film4", all.get(1).name);
        assertEquals(313, all.get(1).number);
        assertEquals("Movies", all.get(1).section);
        assertEquals("Unknown channels follow from 1001", 1001, all.get(2).number);
        assertEquals("News", all.get(2).section);
        assertEquals(1, db.position("all", "", none, 313, false));
        assertEquals("No channel 200", -1, db.position("all", "", none, 200, false));
        assertEquals("Nearest above 200 is 313", 1, db.position("all", "", none, 200, true));
        assertEquals("Past the end: the last channel", 2, db.position("all", "", none, 5000, true));
        assertEquals(1, db.browse("section:Movies", "", none, 0).size());
        assertTrue(db.sections().containsAll(Arrays.asList("Entertainment", "Movies", "News")));
        assertEquals("Film4", db.byKey(ChannelQuery.key("https://example.test/film4.m3u8")).name);
    }
}
