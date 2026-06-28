package com.example.lemm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A small looping illustration that shows, in simple visual terms, what each onboarding step is
 * about (drawing a shape, extruding it to 3D, an angle's value, the AI solving, saved history).
 * Pure Canvas drawing driven by one ValueAnimator — no external animation library.
 */
public class OnboardingAnimationView extends View {
    public static final int TYPE_WELCOME = 0;
    public static final int TYPE_AI = 1;
    public static final int TYPE_DRAW = 2;
    public static final int TYPE_3D = 3;
    public static final int TYPE_THEOREMS = 4;
    public static final int TYPE_HISTORY = 5;

    private int type = TYPE_WELCOME;
    private float p = 0f; // animation progress 0..1
    private ValueAnimator animator;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OnboardingAnimationView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE); stroke.setColor(0xFFFFFFFF); stroke.setStrokeWidth(8f); stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL); fill.setColor(0x33FFFFFF);
        accent.setStyle(Paint.Style.STROKE); accent.setColor(0xFFFFC107); accent.setStrokeWidth(8f); accent.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF); text.setTextAlign(Paint.Align.CENTER); text.setFakeBoldText(true);
    }

    public void setType(int t) { this.type = t; restart(); }

    private void restart() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2200);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> { p = (float) a.getAnimatedValue(); invalidate(); });
        }
        animator.cancel();
        p = 0f;
        animator.start();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); restart(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (animator != null) animator.cancel(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float s = Math.min(w, h) * 0.32f; // base size
        text.setTextSize(s * 0.32f);

        switch (type) {
            case TYPE_AI:       drawAi(c, cx, cy, s); break;
            case TYPE_DRAW:     drawSketch(c, cx, cy, s); break;
            case TYPE_3D:       drawExtrude(c, cx, cy, s); break;
            case TYPE_THEOREMS: drawTheorem(c, cx, cy, s); break;
            case TYPE_HISTORY:  drawHistory(c, cx, cy, s); break;
            default:            drawWelcome(c, cx, cy, s); break;
        }
    }

    // Three shapes fading/scaling in one after another.
    private void drawWelcome(Canvas c, float cx, float cy, float s) {
        float[] starts = {0f, 0.25f, 0.5f};
        for (int i = 0; i < 3; i++) {
            float local = clamp((p - starts[i]) / 0.35f);
            if (local <= 0) continue;
            float sc = ease(local);
            float ox = cx + (i - 1) * s * 1.15f;
            c.save();
            c.scale(sc, sc, ox, cy);
            if (i == 0) c.drawCircle(ox, cy, s * 0.5f, stroke);
            else if (i == 1) {
                Path tri = new Path();
                tri.moveTo(ox, cy - s * 0.5f); tri.lineTo(ox + s * 0.5f, cy + s * 0.45f);
                tri.lineTo(ox - s * 0.5f, cy + s * 0.45f); tri.close();
                c.drawPath(tri, stroke);
            } else c.drawRect(ox - s * 0.45f, cy - s * 0.45f, ox + s * 0.45f, cy + s * 0.45f, stroke);
            c.restore();
        }
    }

    // A "page" with a question mark that turns into checked steps.
    private void drawAi(Canvas c, float cx, float cy, float s) {
        RectF page = new RectF(cx - s, cy - s * 1.2f, cx + s, cy + s * 1.2f);
        c.drawRoundRect(page, 18f, 18f, fill);
        c.drawRoundRect(page, 18f, 18f, stroke);
        if (p < 0.45f) {
            text.setTextSize(s * 1.1f);
            text.setAlpha((int) (255 * (1 - p / 0.45f)));
            c.drawText("?", cx, cy + s * 0.4f, text);
            text.setAlpha(255);
        } else {
            float t = (p - 0.45f) / 0.55f;
            for (int i = 0; i < 3; i++) {
                float ly = cy - s * 0.5f + i * s * 0.6f;
                float lt = clamp((t - i * 0.25f) / 0.25f);
                if (lt <= 0) continue;
                c.drawLine(cx - s * 0.6f, ly, cx - s * 0.6f + lt * s * 1.1f, ly, accent);
            }
        }
    }

    // A triangle drawn stroke-by-stroke.
    private void drawSketch(Canvas c, float cx, float cy, float s) {
        Path tri = new Path();
        tri.moveTo(cx, cy - s); tri.lineTo(cx + s, cy + s * 0.8f);
        tri.lineTo(cx - s, cy + s * 0.8f); tri.close();
        PathMeasure pm = new PathMeasure(tri, false);
        float len = pm.getLength();
        Path dst = new Path();
        pm.getSegment(0, len * ease(p), dst, true);
        c.drawPath(dst, stroke);
        // vertices pop in
        drawDot(c, cx, cy - s, p > 0.05f);
        drawDot(c, cx + s, cy + s * 0.8f, p > 0.45f);
        drawDot(c, cx - s, cy + s * 0.8f, p > 0.8f);
    }

    // A flat square that lifts into a cube (extrude).
    private void drawExtrude(Canvas c, float cx, float cy, float s) {
        float lift = ease(p) * s * 1.1f;
        float dx = s * 0.45f, dy = s * 0.3f; // iso offset for the top face
        // bottom face
        float bl = cx - s * 0.6f, br = cx + s * 0.6f, bt = cy + s * 0.55f, bb = cy + s * 0.95f;
        c.drawRect(bl, bt, br, bb, stroke);
        if (lift > 2) {
            float tl = bl + dx, tr = br + dx, tt = bt - lift - dy, tb = bb - lift - dy;
            c.drawRect(tl, tt, tr, tb, accent);
            // verticals connecting the faces
            c.drawLine(bl, bt, tl, tt, stroke);
            c.drawLine(br, bt, tr, tt, stroke);
            c.drawLine(bl, bb, tl, tb, stroke);
            c.drawLine(br, bb, tr, tb, stroke);
        }
    }

    // A triangle with an angle arc that sweeps to its value.
    private void drawTheorem(Canvas c, float cx, float cy, float s) {
        float ax = cx - s, ay = cy + s * 0.8f;     // vertex with the angle
        float bx = cx + s, by = cy + s * 0.8f;
        float tx = cx, ty = cy - s;
        Path tri = new Path(); tri.moveTo(ax, ay); tri.lineTo(bx, by); tri.lineTo(tx, ty); tri.close();
        c.drawPath(tri, stroke);
        float sweep = 50f * ease(p);
        RectF arc = new RectF(ax - s * 0.5f, ay - s * 0.5f, ax + s * 0.5f, ay + s * 0.5f);
        c.drawArc(arc, -50, sweep, false, accent);
        if (p > 0.6f) {
            accent.setStyle(Paint.Style.FILL); text.setTextSize(s * 0.32f); text.setColor(0xFFFFC107);
            c.drawText("50°", ax + s * 0.5f, ay - s * 0.15f, text);
            text.setColor(0xFFFFFFFF); accent.setStyle(Paint.Style.STROKE);
        }
    }

    // Three cards sliding in and stacking.
    private void drawHistory(Canvas c, float cx, float cy, float s) {
        for (int i = 0; i < 3; i++) {
            float lt = clamp((p - i * 0.18f) / 0.5f);
            if (lt <= 0) continue;
            float offX = (1 - ease(lt)) * s * 2.2f;
            float top = cy - s * 0.9f + i * s * 0.7f;
            RectF card = new RectF(cx - s * 1.1f + offX, top, cx + s * 1.1f + offX, top + s * 0.55f);
            c.drawRoundRect(card, 12f, 12f, fill);
            c.drawRoundRect(card, 12f, 12f, stroke);
        }
    }

    private void drawDot(Canvas c, float x, float y, boolean show) {
        if (!show) return;
        accent.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, 10f, accent);
        accent.setStyle(Paint.Style.STROKE);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float ease(float v) { v = clamp(v); return v * v * (3 - 2 * v); } // smoothstep
}
