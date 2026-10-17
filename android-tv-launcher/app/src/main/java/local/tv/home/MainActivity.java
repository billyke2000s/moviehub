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

/** Native TV home UI based on the supplied visual reference. No WebView. */
public class MainActivity extends Activity {

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private final DpadRepeats dpadRepeats = new DpadRepeats();
    private View movingApp;
    private LinearLayout homeDock;
    private TextView dockHint;
    private boolean eatSelectUp, refreshAppsOnResume, dockRemoving;
    private final LinkedHashMap<String, PosterFeed.State> catalogueStates = new LinkedHashMap<>();
    private JSONObject playbackTitle;
    private Runnable resumeRefresh, myListRefresh;
    private CatalogueScreen catalogue;
    private Dialog catalogueDialog;
    private LinearLayout overlayContent;

    private void rememberCatalogue(String key, PosterFeed.State state) {
        if (state.items.size() > 4000) return;
        catalogueStates.remove(key);
        catalogueStates.put(key, state);
        int count = 0;
        for (PosterFeed.State cached : catalogueStates.values()) count += cached.items.size();
        while (catalogueStates.size() > 8 || count > 4000) {
            String oldest = catalogueStates.keySet().iterator().next();
            count -= catalogueStates.remove(oldest).items.size();
        }
    }

    private void leaveCatalogue() {
        if (catalogue != null) {
            if (catalogue.feed != null && catalogue.path != null) {
                rememberCatalogue(catalogue.type + ":" + catalogue.path, catalogue.feed.snapshot());
            }
            catalogue.close();
            catalogue = null;
        }
        if (catalogueDialog != null) catalogueDialog.dismiss();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (
            eatSelectUp &&
            (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) &&
            event.getAction() == KeyEvent.ACTION_UP
        ) {
            eatSelectUp = false;
            return true;
        }
        if (
            dockRemoving &&
            (key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER)
        ) return true;
        if (movingApp != null) {
            if (
                key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_BACK ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER
            ) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !dpadRepeats.consume(event)) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) moveDockApp(
                        key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1
                    );
                    else if (event.getRepeatCount() == 0) {
                        if (key == KeyEvent.KEYCODE_DPAD_UP) removeDockApp();
                        else {
                            if (
                                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                            ) eatSelectUp = true;
                            finishDockMove();
                        }
                    }
                }
                return true;
            }
        }
        return dpadRepeats.consume(event) || super.dispatchKeyEvent(event);
    }

    private final List<PosterFeed> feeds = new ArrayList<>();

    private void closeFeeds() {
        for (PosterFeed feed : feeds) feed.close();
        feeds.clear();
    }

    private final Artwork artworkLoader = new Artwork();
    private Secrets secrets;
    private MediaRepository media;
    private SharedPreferences prefs;
    private LinearLayout page;
    private Runnable back;
    private Runnable destination;
    private HomeShelf homeShelf;
    private int generation;
    private TextView clock;
    private Button updateButton;
    private AppUpdates.Release availableUpdate;
    private long lastUpdateCheck;
    private boolean updateBusy;
    private long promptedUpdateVersion;
    private final Runnable ticker = new Runnable() {
        public void run() {
            if (clock != null) clock.setText(
                new java.text.SimpleDateFormat(
                    prefs.getBoolean("clock24", true) ? "HH:mm" : "h:mm a",
                    Locale.getDefault()
                ).format(new Date())
            );
            handler.postDelayed(this, 30000);
        }
    };

    private int accent() {
        return prefs.getInt("accent", 0xff8a9eff);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    interface Job<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void accept(T result) throws Exception;
    }

    private <T> void async(Job<T> job, Done<T> done) {
        int request = generation;
        io.execute(() -> {
            try {
                T result = job.run();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || request != generation) return;
                    try {
                        done.accept(result);
                    } catch (Exception e) {
                        toast("Could not display this response");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && request == generation) showRequestError();
                });
            }
        });
    }

    private void showRequestError() {
        LinearLayout target =
            catalogueDialog != null && catalogueDialog.isShowing() && overlayContent != null
                ? overlayContent
                : page;
        TextView pending = findLoading(target);
        if (pending != null) {
            pending.setText("Unable to load. Check your connection or retry this category.");
            return;
        }
        clearLoading(target);
        TextView error = text(
            "Could not load this content. Check your connection and connected accounts.",
            18
        );
        target.addView(error);
        target.addView(button("Settings", this::settings));
    }

    private TextView findLoading(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (
                child instanceof TextView && ((TextView) child).getText().toString().startsWith("Loading")
            ) return (TextView) child;
            if (child instanceof ViewGroup) {
                TextView found = findLoading((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clearLoading(android.view.ViewGroup parent) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (
                child instanceof TextView &&
                (((TextView) child).getText().toString().startsWith("Loading") ||
                    ((TextView) child).getText().toString().startsWith("Checking sources"))
            ) parent.removeViewAt(i);
            else if (child instanceof android.view.ViewGroup) clearLoading((android.view.ViewGroup) child);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean http() {
        return prefs.getBoolean("http", false);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        tvFullscreen();
        secrets = new Secrets(this);
        media = new MediaRepository(secrets);
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        try {
            ChannelSync.schedule(this);
        } catch (Exception ignored) {}
        home();
    }

    private void tvFullscreen() {
        getWindow()
            .getDecorView()
            .setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) tvFullscreen();
    }

    @Override
    public void onStart() {
        super.onStart();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        checkForUpdate();
        try {
            ChannelSync.refreshIfStale(this);
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        if (refreshAppsOnResume) {
            refreshAppsOnResume = false;
            allApps();
        } else if (resumeRefresh != null) {
            Runnable refresh = resumeRefresh;
            resumeRefresh = null;
            refresh.run();
        }
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        leaveCatalogue();
        closeFeeds();
        handler.removeCallbacksAndMessages(null);
        artworkLoader.close();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (back != null) back.run();
    }

    private GradientDrawable box(int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(7));
        return d;
    }

    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(0xfff8f8fc);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private void styleFocus(View view, boolean focused) {
        GradientDrawable background = box(focused ? 0xfff2f4f8 : view.isSelected() ? 0xff29313e : 0xff171d27);
        if (focused || view.isSelected()) background.setStroke(dp(focused ? 3 : 1), accent());
        view.setBackground(
            "navigation".equals(view.getTag()) && !focused && !view.isSelected()
                ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                : background
        );
        focusText(view, focused ? 0xff10141c : 0xfff2f4f8);
        view.setScaleX(focused ? 1.025f : 1f);
        view.setScaleY(focused ? 1.025f : 1f);
    }

    private void focusText(View view, int colour) {
        if (view instanceof TextView) ((TextView) view).setTextColor(colour);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) focusText(group.getChildAt(i), colour);
        }
    }

    private void focus(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((v, focused) -> styleFocus(v, focused));
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xfff8f8fc);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(box(0xff171d27));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        focus(b);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button navigationButton(String label, Runnable action) {
        Button b = button(label, action);
        b.setTag("navigation");
        styleFocus(b, false);
        return b;
    }

    private void screen(String title, Runnable goBack) {
        fixedScreen(title, goBack);
        LinearLayout frame = page;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = vertical();
        page.setPadding(dp(2), dp(12), dp(2), dp(20));
        scroll.addView(page);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private TextView muted(String value, int size) {
        TextView label = compactText(value, size);
        label.setTextColor(0xffa6b2c8);
        return label;
    }

    private void section(LinearLayout parent, String title) {
        TextView label = text(title, 20);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(20), 0, dp(10));
        parent.addView(label);
    }

    private void emptyState(
        LinearLayout target,
        String title,
        String description,
        String action,
        Runnable run
    ) {
        LinearLayout card = vertical();
        card.setPadding(dp(28), dp(24), dp(28), dp(28));
        card.setBackground(box(0xff131c2a));
        card.addView(text(title, 26));
        card.addView(muted(description, 16));
        if (action != null) {
            Button button = button(action, run);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.topMargin = dp(16);
            card.addView(button, lp);
        }
        target.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private Button chip(LinearLayout parent, String label, Runnable action) {
        Button choice = navigationButton(label, action);
        choice.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.rightMargin = dp(8);
        parent.addView(choice, lp);
        return choice;
    }

    private void primary(Button button) {
        button.setSelected(true);
        styleFocus(button, button.hasFocus());
    }

    private void addRow(LinearLayout parent, View view, int width, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(height));
        p.setMargins(dp(5), dp(5), dp(8), dp(5));
        parent.addView(view, p);
    }

    private LinearLayout horizontal(LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row();
        r.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(r);
        parent.addView(scroll);
        return r;
    }

    private void checkForUpdate() {
        checkForUpdate(false);
    }

    private void checkForUpdate(boolean manual) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastUpdateCheck != 0 && now - lastUpdateCheck < 6 * 60 * 60 * 1000L) return;
        lastUpdateCheck = now;
        io.execute(() -> {
            AppUpdates.Release result = null;
            boolean success = false;
            try {
                result = AppUpdates.check(getApplicationContext());
                success = true;
            } catch (Exception ignored) {}
            final boolean checked = success;
            final AppUpdates.Release release = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                availableUpdate = release;
                if (manual) toast(
                    !checked
                        ? "Could not reach the update server. Try again later."
                        : release == null
                          ? "You have the latest version"
                          : "Update available · " + release.name
                );
                if (updateButton != null) updateButton.setVisibility(
                    release == null ? View.GONE : View.VISIBLE
                );
                if (
                    release != null &&
                    (manual ||
                        (prefs.getBoolean("autoUpdates", true) && promptedUpdateVersion != release.version))
                ) {
                    promptedUpdateVersion = release.version;
                    offerUpdate();
                }
            });
        });
    }

    private void offerUpdate() {
        if (availableUpdate == null || updateBusy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                .setTitle("Allow app updates")
                .setMessage("Allow Home to install updates, then select Update again.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(
                            new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName())
                            )
                        );
                    } catch (Exception e) {
                        toast("Open Android Settings to allow app installation");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        final AppUpdates.Release release = availableUpdate;
        new AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("Install version " + release.name + "?")
            .setPositiveButton("Update", (dialog, which) -> {
                updateBusy = true;
                if (updateButton != null) {
                    updateButton.setEnabled(false);
                    updateButton.setText("Downloading…");
                }
                io.execute(() -> {
                    try {
                        AppUpdates.download(getApplicationContext(), release);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                android.net.Uri uri = android.net.Uri.parse(
                                    "content://" + getPackageName() + ".updates/update.apk"
                                );
                                Intent install = new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                install.setClipData(ClipData.newRawUri("Update", uri));
                                startActivity(install);
                            } catch (Exception e) {
                                toast("Android could not open the update installer");
                            }
                            resetUpdateButton();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            toast("Update could not be downloaded or verified. Try again later.");
                            resetUpdateButton();
                        });
                    }
                });
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void resetUpdateButton() {
        updateBusy = false;
        if (updateButton != null) {
            updateButton.setEnabled(true);
            updateButton.setText("Update");
        }
    }

    /** Home is a viewport, never a vertically scrolling page. The dock has reserved space. */
    private void fixedScreen(String title, Runnable goBack) {
        resumeRefresh = null;
        myListRefresh = null;
        finishDockMove();
        leaveCatalogue();
        closeFeeds();
        generation++;
        back = goBack;
        clock = null;
        updateButton = null;
        page = vertical();
        page.setBackgroundColor(0xff090d14);
        page.setPadding(dp(38), dp(20), dp(38), dp(20));
        setContentView(page);
        if (title != null) {
            LinearLayout head = row();
            TextView name = text(title, 24);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            head.addView(button("Back", goBack), new LinearLayout.LayoutParams(dp(72), dp(38)));
            page.addView(head, new LinearLayout.LayoutParams(-1, dp(46)));
        }
    }

    private TextView compactText(String value, int size) {
        TextView label = text(value, size);
        label.setPadding(0, dp(3), 0, dp(3));
        return label;
    }

    private void home() {
        destination = this::home;
        fixedScreen(null, null);
        page.setPadding(dp(30), dp(10), dp(30), dp(10));
        LinearLayout top = row();
        for (String kind : new String[] { "Live TV", "Movies", "Series", "My list", "Continue" })
            top.addView(
                navigationButton(kind, () -> {
                    if (kind.equals("Live TV")) live();
                    else if (kind.equals("My list")) watchlist();
                    else if (kind.equals("Continue")) continueWatching();
                    else library(kind);
                }),
                new LinearLayout.LayoutParams(dp(kind.equals("Continue") ? 110 : 94), dp(38))
            );
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        clock = compactText("", 16);
        top.addView(clock);
        updateButton = button("Update", this::offerUpdate);
        updateButton.setVisibility(availableUpdate == null ? View.GONE : View.VISIBLE);
        top.addView(updateButton);
        top.addView(
            navigationButton("Settings", this::settings),
            new LinearLayout.LayoutParams(dp(96), dp(38))
        );
        page.addView(top, new LinearLayout.LayoutParams(-1, dp(38)));
        ticker.run();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 30000);
        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setBackground(box(0xff111a27));
        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {
                0xff090d14,
                0xdd090d14,
                0x11090d14,
            })
        );
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = vertical();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(18), dp(4), dp(16), dp(4));
        TextView eyebrow = compactText("MOVIEHUB", 11);
        eyebrow.setLetterSpacing(.18f);
        eyebrow.setTextColor(0xffa6b2c8);
        copy.addView(eyebrow);
        TextView heading = compactText("Find your next favourite", 28);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(heading);
        TextView summary = compactText(
            secrets.has("tmdb") ? "Choose a title below" : "Connect TMDB to browse movies and series",
            14
        );
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(summary);
        Button open = button("Connect TMDB", () -> accountScreen("TMDB"));
        copy.addView(open, new LinearLayout.LayoutParams(dp(170), dp(36)));
        hero.addView(copy, new FrameLayout.LayoutParams(dp(510), -1));
        LinearLayout.LayoutParams heroLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        heroLayout.setMargins(0, dp(6), 0, dp(4));
        page.addView(hero, heroLayout);
        LinearLayout categories = row();
        String[] labels = { "For you", "Trending", "Top rated", "New movies", "On TV" };
        LinearLayout posterHost = vertical();
        posterHost.setClipChildren(false);
        HomeShelf shelf = new HomeShelf(posterHost, heading, summary, open, backdrop);
        homeShelf = shelf;
        java.util.function.Consumer<String> load = path -> {
            generation++;
            shelf.close();
            posterHost.removeAllViews();
            TextView loading = compactText("Loading titles…", 14);
            posterHost.addView(loading);
            if (!secrets.has("tmdb")) {
                loading.setText("Connect TMDB in Settings to see artwork and recommendations.");
                return;
            }
            String seedType = prefs.getString("seedType", "movie");
            int seed = prefs.getInt("seed", 0);
            shelf.load(path, pageNumber ->
                path.isEmpty()
                    ? media.featuredPage(seedType, seed, pageNumber)
                    : media.tmdb(path + "?page=" + pageNumber)
            );
        };
        String[] paths = { "", "trending/all/week", "movie/top_rated", "movie/now_playing", "tv/on_the_air" };
        List<Button> tabs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final String path = paths[i];
            Button tab = navigationButton(labels[i], () -> {});
            tabs.add(tab);
            tab.setTextSize(14);
            tab.setOnClickListener(v -> {
                for (Button other : tabs) {
                    other.setSelected(other == v);
                    styleFocus(other, other.hasFocus());
                }
                load.accept(path);
            });
            categories.addView(tab, new LinearLayout.LayoutParams(dp(114), dp(32)));
        }
        tabs.get(0).setSelected(true);
        styleFocus(tabs.get(0), false);
        page.addView(categories, new LinearLayout.LayoutParams(-1, dp(32)));
        page.addView(posterHost, new LinearLayout.LayoutParams(-1, dp(158)));
        if (prefs.getBoolean("featured", true)) load.accept("");
        else {
            hero.setVisibility(View.GONE);
            categories.setVisibility(View.GONE);
            posterHost.setVisibility(View.GONE);
            page.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout dockHeader = row();
        dockHeader.addView(compactText("Your apps", 13), new LinearLayout.LayoutParams(0, -2, 1));
        dockHint = compactText("Hold Select to rearrange", 11);
        dockHeader.addView(dockHint);
        dockHeader.setElevation(dp(8));
        dockHeader.setBackgroundColor(0xff090d14);
        page.addView(dockHeader, new LinearLayout.LayoutParams(-1, dp(28)));
        HorizontalScrollView appScroll = new HorizontalScrollView(this);
        appScroll.setHorizontalScrollBarEnabled(false);
        appScroll.setClipChildren(false);
        appScroll.setClipToPadding(false);
        homeDock = row();
        homeDock.setClipChildren(false);
        homeDock.setPadding(0, dp(8), 0, 0);
        appScroll.addView(homeDock);
        page.addView(appScroll, new LinearLayout.LayoutParams(-1, dp(78)));
        queryApps();
        for (String id : dockOrder())
            for (App app : apps)
                if (id.equals(app.component.flattenToString())) {
                    View cell = addApp(homeDock, app, true);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                    lp.width = dp(108);
                    lp.height = dp(62);
                    lp.topMargin = dp(2);
                    lp.bottomMargin = dp(2);
                    cell.setLayoutParams(lp);
                    LinearLayout contents = (LinearLayout) cell;
                    contents.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
                    TextView caption = (TextView) contents.getChildAt(1);
                    caption.setTextSize(12);
                    caption.setMaxLines(1);
                    caption.setPadding(0, dp(3), 0, 0);
                }
        homeDock.addView(button("All Apps", this::allApps), new LinearLayout.LayoutParams(dp(104), dp(62)));
        page.post(() -> top.getChildAt(0).requestFocus());
    }

    private final class HomeShelf {

        final LinearLayout posterHost;
        final TextView heading, summary;
        final Button open;
        final ImageView backdrop;
        PosterFeed feed;
        boolean previewed;
        Runnable pendingArt;

        HomeShelf(LinearLayout host, TextView title, TextView overview, Button action, ImageView image) {
            posterHost = host;
            heading = title;
            summary = overview;
            open = action;
            backdrop = image;
        }

        void close() {
            if (pendingArt != null) handler.removeCallbacks(pendingArt);
            if (feed != null) {
                feed.close();
                feeds.remove(feed);
                feed = null;
            }
        }

        void show(JSONArray items, String path) throws JSONException {
            load(path, n -> new JSONObject().put("results", items).put("total_pages", 1));
        }

        void load(String path, PosterFeed.Loader loader) {
            close();
            previewed = false;
            posterHost.removeAllViews();
            feed = new PosterFeed(
                MainActivity.this,
                true,
                path.startsWith("tv/") ? "tv" : "movie",
                io,
                loader,
                new PosterFeed.Cards() {
                    public View create(ViewGroup parent) {
                        return posterCard(true);
                    }

                    public void bind(View card, JSONObject item) {
                        bindPoster(card, item, true, () -> preview(item));
                        if (!previewed) {
                            previewed = true;
                            preview(item);
                        }
                    }
                }
            );
            feeds.add(feed);
            posterHost.addView(feed, new LinearLayout.LayoutParams(-1, -1));
            feed.start();
        }

        void preview(JSONObject item) {
            String name = item.optString("title", item.optString("name", "Title"));
            heading.setText(name);
            summary.setText(item.optString("overview", ""));
            open.setText("View details");
            open.setOnClickListener(v -> tmdbTitle(item));
            String art = item.optString("backdrop_path");
            if (pendingArt != null) handler.removeCallbacks(pendingArt);
            pendingArt = () -> {
                if (!backdrop.isAttachedToWindow()) return;
                backdrop.setTag(null);
                backdrop.setImageDrawable(null);
                if (art.startsWith("/")) loadImage(backdrop, "https://image.tmdb.org/t/p/w1280" + art);
            };
            handler.postDelayed(pendingArt, 320);
        }
    }

    private View posterCard(boolean compact) {
        LinearLayout card = vertical();
        card.setBackground(box(0xff171d27));
        card.setClipToOutline(true);
        card.setPadding(dp(compact ? 0 : 6), dp(compact ? 0 : 6), dp(compact ? 0 : 6), dp(compact ? 0 : 6));
        ImageView image = compact
            ? new ImageView(this)
            : new ImageView(this) {
                  @Override
                  protected void onMeasure(int widthSpec, int heightSpec) {
                      int width = View.MeasureSpec.getSize(widthSpec);
                      setMeasuredDimension(width, Math.round(width * 1.5f));
                  }
              };
        image.setScaleType(compact ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        card.addView(image, new LinearLayout.LayoutParams(-1, compact ? -1 : -2));
        if (!compact) {
            PosterTitle title = new PosterTitle(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                -1,
                Math.max(dp(42), (int) Math.ceil(title.getPaint().getFontSpacing() * 2))
            );
            lp.topMargin = dp(4);
            card.addView(title, lp);
        }
        focus(card);
        return card;
    }

    private void bindPoster(View view, JSONObject item, boolean compact, Runnable preview) {
        LinearLayout card = (LinearLayout) view;
        ImageView image = (ImageView) card.getChildAt(0);
        String name = item.optString("title", item.optString("name", "Title"));
        image.setTag(null);
        image.setImageDrawable(null);
        String art = item.optString("poster_path");
        if (art.startsWith("/")) loadImage(image, "https://image.tmdb.org/t/p/w342" + art);
        card.setContentDescription(name + " · " + titleMetadata(item));
        card.setOnLongClickListener(v -> {
            posterActions(v, item);
            return true;
        });
        card.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) posterActions(v, item);
                return true;
            }
            return false;
        });
        PosterTitle title = compact ? null : (PosterTitle) card.getChildAt(1);
        if (title != null) {
            title.setText(name);
            title.cardFocus(card.hasFocus());
        }
        card.setOnFocusChangeListener((v, on) -> {
            styleFocus(v, on);
            if (title != null) title.cardFocus(on);
            if (on && preview != null) preview.run();
        });
        styleFocus(card, card.hasFocus());
        card.setOnClickListener(v -> tmdbTitle(item));
    }

    PosterFeed catalogueFeed(
        LinearLayout right,
        View oldResults,
        LinearLayout menu,
        String type,
        PosterFeed.Loader loader
    ) {
        return catalogueFeed(right, oldResults, menu, type, loader, null);
    }

    private PosterFeed catalogueFeed(
        LinearLayout right,
        View oldResults,
        LinearLayout menu,
        String type,
        PosterFeed.Loader loader,
        PosterFeed.State saved
    ) {
        right.removeAllViews();
        PosterFeed feed = new PosterFeed(
            this,
            false,
            type,
            io,
            loader,
            new PosterFeed.Cards() {
                public View create(ViewGroup parent) {
                    return posterCard(false);
                }

                public void bind(View card, JSONObject item) {
                    CatalogueScreen active = catalogue;
                    bindPoster(card, item, false, active == null ? null : () -> active.preview(item));
                }
            }
        );
        feed.setColumns(6);
        if (catalogue != null && catalogue.content == right) {
            LinearLayout header = (LinearLayout) right.getParent();
            if (header.getChildCount() > 0 && header.getChildAt(0) instanceof LinearLayout) {
                LinearLayout controls = (LinearLayout) header.getChildAt(0);
                for (int i = 0; i < controls.getChildCount(); i++) {
                    View control = controls.getChildAt(i);
                    if (control.isFocusable()) control.setOnKeyListener((v, key, event) -> {
                        if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) feed.focusBelow(v);
                        return true;
                    });
                }
            }
        }
        if (catalogue != null && catalogue.content == right) {
            if (catalogue.feed != null) {
                catalogue.feed.close();
                feeds.remove(catalogue.feed);
            }
            catalogue.feed = feed;
        }
        feeds.add(feed);
        right.addView(feed, new LinearLayout.LayoutParams(-1, 0, 1));
        routeCategoryFocus(menu, feed);
        if (saved != null && !saved.items.isEmpty()) feed.restore(saved);
        else feed.start();
        return feed;
    }

    static void routeCategoryFocus(LinearLayout menu, PosterFeed feed) {
        for (int i = 0; i < menu.getChildCount(); i++) if (menu.getChildAt(i).isFocusable()) menu.getChildAt(
            i
        ).setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) feed.focusBelow(v);
            return true;
        });
    }

    private void loadImage(ImageView view, String url) {
        artworkLoader.load(view, url);
    }

    private void tmdbTitle(JSONObject item) {
        String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
        int tmdbId = item.optInt("id");
        Runnable returnTo = destination == null ? this::home : destination;
        screen("Details", returnTo);
        playbackTitle = item;
        TextView loading = text("Loading details…", 18);
        page.addView(loading);
        async(
            () -> media.tmdb(type + "/" + tmdbId + "?append_to_response=external_ids,credits,videos"),
            details -> {
                details.put("media_type", type);
                page.removeView(loading);
                renderDetails(details);
            }
        );
    }

    private String titleMetadata(JSONObject item) {
        String date = item.optString("release_date", item.optString("first_air_date", ""));
        List<String> parts = new ArrayList<>();
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        parts.add(
            item.optString("media_type", item.has("title") ? "movie" : "tv").equals("tv") ? "Series" : "Movie"
        );
        double score = item.optDouble("vote_average", 0);
        if (score > 0) parts.add("★ " + String.format(Locale.ROOT, "%.1f", score));
        int runtime = item.optInt("runtime");
        if (runtime > 0) parts.add(runtime + " min");
        int seasons = item.optInt("number_of_seasons");
        if (seasons > 0) parts.add(seasons + (seasons == 1 ? " season" : " seasons"));
        return String.join("   ·   ", parts);
    }

    private void posterActions(View anchor, JSONObject item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu()
            .add("View details")
            .setOnMenuItemClickListener(v -> {
                tmdbTitle(item);
                return true;
            });
        menu.getMenu()
            .add(isWatchlisted(item) ? "Remove from my list" : "Add to my list")
            .setOnMenuItemClickListener(v -> {
                toggleWatchlist(item);
                toast(isWatchlisted(item) ? "Added to my list" : "Removed from my list");
                if (myListRefresh != null) myListRefresh.run();
                return true;
            });
        menu.getMenu()
            .add("More like this")
            .setOnMenuItemClickListener(v -> {
                String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
                catalogue(type, type + "/" + item.optInt("id") + "/recommendations", 1);
                return true;
            });
        menu.show();
    }

    private JSONObject recentForTitle(JSONObject item) {
        try {
            String raw = secrets.get("recentPlayback");
            JSONArray recent = new JSONArray(raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject row = recent.getJSONObject(i);
                if (
                    row.optInt("tmdbId") == item.optInt("id") &&
                    row.optString("media_type").equals(item.optString("media_type"))
                ) {
                    Object position = getSharedPreferences("positions", MODE_PRIVATE)
                        .getAll()
                        .get(row.optString("id"));
                    if (position instanceof Number && ((Number) position).longValue() > 0) return row;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void renderDetails(JSONObject item) throws Exception {
        playbackTitle = item;
        String title = item.optString("title", item.optString("name", "Title")),
            type = item.optString("media_type", "movie");
        int tmdbId = item.optInt("id");
        String kind = type.equals("tv") ? "Series" : "Movies";
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(box(0xff121c29));
        hero.setClipToOutline(true);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(background, new FrameLayout.LayoutParams(-1, -1));
        String backdrop = item.optString("backdrop_path");
        if (backdrop.startsWith("/")) loadImage(background, "https://image.tmdb.org/t/p/w1280" + backdrop);
        View shade = new View(this);
        shade.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {
                0xfc0d1420,
                0xe60d1420,
                0x770d1420,
            })
        );
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout layout = row();
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(24), dp(20), dp(24), dp(20));
        hero.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(poster, new LinearLayout.LayoutParams(dp(156), dp(234)));
        String artwork = item.optString("poster_path");
        if (artwork.startsWith("/")) loadImage(poster, "https://image.tmdb.org/t/p/w500" + artwork);
        LinearLayout info = vertical();
        info.setPadding(dp(26), 0, 0, 0);
        layout.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(muted(type.equals("tv") ? "SERIES" : "MOVIE", 12));
        TextView heading = compactText(title, 30);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(heading);
        info.addView(muted(titleMetadata(item), 14));
        JSONArray genres = item.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        if (genres != null) for (int i = 0; i < genres.length(); i++) names.add(
            genres.getJSONObject(i).optString("name")
        );
        if (!names.isEmpty()) info.addView(muted(String.join(" · ", names), 14));
        TextView synopsis = compactText(item.optString("overview", "No synopsis available."), 16);
        synopsis.setMaxLines(3);
        synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(synopsis);
        LinearLayout actions = row();
        JSONObject recent = recentForTitle(item);
        Button resume =
            recent == null
                ? null
                : chip(actions, "Resume", () ->
                      play(
                          recent.optString("title", title),
                          recent.optString("url"),
                          recent.optString("id"),
                          false,
                          recent
                      )
                  );
        if (resume != null) primary(resume);
        Button play = chip(actions, type.equals("tv") ? "Episodes" : "Find sources", () -> {
            prefs.edit().putInt("seed", tmdbId).putString("seedType", type).apply();
            playbackTitle = item;
            if (type.equals("tv")) seasons(tmdbId, title);
            else {
                JSONObject external = item.optJSONObject("external_ids");
                String imdb =
                    external == null
                        ? item.optString("imdb_id")
                        : external.optString("imdb_id", item.optString("imdb_id"));
                torrentSources(imdb, -1, -1, title, kind);
            }
        });
        primary(play);
        Button saved = chip(actions, isWatchlisted(item) ? "✓ My list" : "+ My list", () -> {});
        saved.setOnClickListener(v -> {
            toggleWatchlist(item);
            saved.setText(isWatchlisted(item) ? "✓ My list" : "+ My list");
        });
        JSONArray videos =
            item.optJSONObject("videos") == null
                ? null
                : item.getJSONObject("videos").optJSONArray("results");
        String trailer = "";
        if (videos != null) for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.getJSONObject(i);
            if (
                video.optString("site").equals("YouTube") &&
                video.optString("type").equals("Trailer") &&
                video.optString("key").matches("[A-Za-z0-9_-]{11}")
            ) {
                trailer = video.getString("key");
                if (video.optBoolean("official")) break;
            }
        }
        if (!trailer.isEmpty()) {
            final String key = trailer;
            chip(actions, "Trailer", () -> {
                try {
                    startActivity(
                        new Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.youtube.com/watch?v=" + key)
                        )
                    );
                } catch (Exception e) {
                    toast("No app can open this trailer");
                }
            });
        }
        info.addView(actions);
        page.addView(hero, new LinearLayout.LayoutParams(-1, dp(326)));
        section(page, "About " + title);
        page.addView(muted(item.optString("overview", "No synopsis available."), 16));
        LinearLayout related = row();
        chip(related, "More like this", () -> catalogue(type, type + "/" + tmdbId + "/recommendations", 1));
        JSONObject collection = item.optJSONObject("belongs_to_collection");
        if (collection != null) chip(related, collection.optString("name", "Collection"), () ->
            browse(
                kind,
                collection.optString("name", "Collection"),
                "collection/" + collection.optInt("id"),
                1
            )
        );
        page.addView(related);
        JSONObject credits = item.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast != null && cast.length() > 0) {
            section(page, "Cast");
            LinearLayout actors = horizontal(page);
            for (int i = 0; i < Math.min(12, cast.length()); i++) {
                JSONObject person = cast.getJSONObject(i);
                LinearLayout card = vertical();
                card.setPadding(dp(8), dp(8), dp(8), dp(8));
                ImageView photo = new ImageView(this);
                photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(photo, new LinearLayout.LayoutParams(dp(96), dp(112)));
                String image = person.optString("profile_path");
                if (image.startsWith("/")) loadImage(photo, "https://image.tmdb.org/t/p/w185" + image);
                TextView name = compactText(person.optString("name"), 13);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(name);
                TextView character = muted(person.optString("character"), 11);
                character.setMaxLines(1);
                character.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(character);
                focus(card);
                card.setOnClickListener(v ->
                    browse(
                        kind,
                        person.optString("name"),
                        "person/" + person.optInt("id") + "/" + type + "_credits",
                        1
                    )
                );
                addRow(actors, card, 122, 172);
            }
        }
        if (resume != null) resume.requestFocus();
        else play.requestFocus();
    }

    private void posterGrid(LinearLayout target, JSONArray items, String type) throws Exception {
        int visible = 0;
        LinearLayout posterRow = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optBoolean("adult", false)) continue;
            if (!type.isEmpty()) item.put("media_type", type);
            if (visible % 4 == 0) {
                posterRow = row();
                target.addView(posterRow);
            }
            View card = posterCard(false);
            bindPoster(card, item, false, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(4), dp(5), dp(4), dp(5));
            posterRow.addView(card, lp);
            visible++;
        }
        if (posterRow != null) while (posterRow.getChildCount() < 4)
            posterRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (visible == 0) target.addView(text("No titles found", 18));
    }

    private static class App {

        String label;
        ComponentName component;
        Drawable icon;

        App(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private void queryApps() {
        apps.clear();
        Set<ComponentName> seen = new HashSet<>();
        for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER })
            for (ResolveInfo r : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(category),
                0
            )) {
                ActivityInfo a = r.activityInfo;
                if (!a.enabled || !a.exported || a.packageName.equals(getPackageName())) continue;
                ComponentName component = new ComponentName(a.packageName, a.name);
                if (seen.add(component)) apps.add(
                    new App(
                        r.loadLabel(getPackageManager()).toString(),
                        component,
                        r.loadIcon(getPackageManager())
                    )
                );
            }
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
    }

    private View addApp(LinearLayout into, App app, boolean dock) {
        LinearLayout cell = vertical();
        cell.setTag(app.component.flattenToString());
        cell.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(this);
        image.setImageDrawable(app.icon);
        cell.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = text(app.label, 16);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER);
        cell.addView(label);
        focus(cell);
        cell.setBackground(box(0xff202c43));
        cell.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));
            } catch (Exception e) {
                toast("App is unavailable");
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
        addRow(into, cell, 130, 114);
        return cell;
    }

    /** Migrate the old unordered pins once, using their existing visible order. */
    private List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        String saved = prefs.getString("dockOrder", null);
        if (saved != null) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.getString(i);
                    if (!order.contains(id)) order.add(id);
                }
            } catch (JSONException ignored) {}
        } else {
            Set<String> pins = prefs.getStringSet("pinned", null);
            for (App app : apps)
                if (
                    pins == null ? order.size() < 6 : pins.contains(app.component.flattenToString())
                ) order.add(app.component.flattenToString());
            saveDockOrder(order);
        }
        return order;
    }

    private void saveDockOrder(List<String> order) {
        prefs.edit().putString("dockOrder", new JSONArray(order).toString()).apply();
    }

    private void saveVisibleDock() {
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
        view.animate().translationY(-dp(8)).setDuration(160).start();
        view.setElevation(dp(10));
        dockHint.setText("← → Move · ↑ Remove · Select Done");
    }

    private void finishDockMove() {
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

    private void moveDockApp(int direction) {
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

    private void removeDockApp() {
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
            .translationY(-dp(24))
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
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean pinned = dockOrder().contains(app.component.flattenToString());
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 1, pinned ? "Remove from hotbar" : "Add to hotbar");
        menu.getMenu().add(0, 3, 2, "App info");
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(app.component.getPackageName(), 0);
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
                toast(pinned ? "Removed from hotbar" : "Added to hotbar");
                return true;
            }
            try {
                if (id == 1) startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));
                else {
                    Intent intent = new Intent(
                        id == 4 ? Intent.ACTION_DELETE : Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + app.component.getPackageName())
                    );
                    refreshAppsOnResume = true;
                    startActivity(intent);
                }
            } catch (Exception e) {
                refreshAppsOnResume = false;
                toast("Android could not open this action");
            }
            return true;
        });
        menu.show();
    }

    private void allApps() {
        screen("All Apps", this::home);
        page.addView(text("Hold Select or press Menu for app options", 14));
        queryApps();
        LinearLayout r = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 6 == 0) {
                r = row();
                page.addView(r);
            }
            addApp(r, apps.get(i), false);
        }
        if (apps.isEmpty()) page.addView(text("No launchable apps found", 16));
    }

    private ChannelDatabase channelsDb() {
        return ChannelDatabase.get(this);
    }

    interface ChannelPage {
        List<ChannelDatabase.Row> load(int offset) throws Exception;
    }

    private Set<String> favouriteKeys() {
        return new HashSet<>(prefs.getStringSet("favourites", Collections.emptySet()));
    }

    private void live() {
        channelBrowser("all", "", 0);
    }

    private void channelBrowser(String filter, String search, int offset) {
        screen("Live TV", this::home);
        LinearLayout filters = horizontal(page);
        for (String option : new String[] { "all", "sports", "favourites" }) {
            Button tab = chip(
                filters,
                option.substring(0, 1).toUpperCase(Locale.ROOT) + option.substring(1),
                () -> channelBrowser(option, search, 0)
            );
            if (option.equals(filter)) primary(tab);
        }
        chip(filters, "Groups", () ->
            async(
                () -> channelsDb().groups(),
                groups -> {
                    String[] names = new String[groups.size()];
                    for (int i = 0; i < names.length; i++) names[i] =
                        (groups.get(i).name.isEmpty() ? "Ungrouped" : groups.get(i).name) +
                        " · " +
                        groups.get(i).count;
                    new AlertDialog.Builder(this)
                        .setTitle("Channel groups")
                        .setItems(names, (d, which) ->
                            channelBrowser("group:" + groups.get(which).name, search, 0)
                        )
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            )
        );
        chip(filters, "Search", () -> {
            LinearLayout form = vertical();
            EditText query = field(form, "Channel or group", false, search);
            Dialog dialog = form("Search live TV", form);
            form.addView(
                button("Search", () -> {
                    dialog.dismiss();
                    channelBrowser(filter, query.getText().toString().trim(), 0);
                })
            );
        });
        chip(filters, "Refresh", () -> {
            ChannelSync.refreshNow(this);
            channelBrowser(filter, search, 0);
        });
        if (!search.isEmpty() || filter.startsWith("group:")) {
            LinearLayout active = row();
            active.addView(
                muted(
                    (filter.startsWith("group:") ? filter.substring(6) : "") +
                        (!search.isEmpty() ? "   Search: " + search : ""),
                    15
                ),
                new LinearLayout.LayoutParams(0, -2, 1)
            );
            chip(active, "Clear", () -> channelBrowser("all", "", 0));
            page.addView(active);
        }
        TextView status = text("Loading saved channels…", 16);
        page.addView(status);
        LinearLayout results = vertical();
        page.addView(results);
        async(
            () -> {
                ChannelDatabase db = channelsDb();
                return db.browse(filter, search, favouriteKeys(), offset);
            },
            items -> {
                ChannelImporter.Status sync = ChannelImporter.status(this);
                status.setText(
                    sync.running
                        ? String.format(
                              Locale.getDefault(),
                              "Updating · %,d channels imported",
                              sync.progress
                          )
                        : sync.queued
                          ? "Waiting for network to refresh"
                          : sync.message.isEmpty()
                            ? "Saved channels"
                            : "Saved channels · " + sync.message
                );
                int count = Math.min(items.size(), ChannelDatabase.PAGE);
                Set<String> visibleFavourites = favouriteKeys();
                LinearLayout gridRow = null;
                for (int i = 0; i < count; i++) {
                    ChannelDatabase.Row channel = items.get(i);
                    if (i % 3 == 0) {
                        gridRow = row();
                        results.addView(gridRow);
                    }
                    boolean favourite = visibleFavourites.contains(channel.key);
                    LinearLayout card = vertical();
                    card.setPadding(dp(16), dp(12), dp(16), dp(12));
                    card.setBackground(box(0xff182337));
                    LinearLayout identity = row();
                    ImageView logo = new ImageView(this);
                    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    identity.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
                    if (channel.logo.startsWith("https://")) loadImage(logo, channel.logo);
                    TextView name = text((favourite ? "★  " : "") + channel.name, 18);
                    name.setMaxLines(1);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
                    nameParams.leftMargin = dp(12);
                    identity.addView(name, nameParams);
                    card.addView(identity);
                    TextView group = text(channel.group.isEmpty() ? "Live TV" : channel.group, 13);
                    group.setMaxLines(1);
                    group.setTextColor(0xffa6b2c8);
                    card.addView(group);
                    focus(card);
                    card.setOnClickListener(v ->
                        async(
                            () -> channelsDb().url(channel.id, channel.key),
                            url -> play(channel.name, url, channel.key, true)
                        )
                    );
                    card.setOnLongClickListener(v -> {
                        Set<String> keys = favouriteKeys();
                        boolean added = keys.add(channel.key);
                        if (!added) keys.remove(channel.key);
                        prefs.edit().putStringSet("favourites", keys).apply();
                        name.setText((added ? "★  " : "") + channel.name);
                        toast(added ? "Added to favourites" : "Removed from favourites");
                        return true;
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(105), 1);
                    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                    gridRow.addView(card, lp);
                }
                if (count == 0) results.addView(
                    text("No matching channels. Connect an account or refresh your provider.", 18)
                );
                LinearLayout navigation = row();
                if (offset > 0) navigation.addView(
                    button("Previous page", () ->
                        channelBrowser(filter, search, Math.max(0, offset - ChannelDatabase.PAGE))
                    )
                );
                if (items.size() > ChannelDatabase.PAGE) navigation.addView(
                    button("Next page", () -> channelBrowser(filter, search, offset + ChannelDatabase.PAGE))
                );
                navigation.addView(
                    button("Refresh providers", () -> {
                        ChannelSync.refreshNow(this);
                        channelBrowser(filter, search, 0);
                    })
                );
                page.addView(navigation);
                page.addView(text("Hold Select on a channel to save or remove a favourite", 13));
                int request = generation;
                if (sync.running || sync.queued) handler.postDelayed(
                    new Runnable() {
                        public void run() {
                            if (isFinishing() || isDestroyed() || generation != request) return;
                            ChannelImporter.Status latest = ChannelImporter.status(MainActivity.this);
                            if (latest.running || latest.queued) {
                                status.setText(
                                    latest.running
                                        ? String.format(
                                              Locale.getDefault(),
                                              "Updating · %,d channels imported",
                                              latest.progress
                                          )
                                        : "Waiting for network to refresh"
                                );
                                handler.postDelayed(this, 2000);
                            } else {
                                status.setText(latest.message);
                                page.addView(
                                    button("Reload channels", () -> channelBrowser(filter, search, 0))
                                );
                            }
                        }
                    },
                    2000
                );
            }
        );
    }

    private String id(String url) {
        return ChannelQuery.key(url);
    }

    private void play(String title, String url, String id, boolean live) {
        play(title, url, id, live, null);
    }

    private void play(String title, String url, String id, boolean live, JSONObject metadata) {
        final String safe;
        try {
            safe = Playlist.networkUrl(url, http());
        } catch (Exception e) {
            toast("Could not open this source. Check its address and your HTTP setting.");
            return;
        }
        if (!live) try {
            String stored = secrets.get("recentPlayback");
            JSONArray previous = new JSONArray(stored.isEmpty() ? "[]" : stored),
                recent = new JSONArray();
            JSONObject record = new JSONObject().put("title", title).put("url", safe).put("id", id);
            if (metadata != null) {
                record.put("tmdbId", metadata.optInt("tmdbId", metadata.optInt("id")));
                for (String key : new String[] {
                    "media_type",
                    "poster_path",
                    "backdrop_path",
                    "overview",
                    "season",
                    "episode",
                    "episodeKey",
                })
                    if (metadata.has(key)) record.put(key, metadata.get(key));
            }
            recent.put(record);
            for (int i = 0; i < previous.length() && recent.length() < 50; i++) if (
                !id.equals(previous.getJSONObject(i).optString("id"))
            ) recent.put(previous.getJSONObject(i));
            secrets.put("recentPlayback", recent.toString());
        } catch (Exception ignored) {
            /* History failure must never prevent playback. */
        }
        try {
            startActivity(
                new Intent(this, VlcPlaybackActivity.class)
                    .putExtra("title", title)
                    .putExtra("url", safe)
                    .putExtra("id", id)
                    .putExtra("live", live)
                    .putExtra("episodeKey", metadata == null ? null : metadata.optString("episodeKey", null))
            );
        } catch (Exception e) {
            toast("Playback could not be started");
        }
    }

    private JSONArray watchlistItems() {
        try {
            return new JSONArray(prefs.getString("watchlist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private boolean isWatchlisted(JSONObject item) {
        JSONArray saved = watchlistItems();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (
                row != null &&
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) return true;
        }
        return false;
    }

    private void toggleWatchlist(JSONObject item) {
        JSONArray saved = watchlistItems(),
            next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.optJSONObject(i);
            if (row == null) continue;
            if (
                row.optInt("id") == item.optInt("id") &&
                row.optString("media_type").equals(item.optString("media_type"))
            ) {
                found = true;
                continue;
            }
            next.put(row);
        }
        if (!found) {
            JSONObject entry = new JSONObject();
            try {
                for (String key : new String[] {
                    "id",
                    "title",
                    "name",
                    "media_type",
                    "poster_path",
                    "backdrop_path",
                    "overview",
                })
                    if (item.has(key)) entry.put(key, item.get(key));
                next.put(entry);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("watchlist", next.toString()).apply();
    }

    private void watchlist() {
        watchlist("All", prefs.getString("watchlistSort", "Recently added"));
    }

    private void watchlist(String filter, String sort) {
        destination = () -> watchlist(filter, sort);
        screen("My list", this::home);
        myListRefresh = () -> watchlist(filter, sort);
        LinearLayout tools = horizontal(page);
        for (String kind : new String[] { "All", "Movies", "Series" }) {
            Button tab = chip(tools, kind, () -> watchlist(kind, sort));
            if (kind.equals(filter)) primary(tab);
        }
        chip(tools, "Sort: " + sort, () ->
            new AlertDialog.Builder(this)
                .setTitle("Sort My list")
                .setItems(new String[] { "Recently added", "Title A–Z", "Title Z–A" }, (d, which) -> {
                    String value = new String[] { "Recently added", "Title A–Z", "Title Z–A" }[which];
                    prefs.edit().putString("watchlistSort", value).apply();
                    watchlist(filter, value);
                })
                .show()
        );
        List<JSONObject> rows = new ArrayList<>();
        JSONArray saved = watchlistItems();
        for (int i = saved.length() - 1; i >= 0; i--) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("media_type", item.has("title") ? "movie" : "tv");
            if (filter.equals("All") || filter.equals(type.equals("tv") ? "Series" : "Movies")) rows.add(
                item
            );
        }
        if (sort.startsWith("Title")) {
            rows.sort(
                Comparator.comparing(item ->
                    item.optString("title", item.optString("name", "")).toLowerCase(Locale.ROOT)
                )
            );
            if (sort.endsWith("Z–A")) Collections.reverse(rows);
        }
        page.addView(
            muted(rows.size() + (rows.size() == 1 ? " title" : " titles") + " · Hold Select for options", 13)
        );
        if (rows.isEmpty()) {
            emptyState(
                page,
                "Your next watch starts here",
                filter.equals("All")
                    ? "Save movies and series from their details page."
                    : "No saved " + filter.toLowerCase(Locale.ROOT) + " yet.",
                "Browse " + (filter.equals("Series") ? "series" : "movies"),
                () -> library(filter.equals("Series") ? "Series" : "Movies")
            );
            return;
        }
        LinearLayout grid = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 6 == 0) {
                grid = row();
                grid.setGravity(Gravity.TOP);
                page.addView(grid);
            }
            View card = posterCard(false);
            bindPoster(card, rows.get(i), false, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(4), dp(8), dp(4), dp(8));
            grid.addView(card, lp);
        }
        if (grid != null) while (grid.getChildCount() < 6)
            grid.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void continueWatching() {
        screen("Continue watching", this::home);
        resumeRefresh = this::continueWatching;
        LinearLayout controls = horizontal(page);
        chip(controls, "My list", this::watchlist);
        chip(controls, "Clear history", () ->
            new AlertDialog.Builder(this)
                .setTitle("Clear viewing history?")
                .setMessage("This removes saved playback progress on this device.")
                .setPositiveButton("Clear", (d, w) -> {
                    getSharedPreferences("positions", MODE_PRIVATE).edit().clear().apply();
                    try {
                        secrets.put("recentPlayback", "[]");
                    } catch (Exception e) {
                        toast("Could not clear history");
                    }
                    continueWatching();
                })
                .setNegativeButton("Cancel", null)
                .show()
        );
        try {
            String stored = secrets.get("recentPlayback");
            JSONArray recent = new JSONArray(stored.isEmpty() ? "[]" : stored);
            int count = 0;
            SharedPreferences positions = getSharedPreferences("positions", MODE_PRIVATE);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject item = recent.getJSONObject(i);
                String key = item.getString("id");
                Object saved = positions.getAll().get(key);
                if (!(saved instanceof Number) || ((Number) saved).longValue() <= 0) continue;
                long position = ((Number) saved).longValue(),
                    length = positions.getLong(key + ":duration", 0);
                count++;
                String title = item.optString("title", "Title"),
                    url = item.getString("url");
                LinearLayout card = row();
                card.setPadding(dp(12), dp(12), dp(16), dp(12));
                card.setBackground(box(0xff141e2c));
                ImageView artwork = new ImageView(this);
                artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(artwork, new LinearLayout.LayoutParams(dp(168), dp(94)));
                String path = item.optString("backdrop_path", item.optString("poster_path"));
                if (path.startsWith("/")) loadImage(artwork, "https://image.tmdb.org/t/p/w342" + path);
                LinearLayout copy = vertical();
                copy.setPadding(dp(18), 0, dp(18), 0);
                TextView name = compactText(title, 19);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                copy.addView(name);
                copy.addView(
                    muted(
                        length > position
                            ? Math.max(1, (length - position) / 60000) + " min left"
                            : position / 60000 + " min watched",
                        14
                    )
                );
                if (length > 0) {
                    ProgressBar progress = new ProgressBar(
                        this,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                    );
                    progress.setMax(1000);
                    progress.setProgress((int) Math.min(1000, (position * 1000) / length));
                    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
                    copy.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
                }
                card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                Button resume = button("Resume", () -> play(title, url, key, false, item));
                card.addView(resume, new LinearLayout.LayoutParams(dp(108), dp(44)));
                Button more = button("Options", () -> {});
                card.addView(more, new LinearLayout.LayoutParams(dp(100), dp(44)));
                more.setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(
                            new String[] { "Start over", "Remove from Continue watching" },
                            (d, which) -> {
                                positions.edit().remove(key).apply();
                                if (which == 0) play(title, url, key, false, item);
                                else continueWatching();
                            }
                        )
                        .show()
                );
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(10);
                page.addView(card, lp);
            }
            if (count == 0) emptyState(
                page,
                "Pick up where you left off",
                "Movies and episodes you start will appear here.",
                "Browse movies",
                () -> library("Movies")
            );
        } catch (Exception e) {
            emptyState(
                page,
                "History unavailable",
                "Try opening this screen again.",
                "Retry",
                this::continueWatching
            );
        }
    }

    private void library(String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        browse(kind, "Trending today", "trending/" + type + "/day", 1);
    }

    private String catalogueRegion() {
        String fallback = Locale.getDefault().getCountry();
        return prefs.getString("catalogueRegion", fallback.matches("[A-Z]{2}") ? fallback : "GB");
    }

    private List<TmdbCategories.Entry> catalogueGenres(String type) {
        LinkedHashMap<Integer, TmdbCategories.Entry> rows = new LinkedHashMap<>();
        for (TmdbCategories.Entry entry : TmdbCategories.genres(type)) {
            String raw = entry.path.substring(entry.path.lastIndexOf('=') + 1);
            rows.put(Integer.parseInt(raw), entry);
        }
        try {
            JSONArray cached = new JSONArray(prefs.getString("catalogueGenres_" + type, "[]"));
            for (int i = 0; i < cached.length(); i++) {
                JSONObject genre = cached.getJSONObject(i);
                int id = genre.getInt("id");
                rows.put(id, TmdbCategories.genre(type, id, genre.getString("name")));
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(rows.values());
    }

    private void categoryChoices(LinearLayout target, String kind, List<TmdbCategories.Entry> entries) {
        LinearLayout line = null;
        for (int i = 0; i < entries.size(); i++) {
            TmdbCategories.Entry entry = entries.get(i);
            if (i % 2 == 0) {
                line = row();
                target.addView(line);
            }
            Button choice = button(entry.name, () -> browse(kind, entry.name, entry.path, 1));
            choice.setTextSize(14);
            choice.setTag(entry.path);
            choice.setContentDescription(entry.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            line.addView(choice, lp);
        }
        if (line != null) while (line.getChildCount() < 2)
            line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void renderCategoryHub(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        if (!section.equals(":filters")) {
            target.addView(compactText("Genres", 18));
            categoryChoices(target, kind, catalogueGenres(type));
        }
        if (section.equals(":categories")) {
            target.addView(text("Popular & new", 18));
            categoryChoices(target, kind, TmdbCategories.lists(type));
        }
        if (!section.equals(":genres")) {
            target.addView(text("Browse by", 18));
            categoryChoices(target, kind, TmdbCategories.groups(type));
        }
    }

    private void refreshCatalogueGenres(LinearLayout target, String kind, String section) {
        if (!secrets.has("tmdb")) return;
        int request = generation;
        String type = kind.equals("Movies") ? "movie" : "tv";
        io.execute(() -> {
            try {
                JSONArray result = media.tmdb("genre/" + type + "/list").getJSONArray("genres");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject item = result.getJSONObject(i);
                    TmdbCategories.genre(type, item.getInt("id"), item.getString("name"));
                }
                String data = result.toString();
                if (data.equals(prefs.getString("catalogueGenres_" + type, "[]"))) return;
                prefs
                    .edit()
                    .putString("catalogueGenres_" + type, data)
                    .apply();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != request) return;
                    View focused = getCurrentFocus();
                    Object route = focused == null ? null : focused.getTag();
                    boolean inside = false;
                    for (
                        android.view.ViewParent parent = focused == null ? null : focused.getParent();
                        parent != null;
                        parent = parent.getParent()
                    ) if (parent == target) {
                        inside = true;
                        break;
                    }
                    renderCategoryHub(target, kind, section);
                    if (inside && route != null) {
                        View replacement = target.findViewWithTag(route);
                        if (replacement != null) replacement.requestFocus();
                    }
                });
            } catch (Exception ignored) {
                /* Official built-in and previously fetched genres remain usable offline. */
            }
        });
    }

    private void browse(String kind, String selected, String path, int number) {
        if (catalogue == null || !catalogue.kind.equals(kind)) {
            catalogue = new CatalogueScreen(kind);
            if (path.startsWith(":")) catalogue.load("Trending today", "trending/" + catalogue.type + "/day");
            catalogue.refreshGenres();
        }
        if (path.startsWith(":")) {
            catalogue.showOverlay(selected, path);
            return;
        }
        catalogue.load(selected, path);
    }

    private final class CatalogueScreen {

        final String kind, type;
        final LinearLayout content, controls;
        final ImageView backdrop;
        final TextView collection, previewTitle, previewMeta, previewSummary;
        final ArrayDeque<TmdbCategories.Entry> overlayPages = new ArrayDeque<>();
        PosterFeed feed;
        boolean closed;
        Runnable pendingPreview;
        String selected = "Trending today",
            path;

        CatalogueScreen(String kind) {
            this.kind = kind;
            type = kind.equals("Movies") ? "movie" : "tv";
            fixedScreen(kind, MainActivity.this::home);
            LinearLayout header = (LinearLayout) page.getChildAt(0);
            controls = header;
            View backButton = header.getChildAt(1);
            header.removeView(backButton);
            header.addView(
                navigationButton("Search", () -> searchCatalogue(kind)),
                new LinearLayout.LayoutParams(dp(88), dp(36))
            );
            header.addView(
                navigationButton("Browse", () -> showOverlay("Browse categories", ":categories")),
                new LinearLayout.LayoutParams(dp(100), dp(36))
            );
            header.addView(
                navigationButton("Filters", () -> showOverlay("Filters", ":filters")),
                new LinearLayout.LayoutParams(dp(88), dp(36))
            );
            Button library = navigationButton("Library", () -> {});
            library.setOnClickListener(v ->
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Your library")
                    .setItems(new String[] { "My library", "Premiumize cloud", "Transfers" }, (d, which) -> {
                        if (which == 0) savedLibrary(kind);
                        else if (which == 1) cloud("", kind);
                        else transfers(kind);
                    })
                    .show()
            );
            header.addView(library, new LinearLayout.LayoutParams(dp(88), dp(36)));
            header.addView(backButton, new LinearLayout.LayoutParams(dp(72), dp(36)));
            ViewGroup parent = (ViewGroup) page.getParent();
            parent.removeView(page);
            FrameLayout root = new FrameLayout(MainActivity.this);
            root.setBackgroundColor(0xff090d14);
            backdrop = new ImageView(MainActivity.this);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setAlpha(.2f);
            root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(MainActivity.this);
            shade.setBackground(
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
                    0xaa090d14,
                    0xdd090d14,
                    0xff090d14,
                })
            );
            root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            page.setBackgroundColor(Color.TRANSPARENT);
            root.addView(page, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            LinearLayout preview = vertical();
            preview.setPadding(dp(8), dp(8), dp(8), dp(6));
            previewTitle = compactText("Discover your next watch", 24);
            previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            previewTitle.setSingleLine(true);
            previewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            previewMeta = muted("", 13);
            previewSummary = muted("Browse full posters. Hold Select for quick actions.", 14);
            previewSummary.setMaxLines(2);
            previewSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            preview.addView(previewTitle);
            preview.addView(previewMeta);
            preview.addView(previewSummary);
            page.addView(preview, new LinearLayout.LayoutParams(-1, dp(112)));
            collection = compactText("Trending today", 16);
            collection.setTextColor(0xffaab7cb);
            page.addView(collection, new LinearLayout.LayoutParams(-1, dp(28)));
            content = vertical();
            page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            header.getChildAt(1).requestFocus();
        }

        void close() {
            closed = true;
            if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        }

        void refreshGenres() {
            if (!secrets.has("tmdb")) return;
            io.execute(() -> {
                try {
                    JSONArray result = media.tmdb("genre/" + type + "/list").getJSONArray("genres");
                    String data = result.toString();
                    if (data.equals(prefs.getString("catalogueGenres_" + type, "[]"))) return;
                    prefs
                        .edit()
                        .putString("catalogueGenres_" + type, data)
                        .apply();
                } catch (Exception ignored) {}
            });
        }

        void load(String name, String route) {
            if (closed) return;
            if (feed != null && path != null) rememberCatalogue(type + ":" + path, feed.snapshot());
            if (catalogueDialog != null) catalogueDialog.dismiss();
            generation++;
            selected = name;
            path = route;
            destination = () -> browse(kind, name, route, 1);
            collection.setText(name);
            if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
            backdrop.setTag(null);
            backdrop.setImageDrawable(null);
            if (feed != null) {
                feed.close();
                feeds.remove(feed);
                feed = null;
            }
            content.removeAllViews();
            TextView loading = compactText("Loading titles…", 15);
            content.addView(loading);
            if (!secrets.has("tmdb")) {
                loading.setText("Connect TMDB to browse this catalogue");
                content.addView(button("Connect TMDB", () -> accountScreen("TMDB")));
            } else {
                feed = catalogueFeed(
                    content,
                    loading,
                    controls,
                    type,
                    n -> cataloguePage(type, route, n),
                    catalogueStates.get(type + ":" + route)
                );
                final PosterFeed current = feed;
                final View focusBefore = getCurrentFocus();
                current.onFirstItems = () -> {
                    if (
                        closed || feed != current || current.items.isEmpty() || !current.isAttachedToWindow()
                    ) return;
                    preview(current.items.get(0));
                    if (focusBefore == null || !focusBefore.isAttachedToWindow() || focusBefore == loading) {
                        View first = current.getLayoutManager().findViewByPosition(0);
                        if (first != null) first.requestFocus();
                    }
                };
            }
            // Keep focus on the posters when the catalogue is already being navigated.
        }

        void preview(JSONObject item) {
            previewTitle.setText(item.optString("title", item.optString("name", "Title")));
            previewMeta.setText(titleMetadata(item));
            previewSummary.setText(item.optString("overview", "No synopsis available."));
            if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
            String art = item.optString("backdrop_path");
            pendingPreview = () -> {
                if (closed) return;
                backdrop.setTag(null);
                backdrop.setImageDrawable(null);
                if (art.startsWith("/")) loadImage(backdrop, "https://image.tmdb.org/t/p/w1280" + art);
            };
            handler.postDelayed(pendingPreview, 350);
        }

        void showOverlay(String title, String route) {
            if (catalogueDialog == null) {
                overlayPages.clear();
                Dialog dialog = new Dialog(
                    MainActivity.this,
                    android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
                );
                catalogueDialog = dialog;
                dialog.setOnDismissListener(d -> {
                    if (catalogueDialog == dialog) {
                        catalogueDialog = null;
                        overlayContent = null;
                        generation++;
                    }
                });
                dialog.setOnKeyListener((d, key, event) -> {
                    if (key != KeyEvent.KEYCODE_BACK) return dpadRepeats.consume(event);
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        if (overlayPages.size() > 1) {
                            overlayPages.pop();
                            renderOverlay();
                        } else dialog.dismiss();
                    }
                    return true;
                });
            }
            overlayPages.push(new TmdbCategories.Entry(title, route));
            renderOverlay();
        }

        void renderOverlay() {
            generation++;
            TmdbCategories.Entry entry = overlayPages.peek();
            LinearLayout body = vertical();
            body.setPadding(dp(20), dp(14), dp(20), dp(18));
            LinearLayout head = row();
            TextView title = compactText(entry.name, 22);
            head.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
            head.addView(
                button("Close", () -> catalogueDialog.dismiss()),
                new LinearLayout.LayoutParams(dp(76), dp(36))
            );
            body.addView(head);
            ScrollView scroll = new ScrollView(MainActivity.this);
            LinearLayout choices = vertical();
            overlayContent = choices;
            scroll.addView(choices);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            catalogueDialog.setContentView(body);
            catalogueDialog.show();
            Window window = catalogueDialog.getWindow();
            window
                .getDecorView()
                .setSystemUiVisibility(MainActivity.this.getWindow().getDecorView().getSystemUiVisibility());
            window.setBackgroundDrawable(box(0xff101722));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.55f);
            window.setLayout(
                Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(48)),
                getResources().getDisplayMetrics().heightPixels - dp(48)
            );
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            attributes.x = dp(24);
            window.setAttributes(attributes);
            renderCatalogueOptions(choices, kind, entry.path);
        }
    }

    private JSONObject cataloguePage(String type, String path, int number) throws Exception {
        if (path.startsWith("collection/")) {
            JSONArray parts = media.tmdb(path).getJSONArray("parts");
            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < parts.length(); i++) sorted.add(parts.getJSONObject(i));
            sorted.sort(Comparator.comparing(item -> item.optString("release_date", "9999")));
            return new JSONObject().put("results", new JSONArray(sorted)).put("total_pages", 1);
        }
        if (path.startsWith("person/")) {
            JSONObject response = media.tmdb(path);
            JSONArray items = new JSONArray();
            Set<Integer> seen = new HashSet<>();
            for (String key : new String[] { "cast", "crew" }) {
                JSONArray credits = response.optJSONArray(key);
                if (credits != null) for (int i = 0; i < credits.length(); i++) {
                    JSONObject item = credits.getJSONObject(i);
                    if (seen.add(item.optInt("id"))) items.put(item);
                }
            }
            return new JSONObject().put("results", items).put("total_pages", 1);
        }
        return media.tmdb(path + (path.contains("?") ? "&" : "?") + "page=" + number);
    }

    private void renderCatalogueOptions(LinearLayout grid, String kind, String path) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        if (path.equals(":filters")) {
            grid.addView(button("Refine results", () -> refineCatalogue(kind)));
            categoryChoices(grid, kind, TmdbCategories.groups(type));
            return;
        }
        if (path.equals(":categories") || path.equals(":genres")) {
            renderCategoryHub(grid, kind, path);
            refreshCatalogueGenres(grid, kind, path);
            return;
        }
        if (path.equals(":region")) {
            grid.addView(text("Loading countries…", 16));
            async(
                () ->
                    new JSONArray(
                        Network.text(
                            MediaRepository.tmdbUrl("configuration/countries", secrets.get("tmdb")),
                            null,
                            false
                        )
                    ),
                countries -> {
                    grid.removeAllViews();
                    for (int i = 0; i < countries.length(); i++) {
                        JSONObject country = countries.getJSONObject(i);
                        String code = country.getString("iso_3166_1");
                        grid.addView(
                            button(country.optString("english_name", code), () -> {
                                prefs.edit().putString("catalogueRegion", code).apply();
                                if (catalogue != null) {
                                    catalogue.overlayPages.pop();
                                    catalogue.renderOverlay();
                                }
                            })
                        );
                    }
                }
            );
            return;
        }
        if (path.equals(":years")) {
            grid.addView(
                button("Choose a year", () -> {
                    LinearLayout content = vertical();
                    EditText input = field(
                        content,
                        "Year",
                        false,
                        String.valueOf(java.time.LocalDate.now().getYear())
                    );
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    Dialog dialog = form("Browse by year", content);
                    content.addView(
                        button("Browse", () -> {
                            try {
                                TmdbCategories.Entry entry = TmdbCategories.year(
                                    type,
                                    Integer.parseInt(input.getText().toString().trim())
                                );
                                dialog.dismiss();
                                browse(kind, entry.name, entry.path, 1);
                            } catch (Exception e) {
                                input.setError("Enter a year from 1874 to 9999");
                            }
                        })
                    );
                })
            );
        }
        if (
            Arrays.asList(
                ":years",
                ":ratings",
                ":runtime",
                ":sort",
                ":types",
                ":status",
                ":releases",
                ":availability"
            ).contains(path)
        ) {
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, path, catalogueRegion(), java.time.LocalDate.now())
            );
            return;
        }
        if (
            path.equals(":people") ||
            path.equals(":companies") ||
            path.equals(":keywords") ||
            path.equals(":collections")
        ) {
            entitySearch(grid, kind, path);
            return;
        }
        if (!secrets.has("tmdb")) {
            grid.addView(text("Connect TMDB to browse this catalogue", 20));
            grid.addView(button("Connect TMDB", () -> accountScreen("TMDB")));
            return;
        }
        TextView loading = text("Loading titles…", 15);
        grid.addView(loading);
        if (path.equals(":languages") || path.equals(":countries")) {
            String endpoint = path.equals(":languages")
                ? "configuration/languages"
                : "configuration/countries";
            async(
                () ->
                    new JSONArray(
                        Network.text(MediaRepository.tmdbUrl(endpoint, secrets.get("tmdb")), null, false)
                    ),
                items -> {
                    grid.removeAllViews();
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        boolean language = path.equals(":languages");
                        String code = item.optString(language ? "iso_639_1" : "iso_3166_1"),
                            name = item.optString("english_name", item.optString("name", code));
                        if (code.isEmpty()) continue;
                        choices.add(
                            new TmdbCategories.Entry(
                                name,
                                TmdbCategories.discover(
                                    type,
                                    (language ? "with_original_language=" : "with_origin_country=") +
                                        Network.q(code)
                                )
                            )
                        );
                    }
                    choices.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
        if (path.equals(":services")) {
            grid.removeAllViews();
            grid.addView(button("Region: " + catalogueRegion(), () -> chooseCatalogueRegion(kind)));
            grid.addView(compactText("Availability data: JustWatch", 12));
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, ":availability", catalogueRegion(), java.time.LocalDate.now())
            );
            grid.addView(loading);
            async(
                () ->
                    media
                        .tmdb("watch/providers/" + type + "?watch_region=" + catalogueRegion())
                        .getJSONArray("results"),
                items -> {
                    grid.removeView(loading);
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject provider = items.getJSONObject(i);
                        choices.add(
                            new TmdbCategories.Entry(
                                provider.getString("provider_name"),
                                TmdbCategories.discover(
                                    type,
                                    "watch_region=" +
                                        catalogueRegion() +
                                        "&with_watch_providers=" +
                                        provider.getInt("provider_id")
                                )
                            )
                        );
                    }
                    if (choices.isEmpty()) grid.addView(text("No services listed in this region", 16));
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
    }

    private void refineCatalogue(String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            prefix = "refine_" + type + "_";
        LinearLayout content = vertical();
        List<TmdbCategories.Entry> genres = catalogueGenres(type);
        int[] genre = { prefs.getInt(prefix + "genre", 0) },
            rating = { prefs.getInt(prefix + "rating", 0) },
            sort = { prefs.getInt(prefix + "sort", 0) };
        String[] orders = { "Most popular", "Highest rated", "Newest releases", "Title A–Z" },
            routes = { "popularity.desc", "vote_average.desc", "date.desc", "title.asc" };
        sort[0] = Math.max(0, Math.min(orders.length - 1, sort[0]));
        String genreName = "Any genre";
        for (TmdbCategories.Entry entry : genres)
            if (entry.path.endsWith("=" + genre[0])) genreName = entry.name;
        Button genreButton = button(genreName, () -> {});
        content.addView(muted("Genre", 14));
        content.addView(genreButton);
        genreButton.setOnClickListener(v -> {
            String[] choices = new String[genres.size() + 1];
            choices[0] = "Any genre";
            for (int i = 0; i < genres.size(); i++) choices[i + 1] = genres.get(i).name;
            new AlertDialog.Builder(this)
                .setTitle("Genre")
                .setItems(choices, (d, which) -> {
                    genre[0] =
                        which == 0
                            ? 0
                            : Integer.parseInt(
                                  genres
                                      .get(which - 1)
                                      .path.substring(genres.get(which - 1).path.lastIndexOf('=') + 1)
                              );
                    genreButton.setText(choices[which]);
                })
                .show();
        });
        EditText year = field(
            content,
            "Release year · leave blank for any",
            false,
            prefs.getInt(prefix + "year", 0) == 0 ? "" : String.valueOf(prefs.getInt(prefix + "year", 0))
        );
        year.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(muted("Minimum rating", 14));
        Button ratingButton = button(rating[0] == 0 ? "Any rating" : rating[0] + "+", () -> {});
        content.addView(ratingButton);
        ratingButton.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Minimum rating")
                .setItems(new String[] { "Any rating", "5+", "6+", "7+", "8+", "9+" }, (d, which) -> {
                    rating[0] = which == 0 ? 0 : which + 4;
                    ratingButton.setText(rating[0] == 0 ? "Any rating" : rating[0] + "+");
                })
                .show()
        );
        content.addView(muted("Sort order", 14));
        Button sortButton = button(orders[sort[0]], () -> {});
        content.addView(sortButton);
        sortButton.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Sort order")
                .setSingleChoiceItems(orders, sort[0], (d, which) -> {
                    sort[0] = which;
                    sortButton.setText(orders[which]);
                    d.dismiss();
                })
                .show()
        );
        Dialog dialog = form("Refine " + kind.toLowerCase(Locale.ROOT), content);
        LinearLayout actions = row();
        Button apply = chip(actions, "Apply filters", () -> {
            try {
                int value = year.getText().toString().trim().isEmpty()
                    ? 0
                    : Integer.parseInt(year.getText().toString().trim());
                String route = TmdbCategories.refine(type, genre[0], value, rating[0], routes[sort[0]]);
                prefs
                    .edit()
                    .putInt(prefix + "genre", genre[0])
                    .putInt(prefix + "year", value)
                    .putInt(prefix + "rating", rating[0])
                    .putInt(prefix + "sort", sort[0])
                    .apply();
                dialog.dismiss();
                browse(kind, "Filtered results", route, 1);
            } catch (Exception e) {
                year.setError("Enter a year from 1874 to 9999");
            }
        });
        primary(apply);
        chip(actions, "Reset", () -> {
            genre[0] = 0;
            rating[0] = 0;
            sort[0] = 0;
            genreButton.setText("Any genre");
            ratingButton.setText("Any rating");
            sortButton.setText(orders[0]);
            year.setText("");
        });
        content.addView(actions);
    }

    private void chooseCatalogueRegion(String kind) {
        browse(kind, "Region", ":region", 1);
    }

    private void entitySearch(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            entity = section.equals(":people")
                ? "person"
                : section.equals(":companies")
                  ? "company"
                  : section.equals(":keywords")
                    ? "keyword"
                    : "collection";
        EditText query = field(
            target,
            section.equals(":people")
                ? "Actor, director or creator"
                : section.equals(":companies")
                  ? "Studio name"
                  : section.equals(":keywords")
                    ? "Keyword or theme"
                    : "Collection name",
            false,
            ""
        );
        LinearLayout found = vertical();
        target.addView(
            button("Search", () -> {
                String value = query.getText().toString().trim();
                if (value.isEmpty()) {
                    query.setError("Enter a search term");
                    return;
                }
                if (!secrets.has("tmdb")) {
                    accountScreen("TMDB");
                    return;
                }
                entityResults(found, kind, entity, value, 1);
            })
        );
        target.addView(found);
    }

    private void entityResults(LinearLayout target, String kind, String entity, String query, int number) {
        generation++;
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        TextView loading = text("Loading matches…", 15);
        target.addView(loading);
        async(
            () ->
                media.tmdb(
                    "search/" +
                        entity +
                        "?query=" +
                        Network.q(query) +
                        "&page=" +
                        number +
                        (entity.equals("person") || entity.equals("collection") ? "&include_adult=false" : "")
                ),
            response -> {
                target.removeAllViews();
                JSONArray items = response.getJSONArray("results");
                List<TmdbCategories.Entry> choices = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item.optBoolean("adult", false)) continue;
                    int id = item.getInt("id");
                    String route = entity.equals("collection")
                        ? "collection/" + id
                        : entity.equals("person")
                          ? "person/" + id + "/" + type + "_credits"
                          : TmdbCategories.discover(
                                type,
                                (entity.equals("company") ? "with_companies=" : "with_keywords=") + id
                            );
                    choices.add(new TmdbCategories.Entry(item.getString("name"), route));
                }
                categoryChoices(target, kind, choices);
                if (choices.isEmpty()) target.addView(text("No matches found", 16));
                LinearLayout pages = row();
                if (number > 1) pages.addView(
                    button("Previous", () -> entityResults(target, kind, entity, query, number - 1))
                );
                if (number < Math.min(response.optInt("total_pages", 1), 500)) pages.addView(
                    button("Next", () -> entityResults(target, kind, entity, query, number + 1))
                );
                target.addView(pages);
            }
        );
    }

    private JSONArray recentSearches() {
        try {
            return new JSONArray(prefs.getString("searchHistory", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void rememberSearch(String value) {
        JSONArray next = new JSONArray();
        next.put(value);
        JSONArray old = recentSearches();
        for (int i = 0; i < old.length() && next.length() < 12; i++) if (
            !value.equalsIgnoreCase(old.optString(i))
        ) next.put(old.optString(i));
        prefs.edit().putString("searchHistory", next.toString()).apply();
    }

    private void searchCatalogue(String kind) {
        screen("Search", () -> library(kind));
        LinearLayout typeRow = horizontal(page);
        for (String scope : new String[] { "Movies", "Series" }) {
            Button tab = chip(typeRow, scope, () -> searchCatalogue(scope));
            if (scope.equals(kind)) primary(tab);
        }
        section(page, "What would you like to watch?");
        LinearLayout input = vertical();
        EditText query = field(input, "Search " + kind.toLowerCase(Locale.ROOT), false, "");
        page.addView(input);
        query.setHint("Titles, actors, directors…");
        LinearLayout actions = row();
        Runnable submit = () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) {
                query.setError("Enter a title");
                return;
            }
            rememberSearch(value);
            String type = kind.equals("Movies") ? "movie" : "tv";
            browse(
                kind,
                "Search: " + value,
                "search/" + type + "?query=" + Network.q(value) + "&include_adult=false",
                1
            );
        };
        Button search = chip(actions, "Search titles", submit);
        primary(search);
        chip(actions, "Search people", () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) {
                query.setError("Enter a name");
                return;
            }
            rememberSearch(value);
            browse(kind, "People", ":people", 1);
            if (overlayContent != null) entityResults(overlayContent, kind, "person", value, 1);
        });
        page.addView(actions);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                submit.run();
                return true;
            }
            return false;
        });
        JSONArray history = recentSearches();
        if (history.length() > 0) {
            section(page, "Recent searches");
            LinearLayout recent = horizontal(page);
            for (int i = 0; i < history.length(); i++) {
                String value = history.optString(i);
                chip(recent, value, () -> {
                    query.setText(value);
                    submit.run();
                });
            }
            page.addView(
                button("Clear search history", () -> {
                    prefs.edit().remove("searchHistory").apply();
                    searchCatalogue(kind);
                }),
                new LinearLayout.LayoutParams(dp(200), dp(40))
            );
        } else page.addView(muted("Your recent searches will appear here.", 15));
        query.requestFocus();
    }

    private void mediaRow(
        LinearLayout target,
        String title,
        String description,
        String action,
        Runnable run,
        Runnable more
    ) {
        LinearLayout card = row();
        card.setPadding(dp(18), dp(12), dp(12), dp(12));
        card.setBackground(box(0xff141e2c));
        LinearLayout copy = vertical();
        TextView name = compactText(title, 18);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        if (description != null && !description.isEmpty()) {
            TextView detail = muted(description, 13);
            detail.setMaxLines(1);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(detail);
        }
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = button(action, run);
        card.addView(open, new LinearLayout.LayoutParams(dp(100), dp(44)));
        if (more != null) {
            Button options = button("Options", more);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(100), dp(44));
            lp.leftMargin = dp(8);
            card.addView(options, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(88));
        lp.bottomMargin = dp(8);
        target.addView(card, lp);
    }

    private void savedLibrary(String kind) {
        screen("My " + kind, () -> library(kind));
        LinearLayout tools = horizontal(page);
        chip(tools, "Browse catalogue", () -> catalogueMenu(kind));
        chip(tools, "Add source", () -> addSource(kind, "", ""));
        chip(tools, "Cloud", () -> cloud("", kind));
        chip(tools, "Transfers", () -> transfers(kind));
        section(page, "Saved sources");
        try {
            JSONArray rows = media.library();
            int found = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                if (!kind.equals(item.getString("kind"))) continue;
                found++;
                String title = item.getString("title"),
                    url = item.optString("url"),
                    key = item.getString("id");
                mediaRow(
                    page,
                    title,
                    "premiumize".equals(item.optString("source")) ? "Premiumize cloud" : "Saved media",
                    "Play",
                    () -> {
                        if ("premiumize".equals(item.optString("source"))) {
                            async(
                                () ->
                                    media.premiumize(
                                        "item/details?id=" + Network.q(item.getString("sourceId")),
                                        secrets.get("premiumize")
                                    ),
                                detail -> play(title, detail.getString("link"), key, false)
                            );
                        } else play(title, url, key, false);
                    },
                    () ->
                        new AlertDialog.Builder(this)
                            .setTitle(title)
                            .setItems(new String[] { "Remove from library" }, (dialog, which) ->
                                new AlertDialog.Builder(this)
                                    .setTitle("Remove " + title + "?")
                                    .setPositiveButton("Remove", (d, w) -> {
                                        try {
                                            media.remove(key);
                                            savedLibrary(kind);
                                        } catch (Exception e) {
                                            toast("Could not remove this source");
                                        }
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            )
                            .show()
                );
            }
            if (found == 0) emptyState(
                page,
                "Build your library",
                "Add a media link or save a video from your cloud.",
                "Open cloud",
                () -> cloud("", kind)
            );
        } catch (Exception e) {
            emptyState(page, "Library unavailable", "Try opening this screen again.", "Retry", () ->
                savedLibrary(kind)
            );
        }
    }

    private EditText field(LinearLayout parent, String hint, boolean password, String value) {
        parent.addView(compactText(hint, 14));
        EditText edit = new EditText(this);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        edit.setId(View.generateViewId());
        edit.setHint("Select to type");
        edit.setTextColor(0xfff8f8fc);
        edit.setHintTextColor(0xff99a6bc);
        edit.setTextSize(18);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setSingleLine(true);
        edit.setInputType(
            password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT
        );
        edit.setTypeface(Typeface.DEFAULT);
        edit.setText(value);
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setShowSoftInputOnFocus(false);
        GradientDrawable idle = box(0xff080e1a);
        idle.setStroke(dp(1), 0xff526079);
        edit.setBackground(idle);
        edit.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = box(0xff080e1a);
            bg.setStroke(dp(focused ? 3 : 1), focused ? accent() : 0xff526079);
            v.setBackground(bg);
            if (!focused) (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).hideSoftInputFromWindow(v.getWindowToken(), 0);
        });
        Runnable keyboard = () -> {
            edit.setShowSoftInputOnFocus(true);
            edit.requestFocus();
            (
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)
            ).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        };
        edit.setOnClickListener(v -> keyboard.run());
        edit.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) keyboard.run();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View next = v.focusSearch(
                        key == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN
                    );
                    if (next != null) next.requestFocus();
                }
                return true;
            }
            return false;
        });
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
        return edit;
    }

    private Dialog form(String title, LinearLayout content) {
        Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout shell = vertical();
        shell.setPadding(dp(24), dp(20), dp(24), dp(24));
        shell.setBackgroundColor(0xff101827);
        shell.addView(text(title, 24));
        shell.addView(content);
        shell.addView(button("Close", d::dismiss));
        scroll.addView(shell);
        d.setContentView(scroll);
        d.show();
        d.getWindow().setLayout(
            Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(48)),
            -1
        );
        d.getWindow().setGravity(Gravity.RIGHT);
        d.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        );
        return d;
    }

    private void addSource(String kind, String name, String link) {
        LinearLayout content = vertical();
        EditText title = field(content, "Title", false, name),
            url = field(content, "Authorized media URL", false, link);
        CheckBox permission = new CheckBox(this);
        permission.setText("I have permission to play this source");
        permission.setTextColor(0xfff8f8fc);
        content.addView(permission);
        Dialog dialog = form("Add to " + kind, content);
        content.addView(
            button("Save source", () -> {
                if (!permission.isChecked()) {
                    toast("Confirm permission for this source");
                    return;
                }
                try {
                    media.add(title.getText().toString(), url.getText().toString(), kind, http());
                    dialog.dismiss();
                    library(kind);
                } catch (Exception e) {
                    toast("Enter a title and valid media URL");
                }
            })
        );
    }

    private void cloud(String folder, String kind) {
        cloud(folder, kind, "Cloud", new ArrayList<>());
    }

    private void cloud(String folder, String kind, String folderName, List<String[]> parents) {
        if (!secrets.has("premiumize")) {
            accountScreen("Premiumize");
            return;
        }
        Runnable goBack = () -> {
            if (parents.isEmpty()) library(kind);
            else {
                List<String[]> path = new ArrayList<>(parents);
                String[] parent = path.remove(path.size() - 1);
                cloud(parent[0], kind, parent[1], path);
            }
        };
        screen(folderName, goBack);
        LinearLayout tools = horizontal(page);
        chip(tools, "Transfers", () -> transfers(kind));
        chip(tools, "Saved library", () -> savedLibrary(kind));
        TextView loading = text("Loading files…", 16);
        page.addView(loading);
        async(
            () ->
                media
                    .premiumize(
                        "folder/list" + (folder.isEmpty() ? "" : "?id=" + Network.q(folder)),
                        secrets.get("premiumize")
                    )
                    .getJSONArray("content"),
            items -> {
                page.removeView(loading);
                if (items.length() == 0) {
                    emptyState(
                        page,
                        "This folder is empty",
                        "Finished transfers appear in your cloud.",
                        "View transfers",
                        () -> transfers(kind)
                    );
                    return;
                }
                List<JSONObject> sorted = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) sorted.add(items.getJSONObject(i));
                sorted.sort(
                    Comparator.<JSONObject>comparingInt(item ->
                        item.optString("type").equals("folder") ? 0 : 1
                    ).thenComparing(item -> item.optString("name").toLowerCase(Locale.ROOT))
                );
                for (JSONObject file : sorted) {
                    String name = file.optString("name", "File"),
                        key = file.optString("id"),
                        type = file.optString("type");
                    boolean directory = type.equals("folder");
                    mediaRow(
                        page,
                        name,
                        directory ? "Folder" : file.optString("mime_type", "Cloud file"),
                        directory ? "Open" : "Play",
                        () -> {
                            if (directory) {
                                List<String[]> path = new ArrayList<>(parents);
                                path.add(new String[] { folder, folderName });
                                cloud(key, kind, name, path);
                                return;
                            }
                            async(
                                () ->
                                    media.premiumize(
                                        "item/details?id=" + Network.q(key),
                                        secrets.get("premiumize")
                                    ),
                                detail -> {
                                    String url = detail.optString("link");
                                    if (url.isEmpty()) {
                                        toast("This file has no playback link");
                                        return;
                                    }
                                    play(name, url, "pm:" + key, false);
                                }
                            );
                        },
                        directory
                            ? null
                            : () ->
                                  new AlertDialog.Builder(this)
                                      .setTitle(name)
                                      .setItems(new String[] { "Add to " + kind }, (d, w) -> {
                                          try {
                                              media.addPremiumize(name, key, kind);
                                              toast("Added to " + kind);
                                          } catch (Exception e) {
                                              toast("Could not add this file");
                                          }
                                      })
                                      .show()
                    );
                }
            }
        );
    }

    private static final class FoundChannel extends Exception {}

    /** Reads only until the first playable channel, so connecting a very large playlist stays quick. */
    private boolean hasChannel(String url) throws Exception {
        try (java.io.InputStream in = Network.open(url, null, Network.CATALOGUE_MAX, http())) {
            Playlist.parse(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                url,
                http(),
                c -> {
                    throw new FoundChannel();
                }
            );
        } catch (FoundChannel found) {
            return true;
        }
        return false;
    }

    private void settingsStyle(View view) {
        if (view instanceof Button || view instanceof Switch) {
            view.setFocusable(true);
            view.setOnFocusChangeListener((v, on) -> {
                GradientDrawable bg = box(on ? 0xff263550 : 0xff101827);
                bg.setStroke(dp(on ? 2 : 0), accent());
                v.setBackground(bg);
            });
            view.setBackground(box(0xff101827));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settingsStyle(group.getChildAt(i));
        }
    }

    private void settings() {
        fixedScreen("Settings", this::home);
        LinearLayout layout = row();
        layout.setGravity(Gravity.TOP);
        page.addView(layout, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navigation = vertical();
        navigation.setPadding(0, dp(8), dp(22), 0);
        layout.addView(navigation, new LinearLayout.LayoutParams(dp(190), -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(body);
        layout.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        List<Button> choices = new ArrayList<>();
        for (String tab : new String[] { "Accounts", "Appearance", "Playback", "System" }) {
            Button choice = navigationButton(tab, () -> {});
            choices.add(choice);
            LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(44));
            navParams.bottomMargin = dp(4);
            navigation.addView(choice, navParams);
            choice.setOnClickListener(v -> {
                for (Button other : choices) {
                    other.setSelected(other == v);
                    styleFocus(other, other.hasFocus());
                }
                settingsTab(body, tab);
            });
        }
        choices.get(0).setSelected(true);
        styleFocus(choices.get(0), false);
        settingsTab(body, "Accounts");
        choices.get(0).requestFocus();
    }

    private String accountKey(String service) {
        return service.equals("Xtream")
            ? "xtream"
            : service.equals("M3U playlist")
              ? "m3u"
              : service.equals("TMDB")
                ? "tmdb"
                : "premiumize";
    }

    private void accountCard(LinearLayout body, String service, String description) {
        String key = accountKey(service);
        boolean connected = secrets.has(key);
        LinearLayout card = row();
        card.setPadding(dp(18), dp(9), dp(14), dp(9));
        card.setBackground(box(0xff171d27));
        LinearLayout copy = vertical();
        TextView name = compactText(service, 19);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);
        TextView detail = compactText(description, 13);
        detail.setTextColor(0xffaab4c4);
        copy.addView(detail);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = compactText(connected ? "Connected" : "Not connected", 13);
        status.setTextColor(connected ? 0xff83ddb4 : 0xffaab4c4);
        card.addView(status);
        Button edit = button(connected ? "Manage" : "Connect", () -> accountScreen(service));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(dp(96), dp(38));
        action.leftMargin = dp(18);
        card.addView(edit, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(78));
        lp.bottomMargin = dp(8);
        body.addView(card, lp);
    }

    /** A single-purpose form keeps credentials separate from settings and other providers. */
    private void accountScreen(String service) {
        String key = accountKey(service);
        boolean connected = secrets.has(key);
        screen(service, this::settings);
        LinearLayout layout = row();
        layout.setGravity(Gravity.TOP);
        page.addView(layout);
        LinearLayout explanation = vertical();
        explanation.setPadding(0, dp(10), dp(40), 0);
        layout.addView(explanation, new LinearLayout.LayoutParams(dp(290), -2));
        explanation.addView(text(connected ? "Manage connection" : "Connect " + service, 26));
        String description = service.equals("Xtream")
            ? "Live TV\nEnter the server address, username and password supplied by your TV provider."
            : service.equals("M3U playlist")
              ? "Live TV\nPaste the playlist link supplied by your TV provider. Channels refresh automatically."
              : service.equals("TMDB")
                ? "Movies, series & artwork\nUse the API Key (v3 auth) from your TMDB account’s API settings."
                : "Playback & cloud library\nUse the API key from your Premiumize account.";
        explanation.addView(text(description, 16));
        if (service.equals("TMDB")) explanation.addView(text("themoviedb.org/settings/api", 14));
        if (service.equals("Premiumize")) explanation.addView(text("premiumize.me/account", 14));
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(10), dp(20), dp(20));
        form.setBackground(box(0xff131b27));
        layout.addView(form, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = compactText(
            connected
                ? "Connected · saved credentials are kept until a replacement is verified"
                : "Not connected",
            14
        );
        status.setTextColor(0xffaab4c4);
        form.addView(status);
        List<EditText> inputs = new ArrayList<>();
        if (service.equals("Xtream")) {
            String server = "",
                user = "";
            try {
                if (connected) {
                    JSONObject saved = new JSONObject(secrets.get(key));
                    server = saved.optString("server");
                    user = saved.optString("username");
                }
            } catch (Exception ignored) {}
            inputs.add(field(form, "Server address", false, server));
            inputs.get(0).setHint("https://provider.example:port");
            inputs.add(field(form, "Username", false, user));
            inputs.add(field(form, "Password", true, ""));
        } else {
            String label = service.equals("M3U playlist")
                ? "Playlist URL"
                : service.equals("TMDB")
                  ? "API Key (v3 auth)"
                  : "API key";
            inputs.add(field(form, label, !service.equals("M3U playlist"), ""));
            inputs
                .get(0)
                .setHint(
                    service.equals("M3U playlist")
                        ? "https://provider.example/playlist.m3u"
                        : "Enter your key"
                );
        }
        if (service.equals("Xtream") || service.equals("M3U playlist")) toggle(
            form,
            "Allow HTTP provider connections",
            "http",
            false
        );
        if (!service.equals("M3U playlist")) {
            CheckBox reveal = new CheckBox(this);
            reveal.setText("Show " + (service.equals("Xtream") ? "password" : "key"));
            reveal.setTextColor(0xffaab4c4);
            EditText secret = inputs.get(inputs.size() - 1);
            reveal.setOnCheckedChangeListener((v, on) -> {
                int cursor = secret.getSelectionStart();
                secret.setTransformationMethod(
                    on ? null : android.text.method.PasswordTransformationMethod.getInstance()
                );
                if (cursor >= 0) secret.setSelection(Math.min(cursor, secret.length()));
            });
            form.addView(reveal);
        }
        Button save = button(connected ? "Verify & save" : "Connect", () -> {});
        form.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
        save.setOnClickListener(v -> {
            List<String> values = new ArrayList<>();
            for (EditText input : inputs) {
                String value = input.getText().toString();
                if (!(service.equals("Xtream") && inputs.indexOf(input) == 2)) value = value.trim();
                if (value.isEmpty()) {
                    input.setError("Required");
                    input.requestFocus();
                    return;
                }
                values.add(value);
            }
            if (service.equals("TMDB") && !values.get(0).matches("[0-9a-fA-F]{32}")) {
                inputs.get(0).setError("Use the 32-character API Key (v3 auth)");
                inputs.get(0).requestFocus();
                return;
            }
            save.setEnabled(false);
            save.setText("Connecting…");
            status.setText("Checking your connection…");
            int request = generation;
            boolean allowHttp = http();
            io.execute(() -> {
                String result;
                boolean success = false;
                try {
                    String credential = values.get(0);
                    if (service.equals("Xtream")) credential = MediaRepository.connectXtream(
                        values.get(0),
                        values.get(1),
                        values.get(2),
                        allowHttp
                    ).toString();
                    else if (service.equals("M3U playlist")) {
                        credential = Playlist.networkUrl(credential, allowHttp);
                        if (!hasChannel(credential)) throw new Exception();
                    } else if (service.equals("TMDB")) new JSONObject(
                        Network.text(MediaRepository.tmdbUrl("trending/all/week", credential), null, false)
                    ).getJSONArray("results");
                    else media.premiumize("account/info", credential);
                    secrets.put(key, credential);
                    success = true;
                    result = "Connected";
                } catch (Exception error) {
                    result = service.equals("Xtream")
                        ? "Could not connect. Check the server address, username, password and HTTP setting."
                        : service.equals("M3U playlist")
                          ? "Could not read this playlist. Check the URL, provider access and HTTP setting."
                          : "Could not verify this key. Check the key and your internet connection.";
                }
                final boolean ok = success;
                final String message = result;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != request) return;
                    status.setText(message);
                    status.setTextColor(ok ? 0xff83ddb4 : 0xffffb4aa);
                    save.setEnabled(true);
                    save.setText("Verify & save");
                    if (ok) {
                        if (
                            service.equals("Xtream") || service.equals("M3U playlist")
                        ) ChannelSync.refreshNow(this);
                        accountScreen(service);
                        toast(
                            "Connected" +
                                (service.equals("Xtream") || service.equals("M3U playlist")
                                    ? " · importing channels"
                                    : "")
                        );
                    }
                });
            });
        });
        if (connected) form.addView(
            button("Disconnect", () ->
                new AlertDialog.Builder(this)
                    .setTitle("Disconnect " + service + "?")
                    .setMessage("Remove this connection? Your other accounts and preferences will be kept.")
                    .setPositiveButton("Disconnect", (d, w) -> {
                        secrets.remove(key);
                        if (
                            service.equals("Xtream") || service.equals("M3U playlist")
                        ) ChannelSync.refreshNow(this);
                        settings();
                    })
                    .setNegativeButton("Cancel", null)
                    .show()
            )
        );
    }

    private void toggle(LinearLayout parent, String title, String key, boolean fallback) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setTextColor(0xfff8f8fc);
        s.setChecked(prefs.getBoolean(key, fallback));
        s.setPadding(dp(16), dp(12), dp(16), dp(12));
        s.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean(key, checked).apply());
        parent.addView(s);
    }

    private void languagePreference(
        LinearLayout parent,
        String title,
        String key,
        String fallback,
        boolean subtitles
    ) {
        String[] names = subtitles
            ? new String[] {
                  "Off",
                  "English",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              }
            : new String[] {
                  "English",
                  "Original / default",
                  "Spanish",
                  "French",
                  "German",
                  "Italian",
                  "Portuguese",
                  "Dutch",
                  "Polish",
                  "Japanese",
                  "Korean",
                  "Chinese",
                  "Hindi",
                  "Arabic",
              };
        String[] codes = subtitles
            ? new String[] {
                  "none",
                  "eng,en",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              }
            : new String[] {
                  "eng,en",
                  "",
                  "spa,es",
                  "fra,fre,fr",
                  "deu,ger,de",
                  "ita,it",
                  "por,pt",
                  "nld,dut,nl",
                  "pol,pl",
                  "jpn,ja",
                  "kor,ko",
                  "zho,chi,zh",
                  "hin,hi",
                  "ara,ar",
              };
        String value = prefs.getString(key, fallback);
        int chosen = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(value)) chosen = i;
        Button control = button(title + ": " + names[chosen], () -> {});
        control.setOnClickListener(v -> {
            String current = prefs.getString(key, fallback);
            int selected = 0;
            for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) selected = i;
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, selected, (d, which) -> {
                    prefs.edit().putString(key, codes[which]).apply();
                    control.setText(title + ": " + names[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        parent.addView(control);
    }

    private void settingsTab(LinearLayout body, String tab) {
        body.removeAllViews();
        if (tab.equals("Accounts")) {
            body.addView(compactText("Your connections", 23));
            accountCard(body, "Xtream", "Live channels from your TV provider");
            accountCard(body, "M3U playlist", "Live channels from a playlist link");
            accountCard(body, "TMDB", "Movie and series discovery, details and artwork");
            accountCard(body, "Premiumize", "Playback sources and your cloud library");
        } else if (tab.equals("Appearance")) {
            toggle(body, "Show featured row", "featured", true);
            toggle(body, "24-hour clock", "clock24", true);
            for (int colour : new int[] { 0xff8a9eff, 0xff55aaff, 0xff45c9ac })
                body.addView(
                    button(colour == 0xff8a9eff ? "Violet" : colour == 0xff55aaff ? "Blue" : "Teal", () -> {
                        prefs.edit().putInt("accent", colour).apply();
                        toast("Accent saved");
                    })
                );
            body.addView(button("Return home", this::home));
        } else if (tab.equals("Playback")) {
            toggle(body, "Resume playback", "resume", true);
            languagePreference(body, "Subtitles", "subtitleLanguage", "none", true);
            languagePreference(body, "Audio track", "audioLanguage", "eng,en", false);
        } else {
            toggle(body, "Check for updates automatically", "autoUpdates", true);
            body.addView(
                text(
                    android.os.Build.MANUFACTURER +
                        " " +
                        android.os.Build.MODEL +
                        " · Android " +
                        android.os.Build.VERSION.RELEASE,
                    16
                )
            );
            body.addView(
                button("Check for app updates", () -> {
                    lastUpdateCheck = 0;
                    checkForUpdate(true);
                })
            );
            body.addView(
                button("Open Android Settings", () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e) {
                        toast("Android Settings is unavailable");
                    }
                })
            );
            body.addView(
                text(
                    "TMDB supplies metadata and artwork. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    12
                )
            );
            body.addView(
                button("Metadata attribution", () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Metadata attribution")
                        .setMessage(
                            "The Movie Database (TMDB)\nhttps://www.themoviedb.org\n\nMetadata is separate from your playback sources."
                        )
                        .setPositiveButton("Close", null)
                        .show()
                )
            );
        }
        settingsStyle(body);
    }

    private void catalogueMenu(String kind) {
        library(kind);
    }

    private void catalogue(String type, String path, int number) {
        browse(type.equals("movie") ? "Movies" : "Series", "Browse", path, number);
    }

    private void seasons(int tmdbId, String title) {
        JSONObject series = playbackTitle;
        screen(title, () -> {
            if (series != null) tmdbTitle(series);
            else library("Series");
        });
        TextView loading = text("Loading seasons…", 16);
        page.addView(loading);
        async(
            () -> media.tmdb("tv/" + tmdbId + "?append_to_response=external_ids"),
            details -> {
                page.removeView(loading);
                details.put("media_type", "tv");
                playbackTitle = details;
                renderSeasons(details);
            }
        );
    }

    private void renderSeasons(JSONObject details) throws Exception {
        String title = details.optString("name", "Series");
        int id = details.optInt("id");
        String imdb =
            details.optJSONObject("external_ids") == null
                ? ""
                : details.getJSONObject("external_ids").optString("imdb_id");
        page.addView(muted(titleMetadata(details), 15));
        section(page, "Choose a season");
        JSONArray seasons = details.optJSONArray("seasons");
        if (seasons == null || seasons.length() == 0) {
            emptyState(
                page,
                "No seasons yet",
                "Season information is not available for this series.",
                null,
                null
            );
            return;
        }
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.getJSONObject(i);
            int number = season.optInt("season_number");
            LinearLayout card = row();
            card.setPadding(dp(12), dp(12), dp(18), dp(12));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(poster, new LinearLayout.LayoutParams(dp(64), dp(96)));
            String path = season.optString("poster_path", details.optString("poster_path"));
            if (path.startsWith("/")) loadImage(poster, "https://image.tmdb.org/t/p/w185" + path);
            LinearLayout copy = vertical();
            copy.setPadding(dp(20), 0, 0, 0);
            copy.addView(text(season.optString("name", "Season " + number), 21));
            String date = season.optString("air_date", "");
            copy.addView(
                muted(
                    season.optInt("episode_count") +
                        " episodes" +
                        (date.length() >= 4 ? " · " + date.substring(0, 4) : ""),
                    14
                )
            );
            TextView synopsis = muted(season.optString("overview", ""), 14);
            synopsis.setMaxLines(2);
            synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(synopsis);
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            focus(card);
            styleFocus(card, false);
            card.setOnClickListener(v -> {
                playbackTitle = details;
                episodes(id, imdb, number, title);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(124));
            lp.bottomMargin = dp(10);
            page.addView(card, lp);
        }
    }

    private String episodeKey(int id, int season, int episode) {
        return id + ":" + season + ":" + episode;
    }

    private void episodes(int tmdbId, String imdb, int season, String title) {
        JSONObject series = playbackTitle;
        screen(title + " · Season " + season, () -> {
            playbackTitle = series;
            seasons(tmdbId, title);
        });
        TextView loading = text("Loading episodes…", 18);
        page.addView(loading);
        async(
            () -> media.tmdb("tv/" + tmdbId + "/season/" + season).getJSONArray("episodes"),
            items -> {
                page.removeView(loading);
                renderEpisodes(items, series, tmdbId, imdb, season, title);
            }
        );
    }

    private void renderEpisodes(
        JSONArray episodes,
        JSONObject series,
        int tmdbId,
        String imdb,
        int season,
        String title
    ) throws Exception {
        if (episodes.length() == 0) {
            emptyState(
                page,
                "No episodes yet",
                "Episode information is not available for this season.",
                null,
                null
            );
            return;
        }
        page.addView(muted(episodes.length() + " episodes · Hold Select to mark watched", 14));
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.getJSONObject(i);
            int number = episode.optInt("episode_number");
            String key = episodeKey(tmdbId, season, number),
                name = episode.optString("name", "Episode " + number);
            Set<String> watched = prefs.getStringSet("watchedEpisodes", Collections.emptySet());
            LinearLayout card = row();
            card.setPadding(dp(12), dp(12), dp(16), dp(12));
            ImageView still = new ImageView(this);
            still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(still, new LinearLayout.LayoutParams(dp(170), dp(96)));
            String path = episode.optString("still_path");
            if (path.startsWith("/")) loadImage(still, "https://image.tmdb.org/t/p/w342" + path);
            LinearLayout copy = vertical();
            copy.setPadding(dp(18), 0, 0, 0);
            TextView label = compactText((watched.contains(key) ? "✓  " : "") + number + " · " + name, 19);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(label);
            String date = episode.optString("air_date", "");
            int runtime = episode.optInt("runtime");
            copy.addView(
                muted((runtime > 0 ? runtime + " min" : "") + (date.isEmpty() ? "" : "   ·   " + date), 13)
            );
            TextView synopsis = muted(episode.optString("overview", "No synopsis available."), 14);
            synopsis.setMaxLines(2);
            synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(synopsis);
            card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            focus(card);
            styleFocus(card, false);
            card.setOnClickListener(v -> {
                try {
                    JSONObject metadata =
                        series == null
                            ? new JSONObject().put("id", tmdbId).put("media_type", "tv")
                            : new JSONObject(series.toString());
                    metadata.put("season", season).put("episode", number).put("episodeKey", key);
                    playbackTitle = metadata;
                } catch (JSONException ignored) {}
                torrentSources(
                    imdb,
                    season,
                    number,
                    title + " · S" + season + " E" + number + " · " + name,
                    "Series"
                );
            });
            card.setOnLongClickListener(v -> {
                Set<String> values = new HashSet<>(
                    prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                );
                if (!values.add(key)) values.remove(key);
                prefs.edit().putStringSet("watchedEpisodes", values).apply();
                label.setText((values.contains(key) ? "✓  " : "") + number + " · " + name);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(124));
            lp.bottomMargin = dp(10);
            page.addView(card, lp);
        }
    }

    private String sourceLabel(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String quality =
            lower.contains("2160p") || lower.contains("4k")
                ? "4K"
                : lower.contains("1080p")
                  ? "1080p"
                  : lower.contains("720p")
                    ? "720p"
                    : "Other";
        String hdr =
            lower.contains("dolby vision") ||
            java.util.regex.Pattern.compile("\\b(?:dv|dovi)\\b").matcher(lower).find()
                ? " · Dolby Vision"
                : lower.contains("hdr")
                  ? " · HDR"
                  : "";
        String codec =
            lower.contains("h265") || lower.contains("hevc") || lower.contains("x265")
                ? " · HEVC"
                : lower.contains("h264") || lower.contains("x264")
                  ? " · H.264"
                  : "";
        int split = raw.indexOf(10);
        String provider = split >= 0 ? raw.substring(0, split) : "Source";
        return quality + hdr + codec + "  ·  " + provider;
    }

    private void torrentSources(String imdb, int season, int episode, String title, String kind) {
        final JSONObject metadata = playbackTitle;
        screen("Choose a source", () -> {
            if (metadata != null) tmdbTitle(metadata);
            else catalogueMenu(kind);
        });
        page.addView(text(title, 24));
        if (!secrets.has("premiumize")) {
            emptyState(
                page,
                "Connect Premiumize",
                "Check available sources and play from your cloud account.",
                "Connect Premiumize",
                () -> accountScreen("Premiumize")
            );
            return;
        }
        LinearLayout tools = horizontal(page);
        chip(tools, "Premiumize cloud", () -> cloud("", kind));
        chip(tools, "Transfers", () -> transfers(kind));
        TextView loading = text("Checking sources and Premiumize cache…", 16);
        page.addView(loading);
        SourceRepository repo = new SourceRepository(secrets);
        async(
            () -> repo.torrentio(imdb, season, episode),
            sources -> {
                page.removeView(loading);
                if (sources.isEmpty()) {
                    emptyState(
                        page,
                        "No sources found",
                        "Try again later or choose a file from your cloud.",
                        "Open cloud",
                        () -> cloud("", kind)
                    );
                    return;
                }
                LinearLayout filters = horizontal(page),
                    results = vertical();
                page.addView(results);
                List<Button> tabs = new ArrayList<>();
                for (String quality : new String[] { "All", "4K", "1080p", "720p" }) {
                    Button tab = chip(filters, quality, () -> {});
                    tabs.add(tab);
                    tab.setOnClickListener(v -> {
                        for (Button other : tabs) {
                            other.setSelected(other == v);
                            styleFocus(other, other.hasFocus());
                        }
                        renderSources(
                            results,
                            sources,
                            quality,
                            repo,
                            metadata,
                            imdb,
                            season,
                            episode,
                            title,
                            kind
                        );
                    });
                }
                tabs.get(0).setSelected(true);
                styleFocus(tabs.get(0), false);
                renderSources(results, sources, "All", repo, metadata, imdb, season, episode, title, kind);
            }
        );
    }

    private void renderSources(
        LinearLayout target,
        List<SourceRepository.Source> sources,
        String quality,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind
    ) {
        target.removeAllViews();
        int shown = 0;
        for (SourceRepository.Cache status : SourceRepository.Cache.values()) {
            List<SourceRepository.Source> group = new ArrayList<>();
            for (SourceRepository.Source source : sources)
                if (
                    source.cache == status &&
                    (quality.equals("All") || sourceLabel(source.title).startsWith(quality))
                ) group.add(source);
            if (group.isEmpty()) continue;
            shown += group.size();
            section(
                target,
                (status == SourceRepository.Cache.CACHED
                    ? "Ready to play"
                    : status == SourceRepository.Cache.NOT_CACHED
                      ? "Available to transfer"
                      : "Cache check unavailable") +
                    " · " +
                    group.size()
            );
            for (SourceRepository.Source source : group) {
                LinearLayout card = vertical();
                card.setPadding(dp(18), dp(12), dp(18), dp(12));
                TextView label = compactText(sourceLabel(source.title), 17);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(label);
                TextView filename = muted(source.title.replace('\n', ' '), 13);
                filename.setMaxLines(2);
                filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(filename);
                focus(card);
                styleFocus(card, false);
                card.setOnClickListener(v -> {
                    if (source.cache == SourceRepository.Cache.UNKNOWN) {
                        playbackTitle = metadata;
                        torrentSources(imdb, season, episode, title, kind);
                        return;
                    }
                    if (source.cache == SourceRepository.Cache.NOT_CACHED) {
                        new AlertDialog.Builder(this)
                            .setTitle("Send to Premiumize?")
                            .setMessage("Queue this source in your cloud?")
                            .setPositiveButton("Queue", (d, w) ->
                                async(
                                    () -> repo.queue(source),
                                    id -> {
                                        toast("Transfer queued");
                                        transfers(kind);
                                    }
                                )
                            )
                            .setNegativeButton("Cancel", null)
                            .show();
                        return;
                    }
                    card.setEnabled(false);
                    label.setText("Opening source…");
                    async(
                        () -> {
                            try {
                                return repo.direct(source);
                            } finally {
                                runOnUiThread(() -> {
                                    card.setEnabled(true);
                                    label.setText(sourceLabel(source.title));
                                });
                            }
                        },
                        files -> {
                            screen("Choose a file", () -> {
                                playbackTitle = metadata;
                                torrentSources(imdb, season, episode, title, kind);
                            });
                            page.addView(muted(title, 18));
                            if (files.length() == 0) emptyState(
                                page,
                                "No playable files",
                                "Try a different source.",
                                "Back to sources",
                                () -> {
                                    playbackTitle = metadata;
                                    torrentSources(imdb, season, episode, title, kind);
                                }
                            );
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.getJSONObject(i);
                                String link = file.getString("link"),
                                    name = file.optString("path", "File");
                                int match = VideoFiles.score(name, "", season, episode);
                                Button fileButton = button(
                                    (match >= 2 ? "Suggested episode · " : "") + name,
                                    () -> play(title, link, id(source.uri + ":" + name), false, metadata)
                                );
                                fileButton.setSingleLine(false);
                                fileButton.setMaxLines(2);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(68));
                                lp.topMargin = dp(8);
                                page.addView(fileButton, lp);
                            }
                        }
                    );
                });
                card.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Source details")
                        .setMessage(source.title)
                        .setPositiveButton("Close", null)
                        .show();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(88));
                lp.bottomMargin = dp(8);
                target.addView(card, lp);
            }
        }
        if (shown == 0) emptyState(
            target,
            "No " + quality + " sources",
            "Choose another quality to see available sources.",
            null,
            null
        );
    }

    private void transfers(String kind) {
        screen("Transfers", () -> library(kind));
        LinearLayout tools = horizontal(page);
        chip(tools, "Refresh", () -> transfers(kind));
        chip(tools, "Cloud", () -> cloud("", kind));
        TextView loading = text("Loading transfers…", 16);
        page.addView(loading);
        async(
            () -> media.premiumize("transfer/list", secrets.get("premiumize")).getJSONArray("transfers"),
            items -> {
                page.removeView(loading);
                if (items.length() == 0) {
                    emptyState(
                        page,
                        "No transfers",
                        "Your queued files will appear here.",
                        "Browse catalogue",
                        () -> library(kind)
                    );
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject transfer = items.getJSONObject(i);
                    String status = transfer.optString("status", "unknown"),
                        folder = transfer.optString("folder_id", ""),
                        file = transfer.optString("file_id", "");
                    boolean ready = status.equals("finished") || status.equals("seeding");
                    mediaRow(
                        page,
                        transfer.optString("name", "Transfer"),
                        status +
                            " · " +
                            Math.round(Math.max(0, Math.min(1, transfer.optDouble("progress", 0))) * 100) +
                            "%",
                        ready ? "Open" : "Status",
                        () -> {
                            if (!ready) {
                                toast(transfer.optString("message", "Transfer is " + status));
                                return;
                            }
                            if (!file.isEmpty() && !file.equals("null")) {
                                async(
                                    () ->
                                        media.premiumize(
                                            "item/details?id=" + Network.q(file),
                                            secrets.get("premiumize")
                                        ),
                                    detail ->
                                        play(
                                            detail.optString("name", "File"),
                                            detail.getString("link"),
                                            "pm:" + file,
                                            false
                                        )
                                );
                            } else if (!folder.isEmpty() && !folder.equals("null")) cloud(folder, kind);
                        },
                        null
                    );
                }
            }
        );
    }

    private void advancedCatalogue(String type) {
        browse(type.equals("movie") ? "Movies" : "Series", "More filters", ":filters", 1);
    }
}
