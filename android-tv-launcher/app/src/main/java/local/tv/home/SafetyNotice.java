package local.tv.home;

import android.app.Activity;
import android.content.Context;

/**
 * A plain warning, shown when MovieHub is first opened and kept in Settings: MovieHub never asks
 * you to leave the app, visit a website, ring a number, install anything or pay during a stream.
 * Free channels come from public lists MovieHub does not control, and a stream could one day show
 * a scam message ("your device has a virus, call …"), so it says what to do if one does.
 */
final class SafetyNotice {

    static final String PREF = "safetyNoticeSeen";

    static final String TITLE = "Staying safe";
    static final String TEXT =
        "MovieHub will never ask you, during or about a stream, to:\n\n" +
        "•  go to a website, scan a QR code or ring a phone number\n" +
        "•  install another app or let anyone control your TV or computer\n" +
        "•  enter a password, bank or card details, or pay for anything\n\n" +
        "Live TV channels come from public lists that MovieHub does not control. If a picture or " +
        "message on any channel or video tells you to do one of these things, or says your TV or " +
        "computer has a virus, it is a scam: change channel, ignore it, and never ring the number " +
        "or visit the site.\n\n" +
        "This notice is always in Settings → System.";

    private SafetyNotice() {}

    static boolean seen(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    /** Shows the notice the first time MovieHub opens; it comes back until "I understand". */
    static void showOnce(Activity activity) {
        if (!seen(activity)) show(activity);
    }

    static void show(Activity activity) {
        SidePanel.of(activity, TITLE)
            .message(TEXT)
            .action("I understand", () ->
                activity
                    .getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF, true)
                    .apply()
            )
            .show();
    }
}
