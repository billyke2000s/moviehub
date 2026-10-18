package local.tv.home;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Numbers the saved channels and files them in guide sections; see {@link SkyNumbers}. */
final class ChannelNumbers {

    private ChannelNumbers() {}

    /**
     * Gives every channel its number and guide section. Channels the UK satellite guide knows get
     * its numbers (101 BBC One …); the rest follow from 1001 in list order, or from 101 when no
     * channel has a satellite number. Copies of one channel share its number. Runs after every
     * import, so numbers stay the same while the lists do.
     */
    static void renumber(SQLiteDatabase db) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>(),
            epgs = new ArrayList<>(),
            groups = new ArrayList<>(),
            dkeys = new ArrayList<>();
        try (
            Cursor c = db.rawQuery(
                "SELECT _id,name,epg,grp,dkey," +
                    ChannelDatabase.RANK +
                    " AS rank FROM channels GROUP BY " +
                    ChannelDatabase.GROUP +
                    " ORDER BY rank",
                null
            )
        ) {
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
                names.add(c.getString(1));
                epgs.add(c.getString(2));
                groups.add(c.getString(3));
                dkeys.add(c.getString(4));
            }
        }
        int[] numbers = new int[ids.size()];
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < numbers.length; i++) {
            int sky = SkyNumbers.number(epgs.get(i), names.get(i));
            if (sky > 0 && used.add(sky)) numbers[i] = sky;
        }
        int next = used.isEmpty() ? 101 : SkyNumbers.FIRST_UNLISTED;
        for (int i = 0; i < numbers.length; i++) if (numbers[i] == 0) {
            while (used.contains(next)) next++;
            numbers[i] = next++;
        }
        db.beginTransactionNonExclusive();
        try (
            SQLiteStatement byKey = db.compileStatement(
                "UPDATE channels SET number=?,section=? WHERE dkey=?"
            );
            SQLiteStatement byId = db.compileStatement("UPDATE channels SET number=?,section=? WHERE _id=?")
        ) {
            for (int i = 0; i < numbers.length; i++) {
                boolean shared = !dkeys.get(i).isEmpty();
                SQLiteStatement update = shared ? byKey : byId;
                update.bindLong(1, numbers[i]);
                update.bindString(2, SkyNumbers.section(numbers[i], names.get(i), groups.get(i)));
                if (shared) update.bindString(3, dkeys.get(i));
                else update.bindLong(3, ids.get(i));
                update.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
