package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline fixtures; do not mistake these for successful live provider playback. */
@RunWith(AndroidJUnit4.class)
public final class NuvioSportsTest {

    @Test
    public void serverAddressIsValidatedBeforeAnyNetworkRequest() {
        assertEquals("https://sports.example:7000", NuvioSports.base("https://sports.example:7000/", false));
        assertEquals("http://192.168.1.5:7000", NuvioSports.base("http://192.168.1.5:7000", true));
        for (String url : new String[] {
            "http://sports.example:7000",
            "https://user:password@sports.example",
            "https://sports.example/stream/tv/abc.json",
            "https://sports.example/?token=secret",
            "javascript:alert(1)"
        }) {
            try {
                NuvioSports.base(url, false);
                fail("Rejected invalid service URL");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void locatorIsStableAndDoesNotExposeAPlaybackToken() throws Exception {
        String event = "nuvio_sport_match:2026-11-18:home-away";
        String locator = NuvioSports.locatorFor(event);
        assertEquals(event, NuvioSports.id(locator));
        assertTrue(NuvioSports.locator(locator));
        assertFalse(NuvioSports.locator("https://nuvio.moviehub.invalid/tv/%2e%2e%2fadmin"));
        assertFalse(NuvioSports.locator(locator + "?jwt=secret"));
        assertFalse(NuvioSports.locator("https://not-nuvio.example/tv/" + event));
    }

    @Test
    public void onlyDirectHlsAndStreamingProxyLinksArePlayable() throws Exception {
        JSONArray streams = new JSONArray()
            .put(new JSONObject().put("name", "Website").put("externalUrl", "https://stream.example/embed"))
            .put(new JSONObject().put("name", "YouTube").put("ytId", "someid"))
            .put(new JSONObject().put("url", "https://source.example/watch/player"))
            .put(new JSONObject().put("url", "https://source.example/live.m3u8?token=one"))
            .put(new JSONObject().put("url", "https://source.example/live.m3u8?token=one"))
            .put(new JSONObject().put("url", "https://server.example/api/manifest?url=opaque"));
        List<String> choices = NuvioSports.candidates(streams, false);
        assertEquals(2, choices.size());
        assertEquals("https://source.example/live.m3u8?token=one", choices.get(0));
        assertEquals("https://server.example/api/manifest?url=opaque", choices.get(1));
    }

    @Test
    public void extensionlessProxyUrlsGetCorrectMediaTypes() {
        assertTrue(NuvioSports.hlsProxy("https://server.example/api/manifest?url=opaque"));
        assertTrue(NuvioSports.hlsProxy("https://server.example/api/manifest.m3u8?token=abc"));
        assertTrue(NuvioSports.hlsProxy("https://server.example/playlist?key=abc"));
        assertFalse(NuvioSports.hlsProxy("https://server.example/api/dash/index.m3u8?d=signed"));
        assertFalse(NuvioSports.hlsProxy("https://server.example/api/dashboard"));
        assertFalse(NuvioSports.hlsProxy("not a url"));
    }

    @Test
    public void cleartextPlaybackRequiresExplicitHttpPreference() throws Exception {
        JSONArray streams = new JSONArray()
            .put(new JSONObject().put("url", "http://192.168.1.5:7000/api/manifest?url=test"));
        assertTrue(NuvioSports.candidates(streams, false).isEmpty());
        assertEquals(1, NuvioSports.candidates(streams, true).size());
    }
}
