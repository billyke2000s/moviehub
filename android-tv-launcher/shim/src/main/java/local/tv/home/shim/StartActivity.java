package local.tv.home.shim;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * The Home screen the box opens. It immediately hands over to MovieHub, so pressing Home always
 * returns to MovieHub, including after MovieHub has updated itself.
 *
 * <p>Failsafes:
 * <ul>
 *   <li>If MovieHub crashed {@link CrashGuard#CRASH_LIMIT} times recently, the stock launcher opens.
 *   <li>If MovieHub is not installed or is mid-update, a short message is shown and MovieHub opens
 *       as soon as it is available again. Select opens the stock launcher instead.
 * </ul>
 */
public final class StartActivity extends Activity {

    static final ComponentName MOVIEHUB = new ComponentName("local.tv.home", "local.tv.home.MainActivity");
    /** Give a crash report sent just before MovieHub's process ended time to arrive. */
    private static final long CRASH_REPORT_GRACE_MILLIS = 400;
    private static final long RETRY_MILLIS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView message;

    private final Runnable decide = this::openMovieHubOrFallback;

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(decide);
        handler.postDelayed(decide, CRASH_REPORT_GRACE_MILLIS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(decide);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // This is the Home screen: Back has nowhere to go.
    }

    private void openMovieHubOrFallback() {
        if (isFinishing() || isDestroyed()) return;
        if (CrashGuard.useFallback(CrashReceiver.crashes(this), System.currentTimeMillis())) {
            showMessage(
                "MovieHub closed unexpectedly several times.\nUsing the standard launcher for a few minutes.",
                false
            );
            openStockLauncher();
            return;
        }
        if (!movieHubAvailable()) {
            showMessage("MovieHub is updating or not installed.\nIt will open as soon as it is ready.", true);
            handler.removeCallbacks(decide);
            handler.postDelayed(decide, RETRY_MILLIS);
            return;
        }
        try {
            startActivity(
                new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(MOVIEHUB)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            );
            overridePendingTransition(0, 0);
        } catch (Exception e) {
            showMessage("MovieHub could not be opened.", true);
            handler.removeCallbacks(decide);
            handler.postDelayed(decide, RETRY_MILLIS);
        }
    }

    private boolean movieHubAvailable() {
        try {
            return getPackageManager().getActivityInfo(MOVIEHUB, 0).isEnabled();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens another Home app, preferring the one that came with the box. */
    private void openStockLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = getPackageManager().queryIntentActivities(home, 0);
        ResolveInfo best = null;
        for (ResolveInfo info : homes) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals(MOVIEHUB.getPackageName())) continue;
            boolean system = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (best == null || system) best = info;
            if (system) break;
        }
        try {
            if (best != null) {
                startActivity(
                    new Intent(home)
                        .setComponent(
                            new ComponentName(best.activityInfo.packageName, best.activityInfo.name)
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                );
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception e) {
            showMessage("No other launcher could be opened. Press Select to open Settings.", true);
        }
    }

    /** Shows a message on this otherwise invisible screen. */
    private void showMessage(String text, boolean offerStockLauncher) {
        if (message == null) {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setBackgroundColor(0xff090d14);
            message = new TextView(this);
            message.setTextColor(0xfff8f8fc);
            message.setTextSize(22);
            message.setGravity(Gravity.CENTER);
            layout.addView(message);
            Button stock = new Button(this);
            stock.setText("Open standard launcher");
            stock.setAllCaps(false);
            stock.setId(View.generateViewId());
            stock.setOnClickListener(v -> openStockLauncher());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = 32;
            layout.addView(stock, lp);
            setContentView(layout);
        }
        message.setText(text);
        View stock = ((LinearLayout) message.getParent()).getChildAt(1);
        stock.setVisibility(offerStockLauncher ? View.VISIBLE : View.GONE);
        if (offerStockLauncher) stock.requestFocus();
    }
}
