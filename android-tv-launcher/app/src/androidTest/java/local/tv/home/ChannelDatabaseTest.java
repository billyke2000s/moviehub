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
            old.execSQL("UPDATE channels SET main=0");
            old.setVersion(1);
        }
        assertEquals(3, db.count());
        assertEquals(channel(0).url, db.url(db.all(0).get(0).id, ChannelQuery.key(channel(0).url)));
        assertEquals(
            "The upgrade marks the preferred copies again",
            3,
            db.browse("all", "", Collections.emptySet(), 0).size()
        );
        assertEquals(
            1,
            db.browse("favourites", "", Collections.singleton(ChannelQuery.key(channel(2).url)), 0).size()
        );
    }

    @Test
    public void versionSevenWithoutMainUpgradesAndKeepsChannelsAndFavourites() throws Exception {
        importRows(3);
        String favourite = ChannelQuery.key(channel(2).url);
        Set<String> favourites = Collections.singleton(favourite);
        db.browse("favourites", "", favourites, 0);
        String key = new Secrets(ApplicationProvider.getApplicationContext()).get("channelKey");
        db.close();
        Context context = ApplicationProvider.getApplicationContext();
        try (
            SQLiteDatabase old = SQLiteDatabase.openDatabase(
                context.getDatabasePath("channels-v2.db").getPath(),
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
        ) {
            // Rebuild the actual version-7 table: changing user_version alone leaves the new
            // column in place and would hide the 0.35 -> 0.36 upgrade failure.
            old.beginTransaction();
            try {
                old.execSQL(
                    "CREATE TABLE channels_v7(_id INTEGER PRIMARY KEY,source TEXT NOT NULL," +
                        "position INTEGER NOT NULL,name TEXT NOT NULL,grp TEXT NOT NULL," +
                        "logo TEXT NOT NULL,epg TEXT NOT NULL,key TEXT NOT NULL,url BLOB NOT NULL," +
                        "dkey TEXT NOT NULL DEFAULT '',number INTEGER NOT NULL DEFAULT 0," +
                        "section TEXT NOT NULL DEFAULT '',country TEXT NOT NULL DEFAULT '')"
                );
                String columns = "_id,source,position,name,grp,logo,epg,key,url,dkey,number,section,country";
                old.execSQL("INSERT INTO channels_v7(" + columns + ") SELECT " + columns + " FROM channels");
                old.execSQL("DROP TABLE channels");
                old.execSQL("ALTER TABLE channels_v7 RENAME TO channels");
                old.execSQL("CREATE INDEX channels_order ON channels(source,position)");
                old.execSQL("CREATE INDEX channels_group ON channels(grp,source,position)");
                old.execSQL("CREATE INDEX channels_key ON channels(key)");
                old.execSQL("CREATE INDEX channels_dkey ON channels(dkey)");
                old.execSQL("CREATE INDEX channels_number ON channels(number)");
                old.setVersion(7);
                old.setTransactionSuccessful();
            } finally {
                old.endTransaction();
            }
            try (Cursor columns = old.rawQuery("PRAGMA table_info(channels)", null)) {
                while (columns.moveToNext()) assertNotEquals("main", columns.getString(1));
            }
        }
        assertEquals(3, db.count()); // opens the v7 database and runs the real upgrade
        List<ChannelDatabase.Row> all = db.browse("all", "", Collections.emptySet(), 0);
        assertEquals("The guide can load the existing channels", 3, all.size());
        for (ChannelDatabase.Row row : all) assertTrue(db.url(row.id, row.key).contains("private-password"));
        assertEquals(channel(0).url, db.url(all.get(0).id, ChannelQuery.key(channel(0).url)));
        assertEquals(key, new Secrets(context).get("channelKey"));
        assertEquals(1, db.browse("favourites", "", favourites, 0).size());
        assertEquals(1, db.browse("all", "Channel 2", Collections.emptySet(), 0).size());
        try (Cursor saved = db.getReadableDatabase().rawQuery("SELECT key FROM favourite_keys", null)) {
            assertTrue(saved.moveToFirst());
            assertEquals(favourite, saved.getString(0));
        }
        db.close();
        assertEquals(
            "Reopening the migrated database still works",
            3,
            db.browse("all", "", favourites, 0).size()
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
        // The guide reads all channels from the preferred copies (main=1) through an index; it
        // must give the same list as grouping every copy.
        List<ChannelDatabase.Row> guide = db.browse("all", "", Collections.emptySet(), 0);
        assertEquals(rows.size(), guide.size());
        for (int i = 0; i < rows.size(); i++) assertEquals(rows.get(i).id, guide.get(i).id);
        assertEquals(0, db.position("all", "", Collections.emptySet(), rows.get(0).number, false));
    }

    @Test
    public void channelsGetSatelliteNumbersAndSections() throws Exception {
        ChannelDatabase.Writer writer = db.writer("m3u", () -> {}, n -> {});
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
        assertEquals("Other UK news channels follow in the 500s", 517, all.get(2).number);
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
