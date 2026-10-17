package local.tv.home.shim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;

/**
 * Records a MovieHub crash. MovieHub sends {@link #ACTION_CRASHED} to this package from its
 * uncaught-exception handler just before its process ends.
 */
public final class CrashReceiver extends BroadcastReceiver {

    static final String ACTION_CRASHED = "local.tv.home.shim.MOVIEHUB_CRASHED";
    private static final String PREFS = "shim";
    private static final String KEY = "crashes";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CRASHED.equals(intent.getAction())) return;
        record(context, System.currentTimeMillis());
    }

    static synchronized void record(Context context, long now) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Long> crashes = CrashGuard.recent(CrashGuard.decode(prefs.getString(KEY, "")), now);
        crashes.add(now);
        prefs.edit().putString(KEY, CrashGuard.encode(crashes)).commit();
    }

    static synchronized List<Long> crashes(Context context) {
        return CrashGuard.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")
        );
    }
}
