package local.tv.home;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Date;

/**
 * What the full-screen Live TV picture shows, in the style of the classic UK satellite box:
 * the channel banner (a light-blue title strip with the yellow channel number, name and clock,
 * over a deep-blue panel with Now and Next, how far through the programme is, and the colour
 * keys), the "i" programme information panel, and the pause bar. Static drawables only, so it
 * costs nothing while hidden on slower boxes.
 */
final class SkyOverlay {

    /** Classic satellite-guide colours. */
    static final int STRIP_TOP = 0xff4f86e0,
        STRIP_BOTTOM = 0xff2350b4,
        PANEL_TOP = 0xf0102a7c,
        PANEL_BOTTOM = 0xf00a1a55,
        EDGE = 0x99bcd2ff,
        TIME = 0xff9fd4ff,
        SOFT = 0xffc9d8ff,
        RED = 0xffe53935,
        GREEN = 0xff43a047,
        BLUE = 0xff1e63d6;

    private final VlcPlaybackActivity a;
    private final LinearLayout banner, info, pauseBar, keys;
    private final TextView number, name, clock, nowTime, nowTitle, nextTime, nextTitle, about, source, hint;
    private final TextView infoTitle, infoWhen, infoAbout, infoChannel, pauseIcon, pauseText;
    private final ProgressBar progress;

    SkyOverlay(VlcPlaybackActivity activity, FrameLayout root) {
        a = activity;
        LinearLayout stack = new LinearLayout(a);
        stack.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams stackLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        stackLayout.setMargins(a.dp(56), 0, a.dp(56), a.dp(34));
        root.addView(stack, stackLayout);

        // The "i" panel: programme information above the banner.
        info = panel();
        LinearLayout infoStrip = strip();
        TextView infoHeading = label(17, true, Color.WHITE);
        infoHeading.setText("Programme information");
        infoStrip.addView(infoHeading, new LinearLayout.LayoutParams(0, -2, 1));
        infoChannel = label(17, true, LiveTvScreen.YELLOW);
        infoStrip.addView(infoChannel);
        info.addView(infoStrip);
        LinearLayout infoBody = body();
        infoTitle = label(24, true, Color.WHITE);
        infoBody.addView(infoTitle);
        infoWhen = label(16, false, TIME);
        infoBody.addView(infoWhen);
        infoAbout = label(17, false, Color.WHITE);
        infoAbout.setSingleLine(false);
        infoAbout.setMaxLines(8);
        infoAbout.setLineSpacing(0, 1.15f);
        infoAbout.setPadding(0, a.dp(8), 0, 0);
        infoBody.addView(infoAbout);
        info.addView(infoBody);
        stack.addView(info, spaced());

        // The pause bar, as the satellite recorders showed when live TV was paused.
        pauseBar = panel();
        LinearLayout pauseRow = body();
        pauseRow.setOrientation(LinearLayout.HORIZONTAL);
        pauseRow.setGravity(Gravity.CENTER_VERTICAL);
        pauseIcon = label(22, true, LiveTvScreen.YELLOW);
        pauseIcon.setMinWidth(a.dp(44));
        pauseRow.addView(pauseIcon);
        pauseText = label(18, true, Color.WHITE);
        pauseRow.addView(pauseText, new LinearLayout.LayoutParams(0, -2, 1));
        pauseBar.addView(pauseRow);
        stack.addView(pauseBar, spaced());

        // The banner.
        banner = panel();
        LinearLayout title = strip();
        number = label(26, true, LiveTvScreen.YELLOW);
        number.setMinWidth(a.dp(78));
        title.addView(number);
        name = label(24, true, Color.WHITE);
        title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        source = label(15, false, Color.WHITE);
        source.setPadding(a.dp(12), 0, a.dp(16), 0);
        title.addView(source);
        clock = label(20, true, Color.WHITE);
        title.addView(clock);
        banner.addView(title);
        LinearLayout content = body();
        LinearLayout nowRow = row();
        nowTime = label(18, true, TIME);
        nowTime.setMinWidth(a.dp(150));
        nowRow.addView(nowTime);
        nowTitle = label(20, true, Color.WHITE);
        nowRow.addView(nowTitle, new LinearLayout.LayoutParams(0, -2, 1));
        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(LiveTvScreen.YELLOW));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x55ffffff));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(a.dp(180), a.dp(8));
        progressLayout.leftMargin = a.dp(16);
        nowRow.addView(progress, progressLayout);
        content.addView(nowRow);
        LinearLayout nextRow = row();
        nextTime = label(18, false, TIME);
        nextTime.setMinWidth(a.dp(150));
        nextRow.addView(nextTime);
        nextTitle = label(19, false, SOFT);
        nextRow.addView(nextTitle, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(nextRow);
        about = label(16, false, SOFT);
        about.setSingleLine(false);
        about.setMaxLines(2);
        about.setPadding(0, a.dp(4), 0, 0);
        content.addView(about);
        keys = row();
        keys.setPadding(0, a.dp(10), 0, 0);
        colourKey(RED, "Audio & subtitles");
        colourKey(GREEN, "Source");
        colourKey(LiveTvScreen.YELLOW, "Information");
        colourKey(BLUE, "Options");
        content.addView(keys);
        hint = label(16, true, LiveTvScreen.YELLOW);
        hint.setPadding(0, a.dp(8), 0, 0);
        hint.setText("SELECT to watch   ▲ ▼ channels   ◀ ▶ later programmes   BACK to close");
        content.addView(hint);
        banner.addView(content);
        stack.addView(banner);

        info.setVisibility(View.GONE);
        pauseBar.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
    }

    // ---- Banner -------------------------------------------------------------------------------

    /**
     * Shows the banner. {@code progress} is how far through the programme on now is (0–1000), or
     * -1 when it is not on now. {@code browsing} swaps the colour keys for the browsing keys.
     */
    void banner(
        String channelNumber,
        String channelName,
        String now,
        String nowAt,
        String next,
        String nextAt,
        String description,
        int progressValue,
        String sourceText,
        boolean browsing
    ) {
        number.setText(channelNumber);
        name.setText(channelName);
        clock.setText(LiveTvScreen.clockTime(new Date()));
        nowTime.setText(nowAt);
        nowTitle.setText(now);
        nextTime.setText(nextAt);
        nextTitle.setText(next);
        nextTime.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        nextTitle.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        about.setText(description);
        about.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(progressValue < 0 ? View.GONE : View.VISIBLE);
        if (progressValue >= 0) progress.setProgress(progressValue);
        source.setText(sourceText);
        keys.setVisibility(browsing ? View.GONE : View.VISIBLE);
        hint.setVisibility(browsing ? View.VISIBLE : View.GONE);
        banner.setVisibility(View.VISIBLE);
    }

    /** The channel number being typed, shown where the number goes. */
    void typing(String digits) {
        banner("", "", "", "", "", "", "", -1, "", false);
        number.setText(digits + "–");
        keys.setVisibility(View.GONE);
        nowTime.setVisibility(View.GONE);
    }

    void hideBanner() {
        banner.setVisibility(View.GONE);
        nowTime.setVisibility(View.VISIBLE);
    }

    boolean bannerShowing() {
        return banner.getVisibility() == View.VISIBLE;
    }

    // ---- Programme information -----------------------------------------------------------------

    void info(String channel, String programme, String when, String description) {
        infoChannel.setText(channel);
        infoTitle.setText(programme);
        infoWhen.setText(when);
        infoAbout.setText(description.isEmpty() ? "No description for this programme." : description);
        info.setVisibility(View.VISIBLE);
    }

    void hideInfo() {
        info.setVisibility(View.GONE);
    }

    boolean infoShowing() {
        return info.getVisibility() == View.VISIBLE;
    }

    // ---- Pause bar ------------------------------------------------------------------------------

    /** Paused, or playing behind the live broadcast; hidden when live. */
    void pause(boolean paused, boolean behindLive) {
        if (!paused && !behindLive) {
            pauseBar.setVisibility(View.GONE);
            return;
        }
        pauseIcon.setText(paused ? "❚❚" : "▶");
        pauseText.setText(
            paused
                ? "PAUSED   ·   Press ▶ to carry on"
                : "BEHIND LIVE   ·   Press ▶▶ or Options → Back to live to catch up"
        );
        pauseBar.setVisibility(View.VISIBLE);
    }

    boolean pauseShowing() {
        return pauseBar.getVisibility() == View.VISIBLE;
    }

    // ---- Building blocks ------------------------------------------------------------------------

    /** A rounded panel with a thin light edge; its strip and body go inside. */
    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            PANEL_TOP,
            PANEL_BOTTOM,
        });
        shape.setCornerRadius(a.dp(8));
        shape.setStroke(a.dp(2), EDGE);
        panel.setBackground(shape);
        panel.setClipToOutline(true);
        return panel;
    }

    /** The light-blue title strip across the top of a panel. */
    private LinearLayout strip() {
        LinearLayout strip = row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(8));
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
            STRIP_TOP,
            STRIP_BOTTOM,
        });
        float r = a.dp(7);
        shape.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        strip.setBackground(shape);
        return strip;
    }

    private LinearLayout body() {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(14));
        return body;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = a.dp(10);
        return layout;
    }

    private void colourKey(int colour, String text) {
        View swatch = new View(a);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(a.dp(2));
        shape.setStroke(a.dp(1), 0x66ffffff);
        swatch.setBackground(shape);
        LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(a.dp(16), a.dp(16));
        swatchLayout.rightMargin = a.dp(6);
        keys.addView(swatch, swatchLayout);
        TextView label = label(15, true, Color.WHITE);
        label.setText(text);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-2, -2);
        labelLayout.rightMargin = a.dp(26);
        keys.addView(label, labelLayout);
    }

    private TextView label(int size, boolean bold, int colour) {
        TextView text = new TextView(a);
        text.setTextColor(colour);
        text.setTextSize(size);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
}
