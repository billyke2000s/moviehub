package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds a live channel's link by looking up several of its copies at once (LiveRacePlan has the
 * rules) and hands the chosen one to the player already resolved, so starting it or moving to a
 * standby needs no new lookup. Copies found but never played are closed (a relay holds a local
 * server). Every callback runs on the main thread; a cancelled race calls nothing.
 */
final class LiveRace {

    interface Listener {
        /** Play copy {@code index}, whose link is {@code locator}, already resolved. */
        void play(int index, String locator, LiveStream.Resolution source);

        /** No copy is playing yet and the next one is still being looked up. */
        void stillLooking();

        /** Every copy failed. */
        void exhausted();
    }

    private static final ExecutorService lookups = Executors.newFixedThreadPool(LiveRacePlan.PARALLEL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final ChannelDatabase db;
    private final List<ChannelDatabase.Row> copies;
    private final boolean allowHttp;
    private final Listener listener;
    private final LiveRacePlan plan;
    private final Map<Integer, LiveStream.Resolution> ready = new HashMap<>();
    private final Map<Integer, String> locators = new HashMap<>();
    private final Map<Integer, NativeHttp> requests = new HashMap<>();
    private final Map<Integer, Future<?>> tasks = new HashMap<>();
    private final Runnable windowClosed = this::choose;
    private boolean cancelled,
        choosing = true;
    private int current = -1;

    /** {@code candidates} are indices into {@code copies}, in the guide's copy order. */
    LiveRace(
        Context context,
        ChannelDatabase db,
        List<ChannelDatabase.Row> copies,
        int[] candidates,
        boolean allowHttp,
        Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.copies = copies;
        this.allowHttp = allowHttp;
        this.listener = listener;
        int[] quality = new int[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            quality[i] = SourceRanking.resolution(copies.get(candidates[i]).name);
        }
        plan = new LiveRacePlan(candidates, quality, SystemClock.elapsedRealtime());
    }

    void start() {
        launch();
        main.postDelayed(windowClosed, Math.max(0, plan.windowEnds() - SystemClock.elapsedRealtime()));
    }

    /** Whether another copy could still be tried after the one playing. */
    boolean hasAlternative() {
        return !cancelled && plan.remaining();
    }

    /** The copy playing did not work: play the next one found, or wait for one. */
    void next() {
        if (cancelled) return;
        if (current >= 0) plan.failed(current);
        current = -1;
        choosing = true;
        launch();
        choose();
        if (choosing && !cancelled) listener.stillLooking();
    }

    /** Stops every lookup and closes the links found but not played. */
    void cancel() {
        if (cancelled) return;
        cancelled = true;
        main.removeCallbacks(windowClosed);
        for (NativeHttp http : requests.values()) http.cancel();
        for (Future<?> task : tasks.values()) task.cancel(true);
        for (LiveStream.Resolution source : ready.values()) close(source);
        requests.clear();
        tasks.clear();
        ready.clear();
    }

    private void launch() {
        for (int index : plan.launch()) lookUp(index);
    }

    private void lookUp(int index) {
        ChannelDatabase.Row row = copies.get(index);
        NativeHttp http = new NativeHttp();
        requests.put(index, http);
        tasks.put(
            index,
            lookups.submit(() -> {
                String locator = null;
                LiveStream.Resolution source = null;
                Exception failure = null;
                try {
                    locator = Playlist.networkUrl(db.url(row.id, row.key), allowHttp);
                    // Plain stream addresses need no lookup; provider channels do (as ExoLive.open).
                    source =
                        PlutoSource.nativeUrl(locator) || EngineSources.locator(locator)
                            ? LiveStream.resolve(context, locator, http, null)
                            : new LiveStream.Resolution(null, locator, null);
                } catch (Exception e) {
                    failure = e;
                }
                String found = locator;
                LiveStream.Resolution result = source;
                Exception error = failure;
                main.post(() -> arrived(index, found, result, error));
            })
        );
    }

    private void arrived(int index, String locator, LiveStream.Resolution source, Exception failure) {
        requests.remove(index);
        tasks.remove(index);
        if (cancelled) {
            close(source);
            return;
        }
        if (failure != null || source == null) {
            if (failure != null) Diagnostics.recovered("Live source race lookup", failure);
            plan.failed(index);
        } else {
            locators.put(index, locator);
            ready.put(index, source);
            plan.resolved(index);
        }
        launch();
        choose();
    }

    private void choose() {
        if (cancelled || !choosing) return;
        int index = plan.pick(SystemClock.elapsedRealtime());
        if (index >= 0) {
            choosing = false;
            current = index;
            main.removeCallbacks(windowClosed);
            listener.play(index, locators.get(index), ready.remove(index));
        } else if (!plan.remaining()) {
            choosing = false;
            listener.exhausted();
        }
    }

    private static void close(LiveStream.Resolution source) {
        if (source != null && source.relay != null) source.relay.close();
    }
}
