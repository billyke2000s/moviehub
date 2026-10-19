package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ProviderAdaptersTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanup() {
        ProviderRelay.testConnections = null;
        AdapterSources.invalidate();
    }

    @Test
    public void upstreamPostResolverRunsInIsolatedProcess() throws Exception {
        String module = ProviderPackages.module(context, "stirr");
        AtomicInteger requests = new AtomicInteger();
        JSONObject stream = (JSONObject) ProviderBroker.run(
            module,
            "resolve",
            new JSONObject().put("channelId", "42"),
            (source, op, payload) -> ProviderBridge.call(context, source, op, payload),
            request -> {
                requests.incrementAndGet();
                assertEquals("POST", request.getString("method"));
                assertTrue(ProviderBroker.allowed("stirr", request.getString("url")));
                return new JSONObject()
                    .put("status", 200)
                    .put("url", request.getString("url"))
                    .put("headers", new JSONObject())
                    .put("text", "{\"data\":[{\"media\":[\"https://8.8.8.8/live.m3u8?nonce=[vx_nonce]\"]}]}");
            },
            new NativeHttp()
        );
        assertEquals(1, requests.get());
        assertFalse(stream.getString("url").contains("[vx_nonce]"));
    }

    @Test
    public void capabilitiesRejectForeignHostsAndCredentials() {
        assertTrue(ProviderBroker.allowed("stirr", "https://stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://stirr.com.evil.example/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://user:password@stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "http://stirr.com/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://127.0.0.1/api/videos/list/"));
        assertFalse(ProviderBroker.allowed("stirr", "https://stirr.com:1234/api/videos/list/"));
    }

    @Test
    public void upstreamChannelIdsRoundTripWithoutChangingFavourites() throws Exception {
        String id = "US:channel/compound+id";
        String url = AdapterSources.locator("vidaa", id);
        assertTrue(EngineSources.locator(url));
        assertEquals("vidaa", AdapterSources.provider(url));
        assertEquals(id, AdapterSources.channel(url));
        assertEquals(url, AdapterSources.locator("vidaa", id));
        assertFalse(AdapterSources.locator("https://native.moviehub.invalid/engine/untrusted/a"));
    }

    @Test
    public void relayRewritesHlsAndSendsRequiredHeadersThenCloses() throws Exception {
        List<String> origins = Collections.synchronizedList(new ArrayList<>());
        ProviderRelay.testConnections = uri ->
            new HttpURLConnection(uri.toURL()) {
                @Override
                public void connect() {}

                @Override
                public void disconnect() {}

                @Override
                public boolean usingProxy() {
                    return false;
                }

                @Override
                public int getResponseCode() {
                    origins.add(getRequestProperty("Origin"));
                    return 200;
                }

                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }

                @Override
                public InputStream getInputStream() {
                    String data = uri.getPath().endsWith("m3u8")
                        ? "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nsegment.ts\n"
                        : "fixture-media";
                    return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
                }
            };
        EngineSources.Stream stream = new EngineSources.Stream(
            new JSONObject()
                .put("url", "https://8.8.8.8/master.m3u8")
                .put("headers", new JSONObject().put("Origin", "https://provider.example"))
                .put("userAgent", "")
                .put("referer", "")
        );
        assertTrue(ProviderRelay.needed(stream));
        String address;
        try (ProviderRelay relay = new ProviderRelay(stream)) {
            address = relay.url;
            String master = read(address);
            assertTrue(master.contains("URI=\"http://127.0.0.1:"));
            String child = Arrays.stream(master.split("\n"))
                .filter(s -> s.startsWith("http://"))
                .findFirst()
                .get();
            assertEquals("fixture-media", read(child));
            assertEquals(Arrays.asList("https://provider.example", "https://provider.example"), origins);
            assertTrue(master.indexOf("8.8.8.8") < 0);
        }
        try {
            read(address);
            fail("Closed relay accepted connection");
        } catch (IOException expected) {}
    }

    private static String read(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
