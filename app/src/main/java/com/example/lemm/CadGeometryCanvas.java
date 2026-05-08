package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import java.util.List;

public class CadGeometryCanvas extends View {
    private float scale = 1.0f;
    private boolean snapToGrid = true;
    private boolean snapToPoints = true;

    private CadEngine2d engine;
    private Matrix matrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();
    private float[] tempPts = new float[2];

    private Paint linePaint, gridPaint, vertexPaint, previewPaint;
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
        canvas.drawColor(Color.WHITE);

        canvas.save();
        canvas.concat(matrix);

        drawGrid(canvas);

        // Make lines stay thin regardless of zoom level
        float adjustedStroke = 3f / scale;
        linePaint.setStrokeWidth(adjustedStroke);
        previewPaint.setStrokeWidth(adjustedStroke);

        if (engine != null) {
            List<Geometry> geometries = engine.getGeometries();
            for (Geometry geo : geometries) {
                drawJtsGeometry(canvas, geo, linePaint);
            }
        }

        if (previewStartPoint != null && previewEndPoint != null) {
            canvas.drawLine(previewStartPoint.x, previewStartPoint.y,
                    previewEndPoint.x, previewEndPoint.y, previewPaint);
        }

        canvas.restore();
    }

    private void drawGrid(Canvas canvas) {
        if (!snapToGrid) return;
        float gridSize = 100f;

        // Only draw grid lines in the visible area to prevent lag
        matrix.invert(inverseMatrix);
        RectF visibleRect = new RectF(0, 0, getWidth(), getHeight());
        inverseMatrix.mapRect(visibleRect);

        float startX = (float) Math.floor(visibleRect.left / gridSize) * gridSize;
        float startY = (float) Math.floor(visibleRect.top / gridSize) * gridSize;

        for (float x = startX; x <= visibleRect.right; x += gridSize) {
            canvas.drawLine(x, visibleRect.top, x, visibleRect.bottom, gridPaint);
        }
        for (float y = startY; y <= visibleRect.bottom; y += gridSize) {
            canvas.drawLine(visibleRect.left, y, visibleRect.right, y, gridPaint);
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

        // Draw small points at corners
        float pointRadius = 5f / scale;
        for (Coordinate c : coords) {
            canvas.drawCircle((float) c.x, (float) c.y, pointRadius, vertexPaint);
        }
    }

    public PointF getRawWorldCoords(float screenX, float screenY) {
        matrix.invert(inverseMatrix);
        tempPts[0] = screenX;
        tempPts[1] = screenY;
        inverseMatrix.mapPoints(tempPts);
        return new PointF(tempPts[0], tempPts[1]);
    }

    public void zoomIn() {
        scale *= 1.2f;
        matrix.postScale(1.2f, 1.2f, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void zoomOut() {
        scale *= 0.8f;
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

    public void setSnapToPoints(boolean snap) { this.snapToPoints = snap; invalidate(); }
    public void setSnapToGrid(boolean snap) { this.snapToGrid = snap; invalidate(); }
    public int getZoomPercentage() { return (int) (scale * 100); }
}