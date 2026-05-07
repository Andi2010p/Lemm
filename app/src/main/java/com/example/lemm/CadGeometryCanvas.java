package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

public class CadGeometryCanvas extends View {
    private float scale = 1.0f; // Ensure this is here
    private boolean snapToGrid = true; // Add this if missing
    private boolean snapToPoints = true; // Add this if missing

    private CadEngine2d engine;

    // Transformation Matrix (The secret to Infinite Pan and Zoom)
    private Matrix matrix = new Matrix();

    private Matrix inverseMatrix = new Matrix();
    private float[] tempPts = new float[2];

    // Rendering Paints
    private Paint linePaint, gridPaint, vertexPaint, previewPaint;

    // Interaction Variables
    private float lastTouchX, lastTouchY;
    private boolean isPanning = false;
    private PointF previewEndPoint = null;
    private PointF previewStartPoint = null;

    public CadGeometryCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.BLACK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);

        previewPaint = new Paint(linePaint);
        previewPaint.setColor(Color.BLUE);
        previewPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#E0E0E0"));
        gridPaint.setStrokeWidth(1f);

        vertexPaint = new Paint();
        vertexPaint.setColor(Color.RED);
        vertexPaint.setStyle(Paint.Style.FILL);
    }

    public void setEngine(CadEngine2d engine) {
        this.engine = engine;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Draw UI elements (Non-zoomed)
        canvas.drawColor(Color.WHITE);

        // 2. Apply CAD Transformation (Zoom/Pan)
        canvas.save();
        canvas.concat(matrix);

        drawGrid(canvas);

        if (engine != null) {
            List<Geometry> geometries = engine.getGeometries();
            for (Geometry geo : geometries) {
                drawJtsGeometry(canvas, geo, linePaint);
            }
        }

        // Draw the "Ghost Line" while drawing
        if (previewStartPoint != null && previewEndPoint != null) {
            canvas.drawLine(previewStartPoint.x, previewStartPoint.y,
                    previewEndPoint.x, previewEndPoint.y, previewPaint);
        }

        canvas.restore();
    }

    private void drawGrid(Canvas canvas) {
        float gridSize = 100f;
        float worldLimit = 5000f; // Large work area
        for (float i = -worldLimit; i <= worldLimit; i += gridSize) {
            canvas.drawLine(i, -worldLimit, i, worldLimit, gridPaint);
            canvas.drawLine(-worldLimit, i, worldLimit, i, gridPaint);
        }
    }

    private void drawJtsGeometry(Canvas canvas, Geometry geo, Paint paint) {
        Coordinate[] coords = geo.getCoordinates();
        if (coords.length < 2) return;

        Path path = new Path();
        path.moveTo((float) coords[0].x, (float) coords[0].y);
        for (int i = 1; i < coords.length; i++) {
            path.lineTo((float) coords[i].x, (float) coords[i].y);
        }

        if (geo instanceof Polygon) path.close();
        canvas.drawPath(path, paint);

        // Draw vertices (points) for snapping feedback
        for (Coordinate c : coords) {
            canvas.drawCircle((float) c.x, (float) c.y, 5f, vertexPaint);
        }
    }

    // --- COORDINATE SYSTEM CONVERSION ---
    // Converts screen pixels (where you touch) to World Coordinates (Math)
    public PointF getRawWorldCoords(float screenX, float screenY) {
        matrix.invert(inverseMatrix);
        tempPts[0] = screenX;
        tempPts[1] = screenY;
        inverseMatrix.mapPoints(tempPts);
        return new PointF(tempPts[0], tempPts[1]);
    }

    // --- EXTERNAL CONTROLS ---
    public void zoomIn() {
        matrix.postScale(1.2f, 1.2f, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void zoomOut() {
        matrix.postScale(0.8f, 0.8f, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void pan(float dx, float dy) {
        matrix.postTranslate(dx, dy);
        invalidate();
    }

    public void setPreviewPoints(PointF start, PointF end) {
        this.previewStartPoint = start;
        this.previewEndPoint = end;
        invalidate();
    }
    public void setSnapToPoints(boolean snap) {
        this.snapToPoints = snap;
        invalidate();
    }

    public void setSnapToGrid(boolean snap) {
        this.snapToGrid = snap;
        invalidate();
    }

    public int getZoomPercentage() {
        return (int) (scale * 100);
    }
}