package local.tv.home;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TvNavigationTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private boolean autoUpdates;

    private View text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = text(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View root() {
        try {
            Field field = MainActivity.class.getDeclaredField("catalogueDialog");
            field.setAccessible(true);
            Dialog dialog = (Dialog) field.get(activity);
            if (dialog != null && dialog.isShowing()) return dialog.getWindow().getDecorView();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return activity.getWindow().getDecorView();
    }

    private void select(String label) {
        instrumentation.runOnMainSync(() -> {
            View view = text(root(), label);
            if (view == null) UiEvidence.capture("navigation-missing-" + label);
            assertNotNull(label, view);
            while (!view.hasOnClickListeners() && view.getParent() instanceof View)
                view = (View) view.getParent();
            assertTrue(label, view.performClick());
        });
        instrumentation.waitForIdleSync();
    }

    private void screenshot(String name) throws Exception {
        SystemClock.sleep(500);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(
            instrumentation.getTargetContext().getExternalFilesDir(null),
            "screenshots"
        );
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (OutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        shell("mkdir -p /sdcard/Download/moviehub-test-screenshots");
        shell(
            "cp " +
                new File(directory, name + ".png").getAbsolutePath() +
                " /sdcard/Download/moviehub-test-screenshots/" +
                name +
                ".png"
        );
    }

    private void shell(String command) throws Exception {
        try (
            android.os.ParcelFileDescriptor descriptor = instrumentation
                .getUiAutomation()
                .executeShellCommand(command);
            InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            assertEquals("Screenshot export failed", "", output.toString("UTF-8").trim());
        }
    }

    @Test
    public void tvScreensNavigateAndKeepAVisibleFocusTarget() throws Exception {
        Context context = instrumentation.getTargetContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        ChannelDatabase.Writer writer = db.writer("navigation-fixture", () -> {}, n -> {});
        try {
            for (int i = 1; i <= 12; i++) writer.accept(
                new Playlist.Channel(
                    "Fixture channel " + i,
                    "https://example.test/live/" + i + ".ts",
                    i % 2 == 0 ? "Sports" : "News",
                    "",
                    ""
                )
            );
            writer.commit();
        } finally {
            writer.abort();
        }
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        screenshot("home");
        instrumentation.runOnMainSync(() -> {
            assertNotNull(activity.getCurrentFocus());
            assertFullyVisible("All Apps");
            assertFullyVisible("Settings");
            assertFullyVisible("For you");
            assertFullyVisible("My list");
            assertFullyVisible("Continue");
            for (String category : new String[] { "For you", "Trending", "Top rated", "New movies", "On TV" })
                assertFullyVisible(category);
        });
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("homeShelf");
                field.setAccessible(true);
                Object shelf = field.get(activity);
                Method show = shelf.getClass().getDeclaredMethod("show", JSONArray.class, String.class);
                show.setAccessible(true);
                JSONArray titles = new JSONArray();
                for (int i = 0; i < 12; i++) titles.put(
                    new JSONObject()
                        .put("id", 550 + i)
                        .put("media_type", "movie")
                        .put("title", i == 0 ? "Fight Club" : "Artwork fixture " + i)
                        .put(
                            "overview",
                            "An insomniac office worker and a soap maker form an underground fight club."
                        )
                        .put("poster_path", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg")
                        .put("backdrop_path", "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg")
                );
                show.invoke(shelf, titles, "");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(3000);
        screenshot("home-artwork");
        instrumentation.runOnMainSync(() -> {
            assertFullyVisible("All Apps");
            assertFullyVisible("View details");
        });
        instrumentation.runOnMainSync(() -> text(root(), "Movies").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            for (String category : new String[] {
                "Search",
                "Browse",
                "Filters",
                "Library",
                "Trending today",
            })
                assertFullyVisible(category);
        });
        screenshot("movies");
        select("Browse");
        instrumentation.runOnMainSync(() -> {
            for (String category : new String[] {
                "Trending this week",
                "Popular",
                "Top rated",
                "Now playing",
                "Upcoming",
                "Action",
                "Romance",
            })
                assertNotNull(category, text(root(), category));
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        select("Settings");
        select("Playback");
        screenshot("settings-playback");
        select("Accounts");
        screenshot("settings-accounts");
        instrumentation.runOnMainSync(() -> {
            assertEquals(
                "Account overview must not expose a wall of login fields",
                0,
                countInputs(activity.getWindow().getDecorView())
            );
            assertFullyVisible("Xtream");
            assertFullyVisible("Premiumize");
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        select("Live TV");
        SystemClock.sleep(1000);
        screenshot("live-tv");
    }

    private int countInputs(View view) {
        int count = view instanceof EditText ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countInputs(group.getChildAt(i));
        }
        return count;
    }

    private void assertFullyVisible(String label) {
        View view = text(root(), label);
        if (view == null) UiEvidence.capture("navigation-missing-" + label);
        assertNotNull(label, view);
        Rect visible = new Rect();
        assertTrue(label + " visible", view.getGlobalVisibleRect(visible));
        assertTrue(label + " is vertically clipped", visible.height() >= view.getHeight());
        assertTrue(label + " is horizontally clipped", visible.width() >= view.getWidth());
        if (view instanceof TextView) {
            TextView caption = (TextView) view;
            android.text.Layout layout = caption.getLayout();
            assertNotNull(label + " text layout", layout);
            assertEquals(
                label + " label is truncated",
                0,
                layout.getEllipsisCount(layout.getLineCount() - 1)
            );
            assertTrue(
                label + " text is clipped inside its control",
                layout.getLineBottom(layout.getLineCount() - 1) <=
                    caption.getHeight() - caption.getCompoundPaddingTop() - caption.getCompoundPaddingBottom()
            );
        }
    }

    @Test
    public void detailsAndConnectionFormsFitAndKeepDistinctPurposes() throws Exception {
        Context context = instrumentation.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            try {
                Method method = MainActivity.class.getDeclaredMethod("accountScreen", String.class);
                method.setAccessible(true);
                method.invoke(activity, "TMDB");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertEquals(1, countInputs(activity.getWindow().getDecorView()));
            assertFullyVisible("API Key (v3 auth)");
        });
        screenshot("connect-tmdb");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            try {
                Method screen = MainActivity.class.getDeclaredMethod("screen", String.class, Runnable.class);
                screen.setAccessible(true);
                screen.invoke(activity, "Details", (Runnable) () -> {});
                JSONObject fixture = new JSONObject()
                    .put("id", 100)
                    .put("media_type", "tv")
                    .put("name", "A series with a longer title")
                    .put("poster_path", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg")
                    .put("first_air_date", "2026-01-01")
                    .put("vote_average", 8.1)
                    .put(
                        "overview",
                        "A detailed synopsis that needs room to breathe on a television screen. " +
                            new String(new char[20]).replace(
                                "\0",
                                "The story follows a family on an unexpected journey. "
                            )
                    )
                    .put("genres", new JSONArray().put(new JSONObject().put("name", "Drama")))
                    .put(
                        "credits",
                        new JSONObject().put(
                            "cast",
                            new JSONArray().put(new JSONObject().put("name", "Actor One"))
                        )
                    );
                Method details = MainActivity.class.getDeclaredMethod("renderDetails", JSONObject.class);
                details.setAccessible(true);
                details.invoke(activity, fixture);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertFullyVisible("Episodes");
            assertFullyVisible("+ My list");
            assertNotNull(text(root(), "About A series with a longer title"));
            assertNotNull(text(root(), "Actor One"));
        });
        screenshot("details");
        instrumentation.runOnMainSync(() -> {
            String previous = context.getSharedPreferences("ui", 0).getString("watchlist", "[]");
            try {
                JSONObject title = new JSONObject()
                    .put("id", 991100)
                    .put("media_type", "tv")
                    .put("name", "Saved series");
                Method toggle = MainActivity.class.getDeclaredMethod("toggleWatchlist", JSONObject.class),
                    has = MainActivity.class.getDeclaredMethod("isWatchlisted", JSONObject.class);
                toggle.setAccessible(true);
                has.setAccessible(true);
                assertFalse((Boolean) has.invoke(activity, title));
                toggle.invoke(activity, title);
                assertTrue((Boolean) has.invoke(activity, title));
                toggle.invoke(activity, title);
                assertFalse((Boolean) has.invoke(activity, title));
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                context.getSharedPreferences("ui", 0).edit().putString("watchlist", previous).commit();
            }
        });
    }

    @Test
    public void everyMovieAndSeriesGenreIsAvailableOnEntryAndRoutesByRemote() throws Exception {
        Context context = instrumentation.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        for (String kind : new String[] { "Movies", "Series" }) {
            select(kind);
            instrumentation.runOnMainSync(() -> {
                View daily = text(root(), "Trending today");
                assertNotNull(daily);
                assertTrue("Catalogue collection is visible", daily.isShown());
            });
            String type = kind.equals("Movies") ? "movie" : "tv";
            select("Browse");
            instrumentation.runOnMainSync(() -> {
                for (TmdbCategories.Entry genre : TmdbCategories.genres(type)) {
                    View view = text(root(), genre.name);
                    assertNotNull("Category in Browse " + kind + ": " + genre.name, view);
                    assertEquals(genre.path, view.getTag());
                }
                View last = text(root(), "Western");
                last.requestFocus();
            });
            instrumentation.waitForIdleSync();
            screenshot(kind.toLowerCase() + "-categories");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() ->
                assertNotNull("Remote opens the final genre", text(root(), "Western"))
            );
            select("Filters");
            instrumentation.runOnMainSync(() -> {
                for (String name : new String[] {
                    "Years & decades",
                    "Languages",
                    "Countries",
                    "Streaming services",
                    "Ratings",
                    "Runtime",
                    "Sort order",
                    "Keywords & themes",
                    "Studios",
                    "People",
                    type.equals("movie") ? "Collections" : "Series types",
                    type.equals("movie") ? "Release types" : "Series status",
                })
                    assertNotNull("Filter " + name, text(root(), name));
            });
            screenshot(kind.toLowerCase() + "-filters");
            select(type.equals("movie") ? "Release types" : "Series types");
            instrumentation.runOnMainSync(() ->
                assertNotNull(
                    "Media-specific categories",
                    text(root(), type.equals("movie") ? "Digital releases" : "Miniseries")
                )
            );
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
        }
    }

    @After
    public void cleanup() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        instrumentation
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .commit();
        ChannelDatabase.get(instrumentation.getTargetContext()).clear();
    }
}
