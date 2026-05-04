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
import java.util.Arrays;
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
        linePaint.setStrokeWidth(1.5f);
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

    // Input Methods with History
    public void addPoint(String l, float x, float y, float z) { saveToHistory(); points.add(new Point3D(l, x, y, z)); invalidate(); }
    public void addLine(String a, String b) { saveToHistory(); lines.add(new Line3D(a, b)); invalidate(); }
    public void addPlane(List<String> labels) { saveToHistory(); planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { saveToHistory(); circles.add(new Circle3D(l, x, y, z, r)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) { saveToHistory(); spheres.add(new Sphere3D(l, x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h, float curvature) {
        saveToHistory();
        cones.add(new Cone3D(l, x, y, z, r, h, curvature));
        invalidate();
    }
    public void addCylinder(String l, float x, float y, float z, float r, float h) { saveToHistory(); cylinders.add(new Cylinder3D(l, x, y, z, r, h)); invalidate(); }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); cylinders.clear(); circles.clear(); spheres.clear();
        scaleFactor = 1.0f; rotateX = -25f; rotateY = 45f; rotateZ = 0f;
        historyStack.clear();
        invalidate();
    }

    public void saveToHistory() {
        historyStack.push(serializeState());
    }

    public void undo() {
        if (historyStack.size() > 0) {
            restoreState(historyStack.pop());
            invalidate();
        }
    }

    private String serializeState() {
        return points.size() + "|" + lines.size() + "|" + planes.size() + "|" +
                cones.size() + "|" + cylinders.size() + "|" + circles.size() + "|" + spheres.size();
    }

    private void restoreState(String state) {
        try {
            String[] parts = state.split("\\|");
            if (points.size() > Integer.parseInt(parts[0])) points.subList(Integer.parseInt(parts[0]), points.size()).clear();
            if (lines.size() > Integer.parseInt(parts[1])) lines.subList(Integer.parseInt(parts[1]), lines.size()).clear();
            if (planes.size() > Integer.parseInt(parts[2])) planes.subList(Integer.parseInt(parts[2]), planes.size()).clear();
            if (cones.size() > Integer.parseInt(parts[3])) cones.subList(Integer.parseInt(parts[3]), cones.size()).clear();
            if (cylinders.size() > Integer.parseInt(parts[4])) cylinders.subList(Integer.parseInt(parts[4]), cylinders.size()).clear();
            if (circles.size() > Integer.parseInt(parts[5])) circles.subList(Integer.parseInt(parts[5]), circles.size()).clear();
            if (spheres.size() > Integer.parseInt(parts[6])) spheres.subList(Integer.parseInt(parts[6]), spheres.size()).clear();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public int getZoomPercentage() { return (int) (scaleFactor * 100); }
    public void rotateX(float d) { rotateX += d; invalidate(); }
    public void rotateY(float d) { rotateY += d; invalidate(); }
    public void rotateZ(float d) { rotateZ += d; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);

        float drawCX = getWidth()/2f, drawCY = getHeight()/2f;
        float baseScale = (Math.min(getWidth(), getHeight()) / 600f) * scaleFactor;

        double rx = Math.toRadians(rotateX);
        double ry = Math.toRadians(rotateY);
        double rz = Math.toRadians(rotateZ);

        // Pre-project points
        for (Point3D p : points) project(p, rx, ry, rz, drawCX, drawCY, baseScale);

        // 1. Draw Planes
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

        // 3. Draw Cones (Apex connected to Base Points)
        for (Cone3D c : cones) {
            int slices = 40; // Number of points around the circle

            // A. Calculate the Apex (The Tip)
            // Assuming base is at cy, apex is at cy - height
            Point3D apex = new Point3D(null, c.cx, c.cy - c.h, c.cz);
            project(apex, rx, ry, rz, drawCX, drawCY, baseScale);

            // B. Calculate all points on the Base Circle
            Point3D[] basePoints = new Point3D[slices];
            for (int i = 0; i < slices; i++) {
                double angle = 2 * Math.PI * i / slices;
                float px = c.cx + (float) Math.cos(angle) * c.r;
                float pz = c.cz + (float) Math.sin(angle) * c.r;
                basePoints[i] = new Point3D(null, px, c.cy, pz);
                project(basePoints[i], rx, ry, rz, drawCX, drawCY, baseScale);
            }

            // C. Draw the side faces (Triangles from Apex to Base segments)
            for (int i = 0; i < slices; i++) {
                Point3D p1 = basePoints[i];
                Point3D p2 = basePoints[(i + 1) % slices]; // Next point (loops back to start)

                Path sideFace = new Path();
                sideFace.moveTo(apex.sx, apex.sy);   // Start at tip
                sideFace.lineTo(p1.sx, p1.sy);       // Go to circle point 1
                sideFace.lineTo(p2.sx, p2.sy);       // Go to circle point 2
                sideFace.close();                    // Back to tip

                // SHADING: Based on the angle to the light source
                double faceAngle = 2 * Math.PI * i / slices;
                float light = (float) ((Math.cos(faceAngle + ry) + 1.0) / 2.0);

                planePaint.setStyle(Paint.Style.FILL);
                planePaint.setColor(Color.rgb((int)(50 + 80 * light), (int)(100 + 100 * light), (int)(200 + 55 * light)));
                canvas.drawPath(sideFace, planePaint);

                // Draw thin lines on edges for clarity
                canvas.drawPath(sideFace, linePaint);
            }

            // D. Draw the Base Circle (Bottom cap)
            Path bottomCap = new Path();
            bottomCap.moveTo(basePoints[0].sx, basePoints[0].sy);
            for (int i = 1; i < slices; i++) {
                bottomCap.lineTo(basePoints[i].sx, basePoints[i].sy);
            }
            bottomCap.close();

            planePaint.setColor(Color.LTGRAY);
            planePaint.setAlpha(150);
            canvas.drawPath(bottomCap, planePaint);
        }

        // 4. Draw Cylinders
        for (Cylinder3D cy : cylinders) {
            int segs = 32;
            for (int i = 0; i < segs; i++) {
                double a1 = 2 * Math.PI * i / segs, a2 = 2 * Math.PI * (i + 1) / segs;
                Point3D p1 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy, cy.cz+(float)Math.sin(a1)*cy.r);
                Point3D p2 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p3 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy-cy.h, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p4 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy-cy.h, cy.cz+(float)Math.sin(a1)*cy.r);
                project(p1, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p2, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p3, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p4, rx, ry, rz, drawCX, drawCY, baseScale);

                Path wall = new Path();
                wall.moveTo(p1.sx, p1.sy); wall.lineTo(p2.sx, p2.sy); wall.lineTo(p3.sx, p3.sy); wall.lineTo(p4.sx, p4.sy); wall.close();
                float br = (float)((Math.cos(a1 + ry) + 1.0) / 2.0);
                planePaint.setColor(Color.rgb((int)(100*br), (int)(160*br), (int)(230*br)));
                canvas.drawPath(wall, planePaint);
            }
        }

        // 5. Lines, Points, Spheres
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
        Path path = new Path();
        int segs = 40;
        for (int i = 0; i <= segs; i++) {
            double a = 2 * Math.PI * i / segs;
            Point3D pt = new Point3D(null, cx + (float)Math.cos(a)*r, cy, cz + (float)Math.sin(a)*r);
            project(pt, rx, ry, rz, dcx, dcy, scale);
            if (i == 0) path.moveTo(pt.sx, pt.sy); else path.lineTo(pt.sx, pt.sy);
        }
        path.close();
        return path;
    }

    private void project(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        // Rotation logic
        float x1 = (float) (p.x * Math.cos(ry) + p.z * Math.sin(ry));
        float z1 = (float) (-p.x * Math.sin(ry) + p.z * Math.cos(ry));

        // THE CRITICAL PART: y1 is vertical screen position
        // If y1 is calculated incorrectly, the cone tip (Y=200) will be off-screen.
        float y1 = (float) (p.y * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float) (p.y * Math.sin(rx) + z1 * Math.cos(rx));

        float f = 1200f / (1200f + z2);
        p.sx = cx + x1 * scale * f;
        p.sy = cy + y1 * scale * f; // In Android, +Y goes DOWN.
        p.sz = z2;
    }

    private Point3D findPt(String label) {
        for (Point3D p : points) if (label.equals(p.label)) return p;
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

    public void zoomIn() { scaleFactor *= 1.2f; invalidate(); notifyZoom(); }
    public void zoomOut() { scaleFactor /= 1.2f; invalidate(); notifyZoom(); }
    private void notifyZoom() { if(zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage()); }
}