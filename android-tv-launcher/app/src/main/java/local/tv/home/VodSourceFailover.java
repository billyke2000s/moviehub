package local.tv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On-demand source failover policy. No dependency on LiveZapper or live-TV thresholds.
 * The caller owns player lifecycle, buffering callbacks and position restoration.
 */
final class VodSourceFailover {
    static final long STALL_MS = 4000L;
    static final long WINDOW_MS = 60000L;
    static final int STALLS_PER_WINDOW = 2;

    static final class Source {
        final String url;
        final String label;
        final String provider;
        Source(String url, String label, String provider) {
            this.url = url;
            this.label = label;
            this.provider = provider;
        }
    }

    private final List<Source> sources = new ArrayList<>();
    private final Set<Integer> failed = new HashSet<>();
    private final List<Long> recentStalls = new ArrayList<>();
    private int current = -1;
    private long bufferingSince = -1;
    private boolean hasPlayed;
    private boolean seeking;
    private boolean userPaused;
    private String pinnedProvider;

    void setSources(List<Source> ordered) {
        sources.clear();
        if (ordered != null) for (Source source : ordered) {
            if (source != null && source.url != null && !source.url.trim().isEmpty()) {
                boolean duplicate = false;
                for (Source existing : sources) if (existing.url.equals(source.url)) {
                    duplicate = true;
                    break;
                }
                if (!duplicate) sources.add(source);
            }
        }
        failed.clear();
        recentStalls.clear();
        current = sources.isEmpty() ? -1 : 0;
        resetPlayback();
        pinnedProvider = null;
    }

    List<Source> sources() { return Collections.unmodifiableList(sources); }
    Source current() { return current < 0 ? null : sources.get(current); }
    int currentIndex() { return current; }

    /** A user-selected Premiumize stream must not silently fall back to another provider. */
    boolean select(int index, boolean pinProvider) {
        if (index < 0 || index >= sources.size()) return false;
        current = index;
        failed.remove(index);
        pinnedProvider = pinProvider ? sources.get(index).provider : null;
        recentStalls.clear();
        resetPlayback();
        return true;
    }

    void onPlaying() {
        hasPlayed = true;
        bufferingSince = -1;
    }

    void setSeeking(boolean value) {
        seeking = value;
        if (value) bufferingSince = -1;
    }

    void setUserPaused(boolean value) {
        userPaused = value;
        if (value) bufferingSince = -1;
    }

    /** Call only on a transition into buffering, not on every percentage update. */
    boolean onBufferingStarted(long nowMs) {
        if (!hasPlayed || seeking || userPaused || bufferingSince >= 0) return false;
        bufferingSince = nowMs;
        recentStalls.removeIf(at -> nowMs - at > WINDOW_MS);
        recentStalls.add(nowMs);
        return recentStalls.size() >= STALLS_PER_WINDOW;
    }

    boolean isStalled(long nowMs) {
        return bufferingSince >= 0 && !seeking && !userPaused
                && nowMs - bufferingSince >= STALL_MS;
    }

    void onBufferingEnded() { bufferingSince = -1; }

    /** Return the next eligible source after marking the current one as failed. */
    Source failover() {
        if (current < 0) return null;
        failed.add(current);
        for (int i = 0; i < sources.size(); i++) {
            if (i == current || failed.contains(i)) continue;
            Source candidate = sources.get(i);
            if (pinnedProvider != null && !pinnedProvider.equals(candidate.provider)) continue;
            current = i;
            recentStalls.clear();
            resetPlayback();
            return candidate;
        }
        bufferingSince = -1;
        return null;
    }

    private void resetPlayback() {
        hasPlayed = false;
        seeking = false;
        userPaused = false;
        bufferingSince = -1;
    }
}
