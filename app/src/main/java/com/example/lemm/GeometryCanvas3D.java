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
    private List<Cylinder3D> cylinders = new ArrayList<>();
    private List<Circle3D> circles = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();
    private java.util.Stack<String> historyStack = new java.util.Stack<>();

    private float rotateX = -25f, rotateY = 45f, rotateZ = 0f;
    private float scaleFactor = 1.0f;
    private float prevX, prevY;
    private ScaleGestureDetector scaleDetector;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    // Data Classes
    public static class Point3D {
        public String label; float x, y, z, sx, sy, sz; boolean isVertex;
        Point3D(String l, float x, float y, float z) {
            this.label = l; this.x = x; this.y = y; this.z = z;
            this.isVertex = l != null && !l.isEmpty();
        }
    }
    private static class Line3D { String a, b; Line3D(String a, String b) { this.a = a; this.b = b; } }
    private static class Plane3D { List<String> labels; Plane3D(List<String> l) { this.labels = l; } }
    private static class Cone3D {
        String label; float cx, cy, cz, r, h, curvature;
        Cone3D(String l, float x, float y, float z, float r, float h, float curvature) {
            this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h;
            this.curvature = Math.max(0.1f, curvature);
        }
    }
    private static class Cylinder3D { String label; float cx, cy, cz, r, h;
        Cylinder3D(String l, float x, float y, float z, float r, float h) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; }
    }
    private static class Circle3D { String label; float cx, cy, cz, r;
        Circle3D(String l, float x, float y, float z, float r) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; }
    }
    private static class Sphere3D { String label; float cx, cy, cz, r;
        Sphere3D(String l, float x, float y, float z, float r) { this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; }
    }

    public GeometryCanvas3D(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.BLACK);
        linePaint.setStrokeWidth(2.0f);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.RED);

        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(28f);
        textPaint.setColor(Color.parseColor("#0C3D6A"));
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scaleFactor *= d.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f));
                if (zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage());
                invalidate();
                return true;
            }
        });
    }

    // Input Methods
    public void addPoint(String l, float x, float y, float z) { saveToHistory();points.add(new Point3D(l, x, y, z)); invalidate(); }
    public void addLine(String a, String b) { saveToHistory();lines.add(new Line3D(a, b)); invalidate(); }
    public void addPlane(List<String> labels) {saveToHistory(); planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { saveToHistory();circles.add(new Circle3D(l, x, y, z, r)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) {saveToHistory(); spheres.add(new Sphere3D(l, x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h, float curvature) {
        saveToHistory();cones.add(new Cone3D(l, x, y, z, r, h, curvature));
        invalidate();
    }
    public void addCylinder(String l, float x, float y, float z, float r, float h) { saveToHistory();cylinders.add(new Cylinder3D(l, x, y, z, r, h)); invalidate(); }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); cylinders.clear(); circles.clear(); spheres.clear();
        scaleFactor = 1.0f; rotateX = -25f; rotateY = 45f; rotateZ = 0f;
        invalidate();
    }
    public void saveToHistory() {
        // We convert the current counts of objects into a simple state or clone lists
        // For a simple implementation, we store the counts or clone the lists
        // Here we will use a "Snapshot" approach
        historyStack.push(serializeState());
    }

    public void undo() {
        if (historyStack.size() > 1) {
            historyStack.pop(); // Remove current state
            restoreState(historyStack.peek());
            invalidate();
        } else if (historyStack.size() == 1) {
            clear(); // Last item, just clear
        }
    }

    private String serializeState() {
        // Simple serialization to keep it lightweight
        return points.size() + "|" + lines.size() + "|" + planes.size() + "|" +
                cones.size() + "|" + cylinders.size() + "|" + circles.size();
    }

    private void restoreState(String state) {
        try {
            String[] parts = state.split("\\|");
            // We trim the lists back to the size they were at that point in history
            if (points.size() > Integer.parseInt(parts[0])) points.subList(Integer.parseInt(parts[0]), points.size()).clear();
            if (lines.size() > Integer.parseInt(parts[1])) lines.subList(Integer.parseInt(parts[1]), lines.size()).clear();
            if (planes.size() > Integer.parseInt(parts[2])) planes.subList(Integer.parseInt(parts[2]), planes.size()).clear();
            if (cones.size() > Integer.parseInt(parts[3])) cones.subList(Integer.parseInt(parts[3]), cones.size()).clear();
            if (cylinders.size() > Integer.parseInt(parts[4])) cylinders.subList(Integer.parseInt(parts[4]), cylinders.size()).clear();
            if (circles.size() > Integer.parseInt(parts[5])) circles.subList(Integer.parseInt(parts[5]), circles.size()).clear();
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);

        float drawCX = getWidth()/2f, drawCY = getHeight()/2f;
        float baseScale = (Math.min(getWidth(), getHeight()) / 600f) * scaleFactor;

        double rx = Math.toRadians(rotateX);
        double ry = Math.toRadians(rotateY);
        double rz = Math.toRadians(rotateZ);

        // Pre-project simple points
        for (Point3D p : points) project(p, rx, ry, rz, drawCX, drawCY, baseScale);

        // 1. Draw Planes (Solid Surfaces)
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
                planePaint.setColor(Color.argb(140, 180, 220, 255));
                canvas.drawPath(path, planePaint);
                canvas.drawPath(path, linePaint);
            }
        }

        // 2. Draw Circles
        for (Circle3D c : circles) {
            Path p = drawCirclePath(c.cx, c.cy, c.cz, c.r, rx, ry, rz, drawCX, drawCY, baseScale);
            planePaint.setColor(Color.argb(120, 135, 206, 250));
            canvas.drawPath(p, planePaint);
            canvas.drawPath(p, linePaint);
        }
// 3. Solid Curved Buildings (Architecture)
        for (Cone3D c : cones) {
            int slices = 40;   // Radial segments
            int stacks = 15;   // Vertical segments to show the curve
            float curvature = (c.curvature <= 0) ? 1.0f : c.curvature;

            for (int s = 0; s < stacks; s++) {
                float h1_pct = (float) s / stacks;
                float h2_pct = (float) (s + 1) / stacks;

                // CURVED MATH: Radius at height percentage
                float r1 = c.r * (float) Math.pow(1.0f - h1_pct, curvature);
                float r2 = c.r * (float) Math.pow(1.0f - h2_pct, curvature);

                float y1 = c.cy + (h1_pct * c.h);
                float y2 = c.cy + (h2_pct * c.h);

                for (int i = 0; i < slices; i++) {
                    double a1 = 2 * Math.PI * i / slices;
                    double a2 = 2 * Math.PI * (i + 1) / slices;

                    // Define 4 points of a side segment (A "Panel" of the building)
                    Point3D p1 = new Point3D(null, c.cx + (float)Math.cos(a1)*r1, y1, c.cz + (float)Math.sin(a1)*r1);
                    Point3D p2 = new Point3D(null, c.cx + (float)Math.cos(a2)*r1, y1, c.cz + (float)Math.sin(a2)*r1);
                    Point3D p3 = new Point3D(null, c.cx + (float)Math.cos(a2)*r2, y2, c.cz + (float)Math.sin(a2)*r2);
                    Point3D p4 = new Point3D(null, c.cx + (float)Math.cos(a1)*r2, y2, c.cz + (float)Math.sin(a1)*r2);

                    project(p1, rx, ry, rz, drawCX, drawCY, baseScale);
                    project(p2, rx, ry, rz, drawCX, drawCY, baseScale);
                    project(p3, rx, ry, rz, drawCX, drawCY, baseScale);
                    project(p4, rx, ry, rz, drawCX, drawCY, baseScale);

                    Path segmentPath = new Path();
                    segmentPath.moveTo(p1.sx, p1.sy);
                    segmentPath.lineTo(p2.sx, p2.sy);
                    segmentPath.lineTo(p3.sx, p3.sy);
                    segmentPath.lineTo(p4.sx, p4.sy);
                    segmentPath.close();

                    // Architectural Shading (Light source from top-right)
                    float lightIntensity = (float)((Math.cos(a1) + 1.0) / 2.0);
                    int baseBlue = 180;
                    int rColor = (int)(50 * lightIntensity);
                    int gColor = (int)(100 * lightIntensity);
                    int bColor = (int)(baseBlue * lightIntensity);

                    planePaint.setColor(Color.rgb(rColor + 30, gColor + 50, bColor + 70));
                    canvas.drawPath(segmentPath, planePaint);
                }
            }
            // Base Floor
            Path baseCircle = drawCirclePath(c.cx, c.cy, c.cz, c.r, rx, ry, rz, drawCX, drawCY, baseScale);
            planePaint.setColor(Color.argb(180, 40, 40, 40));
            canvas.drawPath(baseCircle, planePaint);
            canvas.drawPath(baseCircle, linePaint);
        }

        // 4. Draw Cylinders
        for (Cylinder3D cy : cylinders) {
            int segs = 32;
            for (int i = 0; i < segs; i++) {
                double a1 = 2 * Math.PI * i / segs;
                double a2 = 2 * Math.PI * (i + 1) / segs;
                Point3D p1 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy, cy.cz+(float)Math.sin(a1)*cy.r);
                Point3D p2 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p3 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy+cy.h, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p4 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy+cy.h, cy.cz+(float)Math.sin(a1)*cy.r);
                project(p1, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p2, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p3, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p4, rx, ry, rz, drawCX, drawCY, baseScale);

                Path wall = new Path();
                wall.moveTo(p1.sx, p1.sy); wall.lineTo(p2.sx, p2.sy); wall.lineTo(p3.sx, p3.sy); wall.lineTo(p4.sx, p4.sy); wall.close();
                float br = 0.5f + 0.4f * (float)Math.abs(Math.cos(a1 + ry));
                planePaint.setColor(Color.rgb((int)(100*br), (int)(160*br), (int)(230*br)));
                canvas.drawPath(wall, planePaint);
            }
            Path top = drawCirclePath(cy.cx, cy.cy+cy.h, cy.cz, cy.r, rx, ry, rz, drawCX, drawCY, baseScale);
            planePaint.setColor(Color.argb(130, 100, 180, 255));
            canvas.drawPath(top, planePaint);
            canvas.drawPath(top, linePaint);
        }

        // 5. Draw Lines, Spheres, Points
        for (Sphere3D s : spheres) {
            int stacks = 8; int slices = 16;
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
        for (Point3D p : points) {
            if (p.isVertex) {
                canvas.drawCircle(p.sx, p.sy, 7, pointPaint);
                canvas.drawText(p.label, p.sx + 12, p.sy - 12, textPaint);
            }
        }
    }

    private Path drawCirclePath(float cx, float cy, float cz, float r, double rx, double ry, double rz, float dcx, float dcy, float scale) {
        Path p = new Path();
        int segs = 48;
        for (int i = 0; i <= segs; i++) {
            double a = 2 * Math.PI * i / segs;
            Point3D pt = new Point3D(null, cx + (float)Math.cos(a)*r, cy, cz + (float)Math.sin(a)*r);
            project(pt, rx, ry, rz, dcx, dcy, scale);
            if (i == 0) p.moveTo(pt.sx, pt.sy); else p.lineTo(pt.sx, pt.sy);
        }
        p.close();
        return p;
    }

    private void project(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        // Rotation
        float xz = (float)(p.x * Math.cos(rz) - p.y * Math.sin(rz));
        float yz = (float)(p.x * Math.sin(rz) + p.y * Math.cos(rz));
        float x1 = (float)(xz * Math.cos(ry) + p.z * Math.sin(ry));
        float z1 = (float)(-xz * Math.sin(ry) + p.z * Math.cos(ry));
        float y2 = (float)(yz * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float)(yz * Math.sin(rx) + z1 * Math.cos(rx));

        // Simple Perspective
        float f = 1400f / (1400f + z2);
        p.sx = cx + x1 * scale * f;
        p.sy = cy - y2 * scale * f;
        p.sz = z2;
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
                rotateY += (e.getX() - prevX) * 0.5f;
                rotateX -= (e.getY() - prevY) * 0.5f;
                prevX = e.getX(); prevY = e.getY();
                invalidate();
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