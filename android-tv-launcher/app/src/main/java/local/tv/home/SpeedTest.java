package local.tv.home;

import java.io.InputStream;

/**
 * The internet speed test in Troubleshooting: downloads from Cloudflare's public speed-test service
 * for up to {@link #MAX_MS} or {@link #MAX_BYTES}, whichever comes first. Runs only when asked; it
 * sends nothing about the viewer or their accounts. Plain Java (the arithmetic is in the core
 * checks).
 */
final class SpeedTest {

    static final String URL = "https://speed.cloudflare.com/__down?bytes=";
    static final long MAX_BYTES = 50L * 1024 * 1024;
    static final long MAX_MS = 10_000;

    static final class Result {

        /** Megabits per second, and how long the server took to start answering. */
        final float mbps;
        final long answerMs;
        final long bytes;

        Result(float mbps, long answerMs, long bytes) {
            this.mbps = mbps;
            this.answerMs = answerMs;
            this.bytes = bytes;
        }
    }

    private SpeedTest() {}

    /** Megabits per second for {@code bytes} in {@code millis}; 0 when nothing was timed. */
    static float mbps(long bytes, long millis) {
        if (bytes <= 0 || millis <= 0) return 0;
        return (bytes * 8f) / (millis * 1000f);
    }

    /** Runs the test on this thread (not the screen's). */
    static Result run() throws Exception {
        long asked = System.nanoTime();
        try (InputStream in = Network.open(URL + MAX_BYTES, null, MAX_BYTES + 1, false)) {
            long answered = System.nanoTime();
            byte[] buffer = new byte[64 * 1024];
            long bytes = 0;
            long deadline = answered + MAX_MS * 1_000_000L;
            int n;
            while (System.nanoTime() < deadline && (n = in.read(buffer)) != -1) bytes += n;
            long millis = (System.nanoTime() - answered) / 1_000_000L;
            return new Result(mbps(bytes, millis), (answered - asked) / 1_000_000L, bytes);
        }
    }
}
