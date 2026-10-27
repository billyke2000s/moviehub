package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Actual Android container extraction and guarded relay, with a local range-only fixture. */
@RunWith(AndroidJUnit4.class)
public class MediaMetadataProbeTest {

    private ServerSocket server;
    private byte[] movie;
    private volatile boolean closed;
    private final AtomicInteger bytes = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void start() throws Exception {
        try (
            InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("vod.mp4");
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            movie = out.toByteArray();
        }
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> {
            while (!closed)
                try {
                    Socket client = server.accept();
                    new Thread(() -> serve(client), "metadata-fixture").start();
                } catch (IOException ignored) {}
        }, "metadata-accept").start();
        ProviderRelay.testConnections = uri -> (HttpURLConnection) new URL(url()).openConnection();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/vod.mp4";
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)
            );
            String line = reader.readLine();
            int start = 0,
                end = movie.length - 1;
            boolean range = false;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range: bytes=")) {
                    String[] parts = line.substring(line.indexOf('=') + 1).split("-", -1);
                    start = Integer.parseInt(parts[0]);
                    if (!parts[1].isEmpty()) end = Math.min(end, Integer.parseInt(parts[1]));
                    range = true;
                }
            }
            requests.incrementAndGet();
            if (!range || start >= movie.length) {
                client
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(
                            StandardCharsets.US_ASCII
                        )
                    );
                return;
            }
            int size = end - start + 1;
            String headers =
                "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Length: " +
                size +
                "\r\nContent-Range: bytes " +
                start +
                "-" +
                end +
                "/" +
                movie.length +
                "\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(movie, start, size);
            bytes.addAndGet(size);
        } catch (IOException ignored) {}
    }

    @After
    public void stop() throws Exception {
        ProviderRelay.testConnections = null;
        closed = true;
        if (server != null) server.close();
    }

    @Test
    public void readsRealContainerTracksWithoutPlayingAndWithinByteBudget() throws Exception {
        SourceRepository.Source source = new SourceRepository.Source("4K Atmos", "https://8.8.8.8/vod.mp4");
        ErrorLog.clear();
        MediaFacts.Value facts = MediaMetadataProbe.read(source, 2160, new AtomicBoolean());
        StringBuilder diagnostic = new StringBuilder("requests=" + requests.get() + " bytes=" + bytes.get());
        for (ErrorLog.Entry error : ErrorLog.snapshot())
            diagnostic.append("; ").append(error.where).append(": ").append(error.type);
        assertEquals(diagnostic.toString(), MediaFacts.Origin.FILE, facts.origin);
        assertEquals(160, facts.width);
        assertEquals(90, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertTrue(facts.channels > 0);
        assertTrue(facts.durationMs >= 149_000 && facts.durationMs <= 151_000);
        assertFalse(facts.describe().contains("Atmos"));
        assertTrue(bytes.get() > 0 && bytes.get() <= MediaMetadataProbe.BYTE_LIMIT);
    }

    @Test
    public void cancellationStopsBeforeAnyNetworkRead() throws Exception {
        SourceRepository.Source source = new SourceRepository.Source("1080p", "https://8.8.8.8/vod.mp4");
        assertSame(MediaFacts.UNKNOWN, MediaMetadataProbe.read(source, 1080, new AtomicBoolean(true)));
        assertEquals(0, requests.get());
    }

    @Test
    public void randomAccessReadsTheEndWithoutDownloadingTheMiddleAndCachesTheChunk() throws Exception {
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url(), new AtomicBoolean())) {
            byte[] b = new byte[32];
            assertEquals(32, data.readAt(0, b, 0, 32));
            assertEquals(movie.length, data.getSize());
            assertEquals(32, data.readAt(movie.length - 32, b, 0, 32));
            assertEquals(32, data.readAt(movie.length - 32, b, 0, 32));
            // Native sniffers return to the start after reading the end; it must stay cached.
            byte[] first = new byte[32];
            assertEquals(32, data.readAt(0, first, 0, first.length));
            assertArrayEquals(java.util.Arrays.copyOfRange(movie, 0, 32), first);
            assertEquals(2, requests.get());
            assertArrayEquals(java.util.Arrays.copyOfRange(movie, movie.length - 32, movie.length), b);
            assertTrue(bytes.get() <= 2 * 65536);
        }
    }

    @Test
    public void byteBudgetStopsFurtherRandomReads() throws Exception {
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url(), new AtomicBoolean())) {
            byte[] b = new byte[1];
            for (int i = 0; i < 4; i++) assertEquals(1, data.readAt(i * 65536L, b, 0, 1));
            try {
                data.readAt(4 * 65536L, b, 0, 1);
                fail("Read beyond metadata byte budget");
            } catch (IOException expected) {}
            assertEquals(4, requests.get());
            assertEquals(MediaMetadataProbe.BYTE_LIMIT, bytes.get());
        }
    }

    @Test
    public void expiredDeadlineStopsBeforeNetworkRead() throws Exception {
        try (MediaMetadataProbe.Data data = new MediaMetadataProbe.Data(url(), new AtomicBoolean())) {
            android.os.SystemClock.sleep(MediaMetadataProbe.TIME_LIMIT_MS + 50);
            try {
                data.readAt(0, new byte[1], 0, 1);
                fail("Read beyond metadata deadline");
            } catch (InterruptedIOException expected) {}
            assertEquals(0, requests.get());
        }
    }

    @Test
    public void playlistCeilingAndBandwidthAreDeclarationsNotPlayedTrackFacts() {
        MediaFacts.Value value = MediaMetadataProbe.playlist(
            "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\na.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=1920x1080\nb.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160\nc.m3u8\n",
            1080
        );
        assertEquals(1920, value.width);
        assertEquals(7_000_000, value.videoBitrate);
        assertEquals(MediaFacts.Origin.PLAYLIST, value.origin);
        assertEquals("Audio unknown", value.audio());
        assertTrue(value.picture().contains("Up to 1080p"));
    }
}
