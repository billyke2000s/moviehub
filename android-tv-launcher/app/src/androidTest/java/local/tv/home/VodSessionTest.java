package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VodSessionTest {

    private SourceRepository.Source source(String cookie) throws Exception {
        return new SourceRepository.Source(
            "Website · Host\n1080p",
            "https://cdn.example/video.mp4",
            new JSONArray().put(
                new JSONObject().put("path", "video.mp4").put("link", "https://cdn.example/video.mp4")
            ),
            new JSONObject().put("Cookie", cookie)
        );
    }

    @Test
    public void sharedUrlWithDifferentHeadersHasDistinctPlaybackIdentity() throws Exception {
        SourceRepository.Source first = source("one"),
            second = source("two");
        assertNotEquals(first.key(), second.key());
        assertEquals(first.key(), source("one").key());
        VodSourceFailover queue = new VodSourceFailover();
        queue.setSources(java.util.Arrays.asList(VodSources.entry(first), VodSources.entry(second)));
        assertEquals(2, queue.sources().size());
    }

    @Test
    public void refreshReplacesHeadersAndKeepsLogicalIdentityAndFutureRefresh() throws Exception {
        SourceRepository.Source first = source("old");
        AtomicInteger calls = new AtomicInteger();
        first.refresh = () -> {
            calls.incrementAndGet();
            return source("fresh");
        };
        assertSame(first, first.fresh(false));
        first.expiresAt = System.currentTimeMillis() + 5000;
        SourceRepository.Source fresh = first.fresh(false);
        assertEquals(1, calls.get());
        assertEquals("fresh", fresh.headers.getString("Cookie"));
        assertEquals(first.key(), fresh.key());
        fresh.fresh(true);
        assertEquals(2, calls.get());
    }

    @Test
    public void providerLookupEvidenceIsExplicitAndCannotSelectAnotherEpisode() throws Exception {
        JSONObject file = new JSONObject()
            .put("path", "video.mp4")
            .put("link", "https://cdn.example/video")
            .put("identityEvidence", "provider_lookup")
            .put("lookupSeason", 2)
            .put("lookupEpisode", 3);
        JSONArray files = new JSONArray().put(file);
        assertEquals(0, AutoPlayback.pick(files, 2, 3, "Show"));
        assertEquals(-1, AutoPlayback.pick(files, 2, 4, "Show"));
        file.remove("identityEvidence");
        assertEquals(-1, AutoPlayback.pick(files, 2, 3, "Show"));
    }
}
