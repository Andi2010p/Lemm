package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class GeometryCanvas3D extends View {
    private Paint linePaint, dashedPaint, textPaint, pointPaint, planePaint, gizmoPaint, circlePaint;
    private List<Point3D> points = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();
    private List<Plane3D> planes = new ArrayList<>();
    private List<Line3D> lines = new ArrayList<>();
    private List<Circle3D> circles = new ArrayList<>();

    private float rotateX = -25f, rotateY = 45f, rotateZ = 0f;
    private float previousX, previousY;
    private float offsetX = 0, offsetY = 0;
    private float scaleFactor = 1.0f;

    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 15.0f;

    private ScaleGestureDetector scaleDetector;

    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }
    private OnZoomChangeListener zoomChangeListener;

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public static class Point3D {
        public String label;
        public float x, y, z;
        boolean isVertex;
        float sx, sy, sz;

        Point3D(String l, float x, float y, float z) {
            this.label = l;
            this.x = x;
            this.y = y;
            this.z = z;
            this.isVertex = l != null && !l.trim().matches("^-?\\d*(\\.\\d+)?$");
        }
    }

    private static class Sphere3D {
        String label;
        float x, y, z, radius;
        Sphere3D(String l, float x, float y, float z, float r) {
            this.label = l; this.x = x; this.y = y; this.z = z; this.radius = r;
        }
    }

    private static class Circle3D {
        String centerLabel;
        float radius, nx, ny, nz;
        Circle3D(String centerLabel, float radius, float nx, float ny, float nz) {
            this.centerLabel = centerLabel;
            this.radius = radius;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len == 0) len = 1;
            this.nx = nx / len;
            this.ny = ny / len;
            this.nz = nz / len;
        }
    }

    private static class Plane3D {
        List<Integer> pointIndices;
        Plane3D(List<Integer> indices) { this.pointIndices = indices; }
    }

    private static class Line3D {
        String a, b;
        boolean isDashed;
        Line3D(String a, String b, boolean isDashed) { this.a = a; this.b = b; this.isDashed = isDashed; }
    }

    public GeometryCanvas3D(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#1A237E"));
        linePaint.setStrokeWidth(5f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dashedPaint.setColor(Color.parseColor("#881A237E"));
        dashedPaint.setStrokeWidth(3f);
        dashedPaint.setStyle(Paint.Style.STROKE);
        dashedPaint.setPathEffect(new DashPathEffect(new float[]{15, 10}, 0));

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.parseColor("#1A237E"));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#C62828"));
        textPaint.setTextSize(32f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planePaint.setStyle(Paint.Style.FILL);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.parseColor("#882196F3"));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(4f);

        gizmoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gizmoPaint.setStrokeWidth(6f);
        gizmoPaint.setTextSize(30f);
        gizmoPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(MIN_ZOOM, Math.min(scaleFactor, MAX_ZOOM));
                notifyZoom();
                invalidate();
                return true;
            }
        });
    }

    private void notifyZoom() {
        if (zoomChangeListener != null) zoomChangeListener.onZoomChanged(getZoomPercentage());
    }

    public void addPoint(String label, float x, float y, float z) {
        points.add(new Point3D(label, x, y, z));
        invalidate();
    }

    public void addSphere(String label, float x, float y, float z, float radius) {
        spheres.add(new Sphere3D(label, x, y, z, radius));
        invalidate();
    }

    public void addCircle(String centerLabel, float radius, float nx, float ny, float nz) {
        circles.add(new Circle3D(centerLabel, radius, nx, ny, nz));
        invalidate();
    }

    public void addPlane(List<Integer> indices) {
        planes.add(new Plane3D(indices));
        invalidate();
    }

    public void addLine(String a, String b, boolean isDashed) {
        if (a == null || b == null) return;
        a = a.trim(); b = b.trim();
        for (Line3D l : lines) {
            if ((l.a.equalsIgnoreCase(a) && l.b.equalsIgnoreCase(b)) ||
                    (l.a.equalsIgnoreCase(b) && l.b.equalsIgnoreCase(a))) {
                l.isDashed = isDashed;
                invalidate();
                return;
            }
        }
        lines.add(new Line3D(a, b, isDashed));
        invalidate();
    }

    public void clear() {
        points.clear(); spheres.clear(); planes.clear(); lines.clear(); circles.clear();
        rotateX = -25f; rotateY = 45f; rotateZ = 0f;
        scaleFactor = 1.0f; offsetX = 0; offsetY = 0;
        notifyZoom();
        invalidate();
    }

    public void zoomIn()  { scaleFactor = Math.min(scaleFactor * 1.1f, MAX_ZOOM); notifyZoom(); invalidate(); }
    public void zoomOut() { scaleFactor = Math.max(scaleFactor / 1.1f, MIN_ZOOM); notifyZoom(); invalidate(); }
    public void rotateX(float delta) { rotateX += delta; invalidate(); }
    public void rotateY(float delta) { rotateY += delta; invalidate(); }
    public void rotateZ(float delta) { rotateZ += delta; invalidate(); }

    public int getZoomPercentage() { return Math.round(scaleFactor * 100); }
    public List<Point3D> getPoints() { return points; }

    public Bitmap getBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        draw(canvas);
        return bitmap;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                previousX = x; previousY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dx = x - previousX;
                    float dy = y - previousY;
                    if (event.getPointerCount() == 1) {
                        rotateY += dx * 0.5f;
                        rotateX -= dy * 0.5f;
                    } else if (event.getPointerCount() == 2) {
                        offsetX += dx;
                        offsetY += dy;
                    }
                    invalidate();
                }
                previousX = x; previousY = y;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    public Point3D findPoint(String label) {
        for (Point3D p : points) { if (p.label.equalsIgnoreCase(label)) return p; }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);

        if (points.isEmpty() && spheres.isEmpty()) {
            drawGizmo(canvas);
            return;
        }

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (Point3D p : points) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }

        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float centerZ = (minZ + maxZ) / 2f;
        float maxSize = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (maxSize == 0) maxSize = 1000f;

        float cx = getWidth() / 2f + offsetX;
        float cy = getHeight() / 2f + offsetY;
        float baseScale = Math.min(getWidth(), getHeight()) / (maxSize + 250f) * scaleFactor;

        double radX = Math.toRadians(rotateX);
        double radY = Math.toRadians(rotateY);
        double radZ = Math.toRadians(rotateZ);

        drawGizmo(canvas);

        for (Point3D p : points) {
            project(p, centerX, centerY, centerZ, radX, radY, radZ, cx, cy, baseScale);
        }

        // Shaded Planes
        for (Plane3D plane : planes) {
            if (plane.pointIndices.size() < 3) continue;
            Path path = new Path();
            Point3D p1 = points.get(plane.pointIndices.get(0));
            Point3D p2 = points.get(plane.pointIndices.get(1));
            Point3D p3 = points.get(plane.pointIndices.get(2));

            // Flat shading normal calculation
            float[] v1 = {p2.x - p1.x, p2.y - p1.y, p2.z - p1.z};
            float[] v2 = {p3.x - p1.x, p3.y - p1.y, p3.z - p1.z};
            float[] n = cross(v1, v2);
            float len = (float) Math.sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2]);
            if (len > 0) { n[0]/=len; n[1]/=len; n[2]/=len; }

            // Rotated normal for lighting
            float[] rn = rotateVector(n[0], n[1], n[2], radX, radY, radZ);
            float light = Math.max(0.3f, Math.abs(rn[2])); // directional light from camera
            int r = (int) (30 * light);
            int g = (int) (144 * light);
            int b = (int) (255 * light);
            int color = Color.rgb((int)(33*light + 100), (int)(150*light + 50), (int)(243*light + 10));
            planePaint.setColor(Color.rgb(r, g, b));
            planePaint.setAlpha(160); // Adjust transparency (0-255)

            boolean first = true;
            for (int idx : plane.pointIndices) {
                if (idx >= 0 && idx < points.size()) {
                    Point3D pt = points.get(idx);
                    if (first) { path.moveTo(pt.sx, pt.sy); first = false; }
                    else path.lineTo(pt.sx, pt.sy);
                }
            }
            path.close();
            canvas.drawPath(path, planePaint);
            canvas.drawPath(path, linePaint);
        }

        for (Line3D line : lines) {
            Point3D p1 = findPoint(line.a);
            Point3D p2 = findPoint(line.b);
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.sx, p1.sy, p2.sx, p2.sy, line.isDashed ? dashedPaint : linePaint);
            }
        }

        for (Circle3D circle : circles) {
            Point3D center = findPoint(circle.centerLabel);
            if (center != null) drawCircle3D(canvas, center, circle, centerX, centerY, centerZ, radX, radY, radZ, cx, cy, baseScale);
        }

        for (Point3D p : points) {
            if (p.isVertex) {
                canvas.drawCircle(p.sx, p.sy, 8f, pointPaint);
                if (p.label != null && !p.label.isEmpty()) canvas.drawText(p.label, p.sx + 15, p.sy - 15, textPaint);
            }
        }

        for (Sphere3D s : spheres) {
            Point3D pTemp = new Point3D(s.label, s.x, s.y, s.z);
            project(pTemp, centerX, centerY, centerZ, radX, radY, radZ, cx, cy, baseScale);
            float sRadius = s.radius * baseScale * (2000f / (2000f + pTemp.sz));
            canvas.drawCircle(pTemp.sx, pTemp.sy, sRadius, linePaint);
            canvas.drawOval(new RectF(pTemp.sx - sRadius, pTemp.sy - sRadius / 3.5f, pTemp.sx + sRadius, pTemp.sy + sRadius / 3.5f), linePaint);
            if (s.label != null && !s.label.isEmpty()) canvas.drawText(s.label, pTemp.sx + sRadius + 10, pTemp.sy, textPaint);
        }
    }

    private void drawCircle3D(Canvas canvas, Point3D center, Circle3D circle, float cx3d, float cy3d, float cz3d, double rx, double ry, double rz, float cx, float cy, float scale) {
        float[] n = {circle.nx, circle.ny, circle.nz};
        float[] u = perpendicular(n);
        float[] v = cross(n, u);
        int segments = 64;
        Path path = new Path();
        for (int i = 0; i <= segments; i++) {
            double a = 2 * Math.PI * i / segments;
            float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            float px = center.x + circle.radius * (cosA * u[0] + sinA * v[0]);
            float py = center.y + circle.radius * (cosA * u[1] + sinA * v[1]);
            float pz = center.z + circle.radius * (cosA * u[2] + sinA * v[2]);
            Point3D temp = new Point3D(null, px, py, pz);
            project(temp, cx3d, cy3d, cz3d, rx, ry, rz, cx, cy, scale);
            if (i == 0) path.moveTo(temp.sx, temp.sy); else path.lineTo(temp.sx, temp.sy);
        }
        path.close();
        canvas.drawPath(path, circlePaint);
    }

    private float[] perpendicular(float[] n) {
        float[] u = Math.abs(n[0]) < 0.9f ? cross(n, new float[]{1, 0, 0}) : cross(n, new float[]{0, 1, 0});
        float len = (float) Math.sqrt(u[0]*u[0] + u[1]*u[1] + u[2]*u[2]);
        return new float[]{u[0]/len, u[1]/len, u[2]/len};
    }

    private float[] cross(float[] a, float[] b) {
        return new float[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]};
    }

    private void project(Point3D p, float cx3d, float cy3d, float cz3d, double rx, double ry, double rz, float cx, float cy, float scale) {
        float tx = p.x - cx3d, ty = p.y - cy3d, tz = p.z - cz3d;
        float x1 = (float) (tx * Math.cos(ry) + tz * Math.sin(ry));
        float z1 = (float) (-tx * Math.sin(ry) + tz * Math.cos(ry));
        float y2 = (float) (ty * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float) (ty * Math.sin(rx) + z1 * Math.cos(rx));
        float x3 = (float) (x1 * Math.cos(rz) - y2 * Math.sin(rz));
        float y3 = (float) (x1 * Math.sin(rz) + y2 * Math.cos(rz));
        float f = 2000f / (2000f + z2);
        p.sx = cx + (x3 * scale * f); p.sy = cy - (y3 * scale * f); p.sz = z2;
    }

    private float[] rotateVector(float x, float y, float z, double rx, double ry, double rz) {
        float x1 = (float) (x * Math.cos(ry) + z * Math.sin(ry));
        float z1 = (float) (-x * Math.sin(ry) + z * Math.cos(ry));
        float y2 = (float) (y * Math.cos(rx) - z1 * Math.sin(rx));
        float z2 = (float) (y * Math.sin(rx) + z1 * Math.cos(rx));
        float x3 = (float) (x1 * Math.cos(rz) - y2 * Math.sin(rz));
        float y3 = (float) (x1 * Math.sin(rz) + y2 * Math.cos(rz));
        return new float[]{x3, y3, z2};
    }

    private void drawGizmo(Canvas canvas) {
        float size = 40f, gx = 120f, gy = 300f;
        double rx = Math.toRadians(rotateX), ry = Math.toRadians(rotateY), rz = Math.toRadians(rotateZ);
        gizmoPaint.setStrokeWidth(2f); gizmoPaint.setColor(Color.LTGRAY);
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        float[][] v = {{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
        for (int[] e : edges) {
            float[] p1 = rotateGizmo(v[e[0]][0]*size, v[e[0]][1]*size, v[e[0]][2]*size, rx, ry, rz);
            float[] p2 = rotateGizmo(v[e[1]][0]*size, v[e[1]][1]*size, v[e[1]][2]*size, rx, ry, rz);
            canvas.drawLine(gx+p1[0], gy-p1[1], gx+p2[0], gy-p2[1], gizmoPaint);
        }
        drawAxis(canvas, gx, gy, size*1.8f, 0, 0, rx, ry, rz, Color.RED, "X");
        drawAxis(canvas, gx, gy, 0, size*1.8f, 0, rx, ry, rz, Color.GREEN, "Y");
        drawAxis(canvas, gx, gy, 0, 0, size*1.8f, rx, ry, rz, Color.BLUE, "Z");
    }

    private float[] rotateGizmo(float x, float y, float z, double rx, double ry, double rz) {
        float[] r = rotateVector(x, y, z, rx, ry, rz); return new float[]{r[0], r[1]};
    }

    private void drawAxis(Canvas canvas, float gx, float gy, float x, float y, float z, double rx, double ry, double rz, int color, String label) {
        float[] p = rotateGizmo(x, y, z, rx, ry, rz);
        gizmoPaint.setColor(color); gizmoPaint.setStrokeWidth(5f);
        canvas.drawLine(gx, gy, gx + p[0], gy - p[1], gizmoPaint);
        gizmoPaint.setTextSize(24f); canvas.drawText(label, gx + p[0] + 5, gy - p[1], gizmoPaint);
    }
}
