package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FreeVodProvidersTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void packagedPythonImportsEveryProviderAndHostResolverWithoutKodi() throws Exception {
        assertEquals(68, FreeVodProviders.ids(context).size());
        assertEquals(68, FreeVodProviders.validate(context));
    }

    @Test
    public void realExtractorEmitsBothServersThroughTheAndroidCallback() throws Exception {
        FreeVodProviders.ids(context);
        Context tests = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext();
        java.io.File script = java.io.File.createTempFile("free-vod-fixture", ".py", context.getCacheDir());
        try (
            java.io.InputStream input = tests.getAssets().open("free_vod_fixture.py");
            java.io.OutputStream output = new java.io.FileOutputStream(script)
        ) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        com.chaquo.python.PyObject fixture = com.chaquo.python.Python.getInstance()
            .getModule("runpy")
            .callAttr("run_path", script.getAbsolutePath());
        fixture.callAttr("get", "install").call();
        try {
            java.util.List<SourceRepository.Source> sources = new java.util.ArrayList<>();
            FreeVodProviders.search(
                context,
                "free99:my4hydra_top",
                new JSONObject()
                    .put("imdb", "tt1234567")
                    .put("tmdb", 12)
                    .put("title", "Fixture Film")
                    .put("year", "2020")
                    .put("season", -1)
                    .put("episode", -1)
                    .put("http", false),
                new AtomicBoolean(false),
                sources::add
            );
            assertEquals(2, sources.size());
            assertEquals(SourceRepository.Cache.CACHED, sources.get(0).cache);
            assertNotNull("Header-free sources also use counted incoming transport", sources.get(0).stream);
            assertTrue(sources.get(0).responseMs >= 0);
            assertEquals(
                "https://8.8.8.8/video.mp4",
                sources.get(0).files.getJSONObject(0).getString("link")
            );
            assertEquals(
                "https://1.1.1.1/video.mp4",
                sources.get(1).files.getJSONObject(0).getString("link")
            );
            assertEquals(0, AutoPlayback.pick(sources.get(0).files, -1, -1, "Fixture Film"));
        } finally {
            fixture.callAttr("get", "restore").call();
            script.delete();
        }
    }

    @Test
    public void javaCancellationReachesPackagedPythonBeforeAnyProviderRequest() throws Exception {
        AtomicInteger emitted = new AtomicInteger();
        FreeVodProviders.search(
            context,
            "free99:my4hydra_top",
            new JSONObject().put("http", false),
            new AtomicBoolean(true),
            source -> emitted.incrementAndGet()
        );
        assertEquals(0, emitted.get());
    }
}
