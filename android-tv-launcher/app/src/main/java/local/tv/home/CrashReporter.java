package local.tv.home;

import android.content.Context;
import android.content.Intent;

/**
 * Tells the optional MovieHub Start app (the launcher shim in {@code shim/}) that MovieHub crashed,
 * so it can fall back to the standard launcher if MovieHub keeps crashing. Android's normal crash
 * handling still runs afterwards. If the shim is not installed, the report goes nowhere.
 */
final class CrashReporter {

    static final String SHIM_PACKAGE = "local.tv.home.shim";
    static final String ACTION_CRASHED = "local.tv.home.shim.MOVIEHUB_CRASHED";

    private static boolean installed;

    private CrashReporter() {}

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                app.sendBroadcast(new Intent(ACTION_CRASHED).setPackage(SHIM_PACKAGE));
            } catch (Throwable ignored) {
                // Reporting must never change how the crash itself is handled.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
