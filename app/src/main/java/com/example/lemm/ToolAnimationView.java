package com.example.lemm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A small looping illustration that demonstrates, in plain visual terms, what a single editor tool
 * does — pan the canvas, draw a line/rectangle/circle, select & dimension, snap to 90°, mark an angle,
 * extrude to 3D, undo/redo, delete, clear, save. Same technique as {@link OnboardingAnimationView}:
 * pure Canvas drawing driven by one looping ValueAnimator, white outline + amber accent on the blue
 * onboarding gradient. Call {@link #setType(int)} with one of the TYPE_* constants.
 */
public class ToolAnimationView extends View {
    public static final int TYPE_PAN = 0;
    public static final int TYPE_LINE = 1;
    public static final int TYPE_RECT = 2;
    public static final int TYPE_CIRCLE = 3;
    public static final int TYPE_SELECT = 4;
    public static final int TYPE_ORTHO = 5;
    public static final int TYPE_ANGLE = 6;
    public static final int TYPE_EXTRUDE = 7;
    public static final int TYPE_UNDO = 8;
    public static final int TYPE_REDO = 9;
    public static final int TYPE_DELETE = 10;
    public static final int TYPE_CLEAR = 11;
    public static final int TYPE_SAVE = 12;
    public static final int TYPE_DIM = 13;

    private int type = TYPE_PAN;
    private float p = 0f; // animation progress 0..1
    private ValueAnimator animator;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ToolAnimationView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE); stroke.setColor(0xFFFFFFFF); stroke.setStrokeWidth(8f); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL); fill.setColor(0x33FFFFFF);
        accent.setStyle(Paint.Style.STROKE); accent.setColor(0xFFFFC107); accent.setStrokeWidth(8f); accent.setStrokeCap(Paint.Cap.ROUND); accent.setStrokeJoin(Paint.Join.ROUND);
        dashed.setStyle(Paint.Style.STROKE); dashed.setColor(0xFFFFC107); dashed.setStrokeWidth(6f); dashed.setPathEffect(new DashPathEffect(new float[]{16f, 12f}, 0f));
        text.setColor(0xFFFFFFFF); text.setTextAlign(Paint.Align.CENTER); text.setFakeBoldText(true);
    }

    public void setType(int t) { this.type = t; restart(); }

    private void restart() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2600);
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
        float s = Math.min(w, h) * 0.30f; // base size
        text.setTextSize(s * 0.34f);

        switch (type) {
            case TYPE_LINE:    drawLine(c, cx, cy, s); break;
            case TYPE_RECT:    drawRect(c, cx, cy, s); break;
            case TYPE_CIRCLE:  drawCircle(c, cx, cy, s); break;
            case TYPE_SELECT:  drawSelect(c, cx, cy, s); break;
            case TYPE_ORTHO:   drawOrtho(c, cx, cy, s); break;
            case TYPE_ANGLE:   drawAngle(c, cx, cy, s); break;
            case TYPE_EXTRUDE: drawExtrude(c, cx, cy, s); break;
            case TYPE_UNDO:    drawUndoRedo(c, cx, cy, s, false); break;
            case TYPE_REDO:    drawUndoRedo(c, cx, cy, s, true); break;
            case TYPE_DELETE:  drawDelete(c, cx, cy, s); break;
            case TYPE_CLEAR:   drawClear(c, cx, cy, s); break;
            case TYPE_SAVE:    drawSave(c, cx, cy, s); break;
            case TYPE_DIM:     drawDim(c, cx, cy, s); break;
            default:           drawPan(c, cx, cy, s); break;
        }
    }

    // A finger dragging the grid (pan).
    private void drawPan(Canvas c, float cx, float cy, float s) {
        float shift = (ease(triangle(p)) - 0.5f) * s * 1.2f;
        for (int gx = -1; gx <= 1; gx++)
            for (int gy = -1; gy <= 1; gy++)
                c.drawCircle(cx + gx * s * 0.7f + shift, cy + gy * s * 0.7f, 6f, fill);
        // finger
        accent.setStyle(Paint.Style.FILL);
        c.drawCircle(cx + shift, cy, s * 0.22f, accent);
        accent.setStyle(Paint.Style.STROKE);
    }

    // Two endpoints, a line drawn between them, dragging out.
    private void drawLine(Canvas c, float cx, float cy, float s) {
        float ax = cx - s, ay = cy + s * 0.6f, bx = cx + s, by = cy - s * 0.6f;
        float t = ease(p);
        float ex = ax + (bx - ax) * t, ey = ay + (by - ay) * t;
        c.drawLine(ax, ay, ex, ey, stroke);
        drawDot(c, ax, ay, true);
        drawDot(c, ex, ey, true);
    }

    // A rectangle dragged out from a corner.
    private void drawRect(Canvas c, float cx, float cy, float s) {
        float t = ease(p);
        float ax = cx - s, ay = cy - s * 0.7f;
        float bx = ax + (2 * s) * t, by = ay + (1.6f * s) * t;
        c.drawRect(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by), stroke);
        drawDot(c, ax, ay, true);
        drawDot(c, bx, by, t > 0.05f);
    }

    // A centre dot with a radius dragged out into a circle (matches the finger-draw circle/sphere tool).
    private void drawCircle(Canvas c, float cx, float cy, float s) {
        float t = ease(p);
        float r = s * 1.05f * t;
        float ang = (float) (-Math.PI / 4);
        c.drawCircle(cx, cy, r, dashed);
        c.drawLine(cx, cy, cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, stroke);
        accent.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, 9f, accent);
        accent.setStyle(Paint.Style.STROKE);
        drawDot(c, cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, t > 0.1f);
    }

    // A shape gets tapped → highlighted, with a dimension label appearing.
    private void drawSelect(Canvas c, float cx, float cy, float s) {
        c.drawRect(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.6f, stroke);
        float t = triangle(p);
        if (t > 0.35f) { // tap ripple + highlight one edge
            float rt = clamp((t - 0.35f) / 0.4f);
            accent.setAlpha((int) (255 * (1 - rt)));
            c.drawCircle(cx, cy + s * 0.6f, rt * s * 0.7f, accent);
            accent.setAlpha(255);
            c.drawLine(cx - s, cy + s * 0.6f, cx + s, cy + s * 0.6f, accent); // highlighted bottom edge
            text.setColor(0xFFFFC107); text.setTextSize(s * 0.3f);
            c.drawText("5.0", cx, cy + s * 0.6f + s * 0.4f, text);
            text.setColor(0xFFFFFFFF);
        }
    }

    // A line that snaps to a clean 90° with a small right-angle square.
    private void drawOrtho(Canvas c, float cx, float cy, float s) {
        float ax = cx - s, ay = cy + s * 0.7f;
        // angle eases from a slanted line down to a clean horizontal
        float startAng = -0.6f, ang = startAng * (1 - ease(triangle(p)));
        float bx = ax + (float) Math.cos(ang) * 2 * s, by = ay + (float) Math.sin(ang) * 2 * s;
        c.drawLine(ax, ay, cx + s, ay, fill);         // ghost horizontal target
        c.drawLine(ax, ay, bx, by, stroke);
        drawDot(c, ax, ay, true);
        // right-angle marker once nearly horizontal
        if (Math.abs(ang) < 0.12f) {
            float m = s * 0.28f;
            c.drawLine(ax, ay - m, ax + m, ay - m, accent);
            c.drawLine(ax + m, ay - m, ax + m, ay, accent);
        }
    }

    // Two arms from a vertex, an arc sweeping to its value.
    private void drawAngle(Canvas c, float cx, float cy, float s) {
        float vx = cx - s * 0.6f, vy = cy + s * 0.8f;
        c.drawLine(vx, vy, vx + 2f * s, vy, stroke);             // base arm
        float t = ease(p);
        float deg = 55f * t;
        double rad = Math.toRadians(deg);
        c.drawLine(vx, vy, vx + (float) Math.cos(-rad) * 2f * s, vy + (float) Math.sin(-rad) * 2f * s, stroke);
        RectF arc = new RectF(vx - s * 0.6f, vy - s * 0.6f, vx + s * 0.6f, vy + s * 0.6f);
        c.drawArc(arc, 0, -deg, false, accent);
        if (t > 0.6f) {
            text.setColor(0xFFFFC107); text.setTextSize(s * 0.3f);
            c.drawText(Math.round(deg) + "°", vx + s * 0.95f, vy - s * 0.3f, text);
            text.setColor(0xFFFFFFFF);
        }
    }

    // A flat square lifting into a cube (extrude / open 3D).
    private void drawExtrude(Canvas c, float cx, float cy, float s) {
        float lift = ease(p) * s * 1.1f;
        float dx = s * 0.45f, dy = s * 0.3f;
        float bl = cx - s * 0.6f, br = cx + s * 0.6f, bt = cy + s * 0.45f, bb = cy + s * 0.95f;
        c.drawRect(bl, bt, br, bb, stroke);
        if (lift > 2) {
            float tl = bl + dx, tr = br + dx, tt = bt - lift - dy, tb = bb - lift - dy;
            c.drawRect(tl, tt, tr, tb, accent);
            c.drawLine(bl, bt, tl, tt, stroke);
            c.drawLine(br, bt, tr, tt, stroke);
            c.drawLine(bl, bb, tl, tb, stroke);
            c.drawLine(br, bb, tr, tb, stroke);
        }
    }

    // A curved arrow (undo = back/left, redo = forward/right) over a reverting shape.
    private void drawUndoRedo(Canvas c, float cx, float cy, float s, boolean redo) {
        RectF oval = new RectF(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.7f);
        float startA = redo ? 220 : -40, sweep = (redo ? -1 : 1) * 220 * ease(p);
        Path arcP = new Path();
        arcP.addArc(oval, startA, sweep);
        c.drawPath(arcP, accent);
        // arrow head at the leading tip
        float endA = (float) Math.toRadians(startA + sweep);
        float hx = cx + (float) Math.cos(endA) * s, hy = cy + (float) Math.sin(endA) * s * 0.7f;
        float dir = redo ? 1 : -1;
        c.drawLine(hx, hy, hx - dir * s * 0.28f, hy - s * 0.22f, accent);
        c.drawLine(hx, hy, hx - dir * s * 0.28f, hy + s * 0.22f, accent);
    }

    // A shape with an X that fades and shrinks away.
    private void drawDelete(Canvas c, float cx, float cy, float s) {
        float t = ease(p);
        float sc = 1 - 0.5f * t;
        c.save();
        c.scale(sc, sc, cx, cy);
        stroke.setAlpha((int) (255 * (1 - t)));
        c.drawRect(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.7f, stroke);
        stroke.setAlpha(255);
        c.restore();
        // red-ish X (use accent)
        float m = s * 0.45f;
        accent.setAlpha((int) (255 * clamp(t * 1.5f)));
        c.drawLine(cx - m, cy - m, cx + m, cy + m, accent);
        c.drawLine(cx + m, cy - m, cx - m, cy + m, accent);
        accent.setAlpha(255);
    }

    // Several shapes wiped away left to right.
    private void drawClear(Canvas c, float cx, float cy, float s) {
        float t = ease(p);
        float[] xs = {cx - s * 1.1f, cx, cx + s * 1.1f};
        for (int i = 0; i < 3; i++) {
            float gone = clamp((t - i * 0.12f) / 0.3f);
            stroke.setAlpha((int) (255 * (1 - gone)));
            c.drawCircle(xs[i], cy, s * 0.4f, stroke);
        }
        stroke.setAlpha(255);
        // wipe bar
        float wx = cx - s * 1.6f + t * s * 3.2f;
        c.drawLine(wx, cy - s, wx, cy + s, accent);
    }

    // A document with a down-arrow dropping into a tray, then a check.
    private void drawSave(Canvas c, float cx, float cy, float s) {
        float t = ease(p);
        // tray
        c.drawLine(cx - s, cy + s * 0.7f, cx - s, cy + s, stroke);
        c.drawLine(cx - s, cy + s, cx + s, cy + s, stroke);
        c.drawLine(cx + s, cy + s, cx + s, cy + s * 0.7f, stroke);
        // arrow dropping in
        float ay = cy - s * 0.8f + t * s * 1.2f;
        c.drawLine(cx, cy - s * 0.8f, cx, ay, accent);
        c.drawLine(cx, ay, cx - s * 0.22f, ay - s * 0.22f, accent);
        c.drawLine(cx, ay, cx + s * 0.22f, ay - s * 0.22f, accent);
        if (t > 0.85f) { // check
            c.drawLine(cx - s * 0.2f, cy + s * 0.85f, cx - s * 0.02f, cy + s, accent);
            c.drawLine(cx - s * 0.02f, cy + s, cx + s * 0.3f, cy + s * 0.6f, accent);
        }
    }

    // An edge with a measurement label appearing (dimension toggle).
    private void drawDim(Canvas c, float cx, float cy, float s) {
        float ax = cx - s, ay = cy, bx = cx + s, by = cy;
        c.drawLine(ax, ay, bx, by, stroke);
        // extension ticks
        c.drawLine(ax, ay - s * 0.2f, ax, ay + s * 0.2f, stroke);
        c.drawLine(bx, by - s * 0.2f, bx, by + s * 0.2f, stroke);
        float t = ease(p);
        if (t > 0.3f) {
            text.setColor(0xFFFFC107); text.setTextSize(s * 0.34f);
            text.setAlpha((int) (255 * clamp((t - 0.3f) / 0.4f)));
            c.drawText("4.0", cx, cy - s * 0.3f, text);
            text.setAlpha(255); text.setColor(0xFFFFFFFF);
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
    /** 0→1→0 ramp so a one-shot gesture plays forward then resets smoothly within the loop. */
    private static float triangle(float v) { return v < 0.5f ? v * 2f : (1f - v) * 2f; }
}
