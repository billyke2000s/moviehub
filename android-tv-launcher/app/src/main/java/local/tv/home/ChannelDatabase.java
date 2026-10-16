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
    static final int PAGE=150;
    static final class Row {
        final long id;final String name,group,logo,key;
        Row(long id,String name,String group,String logo,String key){this.id=id;this.name=name;this.group=group;this.logo=logo;this.key=key;}
    }
    static final class Group { final String name;final int count;Group(String name,int count){this.name=name;this.count=count;} }

    private static ChannelDatabase instance;
    private final Secrets secrets;
    static synchronized ChannelDatabase get(Context context){if(instance==null)instance=new ChannelDatabase(context.getApplicationContext());return instance;}
    private ChannelDatabase(Context context){super(context,"channels-v2.db",null,2);secrets=new Secrets(context);setWriteAheadLoggingEnabled(true);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE channels(_id INTEGER PRIMARY KEY,source TEXT NOT NULL,position INTEGER NOT NULL,name TEXT NOT NULL,grp TEXT NOT NULL,logo TEXT NOT NULL,epg TEXT NOT NULL,key TEXT NOT NULL,url BLOB NOT NULL)");
        db.execSQL("CREATE INDEX channels_order ON channels(source,position)");
        db.execSQL("CREATE INDEX channels_group ON channels(grp,source,position)");
        db.execSQL("CREATE INDEX channels_key ON channels(key)");
        db.execSQL("CREATE VIRTUAL TABLE channel_search USING fts4(name,grp)");
        db.execSQL("CREATE TABLE favourite_keys(key TEXT PRIMARY KEY)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){
        if(from<2)db.execSQL("CREATE TABLE IF NOT EXISTS favourite_keys(key TEXT PRIMARY KEY)");
    }

    // ---- URL encryption -------------------------------------------------------------------
    private SecretKey urlKey;
    private synchronized SecretKey urlKey(boolean create) throws Exception{
        if(urlKey!=null)return urlKey;
        String stored=secrets.get("channelKey");
        if(stored.isEmpty()){
            if(!create)throw new IllegalStateException("Channel key missing");
            clear();byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);stored=android.util.Base64.encodeToString(raw,android.util.Base64.NO_WRAP);secrets.put("channelKey",stored);
        }
        urlKey=new SecretKeySpec(android.util.Base64.decode(stored,android.util.Base64.NO_WRAP),"AES");return urlKey;
    }

    // ---- Import ---------------------------------------------------------------------------
    /** Streams channels for one source into the database. Old rows are replaced only if the whole import succeeds. */
    final class Writer implements Playlist.Sink {
        private final SQLiteDatabase db;private final String source;private final SQLiteStatement insert,search;private final Cipher cipher;private final SecretKey key;private final SecureRandom random=new SecureRandom();
        private final Cancel cancelled;private final Progress progress;private int position;private boolean finished;
        Writer(String source,Cancel cancelled,Progress progress) throws Exception{
            this.source=source;this.cancelled=cancelled;this.progress=progress;key=urlKey(true);cipher=Cipher.getInstance("AES/GCM/NoPadding");
            db=getWritableDatabase();db.beginTransactionNonExclusive();
            try{
                db.execSQL("DELETE FROM channel_search WHERE docid IN (SELECT _id FROM channels WHERE source=?)",new Object[]{source});
                db.delete("channels","source=?",new String[]{source});
                insert=db.compileStatement("INSERT INTO channels(source,position,name,grp,logo,epg,key,url) VALUES(?,?,?,?,?,?,?,?)");
                search=db.compileStatement("INSERT INTO channel_search(docid,name,grp) VALUES(?,?,?)");
            }catch(Exception e){db.endTransaction();throw e;}
        }
        @Override public void accept(Playlist.Channel c) throws Exception{
            cancelled.check();
            byte[] iv=new byte[12];random.nextBytes(iv);cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));byte[] sealed=cipher.doFinal(c.url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] blob=new byte[iv.length+sealed.length];System.arraycopy(iv,0,blob,0,iv.length);System.arraycopy(sealed,0,blob,iv.length,sealed.length);
            String name=c.name==null||c.name.isEmpty()?"Channel":c.name,group=c.group==null?"":c.group;
            insert.clearBindings();insert.bindString(1,source);insert.bindLong(2,position++);insert.bindString(3,name);insert.bindString(4,group);insert.bindString(5,c.logo==null?"":c.logo);insert.bindString(6,c.epgId==null?"":c.epgId);insert.bindString(7,ChannelQuery.key(c.url));insert.bindBlob(8,blob);
            long id=insert.executeInsert();
            search.clearBindings();search.bindLong(1,id);search.bindString(2,name);search.bindString(3,group);search.executeInsert();
            if(position%1000==0)progress.update(position);
        }
        int count(){return position;}
        /** Commits the import. */
        void commit(){db.setTransactionSuccessful();close(true);}
        /** Discards everything written by this writer, keeping the previous channel list. */
        void abort(){close(false);}
        private void close(boolean committed){if(finished)return;finished=true;try{insert.close();search.close();}finally{db.endTransaction();}}
    }
    interface Progress { void update(int count); }
    /** Throws to stop an import; the transaction is then rolled back. */
    interface Cancel { void check() throws Exception; }
    Writer writer(String source,Cancel cancelled,Progress progress) throws Exception{return new Writer(source,cancelled,progress);}

    /** Removes channels from sources that are no longer configured. */
    void removeSourcesExcept(Collection<String> keep){
        SQLiteDatabase db=getWritableDatabase();db.beginTransactionNonExclusive();
        try{
            List<String> stale=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT DISTINCT source FROM channels",null)){while(c.moveToNext())if(!keep.contains(c.getString(0)))stale.add(c.getString(0));}
            for(String source:stale){db.execSQL("DELETE FROM channel_search WHERE docid IN (SELECT _id FROM channels WHERE source=?)",new Object[]{source});db.delete("channels","source=?",new String[]{source});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    // ---- Queries --------------------------------------------------------------------------
    private static final String COLUMNS="_id,name,grp,logo,key";
    private List<Row> rows(String where,String[] args,int offset){
        List<Row> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM channels"+(where==null?"":" WHERE "+where)+" ORDER BY source,position LIMIT "+(PAGE+1)+" OFFSET "+Math.max(0,offset),args)){
            while(c.moveToNext())out.add(new Row(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)));
        }return out;
    }
    int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM channels",null)){return c.moveToFirst()?c.getInt(0):0;}}
    /** Each query returns up to PAGE+1 rows; an extra row means another page exists. */
    List<Row> all(int offset){return rows(null,null,offset);}
    List<Row> sports(int offset){return rows("grp LIKE '%sport%'",null,offset);}
    List<Row> group(String group,int offset){return rows("grp=?",new String[]{group},offset);}
    List<Row> search(String text,int offset){
        String match=ChannelQuery.fts(text);if(match.isEmpty())return new ArrayList<>();
        return rows("_id IN (SELECT docid FROM channel_search WHERE channel_search MATCH ?)",new String[]{match},offset);
    }
    synchronized List<Row> browse(String filter,String text,Set<String> keys,int offset){
        SQLiteDatabase db=getWritableDatabase();
        if("favourites".equals(filter)){
            db.beginTransactionNonExclusive();
            try(SQLiteStatement insert=db.compileStatement("INSERT OR IGNORE INTO favourite_keys(key) VALUES(?)")){
                db.delete("favourite_keys",null,null);
                for(String key:keys){insert.bindString(1,key);insert.executeInsert();}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        List<String> args=new ArrayList<>();String where="1=1";
        if("favourites".equals(filter))where+=" AND key IN (SELECT key FROM favourite_keys)";
        if("sports".equals(filter))where+=" AND (grp LIKE '%sport%' OR name LIKE '%sport%')";
        if(text!=null&&!text.trim().isEmpty()){
            String match=ChannelQuery.fts(text);if(match.isEmpty())return new ArrayList<>();
            where+=" AND _id IN (SELECT docid FROM channel_search WHERE channel_search MATCH ?)";args.add(match);
        }
        return rows(where,args.toArray(new String[0]),offset);
    }
    List<Group> groups(){
        List<Group> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT grp,COUNT(*) FROM channels GROUP BY grp ORDER BY grp COLLATE NOCASE",null)){while(c.moveToNext())out.add(new Group(c.getString(0),c.getInt(1)));}
        return out;
    }
    /** Decrypts a channel's stream URL for playback. */
    String url(long id,String expectedKey) throws Exception{
        byte[] blob;try(Cursor c=getReadableDatabase().rawQuery("SELECT url,key FROM channels WHERE _id=?",new String[]{String.valueOf(id)})){if(!c.moveToFirst())throw new IllegalStateException("Channel no longer exists");if(!expectedKey.equals(c.getString(1)))throw new IllegalStateException("Channel list changed; select the channel again");blob=c.getBlob(0);}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,urlKey(false),new GCMParameterSpec(128,blob,0,12));
        return new String(cipher.doFinal(blob,12,blob.length-12),java.nio.charset.StandardCharsets.UTF_8);
    }
    /** Clears all channels, for example after the URL key is lost. */
    void clear(){SQLiteDatabase db=getWritableDatabase();db.beginTransactionNonExclusive();try{db.execSQL("DELETE FROM channel_search");db.delete("channels",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
}
