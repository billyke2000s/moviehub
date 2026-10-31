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
        new String[] { "Paste your key" },
        new boolean[] { false },
        "Get a key",
        "https://www.themoviedb.org/settings/api",
        "",
        "Not a key"
    );

    private static final PhoneEntry.Page LOGIN = new PhoneEntry.Page(
        "Login",
        new String[] { "Server", "User", "Password" },
        new boolean[] { false, false, true },
        "",
        "",
        "Use your provider's details",
        "Fill in all three"
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
            values -> values[0].matches("[0-9a-fA-F]{32}"),
            values -> {
                got.set(values[0]);
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
        check(" abc+ ".equals(PhoneEntry.field("a=1&value=%20abc%2B%20", "value")), "decodes as typed");
        check(PhoneEntry.field("a=1&b=2", "value") == null, "missing field");
        check(PhoneEntry.field(null, "value") == null, "no body");
        check(PhoneEntry.field("f0=%zz", "value") == null, "bad escape");
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
        check(response.contains("name=\"f0\" type=\"text\""), "one text box");

        // Wrong method, malformed and oversized requests.
        response = send(uri, "PUT " + path + " HTTP/1.1\r\n\r\n");
        check(response.startsWith("HTTP/1.1 405"), "PUT refused");
        response = send(uri, "nonsense\r\n\r\n");
        check(response.startsWith("HTTP/1.1 400"), "malformed refused");
        response = post(uri, path, "f0=" + "a".repeat(9000));
        check(response.startsWith("HTTP/1.1 400"), "oversized refused");
        response = send(uri, "POST " + path + " HTTP/1.1\r\nContent-Length: -5\r\n\r\n");
        check(response.startsWith("HTTP/1.1 400"), "negative length refused");

        // An invalid value is refused on the phone and never reaches the TV.
        response = post(uri, path, "f0=not-a-key");
        check(response.startsWith("HTTP/1.1 200") && response.contains("Not a key"), "invalid shown");
        check(calls.get() == 0, "invalid not delivered");
        check(!entry.closed(), "still open after invalid");

        // A valid value (with spaces around it) is delivered once, then the page closes.
        response = post(uri, path, "f0=%20" + KEY + "%20");
        check(response.startsWith("HTTP/1.1 200") && response.contains("Sent to your TV"), "sent");
        check(calls.get() == 1 && KEY.equals(got.get()), "delivered trimmed, once");
        waitClosed(entry, 3000);
        check(entry.closed(), "closed after one value");
        check(get(uri, path) == null, "refuses connections after use");

        // Not on the home network: loopback stands in for a non-local client here.
        calls.set(0);
        PhoneEntry strict = start(got, calls, wanted, 60_000, false);
        URI strictUri = URI.create(strict.url());
        response = post(strictUri, strictUri.getPath(), "f0=" + KEY);
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
        for (int i = 1; i < PhoneEntry.MAX_POSTS; i++) post(guessUri, guessUri.getPath(), "f0=x" + i);
        check(!guesses.closed(), "open below the try limit");
        response = post(guessUri, guessUri.getPath(), "f0=last");
        check(response.contains("Too many tries"), "try limit message");
        waitClosed(guesses, 3000);
        check(guesses.closed() && calls.get() == 0, "closed at the try limit");

        // Several boxes: all required, passwords hidden and kept exactly as typed.
        AtomicReference<String[]> login = new AtomicReference<>();
        PhoneEntry several = new PhoneEntry(
            InetAddress.getLoopbackAddress(),
            LOGIN,
            values -> values[0].startsWith("http"),
            login::set,
            () -> true,
            60_000,
            true
        );
        URI loginUri = URI.create(several.url());
        response = get(loginUri, loginUri.getPath());
        check(response.contains("name=\"f2\" type=\"password\""), "password box hidden");
        check(response.contains("name=\"f1\" type=\"text\""), "user box");
        check(response.contains("Use your provider&#39;s details"), "note shown, escaped");
        response = post(loginUri, loginUri.getPath(), "f0=http%3A%2F%2Ftv.example%3A8080&f1=me");
        check(response.contains("Fill in all three") && login.get() == null, "missing box refused");
        response = post(loginUri, loginUri.getPath(), "f0=http%3A%2F%2Ftv.example&f1=me&f2=");
        check(response.contains("Fill in all three") && login.get() == null, "empty box refused");
        response = post(loginUri, loginUri.getPath(), "f0=ftp%3A%2F%2Fx&f1=me&f2=pw");
        check(response.contains("Fill in all three") && login.get() == null, "validator refuses");
        response = post(
            loginUri,
            loginUri.getPath(),
            "f0=+http%3A%2F%2Ftv.example%3A8080+&f1=+me+&f2=+p%26ss+"
        );
        check(response.contains("Sent to your TV"), "login sent");
        String[] values = login.get();
        check(values != null && values.length == 3, "three values");
        check("http://tv.example:8080".equals(values[0]) && "me".equals(values[1]), "text boxes trimmed");
        check(" p&ss ".equals(values[2]), "password kept exactly");
        waitClosed(several, 3000);
        check(several.closed(), "login page closed after use");

        System.out.println(checks + " phone entry checks passed");
    }
}
