package local.tv.home;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The internet speed test in Help and Engineer view: downloads from Cloudflare's public speed-test
 * service on {@link #CONNECTIONS} connections at once for {@link #MAX_MS}, ignores the first
 * {@link #WARMUP_MS} while the connections speed up, and reports the average over the rest. Runs
 * only when asked; it sends nothing about the viewer or their accounts. Plain Java (the arithmetic
 * is in the core checks).
 */
final class SpeedTest {

    static final String URL = "https://speed.cloudflare.com/__down?bytes=";
    /**
     * One request's size. Cloudflare refuses a single request of 100 MB or more (checked 23 Nov
     * 2026: 99,000,000 bytes answered, 100,000,000 refused with 403), so each connection repeats
     * requests of this size until the time is up.
     */
    static final long CHUNK_BYTES = 25L * 1024 * 1024;
    /** Parallel downloads: one connection alone rarely fills a fast line. */
    static final int CONNECTIONS = 4;
    /** Stop early after this much in total, to bound data use on very fast lines. */
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    static final long MAX_MS = 10_000;
    /** The start of the test, not counted in the average while connections speed up. */
    static final long WARMUP_MS = 1_000;

    static final class Result {

        /** Megabits per second, and how long the server took to start answering. */
        final float mbps;
        final long answerMs;
        final long bytes;
        final long pingMs;
        final long jitterMs;
        final int failedProbes;

        Result(float mbps, long answerMs, long bytes, long pingMs, long jitterMs, int failedProbes) {
            this.mbps = mbps;
            this.answerMs = answerMs;
            this.bytes = bytes;
            this.pingMs = pingMs;
            this.jitterMs = jitterMs;
            this.failedProbes = failedProbes;
        }
    }

    /**
     * The latest completed test while MovieHub is open, shared by Help and Engineer view so both
     * show the same measurement; null until one has run.
     */
    static volatile Result last;

    private SpeedTest() {}

    /**
     * The family wording for a result, e.g. "Excellent for films · 412 Mb/s download · 18 ms
     * response". The same measurement as Engineer view, without jitter and probe counts.
     */
    static String summary(Result r) {
        String rating = rating(r.mbps, r.pingMs, r.jitterMs);
        StringBuilder out = new StringBuilder();
        if (!"Unrated".equals(rating)) out.append(rating).append(" for films  ·  ");
        out.append(String.format(java.util.Locale.ROOT, "%.0f Mb/s download", Math.floor(r.mbps)));
        if (r.pingMs >= 0) out.append("  ·  ").append(r.pingMs).append(" ms response");
        return out.toString();
    }

    static String rating(float mbps, long ping, long jitter) {
        if (ping < 0 || jitter < 0) return "Unrated";
        if (mbps < 5 || (ping >= 0 && ping > 250) || (jitter >= 0 && jitter > 100)) return "Poor";
        if (mbps < 15 || (ping >= 0 && ping > 100) || (jitter >= 0 && jitter > 40)) return "Fair";
        if (mbps < 25 || (ping >= 0 && ping > 60) || (jitter >= 0 && jitter > 20)) return "Good";
        return "Excellent";
    }

    // HTTP latency is measured against the speed-test endpoint, not ICMP ping or an IPTV server.
    // One unscored probe first opens the connection (name lookup and secure handshake), then five
    // scored probes reuse it, so jitter is the line's variation rather than one slow first contact.
    private static long[] latency() {
        long[] values = new long[5];
        int count = 0;
        long changes = 0, previous = -1;
        for (int i = -1; i < 5; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(URL + "0&nonce=" + System.nanoTime()).openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                long start = System.nanoTime();
                // Read to the end and close without disconnecting, so the next probe reuses it.
                try (InputStream stream = connection.getInputStream()) { while (stream.read() != -1) {} }
                long sample = (System.nanoTime() - start) / 1_000_000L;
                if (i < 0) continue;
                values[count++] = sample;
                if (previous >= 0) changes += Math.abs(sample - previous);
                previous = sample;
            } catch (Exception e) {
                // A failed probe must not invalidate a successful download test.
                if (connection != null) connection.disconnect();
            }
        }
        if (count < 2) return new long[] {-1, -1, 5 - count};
        java.util.Arrays.sort(values, 0, count);
        return new long[] { values[count / 2], changes / (count - 1), 5 - count };
    }

    /** Megabits per second for {@code bytes} in {@code millis}; 0 when nothing was timed. */
    static float mbps(long bytes, long millis) {
        if (bytes <= 0 || millis <= 0) return 0;
        return (bytes * 8f) / (millis * 1000f);
    }

    /**
     * The steady speed: what arrived after the warm-up over the time after it. Falls back to the
     * whole test when nothing was timed after the warm-up (a very short test).
     */
    static float steadyMbps(long warmBytes, long warmMs, long totalBytes, long totalMs) {
        float steady = mbps(totalBytes - warmBytes, totalMs - warmMs);
        return steady > 0 ? steady : mbps(totalBytes, totalMs);
    }

    /** Runs the test on this thread (not the screen's). */
    static Result run() throws Exception {
        long[] latency = latency();
        final long started = System.nanoTime();
        final long deadline = started + MAX_MS * 1_000_000L;
        final AtomicLong total = new AtomicLong();
        final AtomicLong firstAnswer = new AtomicLong(-1);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        Thread[] workers = new Thread[CONNECTIONS];
        for (int i = 0; i < CONNECTIONS; i++) {
            workers[i] = new Thread(() -> {
                byte[] buffer = new byte[64 * 1024];
                while (System.nanoTime() < deadline && total.get() < MAX_BYTES) {
                    String url = URL + CHUNK_BYTES + "&nonce=" + System.nanoTime();
                    try (InputStream in = Network.open(url, null, CHUNK_BYTES + 1, false)) {
                        firstAnswer.compareAndSet(-1, System.nanoTime());
                        int n;
                        while (System.nanoTime() < deadline && total.get() < MAX_BYTES && (n = in.read(buffer)) != -1)
                            total.addAndGet(n);
                    } catch (Exception e) {
                        // One connection failing must not end the others' measurement.
                        failure.compareAndSet(null, e);
                        return;
                    }
                }
            }, "speed-test-" + i);
            workers[i].setDaemon(true);
            workers[i].start();
        }
        Thread.sleep(WARMUP_MS);
        long warmBytes = total.get();
        long warmMs = (System.nanoTime() - started) / 1_000_000L;
        // A stalled read cannot hold the test past its time by more than a few seconds.
        for (Thread worker : workers)
            worker.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L + 3_000));
        long totalMs = (System.nanoTime() - started) / 1_000_000L;
        long bytes = total.get();
        // Nothing received is a failed test, never a "0 Mb/s" internet speed.
        if (bytes <= 0) {
            Exception why = failure.get();
            throw why != null ? why : new java.io.IOException("The speed test received no data");
        }
        long answered = firstAnswer.get();
        Result result = new Result(
            steadyMbps(warmBytes, warmMs, bytes, totalMs),
            answered < 0 ? -1 : (answered - started) / 1_000_000L,
            bytes,
            latency[0],
            latency[1],
            (int) latency[2]
        );
        last = result;
        return result;
    }
}
