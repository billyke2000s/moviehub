package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real TS/fMP4 track headers, including separate audio, behind misleading HLS declarations. */
@RunWith(AndroidJUnit4.class)
public class HlsMetadataProbeTest {

    private final Map<String, byte[]> resources = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger bytes = new AtomicInteger(),
        segmentRequests = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean closed;

    @Before
    public void start() throws Exception {
        for (String name : Arrays.asList(
            "playback.ts",
            "vod.mp4",
            "metadata-init-video.mp4",
            "metadata-init-audio.mp4"
        )) {
            try (
                InputStream in = InstrumentationRegistry.getInstrumentation()
                    .getContext()
                    .getAssets()
                    .open(name);
                ByteArrayOutputStream out = new ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                resources.put("/" + name, out.toByteArray());
            }
        }
        put(
            "/master.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\nmedia.m3u8\n"
        );
        put("/media.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:30\n#EXTINF:30,\nplayback.ts\n#EXT-X-ENDLIST\n");
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed)
                try {
                    Socket client = server.accept();
                    new Thread(() -> serve(client), "hls-header-fixture").start();
                } catch (IOException ignored) {}
        }, "hls-header-accept").start();
        ProviderRelay.testConnections = uri ->
            (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + uri.getRawPath()
            ).openConnection();
    }

    private void put(String path, String text) {
        resources.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)
            );
            String request = reader.readLine();
            String path = request.split(" ")[1];
            byte[] data = resources.get(path);
            if (data == null) throw new IOException("Missing fixture resource");
            int from = 0,
                to = data.length - 1;
            boolean range = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty())
                if (line.toLowerCase(Locale.ROOT).startsWith("range: bytes=")) {
                    String[] ends = line.substring(13).split("-", -1);
                    from = Integer.parseInt(ends[0]);
                    if (!ends[1].isEmpty()) to = Math.min(to, Integer.parseInt(ends[1]));
                    range = true;
                }
            if (path.endsWith(".ts") || path.contains("init-")) segmentRequests.incrementAndGet();
            int size = Math.max(0, to - from + 1);
            String type = path.endsWith("m3u8")
                ? "application/vnd.apple.mpegurl"
                : path.endsWith("ts")
                  ? "video/mp2t"
                  : "video/mp4";
            String headers =
                "HTTP/1.1 " +
                (range ? "206 Partial Content" : "200 OK") +
                "\r\nContent-Type: " +
                type +
                "\r\nContent-Length: " +
                size +
                "\r\n" +
                (range ? "Content-Range: bytes " + from + "-" + to + "/" + data.length + "\r\n" : "") +
                "Connection: close\r\n\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(data, from, size);
            bytes.addAndGet(size);
        } catch (Exception ignored) {}
    }

    private MediaFacts.Value read() {
        return MediaMetadataProbe.read(
            new SourceRepository.Source("4K Atmos", "https://8.8.8.8/master.m3u8"),
            1080,
            new AtomicBoolean()
        );
    }

    @After
    public void stop() throws Exception {
        closed = true;
        if (server != null) server.close();
        ProviderRelay.testConnections = null;
    }

    @Test
    public void actualTransportStreamHeadersOverrideAdvertised1080pAndRevealAudio() {
        MediaFacts.Value facts = read();
        assertEquals(facts.describe(), MediaFacts.Origin.STREAM, facts.origin);
        assertEquals(160, facts.width);
        assertEquals(90, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertEquals(1, facts.channels);
        assertTrue(facts.listLabel("site").startsWith("90p ✓ · Mono · site"));
        assertEquals("A segment length is not the episode runtime", 0, facts.durationMs);
        assertTrue(facts.bitrate().contains("4.5 Mb/s playlist bandwidth"));
        assertTrue(bytes.get() <= MediaMetadataProbe.BYTE_LIMIT);
    }

    @Test
    public void separateFragmentedMp4AudioUsesItsOwnRealHeaderNotDeclared51() {
        put(
            "/master.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",DEFAULT=YES,LANGUAGE=\"en\",CHANNELS=\"6\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"audio\"\nmedia.m3u8\n"
        );
        put("/media.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"metadata-init-video.mp4\"\n#EXTINF:6,\nunneeded.m4s\n");
        put(
            "/audio.m3u8",
            "#EXTM3U\n#EXT-X-MAP:URI=\"metadata-init-audio.mp4\"\n#EXTINF:6,\nunneeded-audio.m4s\n"
        );
        MediaFacts.Value facts = read();
        assertEquals(facts.describe(), MediaFacts.Origin.STREAM, facts.origin);
        assertEquals(90, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertEquals(1, facts.channels);
        assertEquals("en", facts.language);
        assertEquals(2, segmentRequests.get());
        assertTrue(bytes.get() <= MediaMetadataProbe.BYTE_LIMIT);
    }

    @Test
    public void encryptedMediaKeepsDeclarationsWithoutDownloadingSegmentsOrKeys() {
        put("/media.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:30,\nplayback.ts\n");
        MediaFacts.Value facts = read();
        assertEquals(MediaFacts.Origin.PLAYLIST, facts.origin);
        assertEquals("AAC", facts.audioCodec);
        assertEquals(0, facts.channels);
        assertEquals(0, segmentRequests.get());
        assertTrue(facts.picture().contains("playlist"));
    }

    @Test
    public void deadlineAndByteBudgetAreSharedAcrossResources() throws Exception {
        MediaMetadataProbe.Budget budget = new MediaMetadataProbe.Budget(new AtomicBoolean());
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4";
        for (int i = 0; i < 4; i++) try (
            MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url, budget)
        ) {
            assertEquals(1, data.readAt(i * 65536L, new byte[1], 0, 1));
        }
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url, budget)) {
            try {
                data.readAt(0, new byte[1], 0, 1);
                fail("New resources cannot renew the byte budget");
            } catch (IOException expected) {}
        }
        MediaMetadataProbe.Budget expired = new MediaMetadataProbe.Budget(new AtomicBoolean());
        android.os.SystemClock.sleep(MediaMetadataProbe.TIME_LIMIT_MS + 50);
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url, expired)) {
            try {
                data.readAt(0, new byte[1], 0, 1);
                fail("New resources cannot renew the deadline");
            } catch (InterruptedIOException expected) {}
        }
    }
}
