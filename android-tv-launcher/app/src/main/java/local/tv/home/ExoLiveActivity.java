package local.tv.home;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/**
 * MovieHub Experimental only: full-screen Live TV on {@link ExoLive} (Media3 ExoPlayer) instead
 * of LibVLC. The same satellite banner ({@link SkyOverlay}) and channel changing
 * ({@link LiveZapper}) as {@link VlcPlaybackActivity}; one surface, which the shared player is
 * pointed at, so moving between this screen and the guide's picture never rebuilds the decoder.
 * Normal builds never open it ({@link ExoLive#available()} is false there).
 */
public class ExoLiveActivity extends LiveHost {

    private String url;
    private SurfaceView surface;
    private TextView status;
    private boolean foreground;
    private final LiveMenus menus = new Menus();
    private final ExoLive.Listener events = new ExoLive.Listener() {
        @Override
        public void playing() {
            liveFailed = false;
            status.setVisibility(View.GONE);
        }

        @Override
        public void buffering(boolean buffering) {
            if (liveFailed) return;
            status.setText("Buffering…");
            status.setBackgroundColor(Color.TRANSPARENT);
            status.setVisibility(buffering ? View.VISIBLE : View.GONE);
        }

        @Override
        public void failed(String reason) {
            showError(reason);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        url = getIntent().getStringExtra("url");
        if (url == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new FrameLayout(this);
        surface = new SurfaceView(this);
        video.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(dp(6), 0, dp(1), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setText("Opening channel…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        Bundle guideState =
            state == null ? getIntent().getBundleExtra("liveGuide") : state.getBundle("liveGuide");
        LiveZapper.Session guide =
            guideState == null && LiveZapper.session != null
                ? LiveGuideState.restore(LiveGuideState.save(LiveZapper.session))
                : LiveGuideState.restore(guideState);
        zapper = new LiveZapper(this, guide);
        zapper.attach(root);
        root.setOnClickListener(v -> {
            if (zapper.touch) zapper.tapped();
            else zapper.ok();
        });
        setContentView(root);
        video.setFocusable(true);
        video.setFocusableInTouchMode(true);
        video.requestFocus();
        // Take the picture over at once, so a stream the guide is playing carries straight on.
        ExoLive.attach(this, surface, events);
    }

    @Override
    public void onStart() {
        super.onStart();
        foreground = true;
        ExoLive.attach(this, surface, events);
        if (ExoLive.play(this, url, false)) status.setVisibility(View.GONE);
    }

    @Override
    public void onStop() {
        foreground = false;
        overlayHandler.removeCallbacksAndMessages(null);
        // Back to the guide: its picture has already taken the player over, so it plays on.
        ExoLive.stopIfOwner(this);
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (zapper != null) out.putBundle("liveGuide", LiveGuideState.save(zapper.guideSession()));
    }

    @Override
    public void onDestroy() {
        SidePanel.closeFor(this);
        if (zapper != null) zapper.close();
        overlayHandler.removeCallbacksAndMessages(null);
        ExoLive.stopIfOwner(this);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
    }

    // ---- Remote --------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (
            zapper != null &&
            zapper.overlay != null &&
            event.getAction() == KeyEvent.ACTION_DOWN &&
            event.getRepeatCount() == 0
        ) zapper.overlay.lastButton(
            "Last button received: " +
                KeyEvent.keyCodeToString(event.getKeyCode()) +
                " · scan " +
                event.getScanCode()
        );
        if (zapper == null) return super.dispatchKeyEvent(event);
        if (zapper.colourKey(event, zapper::colour)) return true;
        int key = event.getKeyCode();
        boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (liveFailed && select && !zapper.browsing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!zapper.retryFromFirst()) reconnect();
            }
            return true;
        }
        if (zapper.key(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (
                key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                key == KeyEvent.KEYCODE_MEDIA_PLAY ||
                key == KeyEvent.KEYCODE_MEDIA_PAUSE
            ) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY) ExoLive.resume();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) ExoLive.pause();
                else toggle();
                zapper.showBanner();
                return true;
            }
            if (!system(key)) zapper.showBanner();
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean system(int key) {
        return (
            key == KeyEvent.KEYCODE_VOLUME_UP ||
            key == KeyEvent.KEYCODE_VOLUME_DOWN ||
            key == KeyEvent.KEYCODE_VOLUME_MUTE ||
            key == KeyEvent.KEYCODE_MUTE ||
            key == KeyEvent.KEYCODE_HOME ||
            key == KeyEvent.KEYCODE_POWER
        );
    }

    // ---- LiveHost ------------------------------------------------------------------------------

    @Override
    public LiveMenus liveMenus() {
        return menus;
    }

    @Override
    public void switchTo(String title, String newUrl, String newId) {
        try {
            url = Playlist.networkUrl(
                newUrl,
                getSharedPreferences("ui", MODE_PRIVATE).getBoolean("http", false)
            );
        } catch (Exception e) {
            if (!zapper.failover("This stream's address is not allowed")) showError(
                "This channel's address is not allowed. Turn on Allow HTTP in Settings → System to play it."
            );
            return;
        }
        getIntent().putExtra("title", title).putExtra("url", url).putExtra("id", newId);
        liveFailed = false;
        status.setText("Opening channel…");
        status.setBackgroundColor(Color.TRANSPARENT);
        status.setVisibility(View.VISIBLE);
        ExoLive.attach(this, surface, events);
        ExoLive.play(this, url, true);
    }

    @Override
    public String playbackUrl() {
        return url;
    }

    @Override
    public void panelClosed() {
        dialogOpen = false;
        if (zapper != null) zapper.showBanner();
    }

    @Override
    public void liveSourceFailed() {
        showError("This source could not be played. Try another source or retry the connection.");
    }

    @Override
    public void toggle() {
        if (ExoLive.isPlaying()) ExoLive.pause();
        else ExoLive.resume();
    }

    private void reconnect() {
        liveFailed = false;
        status.setText("Reconnecting…");
        status.setBackgroundColor(Color.TRANSPARENT);
        status.setVisibility(View.VISIBLE);
        ExoLive.attach(this, surface, events);
        ExoLive.play(this, url, true);
    }

    /** Says the channel failed in the picture, after trying any backup copies. */
    private void showError(String reason) {
        if (!foreground || isFinishing()) return;
        if (zapper != null && zapper.failover(reason)) return;
        liveFailed = true;
        status.setText(
            "This channel isn't working right now.\n" +
                reason +
                "\n\n▲ ▼  change channel     SELECT  try again     BACK  guide"
        );
        status.setBackgroundColor(0xd9000000);
        status.setVisibility(View.VISIBLE);
        if (zapper != null) zapper.showBanner();
    }

    // ---- Side panels ---------------------------------------------------------------------------

    private final class Menus implements LiveMenus {

        @Override
        public void audioAndSubtitles() {
            List<String> tracks = ExoLive.audioTracks();
            dialogOpen = true;
            if (tracks.size() < 2) {
                SidePanel.of(ExoLiveActivity.this, "Audio")
                    .message(
                        tracks.isEmpty()
                            ? "No audio tracks reported yet."
                            : "This channel has one audio track: " + tracks.get(0)
                    )
                    .action("Close", null)
                    .onDismiss(ExoLiveActivity.this::panelClosed)
                    .show();
                return;
            }
            SidePanel.of(ExoLiveActivity.this, "Audio")
                .choices(
                    tracks.toArray(new String[0]),
                    ExoLive.selectedAudioTrack(),
                    ExoLive::selectAudioTrack
                )
                .onDismiss(ExoLiveActivity.this::panelClosed)
                .show();
        }

        @Override
        public void chooseSource() {
            List<ChannelDatabase.Row> copies = zapper.copies();
            String[] names = new String[copies.size() + 1];
            String playing = zapper.sourceProvider();
            names[0] =
                "Automatic · " +
                (zapper.automaticSources() && !playing.isEmpty() ? "now " + playing + " · " : "") +
                "switch if a source fails";
            for (int i = 0; i < copies.size(); i++) {
                String provider = AdapterSources.sourceName(copies.get(i).source);
                names[i + 1] =
                    (i == zapper.selectedCopy() ? "▶ " : "") +
                    "Source " +
                    (i + 1) +
                    "  ·  " +
                    (provider.isEmpty() ? "" : provider + "  ·  ") +
                    copies.get(i).name;
            }
            int current = zapper.automaticSources() ? 0 : zapper.selectedCopy() + 1;
            dialogOpen = true;
            SidePanel.of(ExoLiveActivity.this, "Sources")
                .choices(names, current, which -> {
                    if (which == 0) zapper.automaticSources(true);
                    else zapper.useCopy(which - 1, false);
                })
                .onDismiss(ExoLiveActivity.this::panelClosed)
                .show();
        }

        @Override
        public void liveOptions() {
            String[] labels = {
                ExoLive.isPlaying() ? "Pause" : "Play",
                "Audio",
                "Programme information",
                zapper.sourceText(),
                "Reconnect channel",
                "Player status · ExoPlayer · " + ExoLive.status(),
            };
            dialogOpen = true;
            SidePanel panel = SidePanel.of(ExoLiveActivity.this, "Options").onDismiss(
                ExoLiveActivity.this::panelClosed
            );
            for (String label : labels)
                panel.option(label, () -> {
                    if (label.equals("Pause") || label.equals("Play")) toggle();
                    else if (label.equals("Audio")) audioAndSubtitles();
                    else if (label.equals("Programme information")) zapper.information();
                    else if (label.startsWith("Sources")) chooseSource();
                    else if (label.equals("Reconnect channel")) reconnect();
                    else Toast.makeText(ExoLiveActivity.this, ExoLive.status(), Toast.LENGTH_LONG).show();
                });
            panel.show();
        }
    }
}
