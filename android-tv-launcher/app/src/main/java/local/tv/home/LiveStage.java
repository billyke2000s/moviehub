package local.tv.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * MovieHub Experimental: one screen for all of Live TV. MovieHub's pages (the TV Guide menu, the
 * channel guide, and every other screen) are shown in {@link #pageHost}; above them is one video
 * picture that is never rebuilt or moved to another screen, only placed over the guide's corner
 * box or stretched to full screen; above that, the satellite banner while watching full screen.
 * The same {@link ExoLive} player draws on the same surface the whole time.
 *
 * <p>Full screen is not a separate Activity: the guide stays exactly as it was underneath, so BACK
 * returns to it at once. All remote keys go through {@link MainActivity#dispatchKeyEvent}, which
 * sends them here while watching full screen.
 */
final class LiveStage implements LiveScreen {

    private final MainActivity a;
    private final FrameLayout root, pageHost, overlay, bannerHost;
    private final FrameLayout video;
    private final SurfaceView surface;
    private final TextView status, lastButton;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final LiveMenus menus = new ExoMenus(this, this::reconnect);
    private LiveZapper zapper;
    private boolean dialogOpen, liveFailed, full;
    private String url, title;
    /** The guide's corner box the picture sits over, or null when no guide is showing. */
    private View anchor;
    /** The guide's channel label, moved above the picture so the picture does not cover it. */
    private TextView anchorLabel;
    private View focusBeforeFull;
    private final View.OnLayoutChangeListener follow = (v, l, t, r, b, ol, ot, or, ob) -> place();
    private final ExoLive.Listener events = new ExoLive.Listener() {
        @Override
        public void playing() {
            liveFailed = false;
            overlayHandler.removeCallbacks(slow);
            status.setVisibility(View.GONE);
        }

        @Override
        public void buffering(boolean buffering) {
            if (!full || liveFailed) return;
            status.setText("Buffering…");
            status.setBackgroundColor(Color.TRANSPARENT);
            status.setVisibility(buffering ? View.VISIBLE : View.GONE);
        }

        @Override
        public void failed(String reason) {
            if (full) showError(reason);
        }
    };

    LiveStage(MainActivity activity) {
        a = activity;
        root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        pageHost = new FrameLayout(a);
        root.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
        // After the pages, so the picture shows through them wherever it is placed.
        video = new FrameLayout(a);
        surface = new SurfaceView(a);
        video.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addView(video, hidden());
        overlay = new FrameLayout(a);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(a);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setShadowLayer(a.dp(6f), 0, a.dp(1f), 0xcc000000);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        overlay.addView(status, new FrameLayout.LayoutParams(-1, -1));
        bannerHost = new FrameLayout(a);
        overlay.addView(bannerHost, new FrameLayout.LayoutParams(-1, -1));
        // Which button MovieHub last received, on every screen of the experimental app.
        lastButton = new TextView(a);
        lastButton.setTextColor(0xccffffff);
        lastButton.setTextSize(12);
        lastButton.setShadowLayer(a.dp(3f), 0, a.dp(1f), 0xcc000000);
        lastButton.setText("Last button: none yet");
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        corner.setMargins(0, 0, a.dp(12f), a.dp(6f));
        overlay.addView(lastButton, corner);
    }

    private static FrameLayout.LayoutParams hidden() {
        // Kept attached (so its surface survives) but out of sight.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(2, 2, Gravity.TOP | Gravity.START);
        lp.leftMargin = -100;
        return lp;
    }

    // ---- Pages ---------------------------------------------------------------------------------

    /** Shows one of MovieHub's pages, in place of {@code setContentView}. */
    void setPage(View page) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.getChildAt(0) != root) {
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
            a.setContentView(root);
        }
        if (full) exitFull(false);
        clearAnchor();
        pageHost.removeAllViews();
        pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * The guide shows its live picture in {@code box}, with {@code label} naming the channel.
     * The one picture is placed over the box (and follows it if it moves).
     */
    void picture(FrameLayout box, TextView label) {
        clearAnchor();
        anchor = box;
        anchor.addOnLayoutChangeListener(follow);
        if (label != null) {
            if (label.getParent() instanceof ViewGroup) ((ViewGroup) label.getParent()).removeView(label);
            anchorLabel = label;
            overlay.addView(label, 0, new FrameLayout.LayoutParams(1, 1));
        }
        ExoLive.attach(this, surface, events);
        box.post(this::place);
    }

    /** The guide's picture stopped (the guide closed or MovieHub went into the background). */
    void pictureStopped() {
        ExoLive.stopIfOwner(this);
        clearAnchor();
    }

    private void clearAnchor() {
        if (anchor != null) anchor.removeOnLayoutChangeListener(follow);
        anchor = null;
        if (anchorLabel != null && anchorLabel.getParent() == overlay) overlay.removeView(anchorLabel);
        anchorLabel = null;
        if (!full) video.setLayoutParams(hidden());
    }

    /** Puts the picture over the guide's box, or full screen. */
    private void place() {
        if (full) {
            video.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            if (anchorLabel != null) anchorLabel.setVisibility(View.GONE);
            return;
        }
        if (anchor == null || !anchor.isShown() || anchor.getWidth() == 0) {
            video.setLayoutParams(hidden());
            return;
        }
        int[] box = new int[2],
            base = new int[2];
        anchor.getLocationInWindow(box);
        root.getLocationInWindow(base);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            anchor.getWidth(),
            anchor.getHeight(),
            Gravity.TOP | Gravity.START
        );
        lp.leftMargin = box[0] - base[0];
        lp.topMargin = box[1] - base[1];
        video.setLayoutParams(lp);
        if (anchorLabel != null) {
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(
                anchor.getWidth(),
                -2,
                Gravity.TOP | Gravity.START
            );
            label.leftMargin = lp.leftMargin;
            label.topMargin = lp.topMargin + anchor.getHeight() - anchorLabel.getHeight();
            anchorLabel.setLayoutParams(label);
            anchorLabel.setVisibility(View.VISIBLE);
        }
    }

    // ---- Full screen ---------------------------------------------------------------------------

    boolean full() {
        return full;
    }

    /** Watches {@code channelUrl} full screen on the same picture (MainActivity.play). */
    void fullScreen(String channelTitle, String channelUrl, String id) {
        url = channelUrl;
        title = channelTitle;
        focusBeforeFull = a.getCurrentFocus();
        full = true;
        liveFailed = false;
        pageHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pageHost.setVisibility(View.INVISIBLE);
        place();
        LiveZapper.Session guide =
            LiveZapper.session == null
                ? null
                : LiveGuideState.restore(LiveGuideState.save(LiveZapper.session));
        if (guide == null) guide = new LiveZapper.Session("all", "", java.util.Collections.emptySet(), 0);
        zapper = new LiveZapper(this, guide);
        zapper.attach(bannerHost);
        overlay.setFocusable(false);
        video.setFocusable(true);
        video.setFocusableInTouchMode(true);
        video.requestFocus();
        ExoLive.attach(this, surface, events);
        if (!ExoLive.play(a, url, false)) opening("Opening channel…");
        else status.setVisibility(View.GONE);
    }

    /** BACK from full screen: the guide, exactly as it was, with the picture back in its box. */
    @Override
    public void leave() {
        exitFull(true);
    }

    private void exitFull(boolean backToGuide) {
        if (!full) return;
        full = false;
        SidePanel.closeFor(a);
        if (zapper != null) zapper.close();
        zapper = null;
        overlayHandler.removeCallbacksAndMessages(null);
        bannerHost.removeAllViews();
        status.setVisibility(View.GONE);
        video.setFocusable(false);
        pageHost.setVisibility(View.VISIBLE);
        pageHost.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        place();
        if (!backToGuide) return;
        if (focusBeforeFull != null && focusBeforeFull.isAttachedToWindow()) focusBeforeFull.requestFocus();
        // As on returning from a separate player screen: the guide moves to the channel watched
        // last (MainActivity.onResume runs this there), and its label names it.
        Runnable refresh = a.resumeRefresh;
        a.resumeRefresh = null;
        if (refresh != null) refresh.run();
        a.liveTv.startPicture();
    }

    /** MovieHub went into the background while watching. */
    void stopped() {
        ExoLive.stopIfOwner(this);
    }

    /** MovieHub came back while watching full screen. */
    void started() {
        if (!full || url == null) return;
        ExoLive.attach(this, surface, events);
        if (!ExoLive.play(a, url, false)) opening("Opening channel…");
    }

    /** Shows which button arrived (every key, before anything else uses it). */
    void received(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
        String text =
            "Last button: " + KeyEvent.keyCodeToString(event.getKeyCode()) + " · scan " + event.getScanCode();
        lastButton.setText(text);
        if (zapper != null && zapper.overlay != null) zapper.overlay.lastButton(
            "Last button received: " +
                KeyEvent.keyCodeToString(event.getKeyCode()) +
                " · scan " +
                event.getScanCode()
        );
    }

    /** Keys while watching full screen (the satellite remote, see LiveZapper.key). */
    boolean key(KeyEvent event) {
        if (zapper == null) return false;
        if (zapper.colourKey(event, zapper::colour)) return true;
        int code = event.getKeyCode();
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (liveFailed && select && !zapper.browsing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!zapper.retryFromFirst()) reconnect();
            }
            return true;
        }
        if (zapper.key(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY) ExoLive.resume();
            else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) ExoLive.pause();
            else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) toggle();
            else return false;
            zapper.showBanner();
            return true;
        }
        return false;
    }

    /** How long a channel may take to start before the viewer is told it may be unavailable. */
    static final long SLOW_MS = 10_000;

    private final Runnable slow = () -> {
        if (!full || liveFailed || ExoLive.isPlaying() || status.getVisibility() != View.VISIBLE) return;
        status.setText(
            "Still finding this channel's link…\nIt may not be available right now.\n\nBACK  guide     ▲ ▼  another channel"
        );
    };

    /** Says the channel is opening; after {@link #SLOW_MS} says it may not be available, and keeps waiting. */
    private void opening(String text) {
        status.setText(text);
        status.setBackgroundColor(Color.TRANSPARENT);
        status.setVisibility(View.VISIBLE);
        overlayHandler.removeCallbacks(slow);
        overlayHandler.postDelayed(slow, SLOW_MS);
    }

    private void reconnect() {
        if (url == null) return;
        liveFailed = false;
        opening("Reconnecting…");
        ExoLive.attach(this, surface, events);
        ExoLive.play(a, url, true);
    }

    private void showError(String reason) {
        if (!full) return;
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

    // ---- LiveScreen ----------------------------------------------------------------------------

    @Override
    public Activity activity() {
        return a;
    }

    @Override
    public Handler overlayHandler() {
        return overlayHandler;
    }

    @Override
    public FrameLayout video() {
        return video;
    }

    @Override
    public LiveZapper zapper() {
        return zapper;
    }

    @Override
    public boolean dialogOpen() {
        return dialogOpen;
    }

    @Override
    public void dialogOpen(boolean open) {
        dialogOpen = open;
    }

    @Override
    public boolean liveFailed() {
        return liveFailed;
    }

    @Override
    public int dp(int n) {
        return a.dp((float) n);
    }

    @Override
    public LiveMenus liveMenus() {
        return menus;
    }

    @Override
    public void switchTo(String newTitle, String newUrl, String newId) {
        try {
            url = Playlist.networkUrl(newUrl, a.http());
        } catch (Exception e) {
            if (zapper == null || !zapper.failover("This stream's address is not allowed")) showError(
                "This channel's address is not allowed. Turn on Allow HTTP in Settings → System to play it."
            );
            return;
        }
        title = newTitle;
        liveFailed = false;
        opening("Opening channel…");
        ExoLive.attach(this, surface, events);
        ExoLive.play(a, url, true);
    }

    @Override
    public String playbackUrl() {
        return url;
    }

    @Override
    public void panelClosed() {
        dialogOpen = false;
        if (full && zapper != null) zapper.showBanner();
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

    @Override
    public String title() {
        return title;
    }
}
