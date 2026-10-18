package local.tv.home;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Indexed on-device channel store. Stream URLs can contain provider credentials, so they are stored
 * AES-GCM encrypted with a random key that is itself kept in {@link Secrets} (Android Keystore).
 */
final class ChannelDatabase extends SQLiteOpenHelper {

    static final int PAGE = 150;

    static final class Row {

        final long id;
        final String name, group, logo, key;
        /** The playlist's programme-guide id (tvg-id), or "". */
        final String epg;
        /** Duplicate key; see {@link ChannelDatabase#duplicateKey}. */
        final String dkey;
        /** Channel number (101 BBC One …; see {@link SkyNumbers}) and guide section. */
        final int number;
        final String section;

        Row(
            long id,
            String name,
            String group,
            String logo,
            String key,
            String epg,
            String dkey,
            int number,
            String section
        ) {
            this.id = id;
            this.number = number;
            this.section = section == null ? "" : section;
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.key = key;
            this.epg = epg == null ? "" : epg;
            this.dkey = dkey == null ? "" : dkey;
        }
    }

    /** One programme in the guide. Times are in milliseconds since 1970. */
    static final class Programme {

        final long start, stop;
        final String title, description, category;
        /** Details for the guide's tags; see {@link EpgImporter#flags}. */
        final String flags;

        Programme(long start, long stop, String title, String description, String category) {
            this(start, stop, title, description, category, "");
        }

        Programme(long start, long stop, String title, String description, String category, String flags) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.description = description == null ? "" : description;
            this.category = category == null ? "" : category;
            this.flags = flags == null ? "" : flags;
        }

        /** The details as the satellite guide's tags: "S1 Ep3  [15] [S] [HD] [New]", or "". */
        String tags() {
            if (flags.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (String item : flags.split("\\|")) {
                if (out.length() > 0) out.append("  ");
                boolean episode =
                    item.contains(" ") ||
                    (item.startsWith("S") && item.length() > 2 && Character.isDigit(item.charAt(1)));
                out.append(episode ? item : "[" + item + "]");
            }
            return out.toString();
        }
    }

    static final class Group {

        final String name;
        final int count;

        Group(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private static ChannelDatabase instance;
    private final Secrets secrets;
    /** The favourites last written to favourite_keys; null until written since the database opened. */
    private Set<String> writtenFavourites;

    static synchronized ChannelDatabase get(Context context) {
        if (instance == null) instance = new ChannelDatabase(context.getApplicationContext());
        return instance;
    }

    private ChannelDatabase(Context context) {
        super(context, "channels-v2.db", null, 7);
        secrets = new Secrets(context);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE channels(_id INTEGER PRIMARY KEY,source TEXT NOT NULL,position INTEGER NOT NULL,name TEXT NOT NULL,grp TEXT NOT NULL,logo TEXT NOT NULL,epg TEXT NOT NULL,key TEXT NOT NULL,url BLOB NOT NULL)"
        );
        db.execSQL("CREATE INDEX channels_order ON channels(source,position)");
        db.execSQL("CREATE INDEX channels_group ON channels(grp,source,position)");
        db.execSQL("CREATE INDEX channels_key ON channels(key)");
        db.execSQL("CREATE VIRTUAL TABLE channel_search USING fts4(name,grp)");
        db.execSQL("CREATE TABLE favourite_keys(key TEXT PRIMARY KEY)");
        createGuideTables(db);
        addDuplicateKey(db);
        addNumbers(db);
    }

    /**
     * The same channel from several lists (BBC One from Free-TV and from iptv-org) shares a
     * duplicate key: its country and plain name. The guide shows it once; the others are backups.
     */
    private static void addDuplicateKey(SQLiteDatabase db) {
        // Safe to run twice (a database whose version was rolled back already has the column).
        boolean present = false;
        try (Cursor columns = db.rawQuery("PRAGMA table_info(channels)", null)) {
            while (columns.moveToNext()) if ("dkey".equals(columns.getString(1))) present = true;
        }
        if (!present) db.execSQL("ALTER TABLE channels ADD COLUMN dkey TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_dkey ON channels(dkey)");
    }

    /** Adds a column if it is missing (a database whose version was rolled back may have it). */
    private static void addColumn(SQLiteDatabase db, String table, String column, String type) {
        try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (columns.moveToNext()) if (column.equals(columns.getString(1))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    /** Channel numbers, guide sections (version 5) and countries (version 6); filled in by {@link ChannelNumbers#renumber}. */
    private static void addNumbers(SQLiteDatabase db) {
        addColumn(db, "channels", "number", "INTEGER NOT NULL DEFAULT 0");
        addColumn(db, "channels", "section", "TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_number ON channels(number)");
        addColumn(db, "channels", "country", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "descr", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "category", "TEXT NOT NULL DEFAULT ''");
        addColumn(db, "programmes", "flags", "TEXT NOT NULL DEFAULT ''");
    }

    /** The duplicate key for a channel; "" when its name has nothing usable (never merged). */
    static String duplicateKey(String epgId, String name) {
        String plain = EpgMatch.key(name);
        return plain.isEmpty() ? "" : EpgMatch.country(epgId) + ":" + plain;
    }

    /**
     * Programme guide: programmes by guide channel id, and which plain channel keys (see
     * {@link EpgMatch}) each guide channel matches.
     */
    private static void createGuideTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS programmes(channel TEXT NOT NULL,start INTEGER NOT NULL,stop INTEGER NOT NULL,title TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS programmes_time ON programmes(channel,start)");
        db.execSQL("CREATE TABLE IF NOT EXISTS epg_map(chkey TEXT NOT NULL,channel TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS epg_map_match ON epg_map(chkey)");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // A reopened or upgraded database may hold other favourites (or none): write them again.
        synchronized (this) {
            writtenFavourites = null;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) db.execSQL("CREATE TABLE IF NOT EXISTS favourite_keys(key TEXT PRIMARY KEY)");
        if (from < 3) createGuideTables(db);
        if (from < 4) addDuplicateKey(db); // filled in by the next import
        if (from < 6) {
            addNumbers(db);
            ChannelNumbers.renumber(db);
        }
        if (from < 7) addNumbers(db); // programme details (flags); filled in by the next guide download
    }

    // ---- URL encryption -------------------------------------------------------------------
    private SecretKey urlKey;

    private synchronized SecretKey urlKey(boolean create) throws Exception {
        if (urlKey != null) return urlKey;
        String stored = secrets.get("channelKey");
        if (stored.isEmpty()) {
            if (!create) throw new IllegalStateException("Channel key missing");
            clear();
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            stored = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
            secrets.put("channelKey", stored);
        }
        urlKey = new SecretKeySpec(android.util.Base64.decode(stored, android.util.Base64.NO_WRAP), "AES");
        return urlKey;
    }

    // ---- Import ---------------------------------------------------------------------------
    /** Streams channels for one source into the database. Old rows are replaced only if the whole import succeeds. */
    final class Writer implements Playlist.Sink {

        private final SQLiteDatabase db;
        private final String source;
        private final SQLiteStatement insert, search;
        private final Cipher cipher;
        private final SecretKey key;
        private final SecureRandom random = new SecureRandom();
        private final Cancel cancelled;
        private final Progress progress;
        private int position;
        private boolean finished;

        Writer(String source, Cancel cancelled, Progress progress) throws Exception {
            this.source = source;
            this.cancelled = cancelled;
            this.progress = progress;
            key = urlKey(true);
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            db = getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.execSQL(
                    "DELETE FROM channel_search WHERE docid IN (SELECT _id FROM channels WHERE source=?)",
                    new Object[] { source }
                );
                db.delete("channels", "source=?", new String[] { source });
                insert = db.compileStatement(
                    "INSERT INTO channels(source,position,name,grp,logo,epg,key,url,dkey) VALUES(?,?,?,?,?,?,?,?,?)"
                );
                search = db.compileStatement("INSERT INTO channel_search(docid,name,grp) VALUES(?,?,?)");
            } catch (Exception e) {
                db.endTransaction();
                throw e;
            }
        }

        @Override
        public void accept(Playlist.Channel c) throws Exception {
            cancelled.check();
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(c.url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(sealed, 0, blob, iv.length, sealed.length);
            // Circled letters some lists use as notes ("BBC One Ⓖ" for geo-blocked) are dropped.
            String cleaned = c.name == null ? "" : c.name.replaceAll("[\\u24B6-\\u24E9]", "").trim();
            String name = cleaned.isEmpty() ? "Channel" : cleaned,
                group = c.group == null ? "" : c.group;
            insert.clearBindings();
            insert.bindString(1, source);
            insert.bindLong(2, position++);
            insert.bindString(3, name);
            insert.bindString(4, group);
            insert.bindString(5, c.logo == null ? "" : c.logo);
            insert.bindString(6, c.epgId == null ? "" : c.epgId);
            insert.bindString(7, ChannelQuery.key(c.url));
            insert.bindBlob(8, blob);
            insert.bindString(9, duplicateKey(c.epgId, name));
            long id = insert.executeInsert();
            search.clearBindings();
            search.bindLong(1, id);
            search.bindString(2, name);
            search.bindString(3, group);
            search.executeInsert();
            if (position % 1000 == 0) progress.update(position);
        }

        int count() {
            return position;
        }

        /** Commits the import, numbering the channels again. */
        void commit() {
            ChannelNumbers.renumber(db);
            db.setTransactionSuccessful();
            close(true);
        }

        /** Discards everything written by this writer, keeping the previous channel list. */
        void abort() {
            close(false);
        }

        private void close(boolean committed) {
            if (finished) return;
            finished = true;
            try {
                insert.close();
                search.close();
            } finally {
                db.endTransaction();
            }
        }
    }

    interface Progress {
        void update(int count);
    }

    /** Throws to stop an import; the transaction is then rolled back. */
    interface Cancel {
        void check() throws Exception;
    }

    Writer writer(String source, Cancel cancelled, Progress progress) throws Exception {
        return new Writer(source, cancelled, progress);
    }

    /** Removes channels from sources that are no longer configured. */
    void removeSourcesExcept(Collection<String> keep) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            List<String> stale = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT DISTINCT source FROM channels", null)) {
                while (c.moveToNext()) if (!keep.contains(c.getString(0))) stale.add(c.getString(0));
            }
            for (String source : stale) {
                db.execSQL(
                    "DELETE FROM channel_search WHERE docid IN (SELECT _id FROM channels WHERE source=?)",
                    new Object[] { source }
                );
                db.delete("channels", "source=?", new String[] { source });
            }
            if (!stale.isEmpty()) ChannelNumbers.renumber(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- Queries --------------------------------------------------------------------------
    private static final String COLUMNS = "_id,name,grp,logo,key,epg,dkey,number,section";
    /** Providers first, then Free-TV, then everything else; in playlist order within each. */
    static final String ORDER =
        "CASE source WHEN 'xtream' THEN 0 WHEN 'm3u' THEN 1 WHEN 'fta' THEN 2 ELSE 3 END";
    /** One row per channel: duplicates (same non-empty dkey) collapse to the first in ORDER. */
    static final String GROUP = "CASE WHEN dkey='' THEN 'id'||_id ELSE dkey END";
    /** The preferred copy of a channel: SQLite takes the other columns from the MIN row. */
    static final String RANK = "MIN((" + ORDER + ")*1000000000+position)";

    private List<Row> rows(String where, String[] args, int offset, int limit) {
        List<Row> out = new ArrayList<>();
        // SQLite returns the other columns from the row holding MIN(rank), so each duplicate group
        // is shown as its preferred source. Channels are in number order, as on the guide.
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT " +
                    COLUMNS +
                    "," +
                    RANK +
                    " AS rank FROM channels" +
                    (where == null ? "" : " WHERE " + where) +
                    " GROUP BY " +
                    GROUP +
                    " ORDER BY number,rank LIMIT " +
                    Math.max(1, limit) +
                    " OFFSET " +
                    Math.max(0, offset),
                args
            )
        ) {
            while (c.moveToNext()) out.add(row(c));
        }
        return out;
    }

    private static Row row(Cursor c) {
        return new Row(
            c.getLong(0),
            c.getString(1),
            c.getString(2),
            c.getString(3),
            c.getString(4),
            c.getString(5),
            c.getString(6),
            c.getInt(7),
            c.getString(8)
        );
    }

    /** Every copy of this channel, preferred source first: the channel and its backups. */
    List<Row> copies(Row channel) {
        List<Row> out = new ArrayList<>();
        if (channel.dkey.isEmpty()) {
            out.add(channel);
            return out;
        }
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM channels WHERE dkey=? ORDER BY " + ORDER + ",position",
                new String[] { channel.dkey }
            )
        ) {
            while (c.moveToNext()) out.add(row(c));
        }
        if (out.isEmpty()) out.add(channel);
        return out;
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM channels", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Each query returns up to PAGE+1 rows; an extra row means another page exists. */
    List<Row> all(int offset) {
        return rows(null, null, offset, PAGE + 1);
    }

    synchronized List<Row> browse(String filter, String text, Set<String> keys, int offset) {
        return browse(filter, text, keys, offset, PAGE + 1);
    }

    /**
     * Up to {@code limit} rows of a guide list from {@code offset}. Changing channel needs one row
     * and a guide page eleven, so they ask for those instead of a whole page.
     */
    synchronized List<Row> browse(String filter, String text, Set<String> keys, int offset, int limit) {
        List<String> args = new ArrayList<>();
        String where = where(filter, text, keys, args);
        if (where == null) return new ArrayList<>();
        return rows(where, args.toArray(new String[0]), offset, limit);
    }

    /**
     * Where channel number {@code number} is in a guide list: its position, or -1 when that list
     * does not have it. With {@code nearest}, the position of the first channel numbered at or
     * above it instead (the last channel when none is).
     */
    synchronized int position(String filter, String text, Set<String> keys, int number, boolean nearest) {
        List<String> args = new ArrayList<>();
        String where = where(filter, text, keys, args);
        if (where == null) return -1;
        String groups = "SELECT MIN(number) AS n FROM channels WHERE " + where + " GROUP BY " + GROUP;
        // One pass over the list counts the channels before this number, at it, and in all.
        // Its three values come before the list's own in the statement, so they are bound first.
        args.add(0, String.valueOf(Integer.MAX_VALUE));
        args.add(0, String.valueOf(number));
        args.add(0, String.valueOf(number));
        int before = 0,
            exact = 0,
            total = 0;
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT TOTAL(n<CAST(? AS INTEGER)),TOTAL(n=CAST(? AS INTEGER)),TOTAL(n<CAST(? AS INTEGER)) FROM (" +
                    groups +
                    ")",
                args.toArray(new String[0])
            )
        ) {
            if (c.moveToFirst()) {
                before = c.getInt(0);
                exact = c.getInt(1);
                total = c.getInt(2);
            }
        }
        if (exact > 0) return before;
        if (!nearest) return -1;
        return total == 0 ? -1 : Math.min(before, total - 1);
    }

    /** The channel with this stream key (its preferred copy's number and section), or null. */
    Row byKey(String key) {
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM channels WHERE key=? LIMIT 1",
                new String[] { key }
            )
        ) {
            return c.moveToFirst() ? row(c) : null;
        }
    }

    /** International channels' countries in guide order, with how many channels each has. */
    List<Group> countries() {
        List<Group> out = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT country,COUNT(DISTINCT " +
                    GROUP +
                    ") FROM channels WHERE section='International' GROUP BY country ORDER BY MIN(number)",
                null
            )
        ) {
            while (c.moveToNext()) out.add(new Group(c.getString(0), c.getInt(1)));
        }
        return out;
    }

    /** Sections that have channels, in the guide's order. */
    List<String> sections() {
        Set<String> present = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT section FROM channels", null)) {
            while (c.moveToNext()) present.add(c.getString(0));
        }
        List<String> out = new ArrayList<>();
        for (String section : SkyNumbers.SECTIONS) if (present.contains(section)) out.add(section);
        return out;
    }

    /** The SQL condition for a guide list, adding its arguments; null when nothing can match. */
    private String where(String filter, String text, Set<String> keys, List<String> args) {
        // The favourites table is rewritten only when the favourites changed, not on every page
        // and channel change.
        if ("favourites".equals(filter) && !keys.equals(writtenFavourites)) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransactionNonExclusive();
            try (
                SQLiteStatement insert = db.compileStatement(
                    "INSERT OR IGNORE INTO favourite_keys(key) VALUES(?)"
                )
            ) {
                db.delete("favourite_keys", null, null);
                for (String key : keys) {
                    insert.bindString(1, key);
                    insert.executeInsert();
                }
                db.setTransactionSuccessful();
                writtenFavourites = new HashSet<>(keys);
            } finally {
                db.endTransaction();
            }
        }
        String where = "1=1";
        if ("favourites".equals(filter)) where += " AND key IN (SELECT key FROM favourite_keys)";
        if (filter.startsWith("group:")) {
            where += " AND grp=?";
            args.add(filter.substring(6));
        }
        if (filter.startsWith("section:")) {
            where += " AND section=?";
            args.add(filter.substring(8));
        }
        if (filter.startsWith("country:")) {
            where += " AND section='International' AND country=?";
            args.add(filter.substring(8));
        }
        if ("sports".equals(filter)) where += " AND section='Sports'";
        if (text != null && !text.trim().isEmpty()) {
            String match = ChannelQuery.fts(text);
            if (match.isEmpty()) return null;
            where += " AND _id IN (SELECT docid FROM channel_search WHERE channel_search MATCH ?)";
            args.add(match);
        }
        return where;
    }

    List<Group> groups() {
        List<Group> out = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT grp,COUNT(*) FROM channels GROUP BY grp ORDER BY grp COLLATE NOCASE",
                null
            )
        ) {
            while (c.moveToNext()) out.add(new Group(c.getString(0), c.getInt(1)));
        }
        return out;
    }

    /** Decrypts a channel's stream URL for playback. */
    String url(long id, String expectedKey) throws Exception {
        byte[] blob;
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT url,key FROM channels WHERE _id=?",
                new String[] { String.valueOf(id) }
            )
        ) {
            if (!c.moveToFirst()) throw new IllegalStateException("Channel no longer exists");
            if (!expectedKey.equals(c.getString(1))) throw new IllegalStateException(
                "Channel list changed; select the channel again"
            );
            blob = c.getBlob(0);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, urlKey(false), new GCMParameterSpec(128, blob, 0, 12));
        return new String(
            cipher.doFinal(blob, 12, blob.length - 12),
            java.nio.charset.StandardCharsets.UTF_8
        );
    }

    // ---- Programme guide --------------------------------------------------------------------
    /** Every plain key the saved channels can be found under in a guide for {@code country}. */
    Set<String> guideKeys(String country) {
        Set<String> keys = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT epg,name FROM channels", null)) {
            while (c.moveToNext()) keys.addAll(EpgMatch.keys(c.getString(0), c.getString(1), country));
        }
        return keys;
    }

    /** Replaces the whole programme guide in one transaction; a failed download keeps the old one. */
    final class GuideWriter {

        private final SQLiteDatabase db = getWritableDatabase();
        private final SQLiteStatement map, programme;
        int programmes;

        GuideWriter() {
            db.beginTransactionNonExclusive();
            db.delete("programmes", null, null);
            db.delete("epg_map", null, null);
            map = db.compileStatement("INSERT INTO epg_map(chkey,channel) VALUES(?,?)");
            programme = db.compileStatement(
                "INSERT INTO programmes(channel,start,stop,title,descr,category,flags) VALUES(?,?,?,?,?,?,?)"
            );
        }

        void match(String key, String channel) {
            map.bindString(1, key);
            map.bindString(2, channel);
            map.executeInsert();
        }

        void programme(
            String channel,
            long start,
            long stop,
            String title,
            String description,
            String category,
            String flags
        ) {
            programme.bindString(1, channel);
            programme.bindLong(2, start);
            programme.bindLong(3, stop);
            programme.bindString(4, title);
            programme.bindString(5, description == null ? "" : description);
            programme.bindString(6, category == null ? "" : category);
            programme.bindString(7, flags == null ? "" : flags);
            programme.executeInsert();
            programmes++;
        }

        void commit() {
            db.setTransactionSuccessful();
        }

        /** Ends the transaction; without commit() nothing changes. */
        void finish() {
            map.close();
            programme.close();
            db.endTransaction();
        }
    }

    /** Programmes on this channel overlapping [from, to), in time order (at most 12). */
    List<Programme> programmes(Row row, String country, long from, long to) {
        return programmes(row, country, from, to, 12);
    }

    List<Programme> programmes(Row row, String country, long from, long to, int limit) {
        List<Programme> out = new ArrayList<>();
        List<String> keys = EpgMatch.keys(row.epg, row.name, country);
        if (keys.isEmpty()) return out;
        StringBuilder in = new StringBuilder();
        List<String> args = new ArrayList<>(keys);
        for (int i = 0; i < keys.size(); i++) in.append(i == 0 ? "?" : ",?");
        args.add(String.valueOf(to));
        args.add(String.valueOf(from));
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT start,stop,title,descr,category,flags FROM programmes WHERE channel=(SELECT channel FROM epg_map WHERE chkey IN (" +
                    in +
                    ") LIMIT 1) AND start<? AND stop>? ORDER BY start LIMIT " +
                    limit,
                args.toArray(new String[0])
            )
        ) {
            while (c.moveToNext())
                out.add(
                    new Programme(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5)
                    )
                );
        }
        return out;
    }

    /** Clears all channels, for example after the URL key is lost. */
    void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            db.execSQL("DELETE FROM channel_search");
            db.delete("channels", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
