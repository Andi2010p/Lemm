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
    private Paint linePaint, textPaint, pointPaint, planePaint;
    private List<Point3D> points = new ArrayList<>();
    private List<Sphere3D> spheres = new ArrayList<>();
    private List<Plane3D> planes = new ArrayList<>();

    // Rotation and Offset variables
    private float rotateX = -25f, rotateY = 45f;
    private float previousX, previousY;
    private float offsetX = 0, offsetY = 0;
    private float scaleFactor = 1.0f;

    private ScaleGestureDetector scaleDetector;
    private boolean isPenUp = false;

    // Interface for Activity communication
    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }
    private OnZoomChangeListener zoomChangeListener;

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    // --- Data Models ---
    private static class Point3D {
        String label;
        float x, y, z;
        boolean isVertex;
        float sx, sy, sz; // Projected 2D coordinates
        boolean breakBefore = false;

        Point3D(String l, float x, float y, float z, boolean breakPath) {
            this.label = l;
            this.x = x;
            this.y = y;
            this.z = z;
            // If label is just a number, it's a coordinate helper, not a vertex
            this.isVertex = !l.trim().matches("^-?\\d*(\\.\\d+)?$");
            this.breakBefore = breakPath;
        }
    }

    private static class Sphere3D {
        String label;
        float x, y, z, radius;

        Sphere3D(String l, float x, float y, float z, float r) {
            this.label = l;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = r;
        }
    }

    private static class Plane3D {
        List<Integer> pointIndices;
        Plane3D(List<Integer> indices) {
            this.pointIndices = indices;
        }
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

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.parseColor("#1A237E"));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#C62828"));
        textPaint.setTextSize(32f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planePaint.setColor(Color.parseColor("#4D03A9F4")); // Semi-transparent blue
        planePaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.2f, Math.min(scaleFactor, 5.0f));
                notifyZoom();
                invalidate();
                return true;
            }
        });
    }

    private void notifyZoom() {
        if (zoomChangeListener != null) {
            zoomChangeListener.onZoomChanged(getZoomPercentage());
        }
    }

    // --- API Methods ---
    public void penUp() { isPenUp = true; }
    public void addPoint(String label, float x, float y, float z) {
        points.add(new Point3D(label, x, y, z, isPenUp));
        isPenUp = false;
        invalidate();
    }

    public void addSphere(String label, float x, float y, float z, float radius) {
        spheres.add(new Sphere3D(label, x, y, z, radius));
        invalidate();
    }

    public void addPlane(List<Integer> indices) {
        planes.add(new Plane3D(indices));
        invalidate();
    }

    public void clear() {
        points.clear(); spheres.clear(); planes.clear();
        rotateX = -25f; rotateY = 45f; scaleFactor = 1.0f;
        offsetX = 0; offsetY = 0;
        notifyZoom();
        invalidate();
    }

    public void zoomIn() { 
        scaleFactor *= 1.1f; 
        scaleFactor = Math.min(scaleFactor, 5.0f);
        notifyZoom();
        invalidate(); 
    }
    
    public void zoomOut() { 
        scaleFactor /= 1.1f; 
        scaleFactor = Math.max(scaleFactor, 0.2f);
        notifyZoom();
        invalidate(); 
    }
    
    public int getZoomPercentage() { return Math.round(scaleFactor * 100); }

    // --- Touch Logic ---
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                previousX = x;
                previousY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dx = x - previousX;
                    float dy = y - previousY;

                    if (event.getPointerCount() == 1) {
                        // Rotation: Horizontal drag = Y-axis spin, Vertical = X-axis tilt
                        rotateY += dx * 0.6f;
                        rotateX -= dy * 0.6f;
                    } else if (event.getPointerCount() == 2) {
                        // Pan: Move the whole object
                        offsetX += dx;
                        offsetY += dy;
                    }
                    invalidate();
                }
                previousX = x;
                previousY = y;
                break;
        }
        return true;
    }

    // --- Drawing Engine ---
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty() && spheres.isEmpty()) return;

        float cx = getWidth() / 2f + offsetX;
        float cy = getHeight() / 2f + offsetY;

        // Base scale to fit standard coordinates (0-500) into screen view
        float baseScale = Math.min(getWidth(), getHeight()) / 650f * scaleFactor;

        double radX = Math.toRadians(rotateX);
        double radY = Math.toRadians(rotateY);

        // 1. PROJECT POINTS & SPHERES
        // Subtract 250 to center the rotation on the middle of the AI's 500x500 space
        float worldCenter = 250f;

        for (Point3D p : points) {
            float tx = p.x - worldCenter;
            float ty = p.y - worldCenter;
            float tz = p.z - worldCenter;

            // Rotate Y (Horizontal)
            float x1 = (float) (tx * Math.cos(radY) + tz * Math.sin(radY));
            float z1 = (float) (-tx * Math.sin(radY) + tz * Math.cos(radY));
            // Rotate X (Vertical)
            float y2 = (float) (ty * Math.cos(radX) - z1 * Math.sin(radX));
            float z2 = (float) (ty * Math.sin(radX) + z1 * Math.cos(radX));

            // Perspective factor
            float perspective = 1500f;
            float factor = perspective / (perspective + z2);

            p.sx = cx + (x1 * baseScale * factor);
            p.sy = cy - (y2 * baseScale * factor); // Subtract Y because Android Y grows downwards
            p.sz = z2;
        }

        // 2. DRAW PLANES (Faces)
        for (Plane3D plane : planes) {
            Path path = new Path();
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
        }

        // 3. DRAW SKELETON (Edges)
        Path skeletonPath = new Path();
        boolean startNew = true;
        for (Point3D p : points) {
            if (p.breakBefore || startNew) {
                skeletonPath.moveTo(p.sx, p.sy);
                startNew = false;
            } else {
                skeletonPath.lineTo(p.sx, p.sy);
            }
        }
        canvas.drawPath(skeletonPath, linePaint);

        // 4. DRAW LABELS AND DOTS
        for (Point3D p : points) {
            if (p.isVertex) {
                // Dim dots that are further away (Z-buffering feel)
                int alpha = (int) Math.max(80, 255 - (p.sz / 5));
                pointPaint.setAlpha(alpha);

                canvas.drawCircle(p.sx, p.sy, 8f, pointPaint);
                canvas.drawText(p.label, p.sx + 15, p.sy - 15, textPaint);
            }
        }
    }

}
