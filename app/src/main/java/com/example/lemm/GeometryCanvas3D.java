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
    private int activePointerId = -1;
    private ScaleGestureDetector scaleDetector;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    public static class Point3D {
        public String label; float x, y, z, sx, sy; boolean isVertex;
        Point3D(String l, float x, float y, float z) { this.label = l; this.x = x; this.y = y; this.z = z; this.isVertex = l != null && !l.isEmpty(); }
    }
    private static class Line3D { String a, b; Line3D(String a, String b) { this.a = a; this.b = b; } }
    private static class Plane3D { List<String> labels; Plane3D(List<String> l) { this.labels = l; } }
    private static class Cone3D { String label; float cx, cy, cz, r, h, curvature; Cone3D(String l, float x, float y, float z, float r, float h, float cur) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; this.curvature = cur; } }
    private static class Pyramid3D { String label; float cx, cy, cz, w, d, h; Pyramid3D(String l, float x, float y, float z, float w, float d, float h) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.w = w; this.d = d; this.h = h; } }
    private static class Cylinder3D { String label; float cx, cy, cz, r, h; Cylinder3D(String l, float x, float y, float z, float r, float h) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; } }
    private static class Circle3D { String label; float cx, cy, cz, r; Circle3D(String l, float x, float y, float z, float r) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; } }
    private static class Sphere3D { String label; float x, y, z, r; Sphere3D(String l, float x, float y, float z, float r) { this.label = l; this.x = x; this.y = y; this.z = z; this.r = r; } }

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
    public void addLine(String a, String b) { lines.add(new Line3D(a, b)); invalidate(); }
    public void addPlane(List<String> labels) { planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { circles.add(new Circle3D(l, x, y, z, r)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) { spheres.add(new Sphere3D(l, x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h, float cur) { cones.add(new Cone3D(l, x, y, z, r, h, cur)); invalidate(); }
    public void addPyramid(String l, float x, float y, float z, float w, float d, float h) { pyramids.add(new Pyramid3D(l, x, y, z, w, d, h)); invalidate(); }
    public void addCylinder(String l, float x, float y, float z, float r, float h) { cylinders.add(new Cylinder3D(l, x, y, z, r, h)); invalidate(); }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); pyramids.clear(); cylinders.clear(); circles.clear(); spheres.clear();
        resetRotation();
    }

    /** Parses a solution's raw text and adds every 3D command (DRAW3D/LINE3D/PLANE3D/…) to this canvas.
     *  Lets the figure be rebuilt offscreen (e.g. when exporting from History). */
    public void loadFromSolution(String rawText) {
        if (rawText == null) return;
        for (String raw : rawText.split("\n")) {
            String line = raw.trim();
            try {
                if (line.startsWith("DRAW3D:")) { String[] a = cmdArgs(line); if (a.length >= 4) addPoint(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3])); }
                else if (line.startsWith("LINE3D:")) { String[] a = cmdArgs(line); if (a.length >= 2) addLine(a[0].trim(), a[1].trim()); }
                else if (line.startsWith("PLANE3D:")) { String[] a = cmdArgs(line); if (a.length >= 2) { List<String> v = new ArrayList<>(); for (int i = 1; i < a.length; i++) v.add(a[i].trim()); addPlane(v); } }
                else if (line.startsWith("CONE3D:")) { String[] a = cmdArgs(line); if (a.length >= 7) addCone(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5]), pf(a[6])); }
                else if (line.startsWith("PYRAMID3D:")) { String[] a = cmdArgs(line); if (a.length >= 7) addPyramid(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5]), pf(a[6])); }
                else if (line.startsWith("CYLINDER3D:")) { String[] a = cmdArgs(line); if (a.length >= 6) addCylinder(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4]), pf(a[5])); }
                else if (line.startsWith("SPHERE3D:")) { String[] a = cmdArgs(line); if (a.length >= 5) addSphere(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4])); }
                else if (line.startsWith("CIRCLE3D:")) { String[] a = cmdArgs(line); if (a.length >= 5) addCircle(a[0].trim(), pf(a[1]), pf(a[2]), pf(a[3]), pf(a[4])); }
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
                && pyramids.isEmpty() && cylinders.isEmpty() && circles.isEmpty() && spheres.isEmpty();
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
        canvas.drawColor(colBg3d);

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
        for (Pyramid3D p : pyramids) drawPyramid(canvas, p, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Circle3D c : circles) drawCircle(canvas, c, radX, radY, radZ, centerX, centerY, sceneScale);
        for (Sphere3D s : spheres) drawSphere(canvas, s, radX, radY, radZ, centerX, centerY, sceneScale);

        for (Plane3D pl : planes) {
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
                planePaint.setStyle(Paint.Style.FILL);
                planePaint.setColor(Color.argb(120, 180, 210, 255));
                canvas.drawPath(path, planePaint);
                planePaint.setStyle(Paint.Style.STROKE);
                planePaint.setColor(colLine3d);
                canvas.drawPath(path, planePaint);
            }
        }

        for (Line3D l : lines) {
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) {
                linePaint.setStrokeWidth(3f);
                canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, linePaint);
            }
        }

        for (Point3D p : points) {
            if (p.isVertex) {
                canvas.drawCircle(p.sx, p.sy, 6, pointPaint);
                canvas.drawText(p.label, p.sx + 10, p.sy - 10, textPaint);
            }
        }
    }

    private void projectPoint(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        float x = p.x, y = -p.y, z = p.z;
        float x1 = (float) (x * Math.cos(ry) + z * Math.sin(ry));
        float z1 = (float) (-x * Math.sin(ry) + z * Math.cos(ry));
        float y2 = (float) (y * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float) (y * Math.sin(rx) + z1 * Math.cos(rx));
        float x3 = (float) (x1 * Math.cos(rz) - y2 * Math.sin(rz));
        float y3 = (float) (x1 * Math.sin(rz) + y2 * Math.cos(rz));

        float perspective = 1200f / (1200f + z2);
        p.sx = cx + x3 * scale * perspective;
        p.sy = cy + y3 * scale * perspective;
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
        int segments = 36;
        Point3D prev = null;
        Point3D first = null;
        for(int i=0; i<segments; i++) {
            double ang = 2 * Math.PI * i / segments;
            // Changed: Uses c.cy + Math.sin instead of c.cz + Math.sin
            Point3D curr = new Point3D("", c.cx + (float)Math.cos(ang)*c.r, c.cy + (float)Math.sin(ang)*c.r, c.cz);
            projectPoint(curr, rx, ry, rz, cx, cy, scale);
            if (prev != null) canvas.drawLine(prev.sx, prev.sy, curr.sx, curr.sy, linePaint);
            else first = curr;
            prev = curr;
        }
        if (prev != null && first != null) canvas.drawLine(prev.sx, prev.sy, first.sx, first.sy, linePaint);
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

    private Point3D findPt(String label) {
        for (Point3D p : points) if (p.label != null && p.label.equalsIgnoreCase(label)) return p;
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = e.getPointerId(0);
                lastTouchX = e.getX();
                lastTouchY = e.getY();
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
            case MotionEvent.ACTION_CANCEL:
                activePointerId = -1;
                break;
        }
        return true;
    }
}