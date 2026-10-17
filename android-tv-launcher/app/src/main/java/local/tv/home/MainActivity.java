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

/**
 * The single activity of the launcher. It owns shared navigation state and lifecycle and
 * delegates each screen to a feature class (see ARCHITECTURE.md for the map).
 */
public class MainActivity extends TvActivity {

    // Feature controllers. Each one builds the views for one area of the app.
    final UpdatePrompt updates = new UpdatePrompt(this);
    final HomeScreen homeScreen = new HomeScreen(this);
    final AppDock dock = new AppDock(this);
    final PosterCards posters = new PosterCards(this);
    final TitleDetails titleDetails = new TitleDetails(this);
    final LibraryScreens libraryScreens = new LibraryScreens(this);
    final CatalogueScreens catalogues = new CatalogueScreens(this);
    final CatalogueSearch catalogueSearch = new CatalogueSearch(this);
    final LiveTvScreen liveTv = new LiveTvScreen(this);
    final SettingsScreens settingsScreens = new SettingsScreens(this);
    final CloudScreens cloudScreens = new CloudScreens(this);
    final SeriesScreens seriesScreens = new SeriesScreens(this);
    final SourcesScreen sources = new SourcesScreen(this);

    final ExecutorService io = Executors.newFixedThreadPool(3);
    final Handler handler = new Handler(Looper.getMainLooper());
    final DpadRepeats dpadRepeats = new DpadRepeats();
    final LinkedHashMap<String, PosterFeed.State> catalogueStates = new LinkedHashMap<>();
    JSONObject playbackTitle;
    Runnable resumeRefresh, myListRefresh;
    Dialog catalogueDialog;
    LinearLayout overlayContent;

    void rememberCatalogue(String key, PosterFeed.State state) {
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
        if (catalogues.catalogue != null) {
            if (catalogues.catalogue.feed != null && catalogues.catalogue.path != null) {
                rememberCatalogue(
                    catalogues.catalogue.type + ":" + catalogues.catalogue.path,
                    catalogues.catalogue.feed.snapshot()
                );
            }
            catalogues.catalogue.close();
            catalogues.catalogue = null;
        }
        if (catalogueDialog != null) catalogueDialog.dismiss();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (
            dock.eatSelectUp &&
            (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) &&
            event.getAction() == KeyEvent.ACTION_UP
        ) {
            dock.eatSelectUp = false;
            return true;
        }
        if (
            dock.dockRemoving &&
            (key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER)
        ) return true;
        if (dock.movingApp != null) {
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
                    if (
                        key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    ) dock.moveDockApp(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    else if (event.getRepeatCount() == 0) {
                        if (key == KeyEvent.KEYCODE_DPAD_UP) dock.removeDockApp();
                        else {
                            if (
                                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                            ) dock.eatSelectUp = true;
                            dock.finishDockMove();
                        }
                    }
                }
                return true;
            }
        }
        return dpadRepeats.consume(event) || super.dispatchKeyEvent(event);
    }

    final List<PosterFeed> feeds = new ArrayList<>();

    private void closeFeeds() {
        for (PosterFeed feed : feeds) feed.close();
        feeds.clear();
    }

    final Artwork artworkLoader = new Artwork();
    Secrets secrets;
    MediaRepository media;
    LinearLayout page;
    private Runnable back;
    Runnable destination;
    HomeScreen.HomeShelf homeShelf;
    int generation;
    TextView clock;
    Button updateButton;
    final Runnable ticker = new Runnable() {
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

    interface Job<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void accept(T result) throws Exception;
    }

    <T> void async(Job<T> job, Done<T> done) {
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
        target.addView(button("Settings", settingsScreens::settings));
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

    boolean http() {
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
        } catch (Exception e) {
            Diagnostics.recovered("Scheduling channel refresh", e);
        }
        homeScreen.home();
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
        updates.checkForUpdate();
        try {
            ChannelSync.refreshIfStale(this);
        } catch (Exception e) {
            Diagnostics.recovered("Starting stale channel refresh", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dock.refreshAppsOnResume) {
            dock.refreshAppsOnResume = false;
            dock.allApps();
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

    void screen(String title, Runnable goBack) {
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

    /** Home is a viewport, never a vertically scrolling page. The dock has reserved space. */
    void fixedScreen(String title, Runnable goBack) {
        resumeRefresh = null;
        myListRefresh = null;
        dock.finishDockMove();
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

    String id(String url) {
        return ChannelQuery.key(url);
    }

    void play(String title, String url, String id, boolean live) {
        play(title, url, id, live, null);
    }

    void play(String title, String url, String id, boolean live, JSONObject metadata) {
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
        } catch (Exception e) {
            Diagnostics.recovered("Saving recent playback", e);
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
}
