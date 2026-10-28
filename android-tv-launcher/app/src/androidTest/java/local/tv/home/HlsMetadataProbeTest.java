package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real TS/fMP4 track headers, including separate audio, behind misleading HLS declarations. */
@RunWith(AndroidJUnit4.class)
public class HlsMetadataProbeTest {

    private final Map<String, byte[]> resources = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger bytes = new AtomicInteger(),
        segmentRequests = new AtomicInteger(),
        keyRequests = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean closed;
    private volatile CountDownLatch concurrentHeaders;

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
            if (path.endsWith(".key") || path.endsWith("key.bin")) keyRequests.incrementAndGet();
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
            CountDownLatch gate = concurrentHeaders;
            if (gate != null && path.contains("metadata-init-")) {
                gate.countDown();
                if (!gate.await(1500, TimeUnit.MILLISECONDS)) throw new IOException(
                    "Audio/video headers were serialized"
                );
            }
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
        assertEquals(320, facts.width);
        assertEquals(180, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertEquals(1, facts.channels);
        assertTrue(facts.listLabel("site").startsWith("180p ✓ · Mono · site"));
        assertEquals("A segment length is not the episode runtime", 0, facts.durationMs);
        assertTrue(facts.bitrate().contains("4.5 Mb/s playlist bandwidth"));
        assertTrue(bytes.get() <= MediaMetadataProbe.BYTE_LIMIT);
    }

    @Test
    public void separateAudioAndVideoHeadersRunConcurrentlyAndOverrideDeclared51() {
        concurrentHeaders = new CountDownLatch(2);
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
    public void unsupportedEncryptionKeepsDeclarationsWithoutDownloadingSegmentsOrKeys() {
        put(
            "/media.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.example.protected\",URI=\"key.bin\"\n#EXTINF:30,\nplayback.ts\n"
        );
        MediaFacts.Value facts = read();
        assertEquals(MediaFacts.Origin.PLAYLIST, facts.origin);
        assertEquals("AAC", facts.audioCodec);
        assertEquals(0, facts.channels);
        assertEquals(0, segmentRequests.get());
        assertEquals(0, keyRequests.get());
        assertTrue(facts.picture().contains("playlist"));
    }

    private byte[] encrypt(byte[] value, byte[] key, byte[] iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(key, "AES"),
            new javax.crypto.spec.IvParameterSpec(iv)
        );
        return cipher.doFinal(value);
    }

    @Test
    public void aes128SegmentHeadersRevealActualResolutionAndAudioWithinTheSameBudget() throws Exception {
        byte[] key = new byte[16];
        java.util.Arrays.fill(key, (byte) 23);
        resources.put("/media.key", key);
        resources.put(
            "/encrypted.ts",
            encrypt(resources.get("/playback.ts"), key, HlsEncryption.iv("257", null))
        );
        put(
            "/media.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:257\n#EXT-X-KEY:METHOD=AES-128,URI=\"media.key\"\n#EXTINF:30,\nencrypted.ts\n"
        );
        MediaFacts.Value facts = read();
        assertEquals(facts.describe(), MediaFacts.Origin.STREAM, facts.origin);
        assertEquals(320, facts.width);
        assertEquals(180, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertEquals(1, facts.channels);
        assertEquals(0, facts.durationMs);
        assertEquals(1, keyRequests.get());
        assertTrue(bytes.get() <= MediaMetadataProbe.BYTE_LIMIT);
    }

    @Test
    public void aes128InitializationWithExplicitIvAndPreferredEnglishAudio() throws Exception {
        byte[] key = new byte[16];
        java.util.Arrays.fill(key, (byte) 17);
        byte[] iv = HlsEncryption.iv("0", "0x0102030405060708090a0b0c0d0e0f10");
        resources.put("/media.key", key);
        resources.put("/encrypted-init.mp4", encrypt(resources.get("/metadata-init-video.mp4"), key, iv));
        put(
            "/master.m3u8",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",DEFAULT=YES,LANGUAGE=\"ita\",URI=\"wrong.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",DEFAULT=NO,LANGUAGE=\"eng\",URI=\"english.m3u8\"\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,BANDWIDTH=4500000,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"a\"\nmedia.m3u8\n"
        );
        put(
            "/media.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"media.key\",IV=0x0102030405060708090a0b0c0d0e0f10\n#EXT-X-MAP:URI=\"encrypted-init.mp4\"\n#EXTINF:6,\nunneeded.m4s\n"
        );
        put(
            "/english.m3u8",
            "#EXTM3U\n#EXT-X-MAP:URI=\"metadata-init-audio.mp4\"\n#EXTINF:6,\nunneeded-audio.m4s\n"
        );
        MediaFacts.Value facts = read();
        assertEquals(facts.describe(), MediaFacts.Origin.STREAM, facts.origin);
        assertEquals(160, facts.width);
        assertEquals(90, facts.height);
        assertEquals("AAC", MediaFacts.audioName(facts.audioCodec));
        assertEquals(1, facts.channels);
        assertEquals("eng", facts.language);
        assertEquals(1, keyRequests.get());
    }

    @Test
    public void concurrentReservationsCannotOverspendAndReturnUnusedCapacity() throws Exception {
        MediaMetadataProbe.Budget budget = new MediaMetadataProbe.Budget(new AtomicBoolean());
        CountDownLatch attempted = new CountDownLatch(8);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        AtomicInteger granted = new AtomicInteger();
        java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) tasks.add(
                workers.submit(() -> {
                    MediaMetadataProbe.Budget.Permit permit = null;
                    try {
                        try {
                            permit = budget.reserve(65536);
                            granted.incrementAndGet();
                        } catch (IOException limit) {
                            // Four simultaneous 64 KiB reservations exhaust the shared cap.
                        } finally {
                            attempted.countDown();
                        }
                        if (!attempted.await(1, TimeUnit.SECONDS)) throw new AssertionError(
                            "Workers did not rendezvous"
                        );
                        if (permit != null) permit.record(16384);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } finally {
                        if (permit != null) permit.close();
                    }
                })
            );
            for (Future<?> task : tasks) task.get(1500, TimeUnit.MILLISECONDS);
            assertEquals(4, granted.get());
            assertEquals(65536, budget.received);
            try (MediaMetadataProbe.Budget.Permit remaining = budget.reserve(MediaMetadataProbe.BYTE_LIMIT)) {
                assertEquals(MediaMetadataProbe.BYTE_LIMIT - 65536, remaining.count);
            }
            try (MediaMetadataProbe.Budget.Permit returned = budget.reserve(MediaMetadataProbe.BYTE_LIMIT)) {
                assertEquals(
                    "Unused reservations must be returned",
                    MediaMetadataProbe.BYTE_LIMIT - 65536,
                    returned.count
                );
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void playingResolutionUsesSelectedTrackIdAndNeverFallsBackToFirstTrack() {
        org.videolan.libvlc.interfaces.IMedia.VideoTrack first = videoTrack(3, 854, 480);
        org.videolan.libvlc.interfaces.IMedia.VideoTrack selected = videoTrack(9, 1920, 1080);
        org.videolan.libvlc.interfaces.IMedia media = new org.videolan.libvlc.stubs.StubMedia(
            null,
            android.net.Uri.EMPTY
        ) {
            public int getTrackCount() {
                return 2;
            }

            public Track getTrack(int index) {
                return index == 0 ? first : selected;
            }
        };
        assertSame(selected, VodQuality.selectedVideo(media, 9));
        assertSame(first, VodQuality.selectedVideo(media, 3));
        assertNull(VodQuality.selectedVideo(media, 99));
        assertNull(VodQuality.selectedVideo(media, -1));
    }

    private org.videolan.libvlc.interfaces.IMedia.VideoTrack videoTrack(int id, int width, int height) {
        return new org.videolan.libvlc.interfaces.IMedia.VideoTrack(
            "h264",
            "h264",
            0,
            id,
            0,
            0,
            0,
            "",
            "",
            height,
            width,
            1,
            1,
            25,
            1,
            0,
            0
        );
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
