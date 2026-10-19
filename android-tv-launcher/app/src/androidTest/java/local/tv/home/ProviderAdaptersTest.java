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
        new Secrets(context).remove(AdapterSources.TCL_HINTS);
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
        assertTrue(
            ProviderBroker.allowed(
                "samsung",
                "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/refs/heads/master/SamsungTVPlus/.channels.json.gz"
            )
        );
        assertFalse(
            ProviderBroker.allowed(
                "samsung",
                "https://raw.githubusercontent.com/other/repository/master/.channels.json.gz"
            )
        );
        assertTrue(ProviderBroker.allowed("tubi", "https://c.tubi1.apollo.adrise.io/live/master.m3u8"));
        assertFalse(ProviderBroker.allowed("tubi", "https://adrise.io.evil.example/live/master.m3u8"));
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
    public void coldResolversAvoidCatalogueAndTclHintsAreEncryptedAndCompact() throws Exception {
        for (String id : Arrays.asList("stirr", "xumo", "roku", "plex", "dlhd", "tubi", "samsung")) {
            JSONObject row = AdapterSources.resolverRow(context, id, "42", new NativeHttp());
            assertEquals("42", row.getString("channelId"));
            assertEquals(1, row.length());
        }
        String media = "https://media.example/signed.m3u8?token=fixture";
        AdapterSources.saveTclHints(
            context,
            ProviderPackages.module(context, "tcl"),
            new JSONArray().put(
                new JSONObject()
                    .put("channelId", "42")
                    .put("source", "fixture-source")
                    .put("media", media)
                    .put("programs", new JSONArray().put("not a resolver input"))
            )
        );
        JSONObject hint = AdapterSources.resolverRow(context, "tcl", "42", new NativeHttp());
        assertEquals(media, hint.getString("media"));
        assertEquals("fixture-source", hint.getString("source"));
        assertFalse(hint.has("programs"));
        String disk = context.getSharedPreferences("accounts", 0).getString(AdapterSources.TCL_HINTS, "");
        assertFalse(disk.contains("fixture-source"));
        assertFalse(disk.contains("media.example"));
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
                    return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public synchronized int read(byte[] buffer, int offset, int length) {
                            return super.read(buffer, offset, Math.min(2, length));
                        }
                    };
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
