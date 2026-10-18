package local.tv.home;

import android.app.*;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Fullscreen TV playback; account and resume storage retain their original identities. */
public class VlcPlaybackActivity extends Activity {

    private LibVLC engine;
    MediaPlayer player;
    /** Channel changing and backups while watching Live TV from the guide; null otherwise. */
    LiveZapper zapper;
    /** The title at the top of the controls (changes with the channel). */
    TextView titleView;
    /** The side panels (tracks, speed, picture, sleep timer). */
    final PlayerMenus menus = new PlayerMenus(this);
    private VLCVideoLayout video;
    private String url, id;
    private long position;
    boolean live, ended, foreground, dragging, dialogOpen;
    boolean triedAlternative;
    /** Live TV was paused, so the picture is behind the live broadcast until "Back to live". */
    boolean behindLive;
    private int reminderTicks;
    float playbackRate = 1f;
    long sleepAt;
    String aspect;
    final Runnable sleep = () -> {
        Toast.makeText(this, "Sleep timer finished", Toast.LENGTH_SHORT).show();
        finish();
    };
    /** The play/pause control and its icon. */
    View pause;
    ImageView pauseIcon;
    SeekBar timeline;
    TextView timeLeft, status, speed;
    /** Latest download speed in megabits per second, or -1 when unknown. */
    private float downloadMbps = -1;
    private AudioManager audio;
    private AudioFocusRequest audioFocus;
    android.view.View controls;
    /** A second Back within this time leaves the player. */
    static final long EXIT_WINDOW_MS = 3000;
    private long backPressedAt;
    /** Resume point the media was opened at; checked once after playback starts. */
    private long resumeCheck;
    /** Playback has started at least once, so later buffering is a pause while watching. */
    private boolean playedOnce;
    /** A buffering pause is in progress (counted once, not for every progress update). */
    private boolean buffering;
    TextView exitHint;
    final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideExitHint = () -> {
        if (exitHint != null) exitHint.setVisibility(View.GONE);
    };
    final Runnable hideOverlay = () -> {
        if (
            controls != null && !dragging && !dialogOpen && player != null && player.isPlaying()
        ) controls.setVisibility(View.GONE);
    };
    private final Runnable stalled = () -> showError("The source is taking too long to respond.");
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player != null) {
                long length = player.getLength(),
                    time = Math.max(0, player.getTime());
                timeLeft.setText(
                    live
                        ? behindLive
                            ? player.isPlaying()
                                ? "BEHIND LIVE"
                                : "PAUSED"
                            : "LIVE"
                        : length > 0
                          ? format(Math.max(0, length - time))
                          : "—"
                );
                // A programme reminder that is due shows over the picture (checked every 15 s).
                if (++reminderTicks % 15 == 0 && !dialogOpen) showDueReminder();
                downloadMbps = downloadSpeed();
                if (downloadMbps >= 0) Timings.lastMbps = downloadMbps;
                speed.setText(
                    downloadMbps < 0
                        ? ""
                        : String.format(Locale.ROOT, "Downloading at %.1f Mb/s", downloadMbps)
                );
                timeline.setEnabled(!live && player.isSeekable() && length > 0);
                if (!dragging && length > 0) timeline.setProgress(
                    (int) Math.min(1000, (time * 1000) / length)
                );
            }
            if (foreground) overlayHandler.postDelayed(this, 1000);
        }
    };

    int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    private static String format(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
            ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void revealControls() {
        if (controls == null) return;
        boolean hidden = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(View.VISIBLE);
        if (hidden) pause.requestFocus();
        overlayHandler.removeCallbacks(hideOverlay);
        overlayHandler.postDelayed(hideOverlay, 4500);
    }

    private void immersive() {
        getWindow()
            .getDecorView()
            .setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
    }

    /** Satellite-box remote keys while watching Live TV from the guide. */
    private boolean zapperKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean hidden = controls.getVisibility() != View.VISIBLE;
        // Browsing in the banner: up and down move between channels, left and right between
        // programmes, SELECT watches the channel shown.
        if (
            zapper.browsing() ||
            (hidden && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT))
        ) {
            int channel = key == KeyEvent.KEYCODE_DPAD_UP ? 1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? -1 : 0,
                programme =
                    key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
            if (channel != 0 || programme != 0 || select) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (select) zapper.browseSelect();
                    else zapper.browse(channel, zapper.browsing() ? programme : Math.max(0, programme));
                }
                return true;
            }
        }
        boolean up = key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP,
            down = key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN;
        if (hidden && key == KeyEvent.KEYCODE_DPAD_UP) up = true;
        if (hidden && key == KeyEvent.KEYCODE_DPAD_DOWN) down = true;
        boolean digit = key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9;
        boolean handled =
            up ||
            down ||
            digit ||
            key == KeyEvent.KEYCODE_LAST_CHANNEL ||
            key == KeyEvent.KEYCODE_INFO ||
            key == KeyEvent.KEYCODE_GUIDE;
        if (!handled || event.getAction() != KeyEvent.ACTION_DOWN) return handled;
        if (event.getRepeatCount() > 0 && !(up || down)) return true;
        if (up) zapper.channel(1);
        else if (down) zapper.channel(-1);
        else if (digit) zapper.typeDigit(key - KeyEvent.KEYCODE_0);
        else if (key == KeyEvent.KEYCODE_LAST_CHANNEL) zapper.lastChannel();
        else if (key == KeyEvent.KEYCODE_GUIDE) finish();
        // back to the guide
        else zapper.showBanner();
        return true;
    }

    /**
     * Plays another stream in place: the next channel, or a backup copy of this one. Called by
     * {@link LiveZapper}.
     */
    void switchTo(String title, String newUrl, String newId) {
        try {
            url = Playlist.networkUrl(
                newUrl,
                getSharedPreferences("ui", MODE_PRIVATE).getBoolean("http", false)
            );
        } catch (Exception e) {
            if (zapper == null || !zapper.failover("This stream's address is not allowed")) showError(
                "This channel's address is not allowed. Turn on Allow HTTP in Settings → System to play it."
            );
            return;
        }
        id = newId;
        behindLive = false;
        if (titleView != null) titleView.setText(title);
        position = 0;
        resumeCheck = 0;
        playedOnce = false;
        buffering = false;
        triedAlternative = false;
        startPlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Handled here, never by Android's default, and acted on when the key is released
            // (as Android does), so a held key counts as one press. Back first closes the banner
            // when browsing other channels in it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (zapper != null && zapper.browsing()) zapper.endBrowse();
                else onBackKey();
            }
            return true;
        }
        if (zapper != null && zapperKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggle();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (player != null) player.play();
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (player != null) player.pause();
                if (live) behindLive = true;
                showPlaying(false);
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seek(-10000);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (live && behindLive && (player == null || !player.isSeekable())) backToLive();
                else seek(10000);
                return true;
            }
            if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER) {
                if (controls.getVisibility() != View.VISIBLE) {
                    revealControls();
                    return true;
                }
                revealControls();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        id = getIntent().getStringExtra("id");
        if (id == null) id = "media";
        live = getIntent().getBooleanExtra("live", false);
        try {
            url = Playlist.networkUrl(
                getIntent().getStringExtra("url"),
                getSharedPreferences("ui", MODE_PRIVATE).getBoolean("http", false)
            );
        } catch (Exception e) {
            Toast.makeText(this, "Invalid or insecure playback URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Object saved = getSharedPreferences("positions", MODE_PRIVATE).getAll().get(id);
        position =
            state == null
                ? !live &&
                  getSharedPreferences("ui", MODE_PRIVATE).getBoolean("resume", true) &&
                  saved instanceof Number
                    ? ((Number) saved).longValue()
                    : 0
                : state.getLong("position");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VLCVideoLayout(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening source…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        PlayerOverlay.build(this, root);
        LiveZapper.Session guide = LiveZapper.session;
        if (live && guide != null) {
            zapper = new LiveZapper(this, guide);
            zapper.attach(root);
        }
        root.setOnClickListener(v -> revealControls());
        setContentView(root);
        pause.requestFocus();
        revealControls();
    }

    /**
     * The input bitrate VLC reports for the current media, in megabits per second, or -1.
     * VLC gives it in kilobytes per second (VLC for Android scales it by 8 × 1024 for bits).
     */
    private float downloadSpeed() {
        if (player == null) return -1;
        var media = player.getMedia();
        if (media == null) return -1;
        try {
            var stats = media.getStats();
            return stats == null ? -1 : (stats.inputBitrate * 8f * 1024f) / 1_000_000f;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            media.release();
        }
    }

    /** Shows the play or pause icon to match the player. */
    private void showPlaying(boolean playing) {
        if (pauseIcon == null) return;
        pauseIcon.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        pause.setContentDescription(playing ? "Pause" : "Play");
    }

    @Override
    public void onStart() {
        super.onStart();
        foreground = true;
        if (url == null) return;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(change -> {
                if (change < 0 && player != null) {
                    player.pause();
                    showPlaying(false);
                    revealControls();
                }
            })
            .build();
        if (audio.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) startPlayer();
        else showError("Audio is in use by another app.");
        overlayHandler.post(tick);
    }

    private void startPlayer() {
        if (!foreground) return;
        release();
        status.setText("Opening source…");
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(stalled);
        // Live TV with backups moves on sooner than a film would.
        overlayHandler.postDelayed(stalled, zapper != null ? 15000 : 45000);
        engine = new LibVLC(
            this,
            new ArrayList<>(
                Arrays.asList(
                    "--network-caching=1500",
                    "--no-video-title-show",
                    // Network files have no subtitle files beside them; do not probe for any.
                    "--no-sub-autodetect-file",
                    "--audio-language=" +
                        getSharedPreferences("ui", MODE_PRIVATE).getString("audioLanguage", "eng,en"),
                    "--sub-language=" +
                        getSharedPreferences("ui", MODE_PRIVATE).getString("subtitleLanguage", "none")
                )
            )
        );
        player = new MediaPlayer(engine);
        player.setRate(playbackRate);
        if (aspect != null) player.setAspectRatio(aspect);
        player.attachViews(video, null, false, false);
        player.setEventListener(event -> {
            if (!foreground || player == null) return;
            if (event.type == MediaPlayer.Event.Playing) {
                Timings.finish("play", "Press play to picture");
                Timings.finish("stall", "Buffering pause while watching");
                playedOnce = true;
                player.setRate(playbackRate);
                if (aspect != null) player.setAspectRatio(aspect);
                overlayHandler.removeCallbacks(stalled);
                status.setVisibility(View.GONE);
                // The media was opened at the resume point (start-time). If this format ignored
                // that, seek once as a fallback.
                long target = resumeCheck;
                resumeCheck = 0;
                if (target > 0) overlayHandler.postDelayed(() -> {
                    if (
                        player != null && player.isSeekable() && player.getTime() < target - 5000
                    ) player.setTime(target);
                }, 1500);
                showPlaying(true);
                revealControls();
            } else if (event.type == MediaPlayer.Event.Buffering) {
                if (event.getBuffering() < 100) {
                    if (playedOnce) Timings.markOnce("stall");
                    if (playedOnce && zapper != null && !buffering) zapper.bufferingStarted();
                    buffering = true;
                    status.setText(
                        "Buffering… " +
                            Math.round(event.getBuffering()) +
                            "%" +
                            (downloadMbps < 0
                                ? ""
                                : String.format(Locale.ROOT, "  ·  %.1f Mb/s", downloadMbps))
                    );
                    status.setVisibility(View.VISIBLE);
                } else {
                    status.setVisibility(View.GONE);
                    Timings.finish("stall", "Buffering pause while watching");
                    buffering = false;
                    if (zapper != null) zapper.bufferingEnded();
                }
            } else if (event.type == MediaPlayer.Event.EndReached) {
                ended = true;
                getSharedPreferences("positions", MODE_PRIVATE).edit().remove(id).apply();
                String episode = getIntent().getStringExtra("episodeKey");
                if (episode != null && !episode.isEmpty()) {
                    android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
                    Set<String> watched = new HashSet<>(
                        prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                    );
                    watched.add(episode);
                    prefs.edit().putStringSet("watchedEpisodes", watched).apply();
                }
                finish();
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                if (
                    live &&
                    !triedAlternative &&
                    (Uri.parse(url).getPath().endsWith(".ts") || Uri.parse(url).getPath().endsWith(".m3u8"))
                ) {
                    triedAlternative = true;
                    Uri original = Uri.parse(url);
                    String path = original.getEncodedPath();
                    path = path.endsWith(".ts")
                        ? path.substring(0, path.length() - 3) + ".m3u8"
                        : path.substring(0, path.length() - 5) + ".ts";
                    url = original.buildUpon().encodedPath(path).build().toString();
                    video.post(this::startPlayer);
                } else showError(
                    "This source could not be played. Try another source or retry the connection."
                );
            }
        });
        Media media = new Media(engine, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        // Open directly at the resume point instead of starting at 0:00 and then seeking, which
        // made the player buffer twice.
        if (!live && position > 1000) {
            media.addOption(":start-time=" + position / 1000);
            resumeCheck = position;
            position = 0;
        }
        player.setMedia(media);
        media.release();
        player.play();
    }

    private void showError(String message) {
        if (!foreground || isFinishing() || dialogOpen) return;
        if (zapper != null && zapper.failover("This stream is not working")) return;
        overlayHandler.removeCallbacks(stalled);
        if (player != null) {
            long time = player.getTime();
            if (time > 0 && !live) position = time;
            player.pause();
        }
        showPlaying(false);
        status.setText("Playback unavailable");
        status.setVisibility(View.VISIBLE);
        dialogOpen = true;
        SidePanel.of(this, "Can't play this")
            .message(message)
            .action("Try again", this::startPlayer)
            .action("Back to sources", this::finish)
            .onDismiss(this::panelClosed)
            .show();
    }

    /** A side panel over the video closed: show the controls again. */
    void panelClosed() {
        dialogOpen = false;
        revealControls();
    }

    /**
     * Leaving takes two presses, so a stray press does not stop the film.
     */
    private void onBackKey() {
        long now = SystemClock.elapsedRealtime();
        if (ended || player == null || now - backPressedAt < EXIT_WINDOW_MS) {
            finish();
            return;
        }
        backPressedAt = now;
        showExitHint();
    }

    private void showExitHint() {
        revealControls();
        exitHint.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(hideExitHint);
        overlayHandler.postDelayed(hideExitHint, EXIT_WINDOW_MS);
    }

    void toggle() {
        revealControls();
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            if (live) behindLive = true;
            showPlaying(false);
        } else {
            player.play();
            showPlaying(true);
        }
    }

    /**
     * Skips back or forward. Live TV can be rewound only when its stream allows it (many live
     * streams keep a few minutes); otherwise it says so.
     */
    void seek(long delta) {
        revealControls();
        if (player == null) return;
        if (!player.isSeekable()) {
            if (live) Toast.makeText(
                this,
                delta < 0 ? "This channel can't be rewound" : "Already live",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        long target = Math.max(0, player.getTime() + delta),
            length = player.getLength();
        player.setTime(length > 0 ? Math.min(length, target) : target);
        if (live && delta < 0) behindLive = true;
    }

    /** Catches up with the broadcast after pausing or rewinding live TV: opens the stream again. */
    void backToLive() {
        behindLive = false;
        startPlayer();
    }

    /** "Watch now" on a reminder: changes channel here, or leaves for the guide to open it. */
    private void showDueReminder() {
        Reminders.show(this, reminder -> {
            if (zapper != null) zapper.tuneKey(reminder.key);
            else {
                Reminders.pending = reminder;
                finish();
            }
        });
    }

    private void release() {
        if (player != null) {
            player.setEventListener(null);
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
    }

    @Override
    public void onStop() {
        overlayHandler.removeCallbacksAndMessages(null);
        sleepAt = 0;
        foreground = false;
        if (player != null) {
            long time = player.getTime();
            // Before playback starts the time is 0; keep the resume point the media opened at.
            if (time > 0) position = time;
            else if (resumeCheck > 0) position = resumeCheck;
            if (!live) {
                long length = player.getLength();
                android.content.SharedPreferences.Editor edit = getSharedPreferences(
                    "positions",
                    MODE_PRIVATE
                ).edit();
                if (length > 0) edit.putLong(id + ":duration", length);
                if (
                    !ended && getSharedPreferences("ui", MODE_PRIVATE).getBoolean("resume", true)
                ) edit.putLong(id, position);
                edit.apply();
            }
        }
        release();
        if (audio != null && audioFocus != null) audio.abandonAudioFocusRequest(audioFocus);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        long now = player == null ? 0 : player.getTime();
        out.putLong("position", Math.max(Math.max(position, resumeCheck), now));
    }

    @Override
    public void onDestroy() {
        if (zapper != null) zapper.close();
        overlayHandler.removeCallbacksAndMessages(null);
        release();
        super.onDestroy();
    }
}
