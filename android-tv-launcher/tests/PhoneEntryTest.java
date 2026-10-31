package local.tv.home;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The one-use phone page: secret path, local clients, validation, one value, then closed. */
public final class PhoneEntryTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("Phone entry check " + checks + ": " + what);
    }

    private static final PhoneEntry.Page PAGE = new PhoneEntry.Page(
        "MovieHub <test>",
        "Paste your key",
        "Get a key",
        "https://www.themoviedb.org/settings/api",
        "Not a key"
    );

    private static final String KEY = "0123456789abcdef0123456789ABCDEF";

    private static PhoneEntry start(
        AtomicReference<String> got,
        AtomicInteger calls,
        AtomicBoolean wanted,
        long lifetime,
        boolean loopback
    ) throws Exception {
        return new PhoneEntry(
            InetAddress.getLoopbackAddress(),
            PAGE,
            value -> value.matches("[0-9a-fA-F]{32}"),
            value -> {
                got.set(value);
                calls.incrementAndGet();
            },
            wanted::get,
            lifetime,
            loopback
        );
    }

    /** Sends raw bytes; returns the whole response, or null when the connection is refused. */
    private static String send(URI uri, String request) {
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(URI uri, String path) {
        return send(uri, "GET " + path + " HTTP/1.1\r\nHost: tv\r\n\r\n");
    }

    private static String post(URI uri, String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return send(
            uri,
            "POST " +
            path +
            " HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " +
            bytes.length +
            "\r\n\r\n" +
            body
        );
    }

    private static void waitClosed(PhoneEntry entry, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (!entry.closed() && System.currentTimeMillis() < until) Thread.sleep(50);
    }

    public static void main(String[] args) throws Exception {
        // Form parsing and escaping.
        check("abc+".equals(PhoneEntry.field("a=1&value=%20abc%2B%20", "value")), "decodes and trims");
        check(PhoneEntry.field("a=1&b=2", "value") == null, "missing field");
        check(PhoneEntry.field(null, "value") == null, "no body");
        check(PhoneEntry.field("value=%zz", "value") == null, "bad escape");
        check(
            PhoneEntry.escape("<a href=\"x\">&'").equals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;"),
            "escape"
        );

        AtomicReference<String> got = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean wanted = new AtomicBoolean(true);
        PhoneEntry entry = start(got, calls, wanted, 60_000, true);
        URI uri = URI.create(entry.url());
        String path = uri.getPath();
        check(path.matches("/[0-9a-f]{32}"), "128-bit secret path");

        // Only the secret path answers.
        String response = get(uri, "/");
        check(response != null && response.startsWith("HTTP/1.1 404"), "root is 404");
        response = get(uri, "/" + "0".repeat(32));
        check(response.startsWith("HTTP/1.1 404"), "wrong token is 404");
        response = get(uri, path + "?x=1");
        check(response.startsWith("HTTP/1.1 200"), "form page");
        check(response.contains("<form method=\"post\">"), "has a form");
        check(response.contains("https://www.themoviedb.org/settings/api"), "help link");
        check(response.contains("MovieHub &lt;test&gt;"), "title escaped");
        check(response.contains("Cache-Control: no-store"), "not cached");
        check(!response.contains("Not a key"), "no error at first");

        // Wrong method, malformed and oversized requests.
        response = send(uri, "PUT " + path + " HTTP/1.1\r\n\r\n");
        check(response.startsWith("HTTP/1.1 405"), "PUT refused");
        response = send(uri, "nonsense\r\n\r\n");
        check(response.startsWith("HTTP/1.1 400"), "malformed refused");
        response = post(uri, path, "value=" + "a".repeat(9000));
        check(response.startsWith("HTTP/1.1 400"), "oversized refused");
        response = send(uri, "POST " + path + " HTTP/1.1\r\nContent-Length: -5\r\n\r\n");
        check(response.startsWith("HTTP/1.1 400"), "negative length refused");

        // An invalid value is refused on the phone and never reaches the TV.
        response = post(uri, path, "value=not-a-key");
        check(response.startsWith("HTTP/1.1 200") && response.contains("Not a key"), "invalid shown");
        check(calls.get() == 0, "invalid not delivered");
        check(!entry.closed(), "still open after invalid");

        // A valid value (with spaces around it) is delivered once, then the page closes.
        response = post(uri, path, "value=%20" + KEY + "%20");
        check(response.startsWith("HTTP/1.1 200") && response.contains("Sent to your TV"), "sent");
        check(calls.get() == 1 && KEY.equals(got.get()), "delivered trimmed, once");
        waitClosed(entry, 3000);
        check(entry.closed(), "closed after one value");
        check(get(uri, path) == null, "refuses connections after use");

        // Not on the home network: loopback stands in for a non-local client here.
        calls.set(0);
        PhoneEntry strict = start(got, calls, wanted, 60_000, false);
        URI strictUri = URI.create(strict.url());
        response = post(strictUri, strictUri.getPath(), "value=" + KEY);
        check(response.startsWith("HTTP/1.1 403"), "non-local client refused");
        check(calls.get() == 0, "nothing delivered from a refused client");
        check(strict.localClient(InetAddress.getByName("192.168.1.20")), "home network allowed");
        check(strict.localClient(InetAddress.getByName("10.0.0.5")), "10/8 allowed");
        check(strict.localClient(InetAddress.getByName("169.254.3.4")), "link-local allowed");
        check(!strict.localClient(InetAddress.getByName("8.8.8.8")), "public refused");
        check(!strict.localClient(InetAddress.getLoopbackAddress()), "loopback refused");
        strict.close();

        // Leaving the screen stops it.
        AtomicBoolean leaving = new AtomicBoolean(true);
        PhoneEntry left = start(got, calls, leaving, 60_000, true);
        leaving.set(false);
        waitClosed(left, 3000);
        check(left.closed(), "stops when the screen no longer wants it");

        // It expires on its own.
        PhoneEntry expiring = start(got, calls, wanted, 300, true);
        waitClosed(expiring, 3000);
        check(expiring.closed(), "expires");

        // Guessing on the phone is limited.
        calls.set(0);
        PhoneEntry guesses = start(got, calls, wanted, 60_000, true);
        URI guessUri = URI.create(guesses.url());
        for (int i = 1; i < PhoneEntry.MAX_POSTS; i++) post(guessUri, guessUri.getPath(), "value=x" + i);
        check(!guesses.closed(), "open below the try limit");
        response = post(guessUri, guessUri.getPath(), "value=last");
        check(response.contains("Too many tries"), "try limit message");
        waitClosed(guesses, 3000);
        check(guesses.closed() && calls.get() == 0, "closed at the try limit");

        System.out.println(checks + " phone entry checks passed");
    }
}
