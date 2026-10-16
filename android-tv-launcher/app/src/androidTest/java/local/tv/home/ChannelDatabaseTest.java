package local.tv.home;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

/** Real Android SQLite and Keystore checks. */
@RunWith(AndroidJUnit4.class)
public class ChannelDatabaseTest {
    private ChannelDatabase db;
    @Before public void prepare(){db=ChannelDatabase.get(ApplicationProvider.getApplicationContext());db.clear();}
    @After public void cleanup(){db.clear();}
    private Playlist.Channel channel(int i){return new Playlist.Channel("Channel "+i,"https://example.test/live/private-user/private-password/"+i+".ts",i%2==0?"Sports":"News","","");}
    private void importRows(int count) throws Exception{
        ChannelDatabase.Writer writer=db.writer("test",()->{},n->{});
        try{for(int i=0;i<count;i++)writer.accept(channel(i));writer.commit();}finally{writer.abort();}
    }
    @Test public void paginationFavouritesAndSearchHaveNoSilentCap() throws Exception{
        importRows(1301);Set<String> keys=new HashSet<>();for(int i=0;i<1301;i++)keys.add(ChannelQuery.key(channel(i).url));
        Set<Long> seen=new HashSet<>();int offset=0;while(true){List<ChannelDatabase.Row> page=db.browse("favourites","",keys,offset);int shown=Math.min(ChannelDatabase.PAGE,page.size());for(int i=0;i<shown;i++)assertTrue("No duplicated row",seen.add(page.get(i).id));if(page.size()<=ChannelDatabase.PAGE)break;offset+=ChannelDatabase.PAGE;}
        assertEquals(1301,seen.size());assertEquals(1,db.browse("all","Channel 1300",keys,0).size());assertEquals(0,db.browse("sports","Channel 1299",keys,0).size());
        assertTrue(db.browse("favourites","\" OR *",Collections.emptySet(),0).isEmpty());
    }
    @Test public void failedRefreshKeepsPreviousRowsAndUrlsAreEncrypted() throws Exception{
        importRows(3);assertEquals(channel(0).url,db.url(db.all(0).get(0).id,ChannelQuery.key(channel(0).url)));
        ChannelDatabase.Writer writer=db.writer("test",()->{},n->{});try{writer.accept(channel(99));}finally{writer.abort();}
        assertEquals(3,db.count());assertEquals(channel(0).url,db.url(db.all(0).get(0).id,ChannelQuery.key(channel(0).url)));
        try(Cursor cursor=db.getReadableDatabase().rawQuery("SELECT url FROM channels LIMIT 1",null)){assertTrue(cursor.moveToFirst());assertEquals(Cursor.FIELD_TYPE_BLOB,cursor.getType(0));assertFalse(new String(cursor.getBlob(0),java.nio.charset.StandardCharsets.UTF_8).contains("private-password"));}
    }
    @Test public void schemaUpgradePreservesCatalogueAndKey() throws Exception{
        importRows(3);db.close();Context context=ApplicationProvider.getApplicationContext();
        try(SQLiteDatabase old=SQLiteDatabase.openDatabase(context.getDatabasePath("channels-v2.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){old.execSQL("DROP TABLE favourite_keys");old.setVersion(1);}
        assertEquals(3,db.count());assertEquals(channel(0).url,db.url(db.all(0).get(0).id,ChannelQuery.key(channel(0).url)));assertEquals(1,db.browse("favourites","",Collections.singleton(ChannelQuery.key(channel(2).url)),0).size());
    }
}
