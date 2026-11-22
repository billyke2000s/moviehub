package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Another copy of the channel, loading and playing silently in a second player with no picture,
 * while the copy on screen carries on. Only once it has really played ({@link LiveStandbyCheck})
 * is it reported ready, and {@link ExoLive#promote} then moves the picture and sound to it with no
 * gap. If it fails or takes too long it is closed and nothing changes on screen. Main thread only.
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoStandby {

    interface Callback {
        void ready(ExoStandby standby);

        void failed(ExoStandby standby, String reason);
    }

    static final long POLL_MS = 250;

    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The copy's address, as ExoLive plays it. */
    final String locator;
    private final LiveStream.Resolution source;
    private final Callback callback;
    private final LiveStandbyCheck check;
    private final Runnable poll = this::poll;
    private ExoPlayer player;
    private boolean finished, taken;
    private final Player.Listener errors = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            fail("could not be played (" + error.getErrorCodeName() + ")");
        }
    };

    ExoStandby(Context context, String locator, LiveStream.Resolution source, Callback callback) {
        this.locator = locator;
        this.source = source;
        this.callback = callback;
        EngineSources.Stream stream = source.provider;
        // The same address and headers as ExoLive.playResolved.
        String address = source.relay == null ? source.url : source.relay.url;
        String userAgent =
            stream != null
                ? stream.userAgent
                : PlutoSource.nativeUrl(locator)
                  ? PlutoSource.USER_AGENT
                  : null;
        // No audio focus, so the player on screen keeps its sound; silent until it takes over.
        player = ExoLive.newPlayer(context, false);
        player.setVolume(0f);
        player.addListener(errors);
        ExoLive.applyLanguages(player);
        player.setMediaSource(
            ExoLive.liveSource(locator, address, userAgent, stream == null ? null : stream.referer, false)
        );
        player.prepare();
        player.play();
        check = new LiveStandbyCheck(SystemClock.elapsedRealtime());
        main.postDelayed(poll, POLL_MS);
    }

    private void poll() {
        if (finished || player == null) return;
        int verdict = check.sample(
            SystemClock.elapsedRealtime(),
            player.isPlaying(),
            player.getCurrentPosition()
        );
        if (verdict == LiveStandbyCheck.READY) {
            finished = true;
            callback.ready(this);
        } else if (verdict == LiveStandbyCheck.FAILED) fail(
            "did not load within " + LiveStandbyCheck.TIMEOUT_MS / 1000 + " s"
        );
        else main.postDelayed(poll, POLL_MS);
    }

    private void fail(String reason) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(poll);
        callback.failed(this, reason);
    }

    /** Hands the playing player over to ExoLive; its link (and relay) go with it. */
    ExoPlayer take() {
        finished = taken = true;
        main.removeCallbacks(poll);
        ExoPlayer handed = player;
        player = null;
        if (handed != null) handed.removeListener(errors);
        return handed;
    }

    /** The local relay serving this copy, or null. */
    ProviderRelay relay() {
        return source.relay;
    }

    /** When this copy's provider link expires (0 when it does not). */
    long expiresAt() {
        return source.provider == null ? 0 : source.provider.expiresAt;
    }

    /** Not taken over: stops loading and closes its link. Safe to call more than once. */
    void release() {
        finished = true;
        main.removeCallbacks(poll);
        if (player != null) {
            player.removeListener(errors);
            player.release();
            player = null;
        }
        if (!taken && source.relay != null) source.relay.close();
        taken = true;
    }
}
