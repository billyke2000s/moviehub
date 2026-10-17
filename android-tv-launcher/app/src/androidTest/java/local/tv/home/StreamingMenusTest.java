package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamingMenusTest {

    private final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private SharedPreferences prefs;
    private String watchlist, history;
    private Set<String> watched;
    private boolean updates;

    private void call(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            Method m = owner.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(owner, args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = find(((ViewGroup) view).getChildAt(i), label);
            if (match != null) return match;
        }
        return null;
    }

    private View root() {
        return activity.getWindow().getDecorView();
    }

    private void ui(Runnable work) {
        remote.runOnMainSync(work);
        remote.waitForIdleSync();
    }

    @Before
    public void start() {
        Context context = remote.getTargetContext();
        prefs = context.getSharedPreferences("ui", 0);
        updates = prefs.getBoolean("autoUpdates", true);
        watchlist = prefs.getString("watchlist", "[]");
        history = prefs.getString("searchHistory", "[]");
        watched = new HashSet<>(prefs.getStringSet("watchedEpisodes", Collections.emptySet()));
        prefs
            .edit()
            .putBoolean("autoUpdates", false)
            .remove("searchHistory")
            .remove("watchedEpisodes")
            .commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        if (activity != null) ui(activity::finish);
        prefs
            .edit()
            .putBoolean("autoUpdates", updates)
            .putString("watchlist", watchlist)
            .putString("searchHistory", history)
            .putStringSet("watchedEpisodes", watched)
            .commit();
    }

    @Test
    public void searchHistoryIsDeduplicatedAndSearchIsRemoteAccessible() throws Exception {
        ui(() -> {
            for (String value : new String[] { "Alien", "Arrival", "alien" })
                call(activity.catalogueSearch, "rememberSearch", new Class[] { String.class }, value);
            call(activity.catalogueSearch, "searchCatalogue", new Class[] { String.class }, "Movies");
            assertNotNull(find(root(), "Search titles"));
            assertNotNull(find(root(), "Search people"));
            assertNotNull(find(root(), "Recent searches"));
            assertNotNull(activity.getCurrentFocus());
        });
        JSONArray saved = new JSONArray(prefs.getString("searchHistory", "[]"));
        assertEquals(2, saved.length());
        assertEquals("alien", saved.getString(0));
        UiEvidence.capture("search-redesign");
    }

    @Test
    public void myListFiltersAndSortsWithoutDeletingOtherTitles() throws Exception {
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("id", 1).put("media_type", "movie").put("title", "Zebra"))
            .put(new JSONObject().put("id", 2).put("media_type", "tv").put("name", "Series fixture"))
            .put(new JSONObject().put("id", 3).put("media_type", "movie").put("title", "Alpha"));
        prefs.edit().putString("watchlist", items.toString()).commit();
        ui(() -> {
            call(
                activity.libraryScreens,
                "watchlist",
                new Class[] { String.class, String.class },
                "Movies",
                "Title A–Z"
            );
            assertNotNull(find(root(), "Alpha"));
            assertNotNull(find(root(), "Zebra"));
            assertNull(find(root(), "Series fixture"));
        });
        assertEquals(3, new JSONArray(prefs.getString("watchlist", "[]")).length());
        UiEvidence.capture("my-list-redesign");
    }

    @Test
    public void seasonAndEpisodeCardsKeepArtworkSpaceAndWatchedState() throws Exception {
        JSONObject series = new JSONObject()
            .put("id", 100)
            .put("name", "Fixture series")
            .put("media_type", "tv")
            .put(
                "seasons",
                new JSONArray().put(
                    new JSONObject()
                        .put("name", "Season 1")
                        .put("season_number", 1)
                        .put("episode_count", 8)
                        .put("overview", "A season overview.")
                )
            );
        ui(() -> {
            call(
                activity,
                "screen",
                new Class[] { String.class, Runnable.class },
                "Fixture series",
                (Runnable) () -> {}
            );
            call(activity.seriesScreens, "renderSeasons", new Class[] { JSONObject.class }, series);
            assertNotNull(find(root(), "Season 1"));
            assertNotNull(find(root(), "Choose a season"));
        });
        UiEvidence.capture("seasons-redesign");
        JSONArray episodes = new JSONArray().put(
            new JSONObject()
                .put("episode_number", 1)
                .put("name", "Pilot")
                .put("runtime", 42)
                .put("air_date", "2026-01-01")
                .put("overview", "A new journey begins.")
        );
        ui(() -> {
            call(
                activity,
                "screen",
                new Class[] { String.class, Runnable.class },
                "Fixture series · Season 1",
                (Runnable) () -> {}
            );
            call(
                activity.seriesScreens,
                "renderEpisodes",
                new Class[] {
                    JSONArray.class,
                    JSONObject.class,
                    int.class,
                    String.class,
                    int.class,
                    String.class,
                },
                episodes,
                series,
                100,
                "tt12345",
                1,
                "Fixture series"
            );
            View title = find(root(), "1 · Pilot");
            assertNotNull(title);
            View card = (View) title.getParent().getParent();
            assertTrue(card.performLongClick());
            assertNotNull(find(root(), "✓  1 · Pilot"));
        });
        assertTrue(prefs.getStringSet("watchedEpisodes", Collections.emptySet()).contains("100:1:1"));
        UiEvidence.capture("episodes-redesign");
    }

    @Test
    public void sourceQualityFiltersPreserveTheOtherSources() {
        SourceRepository.Source uhd = new SourceRepository.Source(
            "Provider\n2160p HDR HEVC fixture",
            "magnet:?xt=urn:btih:fixture"
        );
        uhd.cache = SourceRepository.Cache.CACHED;
        SourceRepository.Source hd = new SourceRepository.Source(
            "Provider\n1080p fixture",
            "magnet:?xt=urn:btih:fixture2"
        );
        hd.cache = SourceRepository.Cache.NOT_CACHED;
        List<SourceRepository.Source> sources = Arrays.asList(uhd, hd);
        ui(() -> {
            call(
                activity,
                "screen",
                new Class[] { String.class, Runnable.class },
                "Choose a source",
                (Runnable) () -> {}
            );
            LinearLayout target = new LinearLayout(activity);
            target.setOrientation(LinearLayout.VERTICAL);
            try {
                Field field = MainActivity.class.getDeclaredField("page");
                field.setAccessible(true);
                ((LinearLayout) field.get(activity)).addView(target);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            call(
                activity.sources,
                "renderSources",
                new Class[] {
                    LinearLayout.class,
                    List.class,
                    String.class,
                    SourceRepository.class,
                    JSONObject.class,
                    String.class,
                    int.class,
                    int.class,
                    String.class,
                    String.class,
                },
                target,
                sources,
                "4K",
                new SourceRepository(new Secrets(activity)),
                null,
                "tt12345",
                -1,
                -1,
                "Fixture film",
                "Movies"
            );
            assertNotNull(find(root(), "Ready to play · 1"));
            assertNull(find(root(), "Available to transfer · 1"));
        });
        assertEquals(2, sources.size());
        UiEvidence.capture("sources-redesign");
    }
}
