package local.tv.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where to carry on watching a film or episode (SharedPreferences {@code positions}): the position
 * and length saved under the played file's id. Live TV is never saved.
 */
final class ResumePoint {

    private ResumePoint() {}

    private static SharedPreferences positions(Context context) {
        return context.getSharedPreferences("positions", Context.MODE_PRIVATE);
    }

    /** The saved position for {@code id} in milliseconds, or 0 (also when resuming is turned off). */
    static long load(Context context, String id) {
        if (!context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("resume", true)) return 0;
        Object saved = positions(context).getAll().get(id);
        return saved instanceof Number ? ((Number) saved).longValue() : 0;
    }

    /** Saves the length (when known) and, unless it ended or resuming is off, the position. */
    static void save(Context context, String id, long position, long length, boolean ended) {
        SharedPreferences.Editor edit = positions(context).edit();
        if (length > 0) edit.putLong(id + ":duration", length);
        if (
            !ended && context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("resume", true)
        ) edit.putLong(id, position);
        edit.apply();
    }

    /** Forgets the position once the film or episode has been watched to the end. */
    static void clear(Context context, String id) {
        positions(context).edit().remove(id).apply();
    }
}
