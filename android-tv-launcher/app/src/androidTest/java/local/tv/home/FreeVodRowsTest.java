package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

/** The Play page selects individual links and refreshes facts without losing remote focus. */
@RunWith(AndroidJUnit4.class)
public class FreeVodRowsTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean updates;

    @Before
    public void start() {
        Context context = remote.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        updates = prefs.getBoolean("autoUpdates", true);
        prefs.edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void stop() {
        remote.runOnMainSync(() -> activity.finish());
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", updates)
            .commit();
    }

    private SourceRepository.Source source(String route, String url) {
        SourceRepository.Source source = new SourceRepository.Source(route + "\n4K Atmos", url);
        source.site = "fixture.example";
        return source;
    }

    @Test
    public void sameWebsiteLinksStaySeparateAndClickSelectsExactLink() {
        SourceRepository.Source first = source("Route A", "https://fixture.example/one.mp4");
        SourceRepository.Source second = source("Route A", "https://fixture.example/two.mp4");
        AtomicReference<SourceRepository.Source> picked = new AtomicReference<>();
        remote.runOnMainSync(() -> {
            activity.screen("Sources", () -> {});
            LinearLayout target = activity.vertical();
            activity.page.addView(target);
            FreeVodRows rows = new FreeVodRows(activity, target, picked::set);
            rows.render(Arrays.asList(first, second));
            assertEquals(2, target.getChildCount());
            assertNotSame(rows.row(first), rows.row(second));
            assertTrue(rows.row(first).getText().toString().contains("fixture.example"));
            assertFalse(rows.row(first).getText().toString().contains("Atmos"));
            assertFalse(rows.row(first).getText().toString().contains("4K"));
            rows.row(second).performClick();
            assertSame(second, picked.get());
            assertNull("Selecting a link does not open a website submenu", SidePanel.current);
            rows.render(Collections.singletonList(second));
            assertNull(rows.row(first));
            assertEquals(1, target.getChildCount());
        });
    }

    @Test
    public void metadataUpdatesSameRowAndKeepsFocusWithoutCountChange() {
        SourceRepository.Source source = source("Route A", "https://fixture.example/one.mp4");
        remote.runOnMainSync(() -> {
            activity.screen("Sources", () -> {});
            LinearLayout target = activity.vertical();
            activity.page.addView(target);
            FreeVodRows rows = new FreeVodRows(activity, target, ignored -> {});
            rows.render(Collections.singletonList(source));
            Button row = rows.row(source);
            row.requestFocus();
            assertTrue(row.hasFocus());
            source.facts.value = new MediaFacts.Value(
                MediaFacts.Origin.FILE,
                1920,
                1080,
                "video/avc",
                "audio/eac3",
                6,
                "eng",
                8200000,
                384000,
                7200000
            );
            rows.render(Collections.singletonList(source));
            assertSame(row, rows.row(source));
            assertTrue(row.hasFocus());
            String label = row.getText().toString();
            assertTrue(label.startsWith("1080p ✓ · 5.1 · fixture.example\n"));
            assertTrue(label.contains("EAC3"));
            assertTrue(label.contains("8.2 Mb/s"));
        });
    }

    @Test
    public void separateRoutesToSameUrlKeepIndependentQueueIdentity() {
        SourceRepository.Source first = source("Route A", "https://fixture.example/shared.mp4");
        SourceRepository.Source second = source("Route B", "https://fixture.example/shared.mp4");
        assertNotEquals(VodSources.entry(first).url, VodSources.entry(second).url);
        remote.runOnMainSync(() -> {
            LinearLayout target = activity.vertical();
            FreeVodRows rows = new FreeVodRows(activity, target, ignored -> {});
            rows.render(Arrays.asList(first, second));
            assertEquals(2, target.getChildCount());
            assertNotSame(rows.row(first), rows.row(second));
        });
    }
}
