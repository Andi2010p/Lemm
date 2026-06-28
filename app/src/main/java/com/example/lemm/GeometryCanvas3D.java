package com.example.lemm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class GeometryCanvas3D extends View {
    private Paint linePaint, pointPaint, planePaint, textPaint;
    private Paint xAxisPaint, yAxisPaint, zAxisPaint, axesTextPaint;
    private int colBg3d, colLine3d, colText3d;

    private List<Point3D> points = new ArrayList<>();
    private List<Line3D> lines = new ArrayList<>();
    private List<Plane3D> planes = new ArrayList<>();
    private List<Cone3D> cones = new ArrayList<>();
    private List<Pyramid3D> pyramids = new ArrayList<>();
    private List<Cylinder3D> cylinders = new ArrayList<>();
    private List<Circle3D> circles = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();

    private float rotateX = -25f, rotateY = 45f, rotateZ = 0f;
    private float scaleFactor = 1.0f;
    private float translateX = 0f, translateY = 0f;
    private boolean isMoveMode = false;

    // Multi-touch tracking to prevent jumping
    private float lastTouchX, lastTouchY;
    private float downX, downY;
    private boolean movedSinceDown = false;
    private int activePointerId = -1;
    private ScaleGestureDetector scaleDetector;

    // --- Selection / inspection (CAD-style "tap to get info") ---
    private Paint selPaint;
    private int selectedPointIndex = -1;
    private int selectedLineIndex = -1;
    private boolean selectionEnabled = true;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    /** Fired when the user taps an element (or empty space, which clears the selection). */
    public interface OnElementSelectedListener {
        /** info is a ready-to-display, localized multi-line string, or null when nothing is selected. */
        void onElementSelected(String info);
    }
    private OnElementSelectedListener selectionListener;
    public void setOnElementSelectedListener(OnElementSelectedListener l) { this.selectionListener = l; }

    /** When false, taps never select (pure viewer). Default true. */
    public void setSelectionEnabled(boolean enabled) { this.selectionEnabled = enabled; }

    public static class Point3D {
        public String label; float x, y, z, sx, sy; boolean isVertex;
        Point3D(String l, float x, float y, float z) { this.label = l; this.x = x; this.y = y; this.z = z; this.isVertex = l != null && !l.isEmpty(); }
    }
    private static class Line3D {
        String a, b;
        Integer color; // null = use the default 3D line color
        Line3D(String a, String b, Integer color) { this.a = a; this.b = b; this.color = color; }
    }
    private static class Plane3D { List<String> labels; Plane3D(List<String> l) { this.labels = l; } }
    private static class Cone3D { String label; float cx, cy, cz, r, h, curvature; Cone3D(String l, float x, float y, float z, float r, float h, float cur) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; this.curvature = cur; } }
    private static class Pyramid3D { String label; float cx, cy, cz, w, d, h; Pyramid3D(String l, float x, float y, float z, float w, float d, float h) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.w = w; this.d = d; this.h = h; } }
    private static class Cylinder3D { String label; float cx, cy, cz, r, h; Cylinder3D(String l, float x, float y, float z, float r, float h) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; } }
    private static class Circle3D { String label; float cx, cy, cz, r; boolean ground; Circle3D(String l, float x, float y, float z, float r, boolean ground) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.ground = ground; } }
    private static class Sphere3D { String label; float x, y, z, r; Sphere3D(String l, float x, float y, float z, float r) { this.label = l; this.x = x; this.y = y; this.z = z; this.r = r; } }
    /** A 2D profile (in the XZ plane at baseY) extruded by `height` along +Y — the result of an Extrude. */
    private static class Prism3D { String label; float[] xs, zs; float baseY, height; Prism3D(String l, float[] xs, float[] zs, float baseY, float h) { this.label = l; this.xs = xs; this.zs = zs; this.baseY = baseY; this.height = h; } }
    private List<Prism3D> prisms = new ArrayList<>();

    /** An angle at `vertex` between rays vertex→a and vertex→b. Drawn as an arc with its value in degrees. */
    private static class Angle3D {
        String vertex, a, b;
        Float fixedDeg;     // AI-supplied value; if null the value is computed from the coordinates
        double value;       // last computed/used value in degrees
        float labelSx, labelSy; // screen position of the value label (for hit-testing)
        Angle3D(String v, String a, String b, Float deg) { this.vertex = v; this.a = a; this.b = b; this.fixedDeg = deg; }
    }
    private List<Angle3D> angles = new ArrayList<>();

    private int selectedAngleIndex = -1;
    private int selectedPlaneIndex = -1;
    private int selectedMidLineIndex = -1; // a tapped line MIDPOINT
    private Paint anglePaint, angleTextPaint, gradientPaint, midPaint, dimTextPaint;
    private Paint autoAnglePaint, autoAngleTextPaint; // every corner's angle, shown automatically
    private int colBg3dTop, colBg3dBottom;
    private boolean showMidpoints = true;
    private boolean showDimensions = false; // length labels on every edge (like the 2D editor)

    /** Show/hide the length label drawn on each edge. */
    public void setShowDimensions(boolean show) { this.showDimensions = show; invalidate(); }
    public boolean isShowingDimensions() { return showDimensions; }

    // Interaction mode: SELECT inspects/picks; DRAW drops points on the sketch plane (hand drawing);
    // DRAW_CIRCLE / DRAW_SPHERE let the user pick a centre on the ground and drag out the radius by finger.
    public static final int MODE_SELECT = 0;
    public static final int MODE_DRAW = 1;
    public static final int MODE_DRAW_CIRCLE = 2;
    public static final int MODE_DRAW_SPHERE = 3;
    private int interactionMode = MODE_SELECT;
    public void setInteractionMode(int m) { interactionMode = m; }
    public int getInteractionMode() { return interactionMode; }

    // Live "tap centre, drag radius" state for the finger-drawn circle / sphere.
    private boolean isRadiusDragging = false;
    private float pendingCx, pendingCy, pendingCz, pendingRadius;
    private Paint previewPaint;

    /** Fired when the user taps in DRAW mode: gives the label of the snapped/created point (or null if the tap couldn't be placed on the plane). */
    public interface OnSketchPointListener { void onSketchPoint(String label); }
    private OnSketchPointListener sketchListener;
    public void setOnSketchPointListener(OnSketchPointListener l) { this.sketchListener = l; }

    /** Fired when a finger-drawn circle/sphere is finished: gives its centre (on the ground plane) and radius. */
    public interface OnRadiusShapeListener { void onRadiusShape(boolean isCircle, float cx, float cy, float cz, float r); }
    private OnRadiusShapeListener radiusShapeListener;
    public void setOnRadiusShapeListener(OnRadiusShapeListener l) { this.radiusShapeListener = l; }

    public GeometryCanvas3D(Context context, AttributeSet attrs) { super(context, attrs); init(context); }

    private void init(Context context) {
        // Theme-aware colors: dark "paper" + light lines/text in dark mode.
        colBg3d = androidx.core.content.ContextCompat.getColor(context, R.color.canvas_3d_bg);
        colLine3d = androidx.core.content.ContextCompat.getColor(context, R.color.canvas_3d_line);
        colText3d = androidx.core.content.ContextCompat.getColor(context, R.color.canvas_3d_text);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(colLine3d); linePaint.setStrokeWidth(2f); linePaint.setStyle(Paint.Style.STROKE);
        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG); pointPaint.setColor(Color.RED);
        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG); planePaint.setStyle(Paint.Style.FILL);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setTextSize(28f); textPaint.setColor(colText3d); textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        xAxisPaint = new Paint(); xAxisPaint.setColor(Color.RED); xAxisPaint.setStrokeWidth(4f);
        yAxisPaint = new Paint(); yAxisPaint.setColor(Color.GREEN); yAxisPaint.setStrokeWidth(4f);
        zAxisPaint = new Paint(); zAxisPaint.setColor(Color.BLUE); zAxisPaint.setStrokeWidth(4f);
        axesTextPaint = new Paint(); axesTextPaint.setTextSize(26f); axesTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        selPaint = new Paint(Paint.ANTI_ALIAS_FLAG); selPaint.setColor(0xFFFF8C00); selPaint.setStrokeWidth(6f); selPaint.setStyle(Paint.Style.STROKE);

        anglePaint = new Paint(Paint.ANTI_ALIAS_FLAG); anglePaint.setColor(0xFFE91E63); anglePaint.setStrokeWidth(4f); anglePaint.setStyle(Paint.Style.STROKE);
        angleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG); angleTextPaint.setColor(0xFFE91E63); angleTextPaint.setTextSize(26f); angleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        midPaint = new Paint(Paint.ANTI_ALIAS_FLAG); midPaint.setColor(0xFF80DEEA); midPaint.setStyle(Paint.Style.STROKE); midPaint.setStrokeWidth(3f);
        dimTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG); dimTextPaint.setColor(0xFF66BB6A); dimTextPaint.setTextSize(22f); dimTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        autoAnglePaint = new Paint(Paint.ANTI_ALIAS_FLAG); autoAnglePaint.setColor(0xFF26A69A); autoAnglePaint.setStrokeWidth(3f); autoAnglePaint.setStyle(Paint.Style.STROKE);
        autoAngleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG); autoAngleTextPaint.setColor(0xFF00897B); autoAngleTextPaint.setTextSize(22f); autoAngleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG); previewPaint.setColor(0xFFFB8C00); previewPaint.setStrokeWidth(3f); previewPaint.setStyle(Paint.Style.STROKE);
        previewPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f, 10f}, 0f));

        // A subtle vertical gradient backdrop derived from the theme canvas colour (lighter top → deeper bottom).
        colBg3dTop = lighten(colBg3d, 0.10f);
        colBg3dBottom = darken(colBg3d, 0.12f);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleFactor *= d.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 15.0f));
                if (zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage());
                invalidate(); return true;
            }
        });
    }

    public void setMoveMode(boolean move) { this.isMoveMode = move; invalidate(); }
    public boolean isMoveMode() { return isMoveMode; }

    public void addPoint(String l, float x, float y, float z) { points.add(new Point3D(l, x, y, z)); invalidate(); }
    public void addLine(String a, String b) { addLine(a, b, null); }
    /** Add a line with an optional custom color (null = default 3D line color). */
    public void addLine(String a, String b, Integer color) { lines.add(new Line3D(a, b, color)); invalidate(); }

    /**
     * Parses a color argument the AI may put as the 3rd argument of LINE3D.
     * Accepts "#RRGGBB", "#AARRGGBB", or a small set of common names
     * (red, blue, green, orange, purple, magenta, cyan, yellow, pink, brown, black, white,
     *  teal, lime, navy, gold). Returns null on anything unrecognized.
     */
    public static Integer parseColorArg(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        if (t.isEmpty()) return null;
        try {
            if (t.startsWith("#")) return Color.parseColor(t);
            switch (t) {
                case "red":     return 0xFFE53935;
                case "blue":    return 0xFF1E88E5;
                case "green":   return 0xFF43A047;
                case "orange":  return 0xFFFB8C00;
                case "purple":  return 0xFF8E24AA;
                case "magenta": return 0xFFD81B60;
                case "cyan":    return 0xFF00ACC1;
                case "yellow":  return 0xFFFDD835;
                case "pink":    return 0xFFEC407A;
                case "brown":   return 0xFF6D4C41;
                case "black":   return 0xFF000000;
                case "white":   return 0xFFFFFFFF;
                case "teal":    return 0xFF00897B;
                case "lime":    return 0xFFC0CA33;
                case "navy":    return 0xFF1A237E;
                case "gold":    return 0xFFFFC107;
                default: return Color.parseColor(t); // fall back to Android's named-color set
            }
        } catch (Exception e) {
            return null;
        }
    }
    public void addPlane(List<String> labels) { planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { addCircle(l, x, y, z, r, false); }
    /** Add a circle; {@code ground} true lays it flat on the XZ plane (finger-drawn footprint), false stands it up in XY. */
    public void addCircle(String l, float x, float y, float z, float r, boolean ground) { circles.add(new Circle3D(l, x, y, z, r, ground)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) { spheres.add(new Sphere3D(l, x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h, float cur) { cones.add(new Cone3D(l, x, y, z, r, h, cur)); invalidate(); }
    public void addPyramid(String l, float x, float y, float z, float w, float d, float h) { pyramids.add(new Pyramid3D(l, x, y, z, w, d, h)); invalidate(); }
    public void addCylinder(String l, float x, float y, float z, float r, float h) { cylinders.add(new Cylinder3D(l, x, y, z, r, h)); invalidate(); }

    /** Extrudes a 2D profile (points in the XZ plane at baseY) upward by `height` to form a solid. */
    public void addPrism(String label, float[] xs, float[] zs, float baseY, float height) {
        if (xs == null || zs == null || xs.length < 3 || xs.length != zs.length) return;
        prisms.add(new Prism3D(label, xs, zs, baseY, height));
        invalidate();
    }

    /**
     * Marks the angle at `vertex` between rays vertex→a and vertex→b. It's drawn as an arc with its
     * value in degrees. The two rays are auto-drawn as lines if they aren't already present, so the
     * angle is always backed by visible construction. {@code deg} may be null to compute it.
     */
    public void addAngle(String vertex, String a, String b, Float deg) {
        if (vertex == null || a == null || b == null) return;
        ensureLine(vertex, a);
        ensureLine(vertex, b);
        angles.add(new Angle3D(vertex, a, b, deg));
        invalidate();
    }

    /** Adds the line a–b only if an equivalent line isn't already present. */
    private void ensureLine(String a, String b) {
        if (a == null || b == null || a.equalsIgnoreCase(b)) return;
        for (Line3D l : lines) {
            if ((l.a.equalsIgnoreCase(a) && l.b.equalsIgnoreCase(b)) || (l.a.equalsIgnoreCase(b) && l.b.equalsIgnoreCase(a))) return;
        }
        if (findPt(a) != null && findPt(b) != null) lines.add(new Line3D(a, b, null));
    }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); pyramids.clear(); cylinders.clear(); circles.clear(); spheres.clear(); prisms.clear(); angles.clear();
        clearSelection();
        resetRotation();
    }

    /** Removes any current selection without notifying the listener. */
    public void clearSelection() {
        selectedPointIndex = -1;
        selectedLineIndex = -1;
        selectedAngleIndex = -1;
        selectedPlaneIndex = -1;
        selectedMidLineIndex = -1;
    }

    /** Parses a solution's raw text and adds every 3D command (DRAW3D/LINE3D/PLANE3D/…) to this canvas.
     *  Lets the figure be rebuilt offscreen (e.g. when exporting from History). */
    public void loadFromSolution(String rawText) {
        if (rawText == null) return;
        for (String raw : rawText.split("\n")) {
            String line = raw.trim();
            try {
                if (line.startsWith("DRAW3D:")) { String[] a = cmdArgs(line); if (a.length >= 4) addPoint(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3])); }
                else if (line.startsWith("LINE3D:")) {
                    String[] a = cmdArgs(line);
                    if (a.length >= 2) {
                        Integer col = (a.length >= 3) ? parseColorArg(a[2]) : null;
                        addLine(a[0].trim(), a[1].trim(), col);
                    }
                }
                else if (line.startsWith("PLANE3D:")) { String[] a = cmdArgs(line); if (a.length >= 2) { List<String> v = new ArrayList<>(); for (int i = 1; i < a.length; i++) v.add(a[i].trim()); addPlane(v); } }
                else if (line.startsWith("CONE3D:")) { String[] a = cmdArgs(line); if (a.length >= 7) addCone(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5]), pf(a[6])); }
                else if (line.startsWith("PYRAMID3D:")) { String[] a = cmdArgs(line); if (a.length >= 7) addPyramid(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5]), pf(a[6])); }
                else if (line.startsWith("CYLINDER3D:")) { String[] a = cmdArgs(line); if (a.length >= 6) addCylinder(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5])); }
                else if (line.startsWith("SPHERE3D:")) { String[] a = cmdArgs(line); if (a.length >= 5) addSphere(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4])); }
                else if (line.startsWith("CIRCLE3D:")) { String[] a = cmdArgs(line); if (a.length >= 5) addCircle(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4])); }
                else if (line.startsWith("ANGLE3D:")) { String[] a = cmdArgs(line); if (a.length >= 3) addAngle(a[0].trim(), a[1].trim(), a[2].trim(), a.length >= 4 ? pf(a[3]) : null); }
            } catch (Exception ignored) {}
        }
    }

    private static String[] cmdArgs(String line) {
        String[] p = line.split(":");
        return (p.length < 2) ? new String[0] : p[1].trim().split(",");
    }

    private static float pf(String s) {
        try { return Float.parseFloat(s.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0f; }
    }

    /** True when there's no figure to draw (used to skip the canvas when exporting a solution). */
    public boolean isEmpty() {
        return points.isEmpty() && lines.isEmpty() && planes.isEmpty() && cones.isEmpty()
                && pyramids.isEmpty() && cylinders.isEmpty() && circles.isEmpty() && spheres.isEmpty() && prisms.isEmpty();
    }

    public void resetRotation() {
        rotateX = -25f; rotateY = 45f; rotateZ = 0f; translateX = 0f; translateY = 0f; scaleFactor = 1.0f;
        invalidate();
    }

    public int getZoomPercentage() { return (int) (scaleFactor * 100); }
    public void rotateX(float d) { rotateX += d; invalidate(); }
    public void rotateY(float d) { rotateY += d; invalidate(); }
    public void rotateZ(float d) { rotateZ += d; invalidate(); }
    public void zoomIn() { scaleFactor = Math.min(15f, scaleFactor * 1.2f); invalidate(); if(zoomListener!=null) zoomListener.onZoomChanged(getZoomPercentage()); }
    public void zoomOut() { scaleFactor = Math.max(0.1f, scaleFactor / 1.2f); invalidate(); if(zoomListener!=null) zoomListener.onZoomChanged(getZoomPercentage()); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Gradient backdrop (lighter at the top, deeper at the bottom) for a more polished look.
        if (gradientPaint.getShader() == null && getHeight() > 0) {
            gradientPaint.setShader(new android.graphics.LinearGradient(
                    0, 0, 0, getHeight(), colBg3dTop, colBg3dBottom, android.graphics.Shader.TileMode.CLAMP));
        }
        if (gradientPaint.getShader() != null) canvas.drawRect(0, 0, getWidth(), getHeight(), gradientPaint);
        else canvas.drawColor(colBg3d);

        float centerX = getWidth() / 2f + translateX;
        float centerY = getHeight() / 2f + translateY;
        float sceneScale = (Math.min(getWidth(), getHeight()) / 500f) * scaleFactor;

        double radX = Math.toRadians(rotateX);
        double radY = Math.toRadians(rotateY);
        double radZ = Math.toRadians(rotateZ);

        drawAxesCube(canvas, radX, radY, radZ, 120f, 350f);

        for (Point3D p : points) projectPoint(p, radX, radY, radZ, centerX, centerY, sceneScale);

        for (Cone3D c : cones) drawCone(canvas, c, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Cylinder3D c : cylinders) drawCylinder(canvas, c, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Prism3D pr : prisms) drawPrism(canvas, pr, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Pyramid3D p : pyramids) drawPyramid(canvas, p, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Circle3D c : circles) drawCircle(canvas, c, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Sphere3D s : spheres) drawSphere(canvas, s, radX, radY, radZ, centerX, centerY, sceneScale);

        // Live preview while the user is dragging out a finger-drawn circle / sphere.
        if (isRadiusDragging && pendingRadius > 0) {
            if (interactionMode == MODE_DRAW_SPHERE) {
                Point3D pc = new Point3D("", pendingCx, pendingCy, pendingCz);
                projectPoint(pc, radX, radY, radZ, centerX, centerY, sceneScale);
                canvas.drawCircle(pc.sx, pc.sy, pendingRadius * sceneScale, previewPaint);
            } else {
                drawCircleShape(canvas, pendingCx, pendingCy, pendingCz, pendingRadius, true, previewPaint,
                        radX, radY, radZ, centerX, centerY, sceneScale);
            }
            // a small dot at the chosen centre
            Point3D pc = new Point3D("", pendingCx, pendingCy, pendingCz);
            projectPoint(pc, radX, radY, radZ, centerX, centerY, sceneScale);
            canvas.drawCircle(pc.sx, pc.sy, 6f, pointPaint);
        }

        for (int pi = 0; pi < planes.size(); pi++) {
            Plane3D pl = planes.get(pi);
            Path path = new Path();
            boolean first = true;
            for (String label : pl.labels) {
                Point3D pt = findPt(label);
                if (pt != null) {
                    if (first) { path.moveTo(pt.sx, pt.sy); first = false; }
                    else path.lineTo(pt.sx, pt.sy);
                }
            }
            if (!first) {
                path.close();
                boolean sel = pi == selectedPlaneIndex;
                planePaint.setStyle(Paint.Style.FILL);
                planePaint.setColor(sel ? Color.argb(150, 255, 170, 80) : Color.argb(120, 180, 210, 255));
                canvas.drawPath(path, planePaint);
                planePaint.setStyle(Paint.Style.STROKE);
                planePaint.setColor(sel ? 0xFFFF8C00 : colLine3d);
                canvas.drawPath(path, planePaint);
            }
        }

        int defaultLineColor = linePaint.getColor();
        for (Line3D l : lines) {
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) {
                linePaint.setStrokeWidth(3f);
                if (l.color != null) linePaint.setColor(l.color);
                canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, linePaint);
                if (l.color != null) linePaint.setColor(defaultLineColor);
            }
        }

        // Midpoint markers + edge length labels (like the 2D editor's dimensions).
        if (showMidpoints || showDimensions) {
            for (Line3D l : lines) {
                Point3D p1 = findPt(l.a), p2 = findPt(l.b);
                if (p1 == null || p2 == null) continue;
                float mx = (p1.sx + p2.sx) / 2f, my = (p1.sy + p2.sy) / 2f;
                if (showMidpoints) canvas.drawCircle(mx, my, 5f, midPaint);
                if (showDimensions) canvas.drawText(fmt((float) dist(p1, p2)), mx + 8, my - 8, dimTextPaint);
            }
        }

        // Auto angles: every corner shows its value with an arc (drawn under the manual ones).
        drawAutoAngles(canvas);

        // Angle arcs + their values (drawn after lines so they sit on top of the edges).
        for (int ai = 0; ai < angles.size(); ai++) {
            drawAngle(canvas, angles.get(ai), ai == selectedAngleIndex);
        }

        for (Point3D p : points) {
            if (p.isVertex) {
                canvas.drawCircle(p.sx, p.sy, 6, pointPaint);
                canvas.drawText(p.label, p.sx + 10, p.sy - 10, textPaint);
            }
        }

        // Highlight the selected element on top of everything.
        if (selectedLineIndex >= 0 && selectedLineIndex < lines.size()) {
            Line3D l = lines.get(selectedLineIndex);
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, selPaint);
        }
        if (selectedPointIndex >= 0 && selectedPointIndex < points.size()) {
            Point3D p = points.get(selectedPointIndex);
            canvas.drawCircle(p.sx, p.sy, 14, selPaint);
        }
        if (selectedMidLineIndex >= 0 && selectedMidLineIndex < lines.size()) {
            Line3D l = lines.get(selectedMidLineIndex);
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) {
                float mx = (p1.sx + p2.sx) / 2f, my = (p1.sy + p2.sy) / 2f;
                canvas.drawCircle(mx, my, 12, selPaint);
                canvas.drawText("M", mx + 14, my - 8, textPaint); // mark the midpoint as M
            }
        }
    }

    /** Draws an angle as an arc between its two rays, labelled with its value in degrees. */
    private void drawAngle(Canvas canvas, Angle3D ang, boolean selected) {
        Point3D v = findPt(ang.vertex), a = findPt(ang.a), b = findPt(ang.b);
        if (v == null || a == null || b == null) return;

        double[] u = {a.x - v.x, a.y - v.y, a.z - v.z};
        double[] w = {b.x - v.x, b.y - v.y, b.z - v.z};
        double lu = Math.sqrt(u[0]*u[0]+u[1]*u[1]+u[2]*u[2]);
        double lw = Math.sqrt(w[0]*w[0]+w[1]*w[1]+w[2]*w[2]);
        if (lu < 1e-3 || lw < 1e-3) return;
        for (int i = 0; i < 3; i++) { u[i] /= lu; w[i] /= lw; }

        double dot = Math.max(-1, Math.min(1, u[0]*w[0]+u[1]*w[1]+u[2]*w[2]));
        double theta = Math.acos(dot);
        ang.value = (ang.fixedDeg != null) ? ang.fixedDeg : Math.toDegrees(theta);
        if (theta < 1e-3 || Math.abs(theta - Math.PI) < 1e-3) { ang.labelSx = v.sx; ang.labelSy = v.sy; return; }

        // Arc radius: a fraction of the shorter ray, in model units.
        double r = 0.28 * Math.min(lu, lw);
        double sinT = Math.sin(theta);

        Paint p = selected ? selPaint : anglePaint;
        Path arc = new Path();
        Point3D mid = null;
        int seg = 20;
        for (int i = 0; i <= seg; i++) {
            double t = (double) i / seg;
            double c1 = Math.sin((1 - t) * theta) / sinT;
            double c2 = Math.sin(t * theta) / sinT;
            Point3D pt = new Point3D("",
                    (float) (v.x + r * (c1 * u[0] + c2 * w[0])),
                    (float) (v.y + r * (c1 * u[1] + c2 * w[1])),
                    (float) (v.z + r * (c1 * u[2] + c2 * w[2])));
            projectCurrent(pt);
            if (i == 0) arc.moveTo(pt.sx, pt.sy); else arc.lineTo(pt.sx, pt.sy);
            if (i == seg / 2) mid = pt;
        }
        float oldW = p.getStrokeWidth();
        p.setStyle(Paint.Style.STROKE);
        canvas.drawPath(arc, p);
        p.setStrokeWidth(oldW);

        if (mid != null) {
            ang.labelSx = mid.sx; ang.labelSy = mid.sy;
            String txt = Math.round(ang.value) + "°";
            angleTextPaint.setColor(selected ? 0xFFFF8C00 : 0xFFE91E63);
            canvas.drawText(txt, mid.sx + 6, mid.sy - 6, angleTextPaint);
        }
    }

    /**
     * Automatically annotates every corner of the figure: for each vertex with two or more incident
     * edges, draws an arc + value for each pair of edges. Corners already covered by a manually added
     * angle are skipped so the two never overlap. Computed on the fly — never stored or saved.
     */
    private void drawAutoAngles(Canvas canvas) {
        for (Point3D v : points) {
            if (!v.isVertex || v.label == null || v.label.isEmpty()) continue;
            // Distinct neighbours connected to this vertex by an edge.
            List<Point3D> nbrs = new ArrayList<>();
            for (Line3D l : lines) {
                Point3D other = null;
                if (v.label.equalsIgnoreCase(l.a)) other = findPt(l.b);
                else if (v.label.equalsIgnoreCase(l.b)) other = findPt(l.a);
                if (other != null && other != v && !nbrs.contains(other)) nbrs.add(other);
            }
            // If this corner also carries a manual angle (radius 0.28), push the auto arcs outside it so
            // the teal and pink arcs never overlap; otherwise keep them compact.
            double base = vertexHasManualAngle(v.label) ? 0.40 : 0.20;
            int nest = 0;
            for (int i = 0; i < nbrs.size(); i++) {
                for (int j = i + 1; j < nbrs.size(); j++) {
                    if (hasManualAngle(v.label, nbrs.get(i).label, nbrs.get(j).label)) continue;
                    drawCornerAngle(canvas, v, nbrs.get(i), nbrs.get(j), base + 0.10 * nest++);
                }
            }
        }
    }

    /** True if the user has already added a manual angle at {@code vertex} between arms {@code a} and {@code b}. */
    private boolean hasManualAngle(String vertex, String a, String b) {
        if (a == null || b == null) return false;
        for (Angle3D ag : angles) {
            if (ag.vertex.equalsIgnoreCase(vertex)
                    && ((ag.a.equalsIgnoreCase(a) && ag.b.equalsIgnoreCase(b))
                     || (ag.a.equalsIgnoreCase(b) && ag.b.equalsIgnoreCase(a)))) return true;
        }
        return false;
    }

    /** True if the user has added any manual angle whose vertex is {@code vertex}. */
    private boolean vertexHasManualAngle(String vertex) {
        for (Angle3D ag : angles) if (ag.vertex.equalsIgnoreCase(vertex)) return true;
        return false;
    }

    /** Draws a single auto-angle arc at radius {@code radiusFrac} of the shorter arm (nested per corner). */
    private void drawCornerAngle(Canvas canvas, Point3D v, Point3D a, Point3D b, double radiusFrac) {
        double[] u = {a.x - v.x, a.y - v.y, a.z - v.z};
        double[] w = {b.x - v.x, b.y - v.y, b.z - v.z};
        double lu = Math.sqrt(u[0]*u[0]+u[1]*u[1]+u[2]*u[2]);
        double lw = Math.sqrt(w[0]*w[0]+w[1]*w[1]+w[2]*w[2]);
        if (lu < 1e-3 || lw < 1e-3) return;
        for (int i = 0; i < 3; i++) { u[i] /= lu; w[i] /= lw; }
        double dot = Math.max(-1, Math.min(1, u[0]*w[0]+u[1]*w[1]+u[2]*w[2]));
        double theta = Math.acos(dot);
        if (theta < 1e-3 || Math.abs(theta - Math.PI) < 1e-3) return; // straight / degenerate — nothing to show

        double r = radiusFrac * Math.min(lu, lw);
        double sinT = Math.sin(theta);
        Path arc = new Path();
        Point3D mid = null;
        int seg = 18;
        for (int i = 0; i <= seg; i++) {
            double t = (double) i / seg;
            double c1 = Math.sin((1 - t) * theta) / sinT;
            double c2 = Math.sin(t * theta) / sinT;
            Point3D pt = new Point3D("",
                    (float) (v.x + r * (c1 * u[0] + c2 * w[0])),
                    (float) (v.y + r * (c1 * u[1] + c2 * w[1])),
                    (float) (v.z + r * (c1 * u[2] + c2 * w[2])));
            projectCurrent(pt);
            if (i == 0) arc.moveTo(pt.sx, pt.sy); else arc.lineTo(pt.sx, pt.sy);
            if (i == seg / 2) mid = pt;
        }
        canvas.drawPath(arc, autoAnglePaint);
        if (mid != null) canvas.drawText(Math.round(Math.toDegrees(theta)) + "°", mid.sx + 4, mid.sy - 4, autoAngleTextPaint);
    }

    /** Projects a single point with the CURRENT view parameters (matches onDraw). */
    private void projectCurrent(Point3D p) {
        float centerX = getWidth() / 2f + translateX;
        float centerY = getHeight() / 2f + translateY;
        float sceneScale = (Math.min(getWidth(), getHeight()) / 500f) * scaleFactor;
        projectPoint(p, Math.toRadians(rotateX), Math.toRadians(rotateY), Math.toRadians(rotateZ), centerX, centerY, sceneScale);
    }

    private static int lighten(int c, float f) {
        int r = (int) Math.min(255, Color.red(c) + 255 * f);
        int g = (int) Math.min(255, Color.green(c) + 255 * f);
        int b = (int) Math.min(255, Color.blue(c) + 255 * f);
        return Color.rgb(r, g, b);
    }
    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * (1 - f)), (int) (Color.green(c) * (1 - f)), (int) (Color.blue(c) * (1 - f)));
    }

    /** Refreshes screen coordinates of every point for the current view (used for hit-testing). */
    private void refreshProjection() {
        float centerX = getWidth() / 2f + translateX;
        float centerY = getHeight() / 2f + translateY;
        float sceneScale = (Math.min(getWidth(), getHeight()) / 500f) * scaleFactor;
        double radX = Math.toRadians(rotateX), radY = Math.toRadians(rotateY), radZ = Math.toRadians(rotateZ);
        for (Point3D p : points) projectPoint(p, radX, radY, radZ, centerX, centerY, sceneScale);
    }

    /** Distance from point (px,py) to the segment (ax,ay)-(bx,by) in screen space. */
    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-3f) return (float) Math.hypot(px - ax, py - ay);
        float t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Hit-tests a screen tap. Priority: vertex → angle value → edge → plane → clear. */
    private void pickAt(float sx, float sy) {
        refreshProjection();
        clearSelection();

        // 1) Vertices (smallest targets).
        float bestPt = 45f;
        for (int i = 0; i < points.size(); i++) {
            Point3D p = points.get(i);
            if (!p.isVertex) continue;
            float d = (float) Math.hypot(sx - p.sx, sy - p.sy);
            if (d < bestPt) { bestPt = d; selectedPointIndex = i; }
        }

        // 2) Line midpoints (CAD reference points).
        if (selectedPointIndex == -1 && showMidpoints) {
            float bestMid = 40f;
            for (int i = 0; i < lines.size(); i++) {
                Line3D l = lines.get(i);
                Point3D a = findPt(l.a), b = findPt(l.b);
                if (a == null || b == null) continue;
                float mx = (a.sx + b.sx) / 2f, my = (a.sy + b.sy) / 2f;
                float d = (float) Math.hypot(sx - mx, sy - my);
                if (d < bestMid) { bestMid = d; selectedMidLineIndex = i; }
            }
        }

        // 3) Angle labels (their screen position is set during draw).
        if (selectedPointIndex == -1 && selectedMidLineIndex == -1) {
            float bestAng = 45f;
            for (int i = 0; i < angles.size(); i++) {
                Angle3D ag = angles.get(i);
                float d = (float) Math.hypot(sx - ag.labelSx, sy - ag.labelSy);
                if (d < bestAng) { bestAng = d; selectedAngleIndex = i; }
            }
        }

        // 4) Edges.
        if (selectedPointIndex == -1 && selectedMidLineIndex == -1 && selectedAngleIndex == -1) {
            float bestLn = 35f;
            for (int i = 0; i < lines.size(); i++) {
                Line3D l = lines.get(i);
                Point3D a = findPt(l.a), b = findPt(l.b);
                if (a == null || b == null) continue;
                float d = distToSegment(sx, sy, a.sx, a.sy, b.sx, b.sy);
                if (d < bestLn) { bestLn = d; selectedLineIndex = i; }
            }
        }

        // 5) Plane faces (largest, lowest priority): tap inside the projected polygon.
        if (selectedPointIndex == -1 && selectedMidLineIndex == -1 && selectedAngleIndex == -1 && selectedLineIndex == -1) {
            for (int i = planes.size() - 1; i >= 0; i--) {
                if (pointInPlane(planes.get(i), sx, sy)) { selectedPlaneIndex = i; break; }
            }
        }

        if (selectionListener != null) selectionListener.onElementSelected(buildSelectionInfo());
        invalidate();
    }

    /** True if (sx,sy) is inside the screen polygon of the plane's projected vertices. */
    private boolean pointInPlane(Plane3D pl, float sx, float sy) {
        java.util.List<float[]> poly = new ArrayList<>();
        for (String label : pl.labels) {
            Point3D pt = findPt(label);
            if (pt != null) poly.add(new float[]{pt.sx, pt.sy});
        }
        if (poly.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            float xi = poly.get(i)[0], yi = poly.get(i)[1];
            float xj = poly.get(j)[0], yj = poly.get(j)[1];
            boolean intersect = ((yi > sy) != (yj > sy))
                    && (sx < (xj - xi) * (sy - yi) / (yj - yi + 1e-6f) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /** Builds a localized, descriptive (CAD-style) description of the current selection (or null). */
    private String buildSelectionInfo() {
        Context ctx = getContext();

        if (selectedPointIndex >= 0 && selectedPointIndex < points.size()) {
            Point3D p = points.get(selectedPointIndex);
            String name = (p.label == null || p.label.isEmpty()) ? "?" : p.label;
            String neighbours = connectedNeighbours(name);
            String s = ctx.getString(R.string.info_point) + " " + name + "\n"
                    + ctx.getString(R.string.info_position) + ": (" + fmt(p.x) + ", " + fmt(p.y) + ", " + fmt(p.z) + ")";
            if (!neighbours.isEmpty()) s += "\n" + ctx.getString(R.string.info_connects) + ": " + neighbours;
            return s;
        }

        if (selectedLineIndex >= 0 && selectedLineIndex < lines.size()) {
            Line3D l = lines.get(selectedLineIndex);
            Point3D a = findPt(l.a), b = findPt(l.b);
            if (a != null && b != null) {
                double len = dist(a, b);
                float mx = (a.x + b.x) / 2f, my = (a.y + b.y) / 2f, mz = (a.z + b.z) / 2f;
                return ctx.getString(R.string.info_edge) + " " + l.a + "–" + l.b + "\n"
                        + ctx.getString(R.string.info_length) + ": " + fmt((float) len) + "\n"
                        + l.a + " (" + fmt(a.x) + ", " + fmt(a.y) + ", " + fmt(a.z) + ")\n"
                        + l.b + " (" + fmt(b.x) + ", " + fmt(b.y) + ", " + fmt(b.z) + ")\n"
                        + ctx.getString(R.string.info_midpoint) + " " + l.a + l.b + ": (" + fmt(mx) + ", " + fmt(my) + ", " + fmt(mz) + ")";
            }
        }

        if (selectedMidLineIndex >= 0 && selectedMidLineIndex < lines.size()) {
            Line3D l = lines.get(selectedMidLineIndex);
            Point3D a = findPt(l.a), b = findPt(l.b);
            if (a != null && b != null) {
                float mx = (a.x + b.x) / 2f, my = (a.y + b.y) / 2f, mz = (a.z + b.z) / 2f;
                // e.g. "M  —  midpoint of A–B"
                return "M  —  " + ctx.getString(R.string.info_midpoint) + " " + l.a + "–" + l.b + "\n"
                        + ctx.getString(R.string.info_position) + ": (" + fmt(mx) + ", " + fmt(my) + ", " + fmt(mz) + ")\n"
                        + ctx.getString(R.string.info_length) + " (" + l.a + l.b + "): " + fmt((float) dist(a, b));
            }
        }

        if (selectedAngleIndex >= 0 && selectedAngleIndex < angles.size()) {
            Angle3D ag = angles.get(selectedAngleIndex);
            return "∠" + ag.a + ag.vertex + ag.b + " = " + Math.round(ag.value) + "°\n"
                    + ctx.getString(R.string.info_vertex_at) + " " + ag.vertex + "\n"
                    + ctx.getString(R.string.info_rays) + ": " + ag.vertex + "→" + ag.a + ", " + ag.vertex + "→" + ag.b;
        }

        if (selectedPlaneIndex >= 0 && selectedPlaneIndex < planes.size()) {
            Plane3D pl = planes.get(selectedPlaneIndex);
            StringBuilder verts = new StringBuilder();
            for (String s : pl.labels) { if (verts.length() > 0) verts.append("–"); verts.append(s); }
            return ctx.getString(R.string.info_plane) + " " + verts + "\n"
                    + ctx.getString(R.string.info_vertices) + ": " + pl.labels.size() + "\n"
                    + ctx.getString(R.string.info_area) + ": " + fmt((float) planeArea(pl)) + "\n"
                    + ctx.getString(R.string.info_perimeter) + ": " + fmt((float) planePerimeter(pl));
        }
        return null;
    }

    private static double dist(Point3D a, Point3D b) {
        return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
    }

    /** Comma-separated list of the labels each edge connects this point to. */
    private String connectedNeighbours(String label) {
        StringBuilder sb = new StringBuilder();
        for (Line3D l : lines) {
            String other = null;
            if (label.equalsIgnoreCase(l.a)) other = l.b;
            else if (label.equalsIgnoreCase(l.b)) other = l.a;
            if (other != null) { if (sb.length() > 0) sb.append(", "); sb.append(other); }
        }
        return sb.toString();
    }

    private double planePerimeter(Plane3D pl) {
        java.util.List<Point3D> pts = new ArrayList<>();
        for (String s : pl.labels) { Point3D p = findPt(s); if (p != null) pts.add(p); }
        if (pts.size() < 2) return 0;
        double per = 0;
        for (int i = 0; i < pts.size(); i++) per += dist(pts.get(i), pts.get((i + 1) % pts.size()));
        return per;
    }

    /** Area of a (planar) 3D polygon via the Newell/cross-product method. */
    private double planeArea(Plane3D pl) {
        java.util.List<Point3D> pts = new ArrayList<>();
        for (String s : pl.labels) { Point3D p = findPt(s); if (p != null) pts.add(p); }
        if (pts.size() < 3) return 0;
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < pts.size(); i++) {
            Point3D c = pts.get(i), n = pts.get((i + 1) % pts.size());
            nx += (c.y - n.y) * (c.z + n.z);
            ny += (c.z - n.z) * (c.x + n.x);
            nz += (c.x - n.x) * (c.y + n.y);
        }
        return 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    // ---- Editable-engine foundation (used by the 3D editor; safe no-ops when nothing is selected) ----

    /** Adds a free vertex at model coordinates and returns its auto-generated label. */
    public String addVertex(float x, float y, float z) {
        String label = nextVertexLabel();
        points.add(new Point3D(label, x, y, z));
        invalidate();
        return label;
    }

    /** Connects two existing points by label (no-op if either is missing or they are equal). */
    public boolean connect(String a, String b) {
        if (a == null || b == null || a.equalsIgnoreCase(b)) return false;
        if (findPt(a) == null || findPt(b) == null) return false;
        addLine(a, b, null);
        return true;
    }

    /** Deletes the currently selected vertex (and its incident lines) or line. Returns true if something was removed. */
    public boolean deleteSelected() {
        if (selectedLineIndex >= 0 && selectedLineIndex < lines.size()) {
            lines.remove(selectedLineIndex);
            clearSelection();
            if (selectionListener != null) selectionListener.onElementSelected(null);
            invalidate();
            return true;
        }
        if (selectedPointIndex >= 0 && selectedPointIndex < points.size()) {
            String label = points.get(selectedPointIndex).label;
            points.remove(selectedPointIndex);
            if (label != null && !label.isEmpty()) {
                for (int i = lines.size() - 1; i >= 0; i--) {
                    Line3D l = lines.get(i);
                    if (label.equalsIgnoreCase(l.a) || label.equalsIgnoreCase(l.b)) lines.remove(i);
                }
            }
            clearSelection();
            if (selectionListener != null) selectionListener.onElementSelected(null);
            invalidate();
            return true;
        }
        return false;
    }

    /** Extrudes the vertices currently on the ground plane (y≈0), in insertion order, into a solid. */
    public boolean extrudeGroundProfile(float height) {
        // Reuse the existing ground vertices as the bottom face, then build the top face above them
        // so the result is REAL geometry: e.g. a sketched rectangle (4 points) becomes a box with 8
        // lettered points, 12 edges (each with a midpoint) and 6 faces.
        List<Point3D> base = new ArrayList<>();
        for (Point3D p : points) if (p.isVertex && Math.abs(p.y) < 0.5f) base.add(p);
        if (base.size() < 3) return false;

        int n = base.size();
        String[] bottom = new String[n], top = new String[n];
        for (int i = 0; i < n; i++) {
            bottom[i] = base.get(i).label;
            top[i] = addVertex(base.get(i).x, height, base.get(i).z); // new lettered top vertex
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            ensureLine(bottom[i], bottom[j]); // bottom ring (may already exist from the sketch)
            ensureLine(top[i], top[j]);        // top ring
            ensureLine(bottom[i], top[i]);     // vertical edge
        }
        addFaces(bottom, top);
        invalidate();
        return true;
    }

    /**
     * Extrudes a raw profile (no pre-existing points) into a full solid with real lettered vertices.
     * Used by the 2D editor's Extrude: a rectangle profile becomes an 8-point box.
     */
    public void extrudeProfileToSolid(float[] xs, float[] zs, float baseY, float height) {
        if (xs == null || zs == null || xs.length < 3 || xs.length != zs.length) return;
        int n = xs.length;
        String[] bottom = new String[n], top = new String[n];
        for (int i = 0; i < n; i++) {
            bottom[i] = addVertex(xs[i], baseY, zs[i]);
            top[i] = addVertex(xs[i], baseY + height, zs[i]);
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            ensureLine(bottom[i], bottom[j]);
            ensureLine(top[i], top[j]);
            ensureLine(bottom[i], top[i]);
        }
        addFaces(bottom, top);
        invalidate();
    }

    /** Adds the bottom, top and side faces (as shaded planes) for a prism given its two rings. */
    private void addFaces(String[] bottom, String[] top) {
        int n = bottom.length;
        List<String> b = new ArrayList<>(), t = new ArrayList<>();
        for (int i = 0; i < n; i++) { b.add(bottom[i]); t.add(top[i]); }
        addPlane(b);
        addPlane(t);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            addPlane(java.util.Arrays.asList(bottom[i], bottom[j], top[j], top[i]));
        }
    }

    /** Label of the selected vertex, or null. Lets the editor chain "add line between two taps". */
    public String getSelectedPointLabel() {
        if (selectedPointIndex >= 0 && selectedPointIndex < points.size()) return points.get(selectedPointIndex).label;
        return null;
    }

    // ---- Editing the values of the selected object (the 3D analog of the 2D "Dimension" editing) ----
    public static final int KIND_NONE = 0, KIND_POINT = 1, KIND_EDGE = 2, KIND_ANGLE = 3, KIND_MIDPOINT = 4, KIND_PLANE = 5;

    /** What kind of object is currently selected (so the editor opens the right value dialog). */
    public int getSelectionKind() {
        if (selectedPointIndex >= 0) return KIND_POINT;
        if (selectedLineIndex >= 0) return KIND_EDGE;
        if (selectedAngleIndex >= 0) return KIND_ANGLE;
        if (selectedMidLineIndex >= 0) return KIND_MIDPOINT;
        if (selectedPlaneIndex >= 0) return KIND_PLANE;
        return KIND_NONE;
    }

    /** Coordinates of the selected point (for pre-filling the edit dialog), or null. */
    public float[] getSelectedPointCoords() {
        if (selectedPointIndex < 0 || selectedPointIndex >= points.size()) return null;
        Point3D p = points.get(selectedPointIndex);
        return new float[]{p.x, p.y, p.z};
    }

    /** Moves the selected point; everything attached to it (edges, faces, angles) follows by label. */
    public boolean setSelectedPointCoords(float x, float y, float z) {
        if (selectedPointIndex < 0 || selectedPointIndex >= points.size()) return false;
        Point3D p = points.get(selectedPointIndex);
        p.x = x; p.y = y; p.z = z;
        invalidate();
        return true;
    }

    public float getSelectedEdgeLength() {
        if (selectedLineIndex < 0 || selectedLineIndex >= lines.size()) return 0;
        Line3D l = lines.get(selectedLineIndex);
        Point3D a = findPt(l.a), b = findPt(l.b);
        return (a != null && b != null) ? (float) dist(a, b) : 0;
    }

    /** Sets the selected edge's length by sliding its second endpoint along the edge direction. */
    public boolean setSelectedEdgeLength(float len) {
        if (selectedLineIndex < 0 || selectedLineIndex >= lines.size() || len <= 0) return false;
        Line3D l = lines.get(selectedLineIndex);
        Point3D a = findPt(l.a), b = findPt(l.b);
        if (a == null || b == null) return false;
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double cur = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (cur < 1e-4) return false;
        double s = len / cur;
        b.x = (float) (a.x + dx * s); b.y = (float) (a.y + dy * s); b.z = (float) (a.z + dz * s);
        invalidate();
        return true;
    }

    public float getSelectedAngleValue() {
        if (selectedAngleIndex < 0 || selectedAngleIndex >= angles.size()) return 0;
        Angle3D ag = angles.get(selectedAngleIndex);
        Point3D v = findPt(ag.vertex), a = findPt(ag.a), b = findPt(ag.b);
        if (v == null || a == null || b == null) return Math.round(ag.value);
        double[] u = {a.x - v.x, a.y - v.y, a.z - v.z}, w = {b.x - v.x, b.y - v.y, b.z - v.z};
        double lu = norm(u), lw = norm(w);
        if (lu < 1e-4 || lw < 1e-4) return 0;
        double dot = (u[0] * w[0] + u[1] * w[1] + u[2] * w[2]) / (lu * lw);
        return (float) Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot)))));
    }

    /** Sets the selected angle to {@code deg} by rotating its second arm in the arms' plane. */
    public boolean setSelectedAngleValue(float deg) {
        if (selectedAngleIndex < 0 || selectedAngleIndex >= angles.size()) return false;
        Angle3D ag = angles.get(selectedAngleIndex);
        Point3D v = findPt(ag.vertex), a = findPt(ag.a), b = findPt(ag.b);
        if (v == null || a == null || b == null) return false;
        double[] u = {a.x - v.x, a.y - v.y, a.z - v.z}, w = {b.x - v.x, b.y - v.y, b.z - v.z};
        double lu = norm(u), lw = norm(w);
        if (lu < 1e-4 || lw < 1e-4) return false;
        for (int i = 0; i < 3; i++) u[i] /= lu;
        double dot = w[0] * u[0] + w[1] * u[1] + w[2] * u[2];
        double[] perp = {w[0] - dot * u[0], w[1] - dot * u[1], w[2] - dot * u[2]};
        double lp = norm(perp);
        if (lp < 1e-4) return false; // arms are collinear — no rotation plane
        for (int i = 0; i < 3; i++) perp[i] /= lp;
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        b.x = (float) (v.x + lw * (c * u[0] + s * perp[0]));
        b.y = (float) (v.y + lw * (c * u[1] + s * perp[1]));
        b.z = (float) (v.z + lw * (c * u[2] + s * perp[2]));
        ag.fixedDeg = null; // the drawn value now equals the real geometry
        invalidate();
        return true;
    }

    private static double norm(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

    // ---- Save / load the editable 3D model (points, edges, faces, angles) ----

    /** Serializes the current model to JSON (tagged "3d" so History can tell it apart from 2D drawings). */
    public String toJson() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("type", "3d");
            org.json.JSONArray pts = new org.json.JSONArray();
            for (Point3D p : points) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("l", p.label == null ? "" : p.label);
                o.put("x", p.x); o.put("y", p.y); o.put("z", p.z);
                pts.put(o);
            }
            root.put("points", pts);
            org.json.JSONArray lns = new org.json.JSONArray();
            for (Line3D l : lines) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("a", l.a); o.put("b", l.b);
                if (l.color != null) o.put("c", (int) l.color);
                lns.put(o);
            }
            root.put("lines", lns);
            org.json.JSONArray pls = new org.json.JSONArray();
            for (Plane3D pl : planes) {
                org.json.JSONArray la = new org.json.JSONArray();
                for (String s : pl.labels) la.put(s);
                pls.put(la);
            }
            root.put("planes", pls);
            org.json.JSONArray ang = new org.json.JSONArray();
            for (Angle3D a : angles) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("v", a.vertex); o.put("a", a.a); o.put("b", a.b);
                if (a.fixedDeg != null) o.put("d", (double) a.fixedDeg);
                ang.put(o);
            }
            root.put("angles", ang);
            org.json.JSONArray cir = new org.json.JSONArray();
            for (Circle3D c : circles) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("l", c.label == null ? "" : c.label);
                o.put("x", c.cx); o.put("y", c.cy); o.put("z", c.cz); o.put("r", c.r);
                o.put("g", c.ground);
                cir.put(o);
            }
            root.put("circles", cir);
            org.json.JSONArray sph = new org.json.JSONArray();
            for (Sphere3D s : spheres) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("l", s.label == null ? "" : s.label);
                o.put("x", s.x); o.put("y", s.y); o.put("z", s.z); o.put("r", s.r);
                sph.put(o);
            }
            root.put("spheres", sph);
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"3d\"}";
        }
    }

    /** Rebuilds the model from {@link #toJson()} output. */
    public void loadFromJson(String json) {
        clear();
        if (json == null) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray pts = root.optJSONArray("points");
            if (pts != null) for (int i = 0; i < pts.length(); i++) {
                org.json.JSONObject o = pts.getJSONObject(i);
                addPoint(o.optString("l", ""), (float) o.optDouble("x"), (float) o.optDouble("y"), (float) o.optDouble("z"));
            }
            org.json.JSONArray lns = root.optJSONArray("lines");
            if (lns != null) for (int i = 0; i < lns.length(); i++) {
                org.json.JSONObject o = lns.getJSONObject(i);
                Integer c = o.has("c") ? o.getInt("c") : null;
                addLine(o.getString("a"), o.getString("b"), c);
            }
            org.json.JSONArray pls = root.optJSONArray("planes");
            if (pls != null) for (int i = 0; i < pls.length(); i++) {
                org.json.JSONArray la = pls.getJSONArray(i);
                List<String> ls = new ArrayList<>();
                for (int j = 0; j < la.length(); j++) ls.add(la.getString(j));
                addPlane(ls);
            }
            org.json.JSONArray ang = root.optJSONArray("angles");
            if (ang != null) for (int i = 0; i < ang.length(); i++) {
                org.json.JSONObject o = ang.getJSONObject(i);
                Float d = o.has("d") ? (float) o.getDouble("d") : null;
                angles.add(new Angle3D(o.getString("v"), o.getString("a"), o.getString("b"), d));
            }
            org.json.JSONArray cir = root.optJSONArray("circles");
            if (cir != null) for (int i = 0; i < cir.length(); i++) {
                org.json.JSONObject o = cir.getJSONObject(i);
                addCircle(o.optString("l", ""), (float) o.optDouble("x"), (float) o.optDouble("y"), (float) o.optDouble("z"), (float) o.optDouble("r"), o.optBoolean("g", false));
            }
            org.json.JSONArray sph = root.optJSONArray("spheres");
            if (sph != null) for (int i = 0; i < sph.length(); i++) {
                org.json.JSONObject o = sph.getJSONObject(i);
                addSphere(o.optString("l", ""), (float) o.optDouble("x"), (float) o.optDouble("y"), (float) o.optDouble("z"), (float) o.optDouble("r"));
            }
            invalidate();
        } catch (Exception e) {
            android.util.Log.e("GeometryCanvas3D", "loadFromJson failed: " + e.getMessage());
        }
    }

    /** True if the given saved-drawing data is a 3D model (vs. a 2D CAD drawing). */
    public static boolean isJson3d(String data) {
        return data != null && data.contains("\"type\":\"3d\"");
    }

    // ---- Undo / redo (snapshots of the model JSON; the camera is kept across steps) ----
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private static final int HISTORY_CAP = 60;

    /** Sets the current model as the undo baseline. Call once after the initial figure is built. */
    public void initHistory() {
        history.clear();
        history.add(toJson());
        historyIndex = 0;
    }

    /** Records the current model as a new undoable step (call after each user edit). */
    public void recordHistory() {
        if (historyIndex < 0) { initHistory(); return; }
        while (history.size() > historyIndex + 1) history.remove(history.size() - 1); // drop redo tail
        history.add(toJson());
        historyIndex = history.size() - 1;
        while (history.size() > HISTORY_CAP) { history.remove(0); historyIndex--; }
    }

    public boolean canUndo() { return historyIndex > 0; }
    public boolean canRedo() { return historyIndex >= 0 && historyIndex < history.size() - 1; }

    public boolean undo() {
        if (!canUndo()) return false;
        historyIndex--;
        restoreState(history.get(historyIndex));
        return true;
    }

    public boolean redo() {
        if (!canRedo()) return false;
        historyIndex++;
        restoreState(history.get(historyIndex));
        return true;
    }

    /** Rebuilds the model from a snapshot while preserving the current camera. */
    private void restoreState(String json) {
        float rx = rotateX, ry = rotateY, rz = rotateZ, sf = scaleFactor, tx = translateX, ty = translateY;
        loadFromJson(json);
        rotateX = rx; rotateY = ry; rotateZ = rz; scaleFactor = sf; translateX = tx; translateY = ty;
        invalidate();
    }

    private String nextVertexLabel() {
        // A, B, ... Z, then P27, P28, ...
        int n = 0;
        for (Point3D p : points) if (p.label != null && !p.label.isEmpty()) n++;
        if (n < 26) return String.valueOf((char) ('A' + n));
        return "P" + (n + 1);
    }

    /**
     * Hand-drawing: a tap in DRAW mode either snaps to an existing vertex (to connect to it) or drops
     * a new lettered point on the ground sketch plane (y=0). Reports the resulting label to the
     * listener (or null if the view is edge-on so the tap can't be placed on the plane).
     */
    private void handleSketchTap(float sx, float sy) {
        refreshProjection();

        // Snap to a nearby existing vertex so the sketch connects/closes cleanly.
        float snapTol = 45f;
        String snap = null;
        float best = snapTol;
        for (Point3D p : points) {
            if (!p.isVertex) continue;
            float d = (float) Math.hypot(sx - p.sx, sy - p.sy);
            if (d < best) { best = d; snap = p.label; }
        }

        String label = snap;
        if (label == null) {
            float[] g = screenToGround(sx, sy);
            if (g == null) { if (sketchListener != null) sketchListener.onSketchPoint(null); return; }
            label = addVertex(g[0], 0f, g[1]);
        }
        if (sketchListener != null) sketchListener.onSketchPoint(label);
    }

    /**
     * Inverse of the orthographic projection onto the ground plane (model y=0). Returns {x,z} model
     * coordinates for a screen tap, or null when the plane is viewed too edge-on to place a point.
     */
    public float[] screenToGround(float sx, float sy) {
        double rx = Math.toRadians(rotateX), ry = Math.toRadians(rotateY), rz = Math.toRadians(rotateZ);
        double sinRx = Math.sin(rx);
        if (Math.abs(sinRx) < 0.12) return null; // ground is edge-on — can't sketch on it

        float centerX = getWidth() / 2f + translateX;
        float centerY = getHeight() / 2f + translateY;
        float sceneScale = (Math.min(getWidth(), getHeight()) / 500f) * scaleFactor;

        double x3 = (sx - centerX) / sceneScale;
        double y3 = (sy - centerY) / sceneScale;
        // Undo roll (rz)
        double x1 = x3 * Math.cos(rz) + y3 * Math.sin(rz);
        double y2 = -x3 * Math.sin(rz) + y3 * Math.cos(rz);
        // With model y=0: x1 = px*cos(ry)+pz*sin(ry);  y2 = sin(rx)*(px*sin(ry) - pz*cos(ry))
        double co = Math.cos(ry), s = Math.sin(ry);
        double px = co * x1 + (s / sinRx) * y2;
        double pz = s * x1 - (co / sinRx) * y2;
        return new float[]{(float) px, (float) pz};
    }

    private void projectPoint(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        float x = p.x, y = -p.y, z = p.z;
        float x1 = (float) (x * Math.cos(ry) + z * Math.sin(ry));
        float z1 = (float) (-x * Math.sin(ry) + z * Math.cos(ry));
        float y2 = (float) (y * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float) (y * Math.sin(rx) + z1 * Math.cos(rx));
        float x3 = (float) (x1 * Math.cos(rz) - y2 * Math.sin(rz));
        float y3 = (float) (x1 * Math.sin(rz) + y2 * Math.cos(rz));

        // Orthographic (parallel) projection. Perspective division was shrinking far edges, which made
        // parallel edges converge and turned an extruded rectangle into a trapezoid-looking box. With a
        // parallel projection, parallel stays parallel and a box keeps equal, parallel opposite edges.
        p.sx = cx + x3 * scale;
        p.sy = cy + y3 * scale;
    }

    private void drawAxesCube(Canvas canvas, double rx, double ry, double rz, float axCX, float axCY) {
        float axScale = 60f;
        Point3D o = new Point3D("", 0,0,0); Point3D x = new Point3D("", 1,0,0);
        Point3D y = new Point3D("", 0,1,0); Point3D z = new Point3D("", 0,0,1);
        projectPoint(o, rx, ry, rz, axCX, axCY, axScale);
        projectPoint(x, rx, ry, rz, axCX, axCY, axScale);
        projectPoint(y, rx, ry, rz, axCX, axCY, axScale);
        projectPoint(z, rx, ry, rz, axCX, axCY, axScale);

        canvas.drawLine(o.sx, o.sy, x.sx, x.sy, xAxisPaint);
        canvas.drawLine(o.sx, o.sy, y.sx, y.sy, yAxisPaint);
        canvas.drawLine(o.sx, o.sy, z.sx, z.sy, zAxisPaint);

        axesTextPaint.setColor(Color.RED); canvas.drawText("X", x.sx, x.sy, axesTextPaint);
        axesTextPaint.setColor(Color.GREEN); canvas.drawText("Y", y.sx, y.sy, axesTextPaint);
        axesTextPaint.setColor(Color.BLUE); canvas.drawText("Z", z.sx, z.sy, axesTextPaint);
    }

    // --- FIXED: Circle now draws vertically on the XY plane (facing the camera) ---
    private void drawCircle(Canvas canvas, Circle3D c, double rx, double ry, double rz, float cx, float cy, float scale) {
        drawCircleShape(canvas, c.cx, c.cy, c.cz, c.r, c.ground, linePaint, rx, ry, rz, cx, cy, scale);
    }

    /** Draws a circle of radius {@code r} centred at (ccx,ccy,ccz). {@code ground} lays it on the XZ plane, else XY. */
    private void drawCircleShape(Canvas canvas, float ccx, float ccy, float ccz, float r, boolean ground, Paint paint,
                                 double rx, double ry, double rz, float cx, float cy, float scale) {
        int segments = 36;
        Point3D prev = null, first = null;
        for (int i = 0; i < segments; i++) {
            double ang = 2 * Math.PI * i / segments;
            Point3D curr = ground
                    ? new Point3D("", ccx + (float) Math.cos(ang) * r, ccy, ccz + (float) Math.sin(ang) * r)
                    : new Point3D("", ccx + (float) Math.cos(ang) * r, ccy + (float) Math.sin(ang) * r, ccz);
            projectPoint(curr, rx, ry, rz, cx, cy, scale);
            if (prev != null) canvas.drawLine(prev.sx, prev.sy, curr.sx, curr.sy, paint);
            else first = curr;
            prev = curr;
        }
        if (prev != null && first != null) canvas.drawLine(prev.sx, prev.sy, first.sx, first.sy, paint);
    }

    private void drawSphere(Canvas canvas, Sphere3D s, double rx, double ry, double rz, float cx, float cy, float scale) {
        Point3D center = new Point3D("", s.x, s.y, s.z);
        projectPoint(center, rx, ry, rz, cx, cy, scale);
        canvas.drawCircle(center.sx, center.sy, s.r * scale, linePaint);
    }

    // Pyramids, Cones, and Cylinders still draw flat on the XZ floor
    private void drawPyramid(Canvas canvas, Pyramid3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        float hw = p.w/2, hd = p.d/2;
        Point3D apex = new Point3D("", p.cx, p.cy + p.h, p.cz);
        Point3D[] base = { new Point3D("", p.cx - hw, p.cy, p.cz - hd), new Point3D("", p.cx + hw, p.cy, p.cz - hd),
                new Point3D("", p.cx + hw, p.cy, p.cz + hd), new Point3D("", p.cx - hw, p.cy, p.cz + hd) };
        projectPoint(apex, rx, ry, rz, cx, cy, scale);
        for(Point3D b : base) projectPoint(b, rx, ry, rz, cx, cy, scale);
        Path path = new Path();
        for(int i=0; i<4; i++) {
            path.reset(); path.moveTo(base[i].sx, base[i].sy); path.lineTo(base[(i+1)%4].sx, base[(i+1)%4].sy);
            path.lineTo(apex.sx, apex.sy); path.close();
            planePaint.setColor(Color.argb(80, 200, 200, 200)); canvas.drawPath(path, planePaint); canvas.drawPath(path, linePaint);
        }
    }

    private void drawCone(Canvas canvas, Cone3D c, double rx, double ry, double rz, float cx, float cy, float scale) {
        Point3D tip = new Point3D("", c.cx, c.cy + c.h, c.cz);
        projectPoint(tip, rx, ry, rz, cx, cy, scale);
        int segments = 24; Point3D prev = null;
        for(int i=0; i<=segments; i++) {
            double ang = 2 * Math.PI * i / segments;
            Point3D curr = new Point3D("", c.cx + (float)Math.cos(ang)*c.r, c.cy, c.cz + (float)Math.sin(ang)*c.r);
            projectPoint(curr, rx, ry, rz, cx, cy, scale);
            if(prev != null) {
                Path f = new Path(); f.moveTo(prev.sx, prev.sy); f.lineTo(curr.sx, curr.sy); f.lineTo(tip.sx, tip.sy); f.close();
                planePaint.setColor(Color.argb(70, 100, 100, 255)); canvas.drawPath(f, planePaint);
            }
            prev = curr;
        }
    }

    private void drawCylinder(Canvas canvas, Cylinder3D shape, double rx, double ry, double rz, float cx, float cy, float scale) {
        int segments = 24;
        for(int i=0; i<segments; i++) {
            double a = 2 * Math.PI * i / segments; double a2 = 2 * Math.PI * (i+1) / segments;
            Point3D p1 = new Point3D("", shape.cx+(float)Math.cos(a)*shape.r, shape.cy, shape.cz+(float)Math.sin(a)*shape.r);
            Point3D p2 = new Point3D("", shape.cx+(float)Math.cos(a2)*shape.r, shape.cy, shape.cz+(float)Math.sin(a2)*shape.r);
            Point3D p3 = new Point3D("", shape.cx+(float)Math.cos(a2)*shape.r, shape.cy+shape.h, shape.cz+(float)Math.sin(a2)*shape.r);
            Point3D p4 = new Point3D("", shape.cx+(float)Math.cos(a)*shape.r, shape.cy+shape.h, shape.cz+(float)Math.sin(a)*shape.r);
            projectPoint(p1, rx, ry, rz, cx, cy, scale); projectPoint(p2, rx, ry, rz, cx, cy, scale);
            projectPoint(p3, rx, ry, rz, cx, cy, scale); projectPoint(p4, rx, ry, rz, cx, cy, scale);
            Path wall = new Path(); wall.moveTo(p1.sx, p1.sy); wall.lineTo(p2.sx, p2.sy); wall.lineTo(p3.sx, p3.sy); wall.lineTo(p4.sx, p4.sy); wall.close();
            planePaint.setColor(Color.argb(60, 150, 150, 150)); canvas.drawPath(wall, planePaint);
        }
    }

    private void drawPrism(Canvas canvas, Prism3D pr, double rx, double ry, double rz, float cx, float cy, float scale) {
        int n = pr.xs.length;
        Point3D[] bottom = new Point3D[n];
        Point3D[] top = new Point3D[n];
        for (int i = 0; i < n; i++) {
            bottom[i] = new Point3D("", pr.xs[i], pr.baseY, pr.zs[i]);
            top[i] = new Point3D("", pr.xs[i], pr.baseY + pr.height, pr.zs[i]);
            projectPoint(bottom[i], rx, ry, rz, cx, cy, scale);
            projectPoint(top[i], rx, ry, rz, cx, cy, scale);
        }

        // Side walls.
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            Path wall = new Path();
            wall.moveTo(bottom[i].sx, bottom[i].sy);
            wall.lineTo(bottom[j].sx, bottom[j].sy);
            wall.lineTo(top[j].sx, top[j].sy);
            wall.lineTo(top[i].sx, top[i].sy);
            wall.close();
            planePaint.setStyle(Paint.Style.FILL);
            planePaint.setColor(Color.argb(70, 120, 170, 220));
            canvas.drawPath(wall, planePaint);
            planePaint.setStyle(Paint.Style.STROKE);
            planePaint.setColor(colLine3d);
            canvas.drawPath(wall, planePaint);
        }

        // Top and bottom faces (outlines, slightly stronger fill on top).
        Path topFace = new Path(), botFace = new Path();
        for (int i = 0; i < n; i++) {
            if (i == 0) { topFace.moveTo(top[i].sx, top[i].sy); botFace.moveTo(bottom[i].sx, bottom[i].sy); }
            else { topFace.lineTo(top[i].sx, top[i].sy); botFace.lineTo(bottom[i].sx, bottom[i].sy); }
        }
        topFace.close(); botFace.close();
        planePaint.setStyle(Paint.Style.FILL);
        planePaint.setColor(Color.argb(110, 150, 195, 240));
        canvas.drawPath(topFace, planePaint);
        planePaint.setStyle(Paint.Style.STROKE);
        planePaint.setColor(colLine3d);
        canvas.drawPath(topFace, planePaint);
        canvas.drawPath(botFace, planePaint);
    }

    private Point3D findPt(String label) {
        for (Point3D p : points) if (p.label != null && p.label.equalsIgnoreCase(label)) return p;
        return null;
    }

    /**
     * Finger-draws a circle/sphere: ACTION_DOWN drops the centre on the ground plane, ACTION_MOVE drags
     * the radius, ACTION_UP commits it. Returns true when it consumed the gesture; false (e.g. the view is
     * edge-on so the centre can't be placed) lets the normal orbit/zoom handling run instead.
     */
    private boolean handleRadiusDrag(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] g = screenToGround(e.getX(), e.getY());
                if (g == null) { isRadiusDragging = false; return false; } // edge-on: fall back to orbit
                pendingCx = g[0]; pendingCy = 0f; pendingCz = g[1];
                pendingRadius = 0f; isRadiusDragging = true;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!isRadiusDragging) return false;
                float[] g = screenToGround(e.getX(), e.getY());
                if (g != null) {
                    float dx = g[0] - pendingCx, dz = g[1] - pendingCz;
                    pendingRadius = (float) Math.hypot(dx, dz);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!isRadiusDragging) return false;
                isRadiusDragging = false;
                boolean isCircle = interactionMode == MODE_DRAW_CIRCLE;
                if (pendingRadius >= 5f && radiusShapeListener != null)
                    radiusShapeListener.onRadiusShape(isCircle, pendingCx, pendingCy, pendingCz, pendingRadius);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                isRadiusDragging = false; invalidate(); return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if ((interactionMode == MODE_DRAW_CIRCLE || interactionMode == MODE_DRAW_SPHERE) && handleRadiusDrag(e)) return true;
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = e.getPointerId(0);
                lastTouchX = e.getX();
                lastTouchY = e.getY();
                downX = e.getX();
                downY = e.getY();
                movedSinceDown = false;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int pointerIndex = e.getActionIndex();
                int pointerId = e.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    if (newPointerIndex < e.getPointerCount()) {
                        lastTouchX = e.getX(newPointerIndex);
                        lastTouchY = e.getY(newPointerIndex);
                        activePointerId = e.getPointerId(newPointerIndex);
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && e.getPointerCount() == 1) {
                    int idx = e.findPointerIndex(activePointerId);
                    if (idx != -1) {
                        float currX = e.getX(idx);
                        float currY = e.getY(idx);
                        float dx = currX - lastTouchX;
                        float dy = currY - lastTouchY;

                        if (Math.hypot(currX - downX, currY - downY) > 12) movedSinceDown = true;

                        if (isMoveMode) {
                            translateX += dx;
                            translateY += dy;
                        } else {
                            rotateY += dx * 0.5f;
                            rotateX -= dy * 0.5f;
                        }

                        lastTouchX = currX;
                        lastTouchY = currY;
                        invalidate();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (selectionEnabled && !movedSinceDown && !scaleDetector.isInProgress()) {
                    if (interactionMode == MODE_DRAW) handleSketchTap(e.getX(), e.getY());
                    else pickAt(e.getX(), e.getY());
                }
                activePointerId = -1;
                break;
            case MotionEvent.ACTION_CANCEL:
                activePointerId = -1;
                break;
        }
        return true;
    }
}