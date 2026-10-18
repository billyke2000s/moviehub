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
    private VLCVideoLayout video;
    private String url, id;
    private long position;
    private boolean live, ended, foreground, dragging, dialogOpen;
    boolean triedAlternative;
    private float playbackRate = 1f;
    long sleepAt;
    private String aspect;
    private final Runnable sleep = () -> {
        Toast.makeText(this, "Sleep timer finished", Toast.LENGTH_SHORT).show();
        finish();
    };
    /** The play/pause control and its icon. */
    private View pause;
    private ImageView pauseIcon;
    private SeekBar timeline;
    private TextView timeLeft, status;
    private AudioManager audio;
    private AudioFocusRequest audioFocus;
    private android.view.View controls;
    /** A second Back within this time leaves the player. */
    static final long EXIT_WINDOW_MS = 3000;
    private long backPressedAt;
    /** Resume point the media was opened at; checked once after playback starts. */
    private long resumeCheck;
    private TextView exitHint;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideExitHint = () -> {
        if (exitHint != null) exitHint.setVisibility(View.GONE);
    };
    private final Runnable hideOverlay = () -> {
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
                timeLeft.setText(live ? "LIVE" : length > 0 ? format(Math.max(0, length - time)) : "—");
                timeline.setEnabled(!live && player.isSeekable() && length > 0);
                if (!dragging && length > 0) timeline.setProgress(
                    (int) Math.min(1000, (time * 1000) / length)
                );
            }
            if (foreground) overlayHandler.postDelayed(this, 1000);
        }
    };

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    private static String format(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
            ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void revealControls() {
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

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (player != null) player.pause();
                showPlaying(false);
                revealControls();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seek(-10000);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seek(10000);
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
        // Streaming-app layout: the title on a shade at the top; at the bottom a full-width
        // timeline with the time on the right, then play controls on the left and options on
        // the right. The whole overlay hides together.
        FrameLayout overlay = new FrameLayout(this);
        controls = overlay;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(48), dp(28), dp(48), dp(56));
        top.setBackground(shade(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM));
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title);
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(48), dp(64), dp(48), dp(26));
        bottom.setBackground(shade(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP));
        LinearLayout progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        timeLeft = label();
        timeline = new SeekBar(this);
        timeline.setMax(1000);
        int tint = getSharedPreferences("ui", MODE_PRIVATE).getInt("accent", 0xff8a9eff);
        styleTimeline(tint);
        timeline.setEnabled(false);
        progress.addView(timeline, new LinearLayout.LayoutParams(0, dp(28), 1));
        LinearLayout.LayoutParams timeLayout = new LinearLayout.LayoutParams(-2, -2);
        timeLayout.leftMargin = dp(16);
        progress.addView(timeLeft, timeLayout);
        bottom.addView(progress);
        timeline.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                    overlayHandler.removeCallbacks(hideOverlay);
                }

                public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    revealControls();
                }

                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (fromUser && player != null && !live && player.isSeekable()) {
                        player.setTime((player.getLength() * value) / 1000);
                        revealControls();
                    }
                }
            }
        );
        // Flat icon controls, as streaming apps use: no button shapes; the selected one turns
        // bright white and grows a little, the others are dimmed.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        bottom.setClipChildren(false);
        if (!live) add(buttons, R.drawable.ic_player_back10, "10", "−10 s", () -> seek(-10000));
        pause = add(buttons, R.drawable.ic_player_pause, null, "Pause", this::toggle);
        pauseIcon = (ImageView) ((ViewGroup) pause).getChildAt(0);
        if (!live) add(buttons, R.drawable.ic_player_forward10, "10", "+10 s", () -> seek(10000));
        buttons.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1));
        add(
            buttons,
            R.drawable.ic_player_subtitles,
            "Audio & Subtitles",
            "Audio & Subtitles",
            this::audioAndSubtitles
        );
        add(buttons, R.drawable.ic_player_video, "Video", "Video", this::resolution);
        add(buttons, R.drawable.ic_player_more, "More", "More", this::moreControls);
        bottom.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        exitHint = new TextView(this);
        exitHint.setText("Press Back again to stop watching");
        exitHint.setTextColor(Color.WHITE);
        exitHint.setTextSize(16);
        exitHint.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        exitHint.setPadding(dp(22), dp(10), dp(22), dp(10));
        android.graphics.drawable.GradientDrawable hintShape =
            new android.graphics.drawable.GradientDrawable();
        hintShape.setColor(0xe6141a24);
        hintShape.setCornerRadius(dp(6));
        exitHint.setBackground(hintShape);
        exitHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLayout = new FrameLayout.LayoutParams(
            -2,
            -2,
            Gravity.CENTER_HORIZONTAL | Gravity.TOP
        );
        hintLayout.topMargin = dp(96);
        root.addView(exitHint, hintLayout);
        root.setOnClickListener(v -> revealControls());
        setContentView(root);
        pause.requestFocus();
        revealControls();
    }

    private android.graphics.drawable.GradientDrawable shade(
        android.graphics.drawable.GradientDrawable.Orientation direction
    ) {
        return new android.graphics.drawable.GradientDrawable(direction, new int[] {
            0xe6000000,
            0x99000000,
            0x00000000,
        });
    }

    /** A slim rounded timeline in the accent colour with a white dot, like streaming apps. */
    private void styleTimeline(int accent) {
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x4dffffff);
        track.setCornerRadius(dp(3));
        android.graphics.drawable.GradientDrawable played = new android.graphics.drawable.GradientDrawable();
        played.setColor(accent);
        played.setCornerRadius(dp(3));
        android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {
                track,
                new android.graphics.drawable.ClipDrawable(
                    played,
                    Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL
                ),
            }
        );
        bar.setId(0, android.R.id.background);
        bar.setId(1, android.R.id.progress);
        // Keep the bar thin inside the taller touch/focus area.
        bar.setLayerInset(0, 0, dp(11), 0, dp(11));
        bar.setLayerInset(1, 0, dp(11), 0, dp(11));
        timeline.setProgressDrawable(bar);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        dot.setSize(dp(14), dp(14));
        timeline.setThumb(dot);
        timeline.setSplitTrack(false);
        timeline.setPadding(dp(7), 0, dp(7), 0);
    }

    private TextView label() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setText("—");
        return t;
    }

    /**
     * One flat control: an icon with an optional short label, no background shape. The
     * description names it for accessibility and tests.
     */
    private View add(LinearLayout parent, int icon, String label, String description, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(8), dp(12), dp(8));
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(description);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        item.addView(image, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView text = null;
        if (label != null) {
            text = new TextView(this);
            text.setText(label);
            text.setTextSize(16);
            text.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(-2, -2);
            textLayout.leftMargin = dp(8);
            item.addView(text, textLayout);
        }
        TextView caption = text;
        View.OnFocusChangeListener style = (v, focused) -> {
            int colour = focused ? Color.WHITE : 0x99ffffff;
            image.setColorFilter(colour);
            if (caption != null) caption.setTextColor(colour);
            v.setScaleX(focused ? 1.18f : 1f);
            v.setScaleY(focused ? 1.18f : 1f);
        };
        item.setOnFocusChangeListener(style);
        style.onFocusChange(item, false);
        item.setOnClickListener(v -> {
            revealControls();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(52));
        lp.rightMargin = dp(18);
        parent.addView(item, lp);
        return item;
    }

    /** Shows the play or pause icon to match the player. */
    private void showPlaying(boolean playing) {
        if (pauseIcon == null) return;
        pauseIcon.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        pause.setContentDescription(playing ? "Pause" : "Play");
    }

    /** One panel for both, as streaming apps do: pick which one to change. */
    private void audioAndSubtitles() {
        dialogOpen = true;
        SidePanel.of(this, "Audio & Subtitles")
            .option("Audio", () -> tracks(false))
            .option("Subtitles", () -> tracks(true))
            .onDismiss(this::panelClosed)
            .show();
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
        overlayHandler.postDelayed(stalled, 45000);
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
                    status.setText("Buffering… " + Math.round(event.getBuffering()) + "%");
                    status.setVisibility(View.VISIBLE);
                } else status.setVisibility(View.GONE);
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
    private void panelClosed() {
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

    private void toggle() {
        revealControls();
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            showPlaying(false);
        } else {
            player.play();
            showPlaying(true);
        }
    }

    private void seek(long delta) {
        revealControls();
        if (player != null && !live && player.isSeekable()) {
            long target = Math.max(0, player.getTime() + delta),
                length = player.getLength();
            player.setTime(length > 0 ? Math.min(length, target) : target);
        }
    }

    private void resolution() {
        if (player == null) return;
        Media.VideoTrack track = player.getCurrentVideoTrack();
        String size = track == null ? "Resolution unavailable" : track.width + " × " + track.height;
        MediaPlayer.TrackDescription[] choices = player.getVideoTracks();
        if (choices == null || choices.length < 2) {
            dialogOpen = true;
            SidePanel.of(this, "Video · " + size)
                .message(
                    "This source has one picture quality. Choose a different source for another resolution."
                )
                .action("Close", null)
                .onDismiss(this::panelClosed)
                .show();
            return;
        }
        chooseTracks(choices, player.getVideoTrack(), 0, "Video · " + size);
    }

    private void tracks(boolean subtitles) {
        if (player == null) return;
        MediaPlayer.TrackDescription[] choices = subtitles ? player.getSpuTracks() : player.getAudioTracks();
        if (choices == null || choices.length == 0) {
            Toast.makeText(this, "No tracks available", Toast.LENGTH_SHORT).show();
            return;
        }
        chooseTracks(
            choices,
            subtitles ? player.getSpuTrack() : player.getAudioTrack(),
            subtitles ? 2 : 1,
            subtitles ? "Subtitles" : "Audio"
        );
    }

    private void chooseTracks(MediaPlayer.TrackDescription[] choices, int current, int type, String title) {
        String[] names = new String[choices.length];
        int selected = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = choices[i].id < 0 ? "Off" : choices[i].name;
            if (choices[i].id == current) selected = i;
        }
        dialogOpen = true;
        SidePanel.of(this, title)
            .choices(names, selected, which -> {
                if (player != null) {
                    int choice = choices[which].id;
                    if (type == 2) player.setSpuTrack(choice);
                    else if (type == 1) player.setAudioTrack(choice);
                    else player.setVideoTrack(choice);
                }
            })
            .onDismiss(this::panelClosed)
            .show();
    }

    private void moreControls() {
        List<String> labels = new ArrayList<>();
        if (!live) labels.add("Playback speed · " + playbackRate + "×");
        labels.add("Picture size");
        labels.add("Subtitle timing");
        labels.add(
            sleepAt > 0
                ? "Sleep timer · " +
                      Math.max(1, (sleepAt - SystemClock.elapsedRealtime()) / 60000) +
                      " min left"
                : "Sleep timer"
        );
        dialogOpen = true;
        SidePanel panel = SidePanel.of(this, "Playback options").onDismiss(this::panelClosed);
        for (String label : labels)
            panel.option(label, () -> {
                if (label.startsWith("Playback speed")) chooseSpeed();
                else if (label.equals("Picture size")) choosePicture();
                else if (label.equals("Subtitle timing")) subtitleTiming();
                else chooseSleep();
            });
        panel.show();
    }

    private void options(String title, String[] labels, int selected, java.util.function.IntConsumer action) {
        dialogOpen = true;
        SidePanel.of(this, title).choices(labels, selected, action).onDismiss(this::panelClosed).show();
    }

    void chooseSpeed() {
        float[] rates = { .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f };
        int selected = 2;
        for (int i = 0; i < rates.length; i++) if (rates[i] == playbackRate) selected = i;
        options(
            "Playback speed",
            new String[] { "0.5×", "0.75×", "Normal · 1×", "1.25×", "1.5×", "1.75×", "2×" },
            selected,
            which -> {
                playbackRate = rates[which];
                if (player != null) player.setRate(playbackRate);
            }
        );
    }

    void choosePicture() {
        String[] aspects = { null, "16:9", "4:3", "21:9" };
        int selected = 0;
        for (int i = 1; i < aspects.length; i++) if (aspects[i].equals(aspect)) selected = i;
        options("Picture size", new String[] { "Original · Fit", "16:9", "4:3", "21:9" }, selected, which -> {
            aspect = aspects[which];
            if (player != null) {
                player.setScale(0);
                player.setAspectRatio(aspect);
            }
        });
    }

    private void subtitleTiming() {
        if (player == null) return;
        long delay = player.getSpuDelay();
        options(
            "Subtitle timing · " + delay / 1000 + " ms",
            new String[] { "Earlier by 250 ms", "Later by 250 ms", "Reset timing" },
            -1,
            which -> {
                if (player != null) player.setSpuDelay(
                    which == 2 ? 0 : delay + (which == 0 ? -250000 : 250000)
                );
            }
        );
    }

    void chooseSleep() {
        options(
            "Sleep timer",
            new String[] { "Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes" },
            sleepAt == 0 ? 0 : -1,
            which -> {
                overlayHandler.removeCallbacks(sleep);
                int[] minutes = { 0, 15, 30, 60, 90 };
                sleepAt = which == 0 ? 0 : SystemClock.elapsedRealtime() + minutes[which] * 60000L;
                if (sleepAt > 0) overlayHandler.postDelayed(sleep, minutes[which] * 60000L);
            }
        );
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
        overlayHandler.removeCallbacksAndMessages(null);
        release();
        super.onDestroy();
    }
}
