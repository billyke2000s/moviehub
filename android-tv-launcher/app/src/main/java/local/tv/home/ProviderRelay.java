package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Session-scoped loopback HLS transport for headers LibVLC cannot send itself. */
final class ProviderRelay implements AutoCloseable {

    private final ServerSocket server;
    private final String secret = UUID.randomUUID().toString();
    private final Map<String, String> targets = new LinkedHashMap<>();
    private final Set<String> playlists = new HashSet<>();
    private final Map<String, String> headers;
    private final String credentialAuthority;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        2,
        2,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4)
    );
    private volatile boolean closed;

    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws Exception;
    }

    /** Instrumentation fixture only; never assigned by app code. */
    static ConnectionFactory testConnections;
    final String url;

    ProviderRelay(EngineSources.Stream stream) throws Exception {
        credentialAuthority = URI.create(stream.url).getAuthority();
        headers = new LinkedHashMap<>();
        for (Iterator<String> keys = stream.headers.keys(); keys.hasNext(); ) {
            String key = keys.next(),
                value = stream.headers.getString(key);
            if (
                !key.matches("[A-Za-z0-9-]{1,80}") ||
                value.length() > 8192 ||
                value.contains("\r") ||
                value.contains("\n")
            ) throw new IOException("Invalid stream header");
            if (
                Arrays.asList("host", "connection", "content-length", "transfer-encoding").contains(
                    key.toLowerCase(Locale.ROOT)
                )
            ) throw new IOException("Restricted stream header");
            headers.put(key, value);
        }
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        url = register(stream.url, true);
        Thread accept = new Thread(() -> {
            while (!closed)
                try {
                    Socket client = server.accept();
                    clients.add(client);
                    try {
                        workers.execute(() -> serve(client));
                    } catch (RejectedExecutionException e) {
                        clients.remove(client);
                        client.close();
                    }
                } catch (IOException e) {
                    if (!closed) close();
                }
        }, "provider-relay");
        accept.setDaemon(true);
        accept.start();
    }

    static boolean needed(EngineSources.Stream stream) {
        for (Iterator<String> keys = stream.headers.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (
                !key.equalsIgnoreCase("User-Agent") &&
                !key.equalsIgnoreCase("Referer") &&
                !key.equalsIgnoreCase("Accept")
            ) return true;
        }
        return false;
    }

    private synchronized String register(String address, boolean playlist) throws IOException {
        String safe = ProviderUrls.playback(address);
        String token = null;
        for (Map.Entry<String, String> entry : targets.entrySet())
            if (entry.getValue().equals(safe)) {
                token = entry.getKey();
                break;
            }
        if (token == null) {
            // Live playlists rotate indefinitely; evict old grants rather than grow with programme length.
            if (targets.size() >= 2048) {
                String oldest = null;
                for (String key : targets.keySet())
                    if (!playlists.contains(key)) {
                        oldest = key;
                        break;
                    }
                if (oldest == null) throw new IOException("Playlist grant limit");
                targets.remove(oldest);
            }
            token = UUID.randomUUID().toString() + (playlist ? ".m3u8" : ".bin");
            targets.put(token, safe);
        }
        if (playlist) playlists.add(token);
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + secret + "/" + token;
    }

    private static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1 && ch != '\n') {
            if (line.length() >= 8192) throw new IOException("Relay line limit");
            if (ch != '\r') line.append((char) ch);
        }
        return ch == -1 && line.length() == 0 ? null : line.toString();
    }

    private void serve(Socket client) {
        HttpURLConnection upstream = null;
        try (Socket socket = client) {
            socket.setSoTimeout(10000);
            BufferedReader request = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
            );
            String first = readLine(request);
            if (
                first == null || first.length() > 2048 || !first.startsWith("GET /" + secret + "/")
            ) throw new IOException("Invalid relay request");
            String path = first.split(" ")[1],
                token = path.substring(("/" + secret + "/").length()),
                target;
            synchronized (this) {
                target = targets.get(token);
            }
            if (target == null) throw new IOException("Unknown relay grant");
            String range = null,
                line;
            int total = first.length();
            while ((line = readLine(request)) != null && !line.isEmpty()) {
                if ((total += line.length()) > 8192) throw new IOException("Relay headers limit");
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                    range = line.substring(6).trim();
                    if (!range.matches("bytes=[0-9]{0,15}-[0-9]{0,15}")) throw new IOException(
                        "Invalid byte range"
                    );
                }
            }
            URI current = URI.create(target);
            String authority = current.getAuthority();
            for (int hop = 0; hop < 5; hop++) {
                if (closed) throw new IOException("Closed relay");
                ProviderBroker.publicHost(current.getHost());
                upstream =
                    testConnections == null
                        ? (HttpURLConnection) current.toURL().openConnection()
                        : testConnections.open(current);
                active.add(upstream);
                upstream.setConnectTimeout(8000);
                upstream.setReadTimeout(8000);
                upstream.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    boolean sensitive =
                        header.getKey().equalsIgnoreCase("Authorization") ||
                        header.getKey().equalsIgnoreCase("Cookie");
                    if (
                        !sensitive || credentialAuthority.equalsIgnoreCase(current.getAuthority())
                    ) upstream.setRequestProperty(header.getKey(), header.getValue());
                }
                if (range != null) upstream.setRequestProperty("Range", range);
                int status = upstream.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = upstream.getHeaderField("Location");
                    if (next == null) throw new IOException("Missing stream redirect");
                    URI resolved = current.resolve(next);
                    current = URI.create(ProviderUrls.playback(resolved.toString()));
                    active.remove(upstream);
                    upstream.disconnect();
                    upstream = null;
                    continue;
                }
                if (status != 200 && status != 206) throw new IOException("Upstream stream unavailable");
                OutputStream out = socket.getOutputStream();
                try (PushbackInputStream in = new PushbackInputStream(upstream.getInputStream(), 1024)) {
                    byte[] prefix = new byte[10];
                    int n = 0,
                        sniffCount;
                    while (n < prefix.length && (sniffCount = in.read(prefix, n, prefix.length - n)) != -1)
                        n += sniffCount;
                    if (n > 0) in.unread(prefix, 0, n);
                    String signature = new String(prefix, 0, n, StandardCharsets.UTF_8);
                    boolean playlist =
                        signature.startsWith("#EXTM3U") || signature.startsWith("\uFEFF#EXTM3U");
                    byte[] head = new byte[ProviderSegments.HEAD];
                    int held = 0,
                        skip = 0;
                    if (!playlist && range == null) {
                        while (held < head.length) {
                            int count = in.read(head, held, head.length - held);
                            if (count < 0) break;
                            held += count;
                        }
                        skip = ProviderSegments.prefix(head, held);
                    }
                    if (playlist) synchronized (this) {
                        playlists.add(token);
                    }
                    String type = playlist
                        ? "application/vnd.apple.mpegurl"
                        : skip > 0 || (held > 0 && head[0] == 0x47)
                          ? "video/mp2t"
                          : upstream.getContentType();
                    if (type == null || type.contains("\r") || type.contains("\n")) type =
                        "application/octet-stream";
                    String extra = "";
                    String contentRange = upstream.getHeaderField("Content-Range");
                    if (
                        status == 206 && contentRange != null && contentRange.matches("bytes [0-9-]+/[0-9*]+")
                    ) extra = "Content-Range: " + contentRange + "\r\n";
                    out.write(
                        (
                            "HTTP/1.1 " +
                            status +
                            " OK\r\nContent-Type: " +
                            type +
                            "\r\nCache-Control: no-store\r\nConnection: close\r\n" +
                            extra +
                            "\r\n"
                        ).getBytes(StandardCharsets.US_ASCII)
                    );
                    if (playlist) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int count;
                        while ((count = in.read(buf)) != -1) {
                            if (bytes.size() + count > 1024 * 1024) throw new IOException("Playlist limit");
                            bytes.write(buf, 0, count);
                        }
                        out.write(
                            rewrite(bytes.toString(StandardCharsets.UTF_8.name()), current).getBytes(
                                StandardCharsets.UTF_8
                            )
                        );
                    } else {
                        if (held > skip) out.write(head, skip, held - skip);
                        byte[] buf = new byte[32768];
                        int count;
                        while (!closed && (count = in.read(buf)) != -1) out.write(buf, 0, count);
                    }
                    out.flush();
                }
                return;
            }
            throw new IOException("Stream redirect limit");
        } catch (Exception e) {
            if (!closed) Diagnostics.recovered("Provider relay", e);
        } finally {
            clients.remove(client);
            if (upstream != null) {
                active.remove(upstream);
                upstream.disconnect();
            }
        }
    }

    String rewrite(String playlist, URI base) throws IOException {
        StringBuilder result = new StringBuilder();
        Pattern attributes = Pattern.compile("URI=\"([^\"]+)\"");
        for (String line : playlist.split("\\r?\\n")) {
            if (!line.startsWith("#") && !line.trim().isEmpty()) line = register(
                base.resolve(line.trim()).toString(),
                line.contains(".m3u8")
            );
            else {
                Matcher m = attributes.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (m.find())
                    m.appendReplacement(
                        rewritten,
                        Matcher.quoteReplacement(
                            "URI=\"" +
                                register(base.resolve(m.group(1)).toString(), m.group(1).contains(".m3u8")) +
                                "\""
                        )
                    );
                m.appendTail(rewritten);
                line = rewritten.toString();
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {}
        for (HttpURLConnection c : active) c.disconnect();
        active.clear();
        for (Socket s : clients)
            try {
                s.close();
            } catch (IOException ignored) {}
        clients.clear();
        workers.shutdownNow();
        synchronized (this) {
            targets.clear();
            playlists.clear();
        }
    }
}
