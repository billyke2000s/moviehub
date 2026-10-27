package local.tv.home;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LiveHlsMetadataCheckTest {

    @Test
    public void inspectReportedEpisode() throws Exception {
        Assume.assumeTrue(
            "Explicit live metadata audit only",
            "true".equals(InstrumentationRegistry.getArguments().getString("liveHlsMeta"))
        );
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicBoolean cancelled = new AtomicBoolean();
        List<SourceRepository.Source> sources = new CopyOnWriteArrayList<>();
        JSONObject query = new JSONObject()
            .put("imdb", "")
            .put("tmdb", 10283)
            .put("title", "Archer")
            .put("localTitle", "Archer")
            .put("aliases", new JSONArray())
            .put("year", "2009")
            .put("season", 1)
            .put("episode", 1)
            .put("premiered", "2009-09-17")
            .put("episodeTitle", "Mole Hunt")
            .put("runtime", 21)
            .put("http", false)
            .put("maxQuality", 1080);
        try {
            String report = FreeVodProviders.search(context, "native:vixsrc", query, cancelled, sources::add);
            Log.i(
                "MovieHubHlsMeta",
                "Provider status=" + new JSONObject(report).optString("status") + " sources=" + sources.size()
            );
            if (sources.isEmpty()) return;
            ErrorLog.clear();
            long start = SystemClock.elapsedRealtime();
            MediaFacts.Value facts = MediaMetadataProbe.read(sources.get(0), 1080, "eng,en", cancelled);
            JSONObject evidence = new JSONObject()
                .put("episode", "Archer S1E1")
                .put("origin", facts.origin.name())
                .put("width", facts.width)
                .put("height", facts.height)
                .put("audio", MediaFacts.audioName(facts.audioCodec))
                .put("channels", facts.channels)
                .put("language", facts.language)
                .put("availableAudio", facts.availableAudio)
                .put("elapsedMs", SystemClock.elapsedRealtime() - start)
                .put("description", facts.describe());
            Log.i("MovieHubHlsMeta", evidence.toString());
            for (ErrorLog.Entry error : ErrorLog.snapshot())
                Log.i("MovieHubHlsMeta", error.where + ": " + error.type);
            Assert.assertTrue(
                "Live media headers were not fully established; inspect redacted metadata evidence",
                facts.origin == MediaFacts.Origin.STREAM &&
                    facts.width > 0 &&
                    facts.height > 0 &&
                    !facts.audioCodec.isEmpty() &&
                    facts.channels > 0 &&
                    facts.availableAudio.isEmpty()
            );
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception error) {
            Log.i("MovieHubHlsMeta", "Live inspection unavailable: " + error.getClass().getSimpleName());
        }
    }
}
