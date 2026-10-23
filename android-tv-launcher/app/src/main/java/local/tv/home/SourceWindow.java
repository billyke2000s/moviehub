package local.tv.home;

import java.util.*;
import java.util.function.ToIntFunction;

/** Selection window starts at search entry, never restarts when another provider finishes. */
final class SourceWindow<T> {

    static final long WAIT_MS = 3000;
    private final long started;
    private final ToIntFunction<T> rank;
    private final Map<String, T> ready = new LinkedHashMap<>();
    private boolean cancelled;

    SourceWindow(long started, ToIntFunction<T> rank) {
        this.started = started;
        this.rank = rank;
    }

    synchronized boolean add(String key, T value) {
        if (cancelled || ready.containsKey(key)) return false;
        ready.put(key, value);
        return true;
    }

    synchronized List<T> snapshot() {
        List<T> result = new ArrayList<>(ready.values());
        result.sort(Comparator.comparingInt(rank).reversed());
        return result;
    }

    synchronized T pick(long now) {
        if (cancelled || now - started < WAIT_MS || ready.isEmpty()) return null;
        return snapshot().get(0);
    }

    synchronized void cancel() {
        cancelled = true;
        ready.clear();
    }
}
