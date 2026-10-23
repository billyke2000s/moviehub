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
    FrameLayout video;
    VodPlayback vod;
    private VLCVideoLayout filmVideo;
    private String url, id;
    /** The film or episode this file is (see ResumePoint.titleKey), or null. */
    private String titleKey;
    private long position;
    boolean live, ended, foreground, dragging, dialogOpen;
    boolean triedAlternative;
    /** The next live start opens the stream again instead of carrying on. */
    private boolean restartLive;
    /** Live TV: this channel failed; the message is in the picture and SELECT tries again. */
    boolean liveFailed;
    /** Live TV was paused, so the picture is behind the live broadcast until "Back to live". */
    boolean behindLive;
    /** Paused by the viewer (the player reports pausing a moment later, so this is kept here). */
    boolean paused;
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
        forgetReleasedLivePlayer();
        if (
            controls != null && !dragging && !dialogOpen && player != null && player.isPlaying()
        ) controls.setVisibility(View.GONE);
    };
    private final Runnable stalled = () -> showError("The source is taking too long to respond.");
    /** Film skips and seek-bar moves waiting to become one jump (see {@link SkipQueue}). */
    final SkipQueue skips = new SkipQueue();
    final Runnable applySkip = () -> {
        forgetReleasedLivePlayer();
        long target = skips.take();
        if (target >= 0 && vod != null) vod.seeking();
        if (target >= 0 && player != null && player.isSeekable()) player.setTime(target);
    };
    private boolean nextPromptShown, nextCancelled;

    private void launchNextEpisode() {
        if (SourcesScreen.Binge.imdb == null || isFinishing()) return;
        android.content.Intent next = new android.content.Intent(this, MainActivity.class);
        next.addFlags(
            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        next.putExtra("bingeNext", true);
        startActivity(next);
        finish();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            forgetReleasedLivePlayer();
            if (vod != null) vod.tick();
            if (player != null) {
                // While a skip is waiting, show where it will land.
                long length = player.getLength(),
                    time = skips.pending() ? skips.target() : Math.max(0, player.getTime());
                timeLeft.setText(
                    live
                        ? behindLive
                            ? player.isPlaying()
                                ? "BEHIND LIVE"
                                : "PAUSED"
                            : "LIVE"
                        : length > 0
                          ? PlayerOverlay.timeLeft(Math.max(0, length - time))
                          : "—"
                );
                if (
                    !live &&
                    !nextPromptShown &&
                    length > 0 &&
                    time > 0 &&
                    length - time <= 10000 &&
                    length - time > 0 &&
                    getIntent().getStringExtra("episodeKey") != null &&
                    SourcesScreen.Binge.imdb != null
                ) {
                    nextPromptShown = true;
                    SidePanel.of(VlcPlaybackActivity.this, "Up next")
                        .message("Ready for the next episode?")
                        .action("Play next episode", () -> launchNextEpisode())
                        .action("Keep watching", () -> nextCancelled = true)
                        .show();
                }
                // A programme reminder that is due shows over the picture (checked every 15 s).
                if (++reminderTicks % 15 == 0 && !dialogOpen) showDueReminder();
                downloadMbps = PlayerOverlay.downloadSpeed(player);
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

    void revealControls() {
        if (zapper != null) {
            // Live TV from the guide shows the satellite-box overlay instead of film controls.
            if (zapper.overlay != null) zapper.overlay.pause(paused, behindLive);
            return;
        }
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
        getIntent().putExtra("title", title).putExtra("url", url).putExtra("id", id);
        behindLive = false;
        paused = false;
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
        forgetReleasedLivePlayer();
        // A Live TV channel that failed: SELECT tries it again (unless browsing other channels).
        if (
            liveFailed &&
            zapper != null &&
            !zapper.browsing() &&
            (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                // Every copy failed: start again from the first copy so each gets another try.
                if (!zapper.retryFromFirst()) {
                    restartLive = true;
                    startPlayer();
                }
            }
            return true;
        }
        // Live TV from the guide: the satellite remote (see LiveZapper.key).
        if (zapper != null && zapper.key(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Handled here, never by Android's default, and acted on when the key is released
            // (as Android does), so a held key counts as one press.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackKey();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                toggle();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (player != null) player.play();
                paused = false;
                if (vod != null) vod.pauseChanged();
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (live) LivePlayer.pauseForUser();
                if (player != null) player.pause();
                if (live) behindLive = true;
                paused = true;
                if (vod != null) vod.pauseChanged();
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
        titleKey = getIntent().getStringExtra("titleKey");
        if (id == null) id = "media";
        live = getIntent().getBooleanExtra("live", false);
        if (live && Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
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
        position =
            state == null ? (live ? 0 : ResumePoint.load(this, id, titleKey)) : state.getLong("position");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new FrameLayout(this);
        if (!live) {
            filmVideo = new VLCVideoLayout(this);
            video.addView(filmVideo, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening source…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        PlayerOverlay.build(this, root);
        if (live) {
            Bundle guideState =
                state == null ? getIntent().getBundleExtra("liveGuide") : state.getBundle("liveGuide");
            // Older/internal launchers may have no snapshot. Always build Live TV controls.
            LiveZapper.Session guide =
                guideState == null && LiveZapper.session != null
                    ? LiveGuideState.restore(LiveGuideState.save(LiveZapper.session))
                    : LiveGuideState.restore(guideState);
            zapper = new LiveZapper(this, guide);
            zapper.attach(root);
            // The film controls never show over Live TV; the satellite-box overlay does.
            controls.setVisibility(View.GONE);
        }
        root.setOnClickListener(v -> {
            if (zapper != null && zapper.touch) zapper.tapped();
            else revealControls();
        });
        setContentView(root);
        pause.requestFocus();
        revealControls();
    }

    String playbackUrl() {
        return url;
    }

    LibVLC filmEngine() {
        return engine;
    }

    void adoptVod(MediaPlayer ready, VLCVideoLayout layout, String link) {
        MediaPlayer previous = player;
        VLCVideoLayout previousView = filmVideo;
        int volume = previous == null ? 100 : previous.getVolume();
        if (volume < 0) volume = 100;
        player = ready;
        filmVideo = layout;
        url = link;
        getIntent().putExtra("url", link);
        ready.setEventListener(event -> {
            if (player == ready) onPlayerEvent(event);
        });
        if (previous != null) {
            previous.setEventListener(null);
            previous.setVolume(0);
            previous.stop();
            previous.detachViews();
            previous.release();
        }
        if (previousView != null) video.removeView(previousView);
        layout.setAlpha(1f);
        ready.setVolume(volume);
        position = Math.max(0, ready.getTime());
        resumeCheck = 0;
        buffering = false;
        playedOnce = true;
        overlayHandler.removeCallbacks(stalled);
        status.setVisibility(View.GONE);
        showPlaying(true);
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
                forgetReleasedLivePlayer();
                if (change < 0 && player != null) {
                    if (live) LivePlayer.pauseForUser();
                    player.pause();
                    paused = true;
                    if (vod != null) vod.pauseChanged();
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
        liveFailed = false;
        status.setBackgroundColor(Color.TRANSPARENT);
        release();
        status.setText("Opening source…");
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(stalled);
        if (live) {
            startLive();
            return;
        }
        VodSources.Session sources = VodSources.get(getIntent().getStringExtra("vodSession"));
        if (sources != null) vod = new VodPlayback(this, sources);
        armStartupTimeout();
        // A prepared replacement may have used a TextureView. Recreate the initial film layout
        // when restarting so VLC never reuses a layout initialized for another output type.
        if (filmVideo != null) video.removeView(filmVideo);
        filmVideo = new VLCVideoLayout(this);
        video.addView(filmVideo, new FrameLayout.LayoutParams(-1, -1));
        engine = new LibVLC(this, new ArrayList<>(PlayerOptions.film(this)));
        player = new MediaPlayer(engine);
        player.setRate(playbackRate);
        if (aspect != null) player.setAspectRatio(aspect);
        player.attachViews(filmVideo, null, false, false);
        MediaPlayer opened = player;
        player.setEventListener(event -> {
            if (player == opened) onPlayerEvent(event);
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

    /**
     * Live TV uses the one shared live player: when the guide's picture is already playing this
     * channel, it carries on and only moves to the full screen (no reconnecting or buffering).
     * {@code restartLive} opens the stream again (Back to live, Try again).
     */
    private void startLive() {
        boolean carriedOn = LivePlayer.play(this, url, PlayerOptions.live(this), restartLive);
        restartLive = false;
        player = LivePlayer.player();
        LivePlayer.attach(
            this,
            video,
            this::onPlayerEvent,
            () -> {
                if (!foreground) return;
                showError("This channel is unavailable. Check your connection or try another channel.");
            },
            this::armStartupTimeout,
            this::livePlayerReplaced
        );
        // Resolution has its own bounded broker deadline. Starting the video timer before it
        // finishes can discard a working provider before LibVLC has even received its URL.
        if (LivePlayer.isResolving()) {
            status.setText("Finding a working stream…");
        } else armStartupTimeout();
        player.setRate(playbackRate);
        if (aspect != null) player.setAspectRatio(aspect);
        if (carriedOn && player.isPlaying()) {
            // Already playing: no "Playing" event will come, so show it as playing now.
            playedOnce = true;
            overlayHandler.removeCallbacks(stalled);
            status.setVisibility(View.GONE);
            showPlaying(true);
            revealControls();
        }
    }

    private void livePlayerReplaced() {
        player = LivePlayer.player();
        url = LivePlayer.currentUrl();
        id = ChannelQuery.key(url);
        getIntent().putExtra("url", url).putExtra("id", id);
        liveFailed = buffering = paused = behindLive = triedAlternative = false;
        playedOnce = true;
        Timings.finish("stall", "Buffering pause while watching");
        overlayHandler.removeCallbacks(stalled);
        status.setVisibility(View.GONE);
        showPlaying(true);
        if (zapper != null) zapper.sourceReplaced();
    }

    private void armStartupTimeout() {
        overlayHandler.removeCallbacks(stalled);
        if (!foreground) return;
        status.setText("Opening source…");
        // Live TV with backups moves on sooner than a film would, after link resolution.
        overlayHandler.postDelayed(stalled, zapper != null ? 15000 : 45000);
    }

    /** The player's events: playing, buffering, the end, and errors (which try the other format). */
    private void onPlayerEvent(MediaPlayer.Event event) {
        forgetReleasedLivePlayer();
        if (!foreground || player == null) return;
        if (event.type == MediaPlayer.Event.Playing) {
            Timings.finish("play", "Press play to picture");
            Timings.finish("stall", "Buffering pause while watching");
            playedOnce = true;
            if (vod != null) vod.onPlaying();
            player.setRate(playbackRate);
            if (aspect != null) player.setAspectRatio(aspect);
            overlayHandler.removeCallbacks(stalled);
            status.setVisibility(View.GONE);
            // The media was opened at the resume point (start-time). If this format ignored
            // that, seek once as a fallback.
            long target = resumeCheck;
            resumeCheck = 0;
            if (target > 0) overlayHandler.postDelayed(() -> {
                forgetReleasedLivePlayer();
                if (player != null && player.isSeekable() && player.getTime() < target - 5000) player.setTime(
                    target
                );
            }, 1500);
            showPlaying(true);
            revealControls();
        } else if (event.type == MediaPlayer.Event.Buffering) {
            if (event.getBuffering() < 100) {
                if (playedOnce) Timings.markOnce("stall");
                if (playedOnce && vod != null && !buffering) vod.onBuffering(true);
                buffering = true;
                status.setText(
                    "Buffering… " +
                        Math.round(event.getBuffering()) +
                        "%" +
                        (downloadMbps < 0 ? "" : String.format(Locale.ROOT, "  ·  %.1f Mb/s", downloadMbps))
                );
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
                Timings.finish("stall", "Buffering pause while watching");
                buffering = false;
                if (vod != null) vod.onBuffering(false);
            }
        } else if (event.type == MediaPlayer.Event.EndReached) {
            if (live) {
                showError("This live stream ended. Try another source or channel.");
                return;
            }
            ended = true;
            ResumePoint.clear(this, id, titleKey);
            String episode = getIntent().getStringExtra("episodeKey");
            if (episode != null && !episode.isEmpty()) {
                android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
                Set<String> watched = new HashSet<>(
                    prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                );
                watched.add(episode);
                prefs.edit().putStringSet("watchedEpisodes", watched).apply();
            }
            if (
                !live &&
                !nextCancelled &&
                SourcesScreen.Binge.imdb != null &&
                getIntent().getStringExtra("episodeKey") != null
            ) launchNextEpisode();
            else finish();
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
            } else showError("This source could not be played. Try another source or retry the connection.");
        }
    }

    /** Live TV: the current copy could not be opened; moves on to a backup or says so. */
    void liveSourceFailed() {
        showError("This source could not be played. Try another source or retry the connection.");
    }

    private void showError(String message) {
        forgetReleasedLivePlayer();
        if (!foreground || isFinishing() || dialogOpen) return;
        if (zapper != null && zapper.failover("This stream is not working")) return;
        if (vod != null && vod.failover()) return;
        overlayHandler.removeCallbacks(stalled);
        if (player != null) {
            long time = player.getTime();
            if (time > 0 && !live) position = time;
            player.pause();
        }
        showPlaying(false);
        if (zapper != null) {
            // Live TV: say so in the picture, with no panel in the way, so ▲ ▼ go straight on to
            // the next channel. SELECT tries this one again.
            liveFailed = true;
            boolean generic =
                message.startsWith("This source could not") || message.startsWith("The source is taking");
            status.setText(
                (generic ? "This channel isn't working right now." : message) +
                    (zapper.touch
                        ? "\n\nTap the picture for the channel buttons"
                        : "\n\n▲ ▼  change channel     SELECT  try again     BACK  guide")
            );
            status.setBackgroundColor(0xd9000000);
            status.setVisibility(View.VISIBLE);
            zapper.showBanner();
            return;
        }
        status.setText("Playback unavailable");
        status.setVisibility(View.VISIBLE);
        dialogOpen = true;
        SidePanel.of(this, "Can't play this")
            .message(message)
            .action("Try again", () -> {
                restartLive = true;
                startPlayer();
            })
            .action(zapper != null ? "Back to the guide" : "Back to sources", this::finish)
            .onDismiss(this::panelClosed)
            .show();
    }

    /** A side panel over the video closed: show the controls again. */
    void panelClosed() {
        dialogOpen = false;
        revealControls();
    }

    @Override
    public void finish() {
        super.finish();
        if (live && Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
    }

    /**
     * Back on a film first puts the controls away, then asks "Press Back again", and only leaves
     * on another press within {@link #EXIT_WINDOW_MS}, so a stray press does not stop the film.
     * Live TV asks once and leaves on the second press, as before.
     */
    private void onBackKey() {
        long now = SystemClock.elapsedRealtime();
        if (ended || player == null || now - backPressedAt < EXIT_WINDOW_MS) {
            finish();
            return;
        }
        if (!live && controls != null && controls.getVisibility() == View.VISIBLE) {
            overlayHandler.removeCallbacks(hideOverlay);
            controls.setVisibility(View.GONE);
            return;
        }
        backPressedAt = now;
        showExitHint();
    }

    private void showExitHint() {
        if (live) revealControls();
        exitHint.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(hideExitHint);
        overlayHandler.postDelayed(hideExitHint, EXIT_WINDOW_MS);
    }

    void toggle() {
        revealControls();
        if (player == null) return;
        if (player.isPlaying()) {
            if (live) LivePlayer.pauseForUser();
            player.pause();
            if (live) behindLive = true;
            paused = true;
            showPlaying(false);
        } else {
            player.play();
            paused = false;
            showPlaying(true);
        }
        if (vod != null) vod.pauseChanged();
        revealControls();
    }

    /**
     * Skips back or forward. Films combine quick presses into one jump ({@link SkipQueue}). Live TV
     * can be rewound only when its stream allows it (many live streams keep a few minutes);
     * otherwise it says so.
     */
    void seek(long delta) {
        revealControls();
        if (player == null) return;
        if (!live && player.isSeekable()) {
            long length = player.getLength();
            showSkipTarget(skips.skip(player.getTime(), delta, length), length);
            return;
        }
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

    /** Shows a waiting film jump on the controls and jumps once the presses stop. */
    void showSkipTarget(long target, long length) {
        if (vod != null) vod.seeking();
        if (length > 0) {
            timeline.setProgress((int) Math.min(1000, (target * 1000) / length));
            timeLeft.setText(PlayerOverlay.timeLeft(Math.max(0, length - target)));
        }
        overlayHandler.removeCallbacks(applySkip);
        overlayHandler.postDelayed(applySkip, SkipQueue.DELAY_MS);
    }

    /** Catches up with the broadcast after pausing or rewinding live TV: opens the stream again. */
    void backToLive() {
        behindLive = false;
        paused = false;
        restartLive = true;
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

    /**
     * Lets go of the player. Live TV's shared player keeps playing here; {@link #onStop} stops it
     * unless the guide's picture has taken it over.
     */
    /**
     * Live TV plays on the shared player, which the guide (or a test) can release or replace while
     * this screen is closing. Calling a released player crashes the app, so this screen lets go of
     * one that is no longer the shared player.
     */
    private void forgetReleasedLivePlayer() {
        if (live && player != null && player != LivePlayer.player()) player = null;
    }

    private void release() {
        if (vod != null) {
            vod.close();
            vod = null;
        }
        skips.clear();
        overlayHandler.removeCallbacks(applySkip);
        if (live) {
            player = null;
            return;
        }
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
        forgetReleasedLivePlayer();
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
                boolean completed = ended || ResumePoint.completed(position, length);
                ResumePoint.save(this, id, titleKey, position, length, completed);
                if (completed) {
                    String episode = getIntent().getStringExtra("episodeKey");
                    if (episode != null && !episode.isEmpty()) {
                        android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
                        Set<String> watched = new HashSet<>(
                            prefs.getStringSet("watchedEpisodes", Collections.emptySet())
                        );
                        watched.add(episode);
                        prefs.edit().putStringSet("watchedEpisodes", watched).apply();
                    }
                }
            }
        }
        release();
        // Back to the guide: its picture has already taken the live player over, so it plays on.
        // Anywhere else, nothing took it over, so it stops.
        if (live) LivePlayer.stopIfOwner(this);
        if (audio != null && audioFocus != null) audio.abandonAudioFocusRequest(audioFocus);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        forgetReleasedLivePlayer();
        long now = player == null ? 0 : player.getTime();
        out.putLong("position", Math.max(Math.max(position, resumeCheck), now));
        if (zapper != null) out.putBundle("liveGuide", LiveGuideState.save(zapper.guideSession()));
    }

    @Override
    public void onDestroy() {
        SidePanel.closeFor(this);
        if (zapper != null) zapper.close();
        overlayHandler.removeCallbacksAndMessages(null);
        release();
        if (live) LivePlayer.stopIfOwner(this);
        if (isFinishing()) VodSources.forget(getIntent().getStringExtra("vodSession"));
        super.onDestroy();
    }
}
