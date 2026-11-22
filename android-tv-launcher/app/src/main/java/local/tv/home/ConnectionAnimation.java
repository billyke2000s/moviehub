package local.tv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The moving picture on the "Connect to the internet" panel ({@link ConnectionPrompt}): on the left
 * a Wi-Fi sign whose waves light up one after another, on the right a cable plug sliding into a
 * socket. Drawn in code, so it needs no image files. It animates only while on screen.
 */
final class ConnectionAnimation extends View {

    /** One full cycle of the waves and the plug, in milliseconds. */
    static final long CYCLE_MS = 2400;

    private static final int DIM = 0x33f2f4f8;
    private static final int TEXT = 0xffb4bfd2;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int accent;
    private ValueAnimator animator;
    /** Where the cycle is, from 0 to 1. */
    private float phase;

    ConnectionAnimation(Context context, int accent) {
        super(context);
        this.accent = accent;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        words.setColor(TEXT);
        words.setTextAlign(Paint.Align.CENTER);
        words.setTextSize(dp(15));
        setFocusable(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        animator = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(150));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float labelY = getHeight() - dp(8);
        float left = width * 0.27f;
        float right = width * 0.68f;
        wifi(canvas, left, dp(96));
        cable(canvas, right, dp(78));
        canvas.drawText("Wi-Fi", left, labelY, words);
        canvas.drawText("or", width / 2, labelY, words);
        canvas.drawText("Cable", right + dp(34), labelY, words);
    }

    /** A dot and three waves above it; one more wave lights up each quarter of the cycle. */
    private void wifi(Canvas canvas, float x, float y) {
        int lit = (int) (phase * 4); // 0: dot only … 3: all three waves
        fill.setColor(accent);
        canvas.drawCircle(x, y, dp(6), fill);
        stroke.setStrokeWidth(dp(7));
        for (int wave = 1; wave <= 3; wave++) {
            float radius = dp(18 * wave);
            oval.set(x - radius, y - radius, x + radius, y + radius);
            stroke.setColor(wave <= lit ? accent : DIM);
            canvas.drawArc(oval, 225, 90, false, stroke);
        }
    }

    /** A wall socket with a plug that slides in, rests, then slides out again. */
    private void cable(Canvas canvas, float x, float y) {
        // Socket: a square with a dark opening.
        float half = dp(26);
        fill.setColor(DIM);
        oval.set(x + dp(8), y - half, x + dp(8) + half * 2, y + half);
        canvas.drawRoundRect(oval, dp(6), dp(6), fill);
        fill.setColor(0xff101722);
        oval.set(x + dp(18), y - dp(12), x + dp(42), y + dp(12));
        canvas.drawRect(oval, fill);

        // Slide in over the first 40 %, rest, slide out over the last 20 %.
        float in;
        if (phase < 0.4f) in = phase / 0.4f;
        else if (phase < 0.8f) in = 1f;
        else in = 1f - (phase - 0.8f) / 0.2f;
        in = in * in * (3 - 2 * in); // ease in and out
        float tip = x + dp(18) - dp(46) * (1 - in);

        boolean plugged = in > 0.98f;
        int colour = plugged ? accent : 0xfff2f4f8;
        // Cable trailing to the left, then the plug body and its clip.
        stroke.setStrokeWidth(dp(6));
        stroke.setColor(colour);
        canvas.drawLine(tip - dp(80), y, tip - dp(26), y, stroke);
        fill.setColor(colour);
        oval.set(tip - dp(28), y - dp(14), tip, y + dp(14));
        canvas.drawRoundRect(oval, dp(4), dp(4), fill);
        oval.set(tip, y - dp(10), tip + dp(22) * Math.min(1f, in + 0.4f), y + dp(10));
        canvas.drawRect(oval, fill);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}
