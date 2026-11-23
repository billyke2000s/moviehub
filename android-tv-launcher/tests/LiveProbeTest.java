package local.tv.home;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** LiveProbe: a copy counts as working only when its playlist and first video data arrive. */
public final class LiveProbeTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        playlistParsing();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> agent = new AtomicReference<>(),
            referer = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/headers/")) {
                agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                referer.set(exchange.getRequestHeaders().getFirst("Referer"));
            }
            String body = null;
            int status = 200;
            if (path.endsWith("master.m3u8")) body =
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/media.m3u8\n";
            else if (path.endsWith("/media.m3u8")) body =
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n";
            else if (path.endsWith("/deadsegs.m3u8")) body = "#EXTM3U\n#EXTINF:6,\nmissing.ts\n";
            else if (path.endsWith("/empty.m3u8")) body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n";
            else if (path.endsWith("/notaplaylist.m3u8")) body = "<html>blocked</html>";
            else if (path.endsWith("/blank.ts")) body = "";
            else if (path.endsWith(".ts") && !path.endsWith("missing.ts")) body = "G".repeat(400);
            else status = 404;
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            check("media playlist and segment", LiveProbe.answers(base + "/live/media.m3u8", null, null));
            check("master, variant, segment", LiveProbe.answers(base + "/live/master.m3u8", null, null));
            check("plain stream", LiveProbe.answers(base + "/channel.ts", null, null));
            check("missing", !LiveProbe.answers(base + "/gone.m3u8", null, null));
            check("segments missing", !LiveProbe.answers(base + "/live/deadsegs.m3u8", null, null));
            check("playlist naming nothing", !LiveProbe.answers(base + "/live/empty.m3u8", null, null));
            check(
                "a page instead of a playlist",
                !LiveProbe.answers(base + "/notaplaylist.m3u8", null, null)
            );
            check("no bytes", !LiveProbe.answers(base + "/blank.ts", null, null));
            check("nothing listening", !LiveProbe.answers("http://127.0.0.1:1/x.m3u8", null, null));
            check("other schemes refused", !LiveProbe.answers("file:///etc/hosts", null, null));
            check(
                "with the player's headers",
                LiveProbe.answers(base + "/headers/media.m3u8", "Agent/1", "https://site.example/")
            );
            check("agent sent", "Agent/1".equals(agent.get()));
            check("referer sent", "https://site.example/".equals(referer.get()));
        } finally {
            server.stop(0);
        }
        System.out.println(checks + " live probe checks passed");
    }

    private static void playlistParsing() {
        check("media first segment", "a.ts".equals(LiveProbe.nextUri("#EXTM3U\n#EXTINF:4,\na.ts\nb.ts\n")));
        check("windows lines", "a.ts".equals(LiveProbe.nextUri("#EXTM3U\r\n#EXTINF:4,\r\n a.ts \r\n")));
        check(
            "master first variant",
            "hi/index.m3u8".equals(
                LiveProbe.nextUri(
                    "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhi/index.m3u8\n"
                )
            )
        );
        check("not a playlist", LiveProbe.nextUri("<html>") == null);
        check("names nothing", LiveProbe.nextUri("#EXTM3U\n#EXT-X-ENDLIST\n") == null);
        check("null", LiveProbe.nextUri(null) == null);
        check("master", LiveProbe.master("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nx\n"));
        check("media", !LiveProbe.master("#EXTM3U\n#EXTINF:4,\nx\n"));
        byte[] hls = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        check("playlist by text", LiveProbe.playlist("http://h/stream", hls));
        check("playlist by address", LiveProbe.playlist("http://h/a.M3U8?x=1", new byte[] { 'x' }));
        check("plain stream", !LiveProbe.playlist("http://h/a.ts", new byte[] { 0x47 }));
    }

    private static void check(String name, boolean value) {
        checks++;
        if (!value) throw new AssertionError("Live probe check " + checks + ": " + name);
    }
}
