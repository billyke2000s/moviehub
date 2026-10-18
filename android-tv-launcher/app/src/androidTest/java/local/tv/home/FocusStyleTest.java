package local.tv.home;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

/** Focus styling: wide rows do not grow into their neighbours; the top-bar app icon has no tile. */
@RunWith(AndroidJUnit4.class)
public class FocusStyleTest {

    final Instrumentation remote = InstrumentationRegistry.getInstrumentation();
    MainActivity activity;
    boolean autoUpdates;

    @Before
    public void launch() {
        Context context = remote.getTargetContext();
        autoUpdates = context.getSharedPreferences("ui", 0).getBoolean("autoUpdates", true);
        context.getSharedPreferences("ui", 0).edit().putBoolean("autoUpdates", false).commit();
        activity = (MainActivity) remote.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        remote.waitForIdleSync();
    }

    @After
    public void cleanup() {
        remote.runOnMainSync(() -> activity.finish());
        remote
            .getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean("autoUpdates", autoUpdates)
            .commit();
    }

    @Test
    public void wideRowsHighlightWithoutGrowingAndSmallItemsGrow() {
        remote.runOnMainSync(() -> {
            activity.screen("Focus", () -> {});
            Button small = activity.button("Small", () -> {});
            LinearLayout wide = activity.vertical();
            wide.addView(activity.text("A full-width list row", 16));
            activity.focus(wide);
            activity.page.addView(small, new LinearLayout.LayoutParams(activity.dp(120), activity.dp(40)));
            activity.page.addView(wide, new LinearLayout.LayoutParams(-1, activity.dp(88)));
        });
        remote.waitForIdleSync();
        remote.runOnMainSync(() -> {
            View small = activity.page.getChildAt(0),
                wide = activity.page.getChildAt(1);
            activity.styleFocus(small, true);
            activity.styleFocus(wide, true);
            assertTrue("Small focused items grow", small.getScaleX() > 1f);
            assertEquals("Wide rows do not grow past their neighbours", 1f, wide.getScaleX(), 0f);
            assertTrue("Focused items draw above neighbours", wide.getTranslationZ() > 0);
            activity.styleFocus(wide, false);
            assertEquals(0f, wide.getTranslationZ(), 0f);
        });
    }

    @Test
    public void topBarAppIconHasNoTileAndGlowsWhenFocused() {
        remote.runOnMainSync(() -> {
            AppDock.App app = new AppDock.App(
                HomeScreen.MEMORY_CLEAN,
                new ComponentName("fixture.memoryclean", "Main"),
                activity.getDrawable(android.R.drawable.sym_def_app_icon)
            );
            activity.dock.apps.add(app);
            assertSame(app, activity.dock.findApp("MemoryClean"));
            assertNull("Name must match exactly", activity.dock.findApp("memoryclean"));
            View icon = activity.dock.iconShortcut(app);
            assertTrue(icon instanceof ImageView);
            assertNull("No box behind the icon", icon.getBackground());
            ((ViewGroup) activity.page.getChildAt(0)).addView(
                icon,
                new LinearLayout.LayoutParams(activity.dp(38), activity.dp(38))
            );
            assertTrue(icon.requestFocus());
            assertTrue(icon.getScaleX() > 1f);
            assertNotNull("Soft glow while focused", icon.getBackground());
        });
    }
}
