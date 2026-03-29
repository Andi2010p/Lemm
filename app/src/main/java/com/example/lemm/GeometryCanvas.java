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

public class GeometryCanvas extends View {
    private Paint linePaint, textPaint, pointPaint, valuePaint, planePaint;
    private List<GeoPoint> pointsList = new ArrayList<>();
    private List<GeoCircle> circlesList = new ArrayList<>();
    private List<GeoPlane> planesList = new ArrayList<>();

    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1.0f;
    private float posX = 0, posY = 0;
    private float lastTouchX, lastTouchY;
    private boolean shouldBreakPath = false;

    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }
    private OnZoomChangeListener zoomChangeListener;

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    private static class GeoPoint {
        String label;
        float x, y;
        boolean isVertex;
        boolean startsNewSegment;

        GeoPoint(String l, float x, float y, boolean breakPath) {
            this.label = l;
            this.x = x;
            this.y = y;
            this.startsNewSegment = breakPath;
            this.isVertex = !l.trim().matches("^-?\\d*(\\.\\d+)?$");
        }
    }

    private static class GeoCircle {
        String label;
        float cx, cy, radius;

        GeoCircle(String l, float cx, float cy, float r) {
            this.label = l;
            this.cx = cx;
            this.cy = cy;
            this.radius = r;
        }
    }

    private static class GeoPlane {
        List<PointF> vertices;
        GeoPlane(List<PointF> pts) {
            this.vertices = pts;
        }
    }

    public GeometryCanvas(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#0C3D6A"));
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.parseColor("#0C3D6A"));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#D32F2F"));
        textPaint.setTextSize(32f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setColor(Color.BLACK);
        valuePaint.setTextSize(30f);

        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planePaint.setColor(Color.parseColor("#80B3E5FC")); // Light Blue with transparency
        planePaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.2f, Math.min(scaleFactor, 8.0f));
                if (zoomChangeListener != null) {
                    zoomChangeListener.onZoomChanged(getZoomPercentage());
                }
                invalidate();
                return true;
            }
        });
    }

    public void addPoint(String name, float x, float y) {
        pointsList.add(new GeoPoint(name, x, y, shouldBreakPath));
        shouldBreakPath = false;
        invalidate();
    }

    public void addCircle(String label, float cx, float cy, float radius) {
        circlesList.add(new GeoCircle(label, cx, cy, radius));
        invalidate();
    }

    public void addPlane(List<PointF> pts) {
        planesList.add(new GeoPlane(pts));
        invalidate();
    }

    public void penUp() {
        shouldBreakPath = true;
    }

    public void clearPoints() {
        pointsList.clear();
        circlesList.clear();
        planesList.clear();
        shouldBreakPath = false;
        scaleFactor = 1.0f;
        posX = 0;
        posY = 0;
        invalidate();
    }

    public void zoomIn() {
        scaleFactor *= 1.2f;
        scaleFactor = Math.min(scaleFactor, 8.0f);
        invalidate();
    }

    public void zoomOut() {
        scaleFactor /= 1.2f;
        scaleFactor = Math.max(scaleFactor, 0.2f);
        invalidate();
    }

    public int getZoomPercentage() {
        return Math.round(scaleFactor * 100);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    posX += (x - lastTouchX);
                    posY += (y - lastTouchY);
                    invalidate();
                }
                lastTouchX = x;
                lastTouchY = y;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pointsList.isEmpty() && circlesList.isEmpty() && planesList.isEmpty()) return;

        canvas.save();

        canvas.translate(posX, posY);
        canvas.scale(scaleFactor, scaleFactor, getWidth() / 2f, getHeight() / 2f);

        float viewCenterX = getWidth() / 2f;
        float viewCenterY = getHeight() / 2f;
        float baseScale = Math.min(getWidth(), getHeight()) / 500f;

        // Draw Planes (Shaded areas)
        for (GeoPlane plane : planesList) {
            Path p = new Path();
            for (int i = 0; i < plane.vertices.size(); i++) {
                PointF pt = plane.vertices.get(i);
                float sx = viewCenterX + (pt.x - 200) * baseScale;
                float sy = viewCenterY - (pt.y - 200) * baseScale;
                if (i == 0) p.moveTo(sx, sy);
                else p.lineTo(sx, sy);
            }
            p.close();
            canvas.drawPath(p, planePaint);
        }

        // Draw Circles
        for (GeoCircle circle : circlesList) {
            float scx = viewCenterX + (circle.cx - 200) * baseScale;
            float scy = viewCenterY - (circle.cy - 200) * baseScale;
            float sradius = circle.radius * baseScale;
            canvas.drawCircle(scx, scy, sradius, linePaint);
            if (!circle.label.isEmpty()) {
                canvas.drawText(circle.label, scx + 10, scy - 10, textPaint);
            }
        }

        // Draw Path
        Path path = new Path();
        boolean startNewSegment = true;

        for (GeoPoint p : pointsList) {
            float screenX = viewCenterX + (p.x - 200) * baseScale;
            float screenY = viewCenterY - (p.y - 200) * baseScale;

            if (p.startsNewSegment) startNewSegment = true;

            if (p.isVertex) {
                if (startNewSegment) {
                    path.moveTo(screenX, screenY);
                    startNewSegment = false;
                } else {
                    path.lineTo(screenX, screenY);
                }
                canvas.drawCircle(screenX, screenY, 6f, pointPaint);
                canvas.drawText(p.label, screenX + 15, screenY - 15, textPaint);
            } else {
                canvas.drawText(p.label, screenX, screenY, valuePaint);
                startNewSegment = true;
            }
        }

        canvas.drawPath(path, linePaint);
        canvas.restore();
    }
}
