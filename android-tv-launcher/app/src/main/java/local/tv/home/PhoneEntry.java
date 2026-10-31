package local.tv.home;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A one-use page on the home network for typing account details (a TMDB key, a playlist link, an
 * Xtream login) on a phone instead of with the remote. The TV shows its address as a QR code.
 *
 * <ul>
 *   <li>Listens only on the TV's own home-network address, on a random port.
 *   <li>Answers the secret path in the QR code, or the plain address after the 6-digit code shown
 *       on the TV, and only devices on the local network.
 *   <li>Closes after one accepted set of values, after {@code lifetimeMs}, after 20 tries, or when the screen
 *       that opened it no longer wants it.
 * </ul>
 *
 * Plain Java with no Android classes, so the core tests can run it.
 */
final class PhoneEntry implements Closeable {

    static final long LIFETIME_MS = 5 * 60 * 1000L;
    static final int MAX_REQUEST = 8 * 1024,
        MAX_POSTS = 20;

    /** What the phone page shows: one box per label, in order. */
    static final class Page {

        final String title, helpText, helpUrl, note, invalid;
        final String[] labels;
        /** Password boxes: hidden while typed and never trimmed (spaces can be part of them). */
        final boolean[] secret;

        Page(
            String title,
            String[] labels,
            boolean[] secret,
            String helpText,
            String helpUrl,
            String note,
            String invalid
        ) {
            this.title = title;
            this.labels = labels;
            this.secret = secret;
            this.helpText = helpText;
            this.helpUrl = helpUrl;
            this.note = note;
            this.invalid = invalid;
        }
    }

    private final ServerSocket server;
    private final String token, code;
    private final Page page;
    private final Predicate<String[]> valid;
    private final Consumer<String[]> received;
    private final BooleanSupplier wanted;
    private final boolean allowLoopback;
    private final long deadline;
    private volatile boolean closed;
    private int posts;

    /**
     * Starts listening on {@code address}. {@code valid} checks the typed values (one per box);
     * {@code received} gets them once (on this server's thread). {@code allowLoopback} is for tests.
     */
    PhoneEntry(
        InetAddress address,
        Page page,
        Predicate<String[]> valid,
        Consumer<String[]> received,
        BooleanSupplier wanted,
        long lifetimeMs,
        boolean allowLoopback
    ) throws IOException {
        server = new ServerSocket(0, 4, address);
        server.setSoTimeout(1000);
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        token = hex.toString();
        code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
        this.page = page;
        this.valid = valid;
        this.received = received;
        this.wanted = wanted;
        this.allowLoopback = allowLoopback;
        deadline = System.currentTimeMillis() + lifetimeMs;
        Thread thread = new Thread(this::serve, "PhoneEntry");
        thread.setDaemon(true);
        thread.start();
    }

    /** The address to put in the QR code. */
    String url() {
        return "http://" + address() + "/" + token;
    }

    /** The short address to type instead of scanning, such as 192.168.1.50:41234. */
    String address() {
        return server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
    }

    /** The 6-digit code that opens the page from the short address. */
    String code() {
        return code;
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            /* Already closed. */
        }
    }

    /**
     * The TV's home-network IPv4 address (Wi-Fi or Ethernet), or null when it has none. Only
     * private addresses count, so the page is never offered on a public or mobile-data address.
     */
    static InetAddress homeAddress() {
        try {
            InetAddress fallback = null;
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback() || face.isVirtual()) continue;
                String name = face.getName().toLowerCase(Locale.ROOT);
                for (InetAddress address : Collections.list(face.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return address;
                    if (fallback == null) fallback = address;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** Devices that may use the page: the home network only. */
    boolean localClient(InetAddress client) {
        if (client.isLoopbackAddress()) return allowLoopback;
        return client.isSiteLocalAddress() || client.isLinkLocalAddress();
    }

    private void serve() {
        try {
            while (!closed && System.currentTimeMillis() < deadline && wanted.getAsBoolean()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    handle(socket);
                } catch (SocketTimeoutException tick) {
                    /* Check the deadline and the screen again. */
                } catch (IOException e) {
                    if (closed) break;
                }
            }
        } finally {
            close();
        }
    }

    private void handle(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        if (!localClient(socket.getInetAddress())) {
            respond(out, 403, "Forbidden");
            return;
        }
        Request request = Request.read(socket.getInputStream());
        if (request == null) {
            respond(out, 400, "Bad request");
            return;
        }
        if (request.path.equals("/")) {
            unlock(out, request);
            return;
        }
        if (!request.path.equals("/" + token)) {
            respond(out, 404, "Not found");
            return;
        }
        if (request.method.equals("GET")) {
            respond(out, 200, form(""));
            return;
        }
        if (!request.method.equals("POST")) {
            respond(out, 405, "Method not allowed");
            return;
        }
        String[] values = new String[page.labels.length];
        boolean complete = true;
        for (int i = 0; i < values.length; i++) {
            String value = field(request.body, "f" + i);
            if (value != null && !page.secret[i]) value = value.trim();
            values[i] = value;
            if (value == null || value.isEmpty()) complete = false;
        }
        if (!complete || !valid.test(values)) {
            if (++posts >= MAX_POSTS) {
                respond(out, 200, done("Too many tries. Open the QR code again on the TV."));
                close();
                return;
            }
            respond(out, 200, form(page.invalid));
            return;
        }
        respond(out, 200, done("Sent to your TV. You can close this page."));
        close();
        received.accept(values);
    }

    /** The short address: asks for the TV's code, then shows the same page as the QR code. */
    private void unlock(OutputStream out, Request request) throws IOException {
        if (request.method.equals("GET")) {
            respond(out, 200, codePage(""));
            return;
        }
        if (!request.method.equals("POST")) {
            respond(out, 405, "Method not allowed");
            return;
        }
        String typed = field(request.body, "code");
        if (typed != null && typed.replaceAll("[\\s-]", "").equals(code)) {
            respond(out, 200, form(""));
            return;
        }
        if (++posts >= MAX_POSTS) {
            respond(out, 200, done("Too many tries. Open this screen again on the TV."));
            close();
            return;
        }
        respond(out, 200, codePage("That code is not right. Check the number on the TV."));
    }

    private String codePage(String error) {
        return shell(
            "<h1>" +
                escape(page.title) +
                "</h1><p>Enter the 6-digit code shown on the TV.</p>" +
                (error.isEmpty() ? "" : "<p class=\"error\">" + escape(error) + "</p>") +
                "<form method=\"post\" action=\"/\"><label>Code<input name=\"code\" inputmode=\"numeric\" " +
                "autocomplete=\"one-time-code\" maxlength=\"7\"></label>" +
                "<button type=\"submit\">Continue</button></form>"
        );
    }

    /** One value from an application/x-www-form-urlencoded body, as typed; null when absent. */
    static String field(String body, String name) {
        if (body == null) return null;
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            try {
                if (!URLDecoder.decode(pair.substring(0, equals), "UTF-8").equals(name)) continue;
                return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static String escape(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String shell(String body) {
        return (
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>MovieHub</title><style>" +
            "body{font-family:sans-serif;background:#101722;color:#f8f8fc;margin:0;padding:24px}" +
            "h1{font-size:22px}a{color:#8a9eff}" +
            "input{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:8px 0;" +
            "border-radius:8px;border:1px solid #445}" +
            "button{width:100%;font-size:18px;padding:12px;border:0;border-radius:8px;" +
            "background:#8a9eff;color:#101722;font-weight:bold}.error{color:#ffb4aa}" +
            "</style></head><body>" +
            body +
            "</body></html>"
        );
    }

    String form(String error) {
        StringBuilder boxes = new StringBuilder();
        for (int i = 0; i < page.labels.length; i++) boxes
            .append("<label>")
            .append(escape(page.labels[i]))
            .append("<input name=\"f")
            .append(i)
            .append("\" type=\"")
            .append(page.secret[i] ? "password" : "text")
            .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"></label>");
        return shell(
            "<h1>" +
                escape(page.title) +
                "</h1>" +
                (page.helpUrl.isEmpty()
                    ? ""
                    : "<p><a href=\"" +
                      escape(page.helpUrl) +
                      "\" target=\"_blank\" rel=\"noopener\">" +
                      escape(page.helpText) +
                      "</a></p>") +
                (page.note.isEmpty() ? "" : "<p>" + escape(page.note) + "</p>") +
                (error.isEmpty() ? "" : "<p class=\"error\">" + escape(error) + "</p>") +
                "<form method=\"post\" action=\"/" +
                token +
                "\">" +
                boxes +
                "<button type=\"submit\">Send to TV</button></form>"
        );
    }

    private String done(String message) {
        return shell("<h1>" + escape(page.title) + "</h1><p>" + escape(message) + "</p>");
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Error";
        String head =
            "HTTP/1.1 " +
            status +
            " " +
            reason +
            "\r\nContent-Type: " +
            (status == 200 ? "text/html" : "text/plain") +
            "; charset=utf-8\r\nContent-Length: " +
            bytes.length +
            "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** The parts of an HTTP request this page needs, read within {@link #MAX_REQUEST} bytes. */
    static final class Request {

        final String method, path, body;

        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int headerEnd = -1;
            byte[] buffer = new byte[1024];
            while (headerEnd < 0) {
                int count = in.read(buffer);
                if (count < 0) return null;
                bytes.write(buffer, 0, count);
                if (bytes.size() > MAX_REQUEST) return null;
                headerEnd = bytes.toString("ISO-8859-1").indexOf("\r\n\r\n");
            }
            String text = bytes.toString("ISO-8859-1");
            String[] lines = text.substring(0, headerEnd).split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return null;
            String path = first[1];
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            int length = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 0) continue;
                if (lines[i].substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        length = Integer.parseInt(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            if (length < 0 || headerEnd + 4 + length > MAX_REQUEST) return null;
            while (bytes.size() < headerEnd + 4 + length) {
                int count = in.read(buffer);
                if (count < 0) return null;
                bytes.write(buffer, 0, count);
                if (bytes.size() > MAX_REQUEST) return null;
            }
            byte[] all = bytes.toByteArray();
            String body = new String(all, headerEnd + 4, length, StandardCharsets.UTF_8);
            return new Request(first[0], path, body);
        }
    }
}
