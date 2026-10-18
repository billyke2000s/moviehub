package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Installed apps: the Home dock (ordering, lifting, removing) and the All Apps screen. */
final class AppDock {

    private final MainActivity activity;

    AppDock(MainActivity activity) {
        this.activity = activity;
    }

    final List<App> apps = new ArrayList<>();
    View movingApp;
    LinearLayout homeDock;
    TextView dockHint;
    boolean eatSelectUp, refreshAppsOnResume, dockRemoving;

    static class App {

        String label;
        ComponentName component;
        Drawable icon;

        App(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    void queryApps() {
        apps.clear();
        Set<ComponentName> seen = new HashSet<>();
        for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER })
            for (ResolveInfo r : activity
                .getPackageManager()
                .queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                ActivityInfo a = r.activityInfo;
                if (!a.enabled || !a.exported || a.packageName.equals(activity.getPackageName())) continue;
                ComponentName component = new ComponentName(a.packageName, a.name);
                if (seen.add(component)) apps.add(
                    new App(
                        r.loadLabel(activity.getPackageManager()).toString(),
                        component,
                        r.loadIcon(activity.getPackageManager())
                    )
                );
            }
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
    }

    /** The installed app with exactly this name, or null. Call queryApps() first. */
    App findApp(String label) {
        for (App app : apps) if (app.label.equals(label)) return app;
        return null;
    }

    /**
     * Just the app's own icon, with no tile behind it, for the Home top bar. Focus enlarges it and
     * adds a soft round glow instead of a box.
     */
    View iconShortcut(App app) {
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
        icon.setContentDescription(app.label);
        icon.setFocusable(true);
        icon.setFocusableInTouchMode(true);
        icon.setAlpha(.85f);
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        glow.setColor(0x33ffffff);
        icon.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? glow : null);
            v.setAlpha(focused ? 1f : .85f);
            v.setScaleX(focused ? 1.2f : 1f);
            v.setScaleY(focused ? 1.2f : 1f);
        });
        icon.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));
            } catch (Exception e) {
                activity.toast(app.label + " is unavailable");
            }
        });
        return icon;
    }

    View addApp(LinearLayout into, App app, boolean dock) {
        LinearLayout cell = activity.vertical();
        cell.setTag(app.component.flattenToString());
        cell.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(activity);
        image.setImageDrawable(app.icon);
        cell.addView(image, new LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)));
        TextView label = activity.text(app.label, 16);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER);
        cell.addView(label);
        activity.focus(cell);
        cell.setBackground(activity.box(0xff202c43));
        cell.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));
            } catch (Exception e) {
                activity.toast("App is unavailable");
            }
        });
        cell.setOnLongClickListener(v -> {
            if (dock) beginDockMove(v);
            else appMenu(v, app);
            return true;
        });
        cell.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (dock) beginDockMove(v);
                    else appMenu(v, app);
                }
                return true;
            }
            return false;
        });
        activity.addRow(into, cell, 130, 114);
        return cell;
    }

    /** Migrate the old unordered pins once, using their existing visible order. */
    List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        String saved = activity.prefs.getString("dockOrder", null);
        if (saved != null) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.getString(i);
                    if (!order.contains(id)) order.add(id);
                }
            } catch (JSONException e) {
                Diagnostics.recovered("Reading saved dock order", e);
            }
        } else {
            Set<String> pins = activity.prefs.getStringSet("pinned", null);
            for (App app : apps)
                if (
                    pins == null ? order.size() < 6 : pins.contains(app.component.flattenToString())
                ) order.add(app.component.flattenToString());
            saveDockOrder(order);
        }
        return order;
    }

    private void saveDockOrder(List<String> order) {
        activity.prefs.edit().putString("dockOrder", new JSONArray(order).toString()).apply();
    }

    void saveVisibleDock() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < homeDock.getChildCount(); i++) {
            Object id = homeDock.getChildAt(i).getTag();
            if (id instanceof String) order.add((String) id);
        }
        saveDockOrder(order);
    }

    private void beginDockMove(View view) {
        if (dockRemoving) return;
        finishDockMove();
        movingApp = view;
        eatSelectUp = true;
        view.requestFocus();
        view.animate().translationY(-activity.dp(8)).setDuration(160).start();
        view.setElevation(activity.dp(10));
        dockHint.setText("← → Move · ↑ Remove · Select Done");
    }

    void finishDockMove() {
        if (movingApp != null) {
            movingApp.animate().translationY(0).setDuration(160).start();
            movingApp.setElevation(0);
            movingApp = null;
        }
        if (dockHint != null) dockHint.setText("Hold Select to rearrange");
    }

    /** Animate from the positions actually on screen, including any unfinished movement. */
    private void animateDockChange(Runnable change) {
        LinearLayout dock = homeDock;
        Map<View, Float> positions = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            positions.put(child, child.getX());
            child.animate().cancel();
        }
        change.run();
        dock.getViewTreeObserver().addOnPreDrawListener(
            new android.view.ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    dock.getViewTreeObserver().removeOnPreDrawListener(this);
                    for (int i = 0; i < dock.getChildCount(); i++) {
                        View child = dock.getChildAt(i);
                        Float old = positions.get(child);
                        if (old != null) {
                            child.setTranslationX(old - child.getLeft());
                            child
                                .animate()
                                .translationX(0)
                                .setDuration(220)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        }
                    }
                    return true;
                }
            }
        );
    }

    void moveDockApp(int direction) {
        View cell = movingApp;
        int index = homeDock.indexOfChild(cell),
            target = index + direction;
        if (target < 0 || target >= homeDock.getChildCount() - 1) return;
        animateDockChange(() -> {
            homeDock.removeView(cell);
            homeDock.addView(cell, target);
            cell.requestFocus();
        });
        saveVisibleDock();
    }

    void removeDockApp() {
        View cell = movingApp;
        LinearLayout dock = homeDock;
        dockRemoving = true;
        int index = dock.indexOfChild(cell);
        finishDockMove();
        cell.setEnabled(false);
        cell.setFocusable(false);
        homeDock.getChildAt(Math.min(index + 1, homeDock.getChildCount() - 1)).requestFocus();
        // Remove from saved order immediately; keep the outgoing tile until its exit ends.
        List<String> order = dockOrder();
        order.remove((String) cell.getTag());
        saveDockOrder(order);
        cell.animate()
            .translationY(-activity.dp(24))
            .alpha(0)
            .setDuration(160)
            .withEndAction(() -> {
                dockRemoving = false;
                if (cell.getParent() != homeDock || homeDock != dock) return;
                animateDockChange(() -> homeDock.removeView(cell));
                homeDock.getChildAt(Math.min(index, homeDock.getChildCount() - 1)).requestFocus();
            })
            .start();
    }

    private void appMenu(View anchor, App app) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        boolean pinned = dockOrder().contains(app.component.flattenToString());
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 1, pinned ? "Remove from hotbar" : "Add to hotbar");
        menu.getMenu().add(0, 3, 2, "App info");
        try {
            ApplicationInfo info = activity
                .getPackageManager()
                .getApplicationInfo(app.component.getPackageName(), 0);
            if (
                (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            ) menu.getMenu().add(0, 4, 3, "Uninstall");
        } catch (PackageManager.NameNotFoundException ignored) {}
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 2) {
                List<String> order = dockOrder();
                String component = app.component.flattenToString();
                if (!order.remove(component)) order.add(component);
                saveDockOrder(order);
                activity.toast(pinned ? "Removed from hotbar" : "Added to hotbar");
                return true;
            }
            try {
                if (id == 1) activity.startActivity(
                    new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                );
                else {
                    Intent intent = new Intent(
                        id == 4 ? Intent.ACTION_DELETE : Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + app.component.getPackageName())
                    );
                    refreshAppsOnResume = true;
                    activity.startActivity(intent);
                }
            } catch (Exception e) {
                refreshAppsOnResume = false;
                activity.toast("Android could not open this action");
            }
            return true;
        });
        menu.show();
    }

    void allApps() {
        activity.screen("All Apps", activity.homeScreen::home);
        activity.page.addView(activity.text("Hold Select or press Menu for app options", 14));
        queryApps();
        LinearLayout r = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 6 == 0) {
                r = activity.row();
                activity.page.addView(r);
            }
            addApp(r, apps.get(i), false);
        }
        if (apps.isEmpty()) activity.page.addView(activity.text("No launchable apps found", 16));
    }
}
