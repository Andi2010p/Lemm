package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class GeometryCanvas3D extends View {
    private Paint linePaint, pointPaint, planePaint, textPaint;
    private List<Point3D> points = new ArrayList<>();
    private List<Line3D> lines = new ArrayList<>();
    private List<Plane3D> planes = new ArrayList<>();
    private List<Cone3D> cones = new ArrayList<>();
    private List<Circle3D> circles = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();

    private float rotateX = -25f, rotateY = 45f, rotateZ = 0f;
    private float scaleFactor = 1.0f;
    private float prevX, prevY;
    private ScaleGestureDetector scaleDetector;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    public static class Point3D {
        public String label; float x, y, z, sx, sy; boolean isVertex;
        Point3D(String l, float x, float y, float z) {
            this.label = l; this.x = x; this.y = y; this.z = z;
            this.isVertex = l != null && !l.isEmpty();
        }
    }
    private static class Line3D { String a, b; Line3D(String a, String b) { this.a = a; this.b = b; } }
    private static class Plane3D { List<String> labels; Plane3D(List<String> l) { this.labels = l; } }
    private static class Cone3D { float cx, cy, cz, r, h; Cone3D(float x, float y, float z, float r, float h) { this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; } }
    private static class Circle3D { float cx, cy, cz, r; Circle3D(float x, float y, float z, float r) { this.cx = x; this.cy = y; this.cz = z; this.r = r; } }
    private static class Sphere3D { float cx, cy, cz, r; Sphere3D(float x, float y, float z, float r) { this.cx = x; this.cy = y; this.cz = z; this.r = r; } }

    public GeometryCanvas3D(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(Color.BLACK); linePaint.setStrokeWidth(3f); linePaint.setStyle(Paint.Style.STROKE);
        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG); pointPaint.setColor(Color.RED);
        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG); planePaint.setStyle(Paint.Style.FILL);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setTextSize(32f); textPaint.setColor(Color.BLUE); textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleFactor *= d.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f));
                if (zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage());
                invalidate(); return true;
            }
        });
    }

    public void addPoint(String l, float x, float y, float z) { points.add(new Point3D(l, x, y, z)); invalidate(); }
    public void addLine(String a, String b) { lines.add(new Line3D(a, b)); invalidate(); }
    public void addPlane(List<String> labels) { planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { circles.add(new Circle3D(x, y, z, r)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) { spheres.add(new Sphere3D(x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h) { cones.add(new Cone3D(x, y, z, r, h)); invalidate(); }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); circles.clear(); spheres.clear();
        scaleFactor = 1.0f; rotateX = -25f; rotateY = 45f; rotateZ = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);

        float drawCX = getWidth()/2f, drawCY = getHeight()/2f;
        float baseScale = (Math.min(getWidth(), getHeight()) / 600f) * scaleFactor;
        double rx = Math.toRadians(rotateX), ry = Math.toRadians(rotateY), rz = Math.toRadians(rotateZ);

        for (Point3D p : points) project(p, rx, ry, rz, drawCX, drawCY, baseScale);

        // 1. Draw Shaded Planes
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
                planePaint.setColor(Color.argb(100, 100, 200, 255));
                canvas.drawPath(path, planePaint);
                canvas.drawPath(path, linePaint);
            }
        }

        // 2. Draw Circles (with fill/plane inside)
        for (Circle3D c : circles) {
            Path p = new Path();
            for (int i = 0; i <= 40; i++) {
                double a = 2 * Math.PI * i / 40;
                Point3D pt = new Point3D(null, c.cx + (float)Math.cos(a)*c.r, c.cy, c.cz + (float)Math.sin(a)*c.r);
                project(pt, rx, ry, rz, drawCX, drawCY, baseScale);
                if (i == 0) p.moveTo(pt.sx, pt.sy); else p.lineTo(pt.sx, pt.sy);
            }
            p.close();
            planePaint.setColor(Color.argb(70, 100, 200, 255));
            canvas.drawPath(p, planePaint);
            canvas.drawPath(p, linePaint);
        }

        // 3. Draw Solid Cones
        for (Cone3D c : cones) {
            Point3D apex = new Point3D(null, c.cx, c.cy + c.h, c.cz);
            project(apex, rx, ry, rz, drawCX, drawCY, baseScale);
            int segs = 16;
            Point3D[] basePts = new Point3D[segs];
            for (int i = 0; i < segs; i++) {
                double a = 2 * Math.PI * i / segs;
                basePts[i] = new Point3D(null, c.cx + (float)Math.cos(a)*c.r, c.cy, c.cz + (float)Math.sin(a)*c.r);
                project(basePts[i], rx, ry, rz, drawCX, drawCY, baseScale);
            }

            // Sides (shaded triangles)
            for (int i = 0; i < segs; i++) {
                Path side = new Path();
                side.moveTo(apex.sx, apex.sy);
                side.lineTo(basePts[i].sx, basePts[i].sy);
                side.lineTo(basePts[(i+1)%segs].sx, basePts[(i+1)%segs].sy);
                side.close();
                float brightness = 0.5f + 0.5f * (float)Math.abs(Math.cos(2 * Math.PI * i / segs + ry));
                planePaint.setColor(Color.rgb((int)(100*brightness), (int)(180*brightness), (int)(255*brightness)));
                canvas.drawPath(side, planePaint);
                canvas.drawLine(apex.sx, apex.sy, basePts[i].sx, basePts[i].sy, linePaint);
            }
            // Base Circle (shaded)
            Path base = new Path();
            for (int i = 0; i < segs; i++) if (i==0) base.moveTo(basePts[i].sx, basePts[i].sy); else base.lineTo(basePts[i].sx, basePts[i].sy);
            base.close();
            planePaint.setColor(Color.argb(80, 0, 120, 255));
            canvas.drawPath(base, planePaint);
            canvas.drawPath(base, linePaint);
        }

        // 4. Draw Spheres, Lines, and Points
        for (Sphere3D s : spheres) {
            int stacks = 8; int slices = 12;
            for (int i = 1; i < stacks; i++) {
                double phi = Math.PI * i / stacks - Math.PI / 2;
                float r = s.r * (float)Math.cos(phi);
                float y = s.cy + s.r * (float)Math.sin(phi);
                Path path = new Path();
                for (int j = 0; j <= slices; j++) {
                    double theta = 2 * Math.PI * j / slices;
                    Point3D pt = new Point3D(null, s.cx + r * (float)Math.cos(theta), y, s.cz + r * (float)Math.sin(theta));
                    project(pt, rx, ry, rz, drawCX, drawCY, baseScale);
                    if (j == 0) path.moveTo(pt.sx, pt.sy); else path.lineTo(pt.sx, pt.sy);
                }
                canvas.drawPath(path, linePaint);
            }
        }
        for (Line3D l : lines) {
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, linePaint);
        }
        for (Point3D p : points) if (p.isVertex) {
            canvas.drawCircle(p.sx, p.sy, 10, pointPaint);
            canvas.drawText(p.label, p.sx + 15, p.sy - 15, textPaint);
        }
    }

    private void project(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        float x = p.x, y = p.y, z = p.z;
        float xz = (float)(x * Math.cos(rz) - y * Math.sin(rz));
        float yz = (float)(x * Math.sin(rz) + y * Math.cos(rz));
        float x1 = (float)(xz * Math.cos(ry) + z * Math.sin(ry));
        float z1 = (float)(-xz * Math.sin(ry) + z * Math.cos(ry));
        float y2 = (float)(yz * Math.cos(rx) - z1 * Math.sin(rx));
        p.sx = cx + x1 * scale; p.sy = cy - y2 * scale;
    }

    private Point3D findPt(String l) {
        if (l == null) return null;
        for (Point3D p : points) if (l.equalsIgnoreCase(p.label)) return p;
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() == 1) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { prevX = e.getX(); prevY = e.getY(); }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                rotateY += (e.getX() - prevX) * 0.5f; rotateX -= (e.getY() - prevY) * 0.5f;
                prevX = e.getX(); prevY = e.getY(); invalidate();
            }
        }
        return true;
    }
    public void rotateX(float d) { rotateX += d; invalidate(); }
    public void rotateY(float d) { rotateY += d; invalidate(); }
    public void rotateZ(float d) { rotateZ += d; invalidate(); }
    public void zoomIn() { scaleFactor *= 1.2f; invalidate(); notifyZoom(); }
    public void zoomOut() { scaleFactor /= 1.2f; invalidate(); notifyZoom(); }
    private void notifyZoom() { if(zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage()); }
    public int getZoomPercentage() { return (int)(scaleFactor * 100); }
}
