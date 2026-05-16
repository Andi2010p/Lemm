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

    private List<Point3D> points = new ArrayList<>();
    private List<Line3D> lines = new ArrayList<>();
    private List<Plane3D> planes = new ArrayList<>();
    private List<Cone3D> cones = new ArrayList<>();
    private List<Pyramid3D> pyramids = new ArrayList<>();
    private List<Cylinder3D> cylinders = new ArrayList<>();
    private List<Circle3D> circles = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();
    private java.util.Stack<String> historyStack = new java.util.Stack<>();

    private float rotateX = -25f, rotateY = 45f, rotateZ = 0f;
    private float scaleFactor = 1.0f;
    private float translateX = 0f, translateY = 0f;
    private boolean isMoveMode = false;

    // Stabilizers
    private float lastTouchX, lastTouchY;
    private int activePointerId = -1;
    private ScaleGestureDetector scaleDetector;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    public static class Point3D {
        public String label; float x, y, z, sx, sy, sz; boolean isVertex;
        Point3D(String l, float x, float y, float z) { this.label = l; this.x = x; this.y = y; this.z = z; this.isVertex = l != null && !l.isEmpty(); }
    }
    private static class Line3D { String a, b; Line3D(String a, String b) { this.a = a; this.b = b; } }
    private static class Plane3D { List<String> labels; Plane3D(List<String> l) { this.labels = l; } }
    private static class Cone3D {
        String label; float cx, cy, cz, r, h, curvature;
        Cone3D(String l, float x, float y, float z, float r, float h, float curvature) {
            this.label = l; this.cx = x; this.cy = y; this.cz = z; this.r = r; this.h = h; this.curvature = Math.max(0.1f, curvature);
        }
    }
    private static class Pyramid3D {
        String label; float cx, cy, cz, w, d, h;
        Pyramid3D(String l, float x, float y, float z, float w, float d, float h) {
            this.label = l; this.cx = x; this.cy = y; this.cz = z; this.w = w; this.d = d; this.h = h;
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

    public GeometryCanvas3D(Context context, AttributeSet attrs) { super(context, attrs); init(context); }

    private void init(Context context) {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(Color.BLACK); linePaint.setStrokeWidth(1.2f); linePaint.setStyle(Paint.Style.STROKE);
        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG); pointPaint.setColor(Color.RED);
        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG); planePaint.setStyle(Paint.Style.FILL);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setTextSize(26f); textPaint.setColor(Color.parseColor("#0C3D6A")); textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        xAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG); xAxisPaint.setColor(Color.RED); xAxisPaint.setStrokeWidth(5f);
        yAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG); yAxisPaint.setColor(Color.GREEN); yAxisPaint.setStrokeWidth(5f);
        zAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG); zAxisPaint.setColor(Color.BLUE); zAxisPaint.setStrokeWidth(5f);
        axesTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG); axesTextPaint.setTextSize(30f); axesTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float oldScale = scaleFactor;
                scaleFactor *= d.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f));

                // Keep scaling focused on the pinch center
                float ratio = scaleFactor / oldScale;
                float focusX = d.getFocusX();
                float focusY = d.getFocusY();
                translateX = focusX - (focusX - translateX) * ratio;
                translateY = focusY - (focusY - translateY) * ratio;

                if (zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage());
                invalidate(); return true;
            }
        });
    }

    public void setMoveMode(boolean move) { this.isMoveMode = move; }
    public boolean isMoveMode() { return isMoveMode; }

    public void addPoint(String l, float x, float y, float z) { saveToHistory(); points.add(new Point3D(l, x, y, z)); invalidate(); }
    public void addLine(String a, String b) { saveToHistory(); lines.add(new Line3D(a, b)); invalidate(); }
    public void addPlane(List<String> labels) { saveToHistory(); planes.add(new Plane3D(labels)); invalidate(); }
    public void addCircle(String l, float x, float y, float z, float r) { saveToHistory(); circles.add(new Circle3D(l, x, y, z, r)); invalidate(); }
    public void addSphere(String l, float x, float y, float z, float r) { saveToHistory(); spheres.add(new Sphere3D(l, x, y, z, r)); invalidate(); }
    public void addCone(String l, float x, float y, float z, float r, float h, float curvature) { saveToHistory(); cones.add(new Cone3D(l, x, y, z, r, h, curvature)); invalidate(); }
    public void addPyramid(String l, float x, float y, float z, float w, float d, float h) { saveToHistory(); pyramids.add(new Pyramid3D(l, x, y, z, w, d, h)); invalidate(); }
    public void addCylinder(String l, float x, float y, float z, float r, float h) { saveToHistory(); cylinders.add(new Cylinder3D(l, x, y, z, r, h)); invalidate(); }

    public void clear() {
        points.clear(); lines.clear(); planes.clear(); cones.clear(); pyramids.clear(); cylinders.clear(); circles.clear(); spheres.clear();
        scaleFactor = 1.0f; rotateX = -25f; rotateY = 45f; rotateZ = 0f; translateX = 0f; translateY = 0f;
        historyStack.clear(); invalidate();
    }

    public void saveToHistory() { historyStack.push(serializeState()); }
    public void undo() { if (historyStack.size() > 0) { restoreState(historyStack.pop()); invalidate(); } }

    private String serializeState() {
        return points.size() + "|" + lines.size() + "|" + planes.size() + "|" +
                cones.size() + "|" + cylinders.size() + "|" + circles.size() + "|" + spheres.size() + "|" + pyramids.size();
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
            if (pyramids.size() > Integer.parseInt(parts[7])) pyramids.subList(Integer.parseInt(parts[7]), pyramids.size()).clear();
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
        float drawCX = getWidth()/2f + translateX, drawCY = getHeight()/2f + translateY;
        float baseScale = (Math.min(getWidth(), getHeight()) / 600f) * scaleFactor;
        double rx = Math.toRadians(rotateX), ry = Math.toRadians(rotateY), rz = Math.toRadians(rotateZ);

        drawAxesCube(canvas, rx, ry, rz);
        for (Point3D p : points) project(p, rx, ry, rz, drawCX, drawCY, baseScale);
        for (Plane3D pl : planes) {
            Path path = new Path(); boolean first = true;
            for (String label : pl.labels) {
                Point3D pt = findPt(label);
                if (pt != null) { if (first) { path.moveTo(pt.sx, pt.sy); first = false; } else path.lineTo(pt.sx, pt.sy); }
            }
            if (!first) { path.close(); planePaint.setColor(Color.argb(140, 180, 220, 255)); canvas.drawPath(path, planePaint); canvas.drawPath(path, linePaint); }
        }
        for (Circle3D c : circles) {
            Path p = drawCirclePath(c.cx, c.cy, c.cz, c.r, rx, ry, rz, drawCX, drawCY, baseScale);
            planePaint.setColor(Color.argb(120, 135, 206, 250)); canvas.drawPath(p, planePaint); canvas.drawPath(p, linePaint);
        }
        for (Cone3D c : cones) {
            int slices = 48, stacks = 12;
            for (int i = 0; i < slices; i++) {
                double a1 = 2 * Math.PI * i / slices, a2 = 2 * Math.PI * (i + 1) / slices;
                for (int j = 0; j < stacks; j++) {
                    float h1 = (float) j / stacks, h2 = (float) (j + 1) / stacks;
                    float r1 = c.r * (float) Math.pow(1.0 - h1, c.curvature), r2 = c.r * (float) Math.pow(1.0 - h2, c.curvature);
                    float y1 = c.cy + c.h * h1, y2 = c.cy + c.h * h2;
                    Point3D p1 = new Point3D(null, c.cx + (float)Math.cos(a1)*r1, y1, c.cz + (float)Math.sin(a1)*r1);
                    Point3D p2 = new Point3D(null, c.cx + (float)Math.cos(a2)*r1, y1, c.cz + (float)Math.sin(a2)*r1);
                    Point3D p3 = new Point3D(null, c.cx + (float)Math.cos(a2)*r2, y2, c.cz + (float)Math.sin(a2)*r2);
                    Point3D p4 = new Point3D(null, c.cx + (float)Math.cos(a1)*r2, y2, c.cz + (float)Math.sin(a1)*r2);
                    project(p1, rx, ry, rz, drawCX, drawCY, baseScale); project(p2, rx, ry, rz, drawCX, drawCY, baseScale);
                    project(p3, rx, ry, rz, drawCX, drawCY, baseScale); project(p4, rx, ry, rz, drawCX, drawCY, baseScale);
                    Path face = new Path(); face.moveTo(p1.sx, p1.sy); face.lineTo(p2.sx, p2.sy); face.lineTo(p3.sx, p3.sy); face.lineTo(p4.sx, p4.sy); face.close();
                    float br = (float)((Math.cos(a1 + ry) + 1.0) / 2.0);
                    planePaint.setColor(Color.rgb((int)(80*br + 40), (int)(130*br + 60), (int)(220*br + 30)));
                    canvas.drawPath(face, planePaint);
                    if (j == stacks - 1 || i % 8 == 0) canvas.drawPath(face, linePaint);
                }
            }
            Path base = drawCirclePath(c.cx, c.cy, c.cz, c.r, rx, ry, rz, drawCX, drawCY, baseScale);
            planePaint.setColor(Color.argb(120, 50, 100, 200)); canvas.drawPath(base, planePaint); canvas.drawPath(base, linePaint);
        }
        for (Pyramid3D p : pyramids) {
            float hw = p.w / 2, hd = p.d / 2;
            Point3D apex = new Point3D(null, p.cx, p.cy + p.h, p.cz);
            Point3D b1 = new Point3D(null, p.cx - hw, p.cy, p.cz - hd); Point3D b2 = new Point3D(null, p.cx + hw, p.cy, p.cz - hd);
            Point3D b3 = new Point3D(null, p.cx + hw, p.cy, p.cz + hd); Point3D b4 = new Point3D(null, p.cx - hw, p.cy, p.cz + hd);
            project(apex, rx, ry, rz, drawCX, drawCY, baseScale); project(b1, rx, ry, rz, drawCX, drawCY, baseScale);
            project(b2, rx, ry, rz, drawCX, drawCY, baseScale); project(b3, rx, ry, rz, drawCX, drawCY, baseScale); project(b4, rx, ry, rz, drawCX, drawCY, baseScale);
            Point3D[][] faces = {{b1, b2, apex}, {b2, b3, apex}, {b3, b4, apex}, {b4, b1, apex}, {b1, b2, b3, b4}};
            for (int i = 0; i < faces.length; i++) {
                Path path = new Path(); path.moveTo(faces[i][0].sx, faces[i][0].sy);
                for (int k = 1; k < faces[i].length; k++) path.lineTo(faces[i][k].sx, faces[i][k].sy);
                path.close(); planePaint.setColor(Color.argb(160, 100 + i * 20, 150 + i * 10, 220));
                canvas.drawPath(path, planePaint); canvas.drawPath(path, linePaint);
            }
        }
        for (Cylinder3D cy : cylinders) {
            int segs = 32;
            for (int i = 0; i < segs; i++) {
                double a1 = 2 * Math.PI * i / segs, a2 = 2 * Math.PI * (i + 1) / segs;
                Point3D p1 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy, cy.cz+(float)Math.sin(a1)*cy.r);
                Point3D p2 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p3 = new Point3D(null, cy.cx+(float)Math.cos(a2)*cy.r, cy.cy+cy.h, cy.cz+(float)Math.sin(a2)*cy.r);
                Point3D p4 = new Point3D(null, cy.cx+(float)Math.cos(a1)*cy.r, cy.cy+cy.h, cy.cz+(float)Math.sin(a1)*cy.r);
                project(p1, rx, ry, rz, drawCX, drawCY, baseScale); project(p2, rx, ry, rz, drawCX, drawCY, baseScale);
                project(p3, rx, ry, rz, drawCX, drawCY, baseScale); project(p4, rx, ry, rz, drawCX, drawCY, baseScale);
                Path wall = new Path(); wall.moveTo(p1.sx, p1.sy); wall.lineTo(p2.sx, p2.sy); wall.lineTo(p3.sx, p3.sy); wall.lineTo(p4.sx, p4.sy); wall.close();
                float br = (float)((Math.cos(a1 + ry) + 1.0) / 2.0);
                planePaint.setColor(Color.rgb((int)(100*br), (int)(160*br), (int)(230*br))); canvas.drawPath(wall, planePaint);
            }
        }
        for (Line3D l : lines) {
            Point3D p1 = findPt(l.a), p2 = findPt(l.b);
            if (p1 != null && p2 != null) canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, linePaint);
        }
        for (Point3D p : points) if (p.isVertex) { canvas.drawCircle(p.sx, p.sy, 7, pointPaint); canvas.drawText(p.label, p.sx + 12, p.sy - 12, textPaint); }
        for (Sphere3D s : spheres) {
            Point3D cp = new Point3D(null, s.cx, s.cy, s.cz); project(cp, rx, ry, rz, drawCX, drawCY, baseScale);
            canvas.drawCircle(cp.sx, cp.sy, s.r * baseScale, linePaint);
        }
    }

    private void drawAxesCube(Canvas canvas, double rx, double ry, double rz) {
        float cubeCX = 100f, cubeCY = 100f, cubeScale = 60f;
        Point3D origin = new Point3D(null, 0, 0, 0); Point3D xAxis = new Point3D(null, 1, 0, 0);
        Point3D yAxis = new Point3D(null, 0, 1, 0); Point3D zAxis = new Point3D(null, 0, 0, 1);
        project(origin, rx, ry, rz, cubeCX, cubeCY, cubeScale); project(xAxis, rx, ry, rz, cubeCX, cubeCY, cubeScale);
        project(yAxis, rx, ry, rz, cubeCX, cubeCY, cubeScale); project(zAxis, rx, ry, rz, cubeCX, cubeCY, cubeScale);
        canvas.drawLine(origin.sx, origin.sy, xAxis.sx, xAxis.sy, xAxisPaint);
        canvas.drawLine(origin.sx, origin.sy, yAxis.sx, yAxis.sy, yAxisPaint);
        canvas.drawLine(origin.sx, origin.sy, zAxis.sx, zAxis.sy, zAxisPaint);
        axesTextPaint.setColor(Color.RED); canvas.drawText("X", xAxis.sx + 10, xAxis.sy + 10, axesTextPaint);
        axesTextPaint.setColor(Color.GREEN); canvas.drawText("Y", yAxis.sx + 10, yAxis.sy + 10, axesTextPaint);
        axesTextPaint.setColor(Color.BLUE); canvas.drawText("Z", zAxis.sx + 10, zAxis.sy + 10, axesTextPaint);
    }

    private Path drawCirclePath(float cx, float cy, float cz, float r, double rx, double ry, double rz, float dcx, float dcy, float scale) {
        Path path = new Path(); int segs = 48;
        for (int i = 0; i <= segs; i++) {
            double a = 2 * Math.PI * i / segs; Point3D pt = new Point3D(null, cx + (float)Math.cos(a)*r, cy, cz + (float)Math.sin(a)*r);
            project(pt, rx, ry, rz, dcx, dcy, scale); if (i == 0) path.moveTo(pt.sx, pt.sy); else path.lineTo(pt.sx, pt.sy);
        }
        path.close(); return path;
    }

    private void project(Point3D p, double rx, double ry, double rz, float cx, float cy, float scale) {
        float x = p.x, y = p.y, z = p.z;
        float xz = (float)(x * Math.cos(rz) - y * Math.sin(rz));
        float yz = (float)(x * Math.sin(rz) + y * Math.cos(rz));
        float x1 = (float)(xz * Math.cos(ry) + z * Math.sin(ry));
        float z1 = (float)(-xz * Math.sin(ry) + z * Math.cos(ry));
        float y2 = (float)(yz * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float)(yz * Math.sin(rx) + z1 * Math.cos(rx));
        float f = 1200f / (1200f + z2);
        p.sx = cx + x1 * scale * f; p.sy = cy - y2 * scale * f; p.sz = z2;
    }

    private Point3D findPt(String label) { for (Point3D p : points) if (label.equals(p.label)) return p; return null; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = e.getPointerId(0);
                lastTouchX = e.getX(); lastTouchY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && activePointerId != -1) {
                    int idx = e.findPointerIndex(activePointerId);
                    if (idx != -1) {
                        float dx = e.getX(idx) - lastTouchX;
                        float dy = e.getY(idx) - lastTouchY;
                        if (isMoveMode) { translateX += dx; translateY += dy; }
                        else { rotateY += dx * 0.5f; rotateX -= dy * 0.5f; }
                        lastTouchX = e.getX(idx); lastTouchY = e.getY(idx);
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int pIdx = (action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                if (e.getPointerId(pIdx) == activePointerId) {
                    int newIdx = pIdx == 0 ? 1 : 0;
                    lastTouchX = e.getX(newIdx); lastTouchY = e.getY(newIdx);
                    activePointerId = e.getPointerId(newIdx);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointerId = -1;
                break;
        }
        return true;
    }

    public void zoomIn() {
        float oldScale = scaleFactor;
        scaleFactor = Math.min(10.0f, scaleFactor * 1.2f);
        float ratio = scaleFactor / oldScale;
        translateX = getWidth()/2f - (getWidth()/2f - translateX) * ratio;
        translateY = getHeight()/2f - (getHeight()/2f - translateY) * ratio;
        invalidate(); notifyZoom();
    }
    public void zoomOut() {
        float oldScale = scaleFactor;
        scaleFactor = Math.max(0.1f, scaleFactor / 1.2f);
        float ratio = scaleFactor / oldScale;
        translateX = getWidth()/2f - (getWidth()/2f - translateX) * ratio;
        translateY = getHeight()/2f - (getHeight()/2f - translateY) * ratio;
        invalidate(); notifyZoom();
    }
    private void notifyZoom() { if(zoomListener != null) zoomListener.onZoomChanged(getZoomPercentage()); }
}