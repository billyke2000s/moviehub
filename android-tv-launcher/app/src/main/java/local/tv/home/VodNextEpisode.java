package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One next-episode lookup/search, without playback or a second decoder. Owned by the player. */
final class VodNextEpisode implements AutoCloseable {

    private static VodNextEpisode handedOff;
    private static final Handler expiry = new Handler(Looper.getMainLooper());
    private final VlcPlaybackActivity activity;
    private final SeriesPlayback.Metadata metadata;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean started, closed, transferred, adopted;
    private String currentKey;
    private long preparedAt;
    volatile SeriesPlayback.Next next;
    volatile VodSearch search;
    SourceRepository repo;
    private Future<?> task;

    VodNextEpisode(VlcPlaybackActivity activity) {
        this(activity, null);
    }

    VodNextEpisode(VlcPlaybackActivity activity, SeriesPlayback.Metadata metadata) {
        this.activity = activity;
        this.metadata = metadata;
    }

    synchronized void prepare() {
        if (started || closed) return;
        String raw = activity.getIntent().getStringExtra("episodeMetadata");
        if (raw == null) return;
        started = true;
        preparedAt = android.os.SystemClock.elapsedRealtime();
        Context app = activity.getApplicationContext();
        task = worker.submit(() -> {
            try {
                JSONObject current = new JSONObject(raw);
                currentKey = current.optString("episodeKey");
                if (currentKey.isEmpty()) return;
                Secrets secrets = new Secrets(app);
                MediaRepository media = new MediaRepository(secrets);
                SeriesPlayback.Next found = SeriesPlayback.resolve(
                    current,
                    current.getInt("season"),
                    current.getInt("episode"),
                    metadata == null ? media::tmdb : metadata,
                    LocalDate.now(ZoneOffset.UTC).toString()
                );
                synchronized (this) {
                    if (closed || found == null) return;
                    next = found;
                    // Season packs already carry their exact next file; no provider search is needed.
                    if (
                        SourcesScreen.Binge.files != null &&
                        AutoPlayback.pick(
                            SourcesScreen.Binge.files,
                            found.season,
                            found.episode,
                            current.optString("name")
                        ) >= 0
                    ) return;
                    repo = new SourceRepository(secrets);
                    search = new VodSearch(
                        app,
                        FreeVodScreen.query(
                            SeriesPlayback.imdb(found.metadata),
                            found.season,
                            found.episode,
                            found.metadata.optString("name"),
                            found.metadata,
                            app.getSharedPreferences("ui", 0).getBoolean("http", false)
                        ),
                        repo,
                        secrets.has("premiumize"),
                        app.getSharedPreferences("ui", 0).getInt(AutoPlayback.QUALITY, 1080),
                        false
                    );
                }
            } catch (Exception error) {
                Diagnostics.recovered("Preparing next episode sources", error);
            } finally {
                worker.shutdown();
            }
        });
    }

    synchronized void handoff() {
        if (closed || next == null) return;
        transferred = true;
        synchronized (VodNextEpisode.class) {
            if (handedOff != null) handedOff.dispose();
            handedOff = this;
        }
        // An interrupted Activity transition cannot retain a search indefinitely.
        expiry.postDelayed(() -> {
            synchronized (VodNextEpisode.class) {
                if (handedOff == this) handedOff = null;
                if (!adopted) dispose();
            }
        }, 60_000);
    }

    synchronized void claim() {
        adopted = true;
    }

    static synchronized VodNextEpisode take(JSONObject current) {
        VodNextEpisode prepared = handedOff;
        handedOff = null;
        if (prepared == null) return null;
        if (
            current == null ||
            !current.optString("episodeKey").equals(prepared.currentKey) ||
            android.os.SystemClock.elapsedRealtime() - prepared.preparedAt > 5 * 60_000L
        ) {
            prepared.dispose();
            return null;
        }
        return prepared;
    }

    private synchronized void dispose() {
        closed = true;
        if (task != null) task.cancel(true);
        if (search != null) search.close();
        worker.shutdownNow();
    }

    @Override
    public synchronized void close() {
        if (!transferred) dispose();
    }
}
