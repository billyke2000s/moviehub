package local.tv.home;

import android.content.Context;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** One bounded search continues collecting backups after the first stream has started. */
final class VodSearch implements AutoCloseable {

    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger(1);
    private final SourceWindow<SourceRepository.Source> window;
    private final long deadline;
    private final List<java.util.function.Consumer<SourceRepository.Source>> observers = new ArrayList<>();
    private static volatile VodSearch current;

    VodSearch(Context context, JSONObject query, SourceRepository repo, boolean premiumize, int quality) {
        VodSearch previous = current;
        if (previous != null) previous.close();
        current = this;
        long started = SystemClock.elapsedRealtime();
        deadline = started + 120000;
        pending.set(premiumize ? 2 : 1);
        try {
            query.put("maxQuality", quality);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid source quality", error);
        }
        window = new SourceWindow<>(started, source ->
            SourceRanking.readyRank(
                source.ranking == null ? source.title : source.ranking,
                quality,
                source.probeMbps,
                source.responseMs
            )
        );
        Context app = context.getApplicationContext();
        // Runtime extraction and imports must never hold the UI thread.
        workers.submit(() -> {
            try {
                for (String id : FreeVodProviders.ids(app)) {
                    if (cancelled.get()) break;
                    pending.incrementAndGet();
                    workers.submit(() -> {
                        try {
                            if (!expired()) FreeVodProviders.search(app, id, query, cancelled, this::found);
                        } catch (Exception error) {
                            Diagnostics.recovered("Free provider search", error);
                        } finally {
                            if (pending.decrementAndGet() == 0) workers.shutdown();
                        }
                    });
                }
            } catch (Exception error) {
                Diagnostics.recovered("Loading free providers", error);
            } finally {
                pending.decrementAndGet();
                if (pending.get() == 0) workers.shutdown();
            }
        });
        if (premiumize) {
            workers.submit(() -> {
                try {
                    List<SourceRepository.Source> sources = repo.torrentio(
                        query.getString("imdb"),
                        query.getInt("season"),
                        query.getInt("episode")
                    );
                    sources.sort(
                        Comparator.comparingInt((SourceRepository.Source source) ->
                            SourceRanking.rank(source.title, quality)
                        ).reversed()
                    );
                    int attempted = 0;
                    for (SourceRepository.Source source : sources) {
                        if (expired() || attempted >= 5) break;
                        if (source.cache != SourceRepository.Cache.CACHED) continue;
                        attempted++;
                        JSONArray files = repo.direct(source);
                        int pick = AutoPlayback.pick(
                            files,
                            query.getInt("season"),
                            query.getInt("episode"),
                            query.getString("title")
                        );
                        if (pick >= 0) found(
                            new SourceRepository.Source(source.title, source.uri, files, new JSONObject())
                        );
                    }
                } catch (Exception error) {
                    Diagnostics.recovered("Optional cloud search", error);
                } finally {
                    if (pending.decrementAndGet() == 0) workers.shutdown();
                }
            });
        }
    }

    private boolean expired() {
        if (SystemClock.elapsedRealtime() >= deadline) close();
        return cancelled.get();
    }

    private synchronized void found(SourceRepository.Source source) {
        if (expired() || !window.add(source.uri, source)) return;
        for (var observer : observers) observer.accept(source);
    }

    synchronized void observe(java.util.function.Consumer<SourceRepository.Source> observer) {
        for (var source : snapshot()) observer.accept(source);
        if (!cancelled.get()) observers.add(observer);
    }

    List<SourceRepository.Source> snapshot() {
        return window.snapshot();
    }

    SourceRepository.Source selected() {
        return window.pick(SystemClock.elapsedRealtime());
    }

    boolean finished() {
        return expired() || pending.get() == 0;
    }

    public synchronized void close() {
        cancelled.set(true);
        observers.clear();
        workers.shutdownNow();
        if (current == this) current = null;
    }
}
