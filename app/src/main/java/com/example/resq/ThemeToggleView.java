package com.example.resq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

/**
 * Small one-tap sun / moon pill. The crimson knob slides to the sun (light) or the moon (dark);
 * after the slide the whole app switches theme (ThemeHelper). Drawn in code, so it needs no images.
 */
public class ThemeToggleView extends View {

    private final float dp;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Path outer = new Path();
    private final Path inner = new Path();
    private final int colDim;
    private final int colOn;

    private boolean dark;
    private float progress;        // 0 = light (knob on the sun), 1 = dark (knob on the moon)
    private ValueAnimator anim;

    public ThemeToggleView(Context context) { this(context, null); }

    public ThemeToggleView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public ThemeToggleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        dp = context.getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.pink_card));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp);
        strokePaint.setColor(ContextCompat.getColor(context, R.color.pink_stroke));
        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(ContextCompat.getColor(context, R.color.brand_primary));
        iconFill.setStyle(Paint.Style.FILL);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeWidth(1.5f * dp);
        colDim = ContextCompat.getColor(context, R.color.text_secondary);
        colOn = ContextCompat.getColor(context, R.color.white);

        dark = ThemeHelper.isDark(context);
        progress = dark ? 1f : 0f;
        setClickable(true);
        setFocusable(true);
        setContentDescription("Dark mode");
        setOnClickListener(v -> toggle());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) (64 * dp), widthMeasureSpec),
                resolveSize((int) (40 * dp), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pw = 52 * dp;
        float ph = 28 * dp;
        float left = (getWidth() - pw) / 2f;
        float top = (getHeight() - ph) / 2f;
        float r = ph / 2f;
        pill.set(left, top, left + pw, top + ph);

        canvas.drawRoundRect(pill, r, r, trackPaint);
        float half = dp / 2f;
        canvas.drawRoundRect(pill.left + half, pill.top + half, pill.right - half,
                pill.bottom - half, r - half, r - half, strokePaint);

        float knobR = r - 3 * dp;
        float sunX = left + r;
        float moonX = left + pw - r;
        float cy = top + r;
        float knobX = sunX + (moonX - sunX) * progress;
        canvas.drawCircle(knobX, cy, knobR, knobPaint);

        // the icon under the knob turns white, the other one stays soft
        drawSun(canvas, sunX, cy, blend(knobX, sunX, knobR));
        drawMoon(canvas, moonX, cy, blend(knobX, moonX, knobR));
    }

    private int blend(float knobX, float iconX, float knobR) {
        float t = 1f - Math.min(1f, Math.abs(knobX - iconX) / (knobR * 1.6f));
        return ColorUtils.blendARGB(colDim, colOn, t);
    }

    private void drawSun(Canvas canvas, float cx, float cy, int color) {
        iconFill.setColor(color);
        iconStroke.setColor(color);
        canvas.drawCircle(cx, cy, 3.4f * dp, iconFill);
        canvas.save();
        canvas.rotate(90f * progress, cx, cy);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            canvas.drawLine(cx + c * 5.4f * dp, cy + s * 5.4f * dp,
                    cx + c * 7f * dp, cy + s * 7f * dp, iconStroke);
        }
        canvas.restore();
    }

    private void drawMoon(Canvas canvas, float cx, float cy, int color) {
        iconFill.setColor(color);
        outer.reset();
        inner.reset();
        outer.addCircle(cx, cy, 6f * dp, Path.Direction.CW);
        inner.addCircle(cx + 3.2f * dp, cy - 2.4f * dp, 5f * dp, Path.Direction.CW);
        outer.op(inner, Path.Op.DIFFERENCE);
        canvas.drawPath(outer, iconFill);
    }

    private void toggle() {
        if (anim != null && anim.isRunning()) return;
        final boolean toDark = !dark;
        anim = ValueAnimator.ofFloat(progress, toDark ? 1f : 0f);
        anim.setDuration(220);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }

            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                dark = toDark;
                // saves the choice and re-creates the open screens in the new theme
                ThemeHelper.setDark(getContext(), toDark);
            }
        });
        anim.start();
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        super.dispatchSetPressed(pressed);
        setAlpha(pressed ? 0.7f : 1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(dark);
    }
}
