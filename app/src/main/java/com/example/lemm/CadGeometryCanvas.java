package com.example.lemm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

public class CadGeometryCanvas extends View {
    private float scale = 1.0f;
    private String currentTool = "MOVE";
    private CadEngine2d engine;

    private Matrix matrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();
    private float[] tempPts = new float[2];

    private Paint linePaint, selectedPaint, gridPaint, vertexPaint, previewPaint, snapIndicatorPaint, centerPaint, startDotPaint, textPaint;
    private PointF previewEndPoint = null;
    private PointF previewStartPoint = null;
    private PointF snapIndicatorPos = null;

    private List<PointF> activePolyline = new ArrayList<>();
    private Geometry selectedGeometry = null;

    // --- NEW ZOOM LISTENER ---
    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }
    private OnZoomChangeListener zoomListener;

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomListener = listener;
    }
    // --------------------------

    public CadGeometryCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#2C3E50"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);

        selectedPaint = new Paint(linePaint);
        selectedPaint.setColor(Color.parseColor("#E67E22"));
        selectedPaint.setStrokeWidth(6f);

        previewPaint = new Paint(linePaint);
        previewPaint.setColor(Color.parseColor("#3498DB"));
        previewPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#D5D8DC"));
        gridPaint.setStrokeWidth(1f);

        vertexPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        vertexPaint.setColor(Color.parseColor("#E74C3C"));
        vertexPaint.setStyle(Paint.Style.FILL);

        snapIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        snapIndicatorPaint.setColor(Color.parseColor("#27AE60"));
        snapIndicatorPaint.setStyle(Paint.Style.STROKE);
        snapIndicatorPaint.setStrokeWidth(2f);

        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(Color.MAGENTA);
        centerPaint.setStyle(Paint.Style.STROKE);

        startDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        startDotPaint.setColor(Color.parseColor("#27AE60"));
        startDotPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#0C3D6A"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setEngine(CadEngine2d engine) {
        this.engine = engine;
        invalidate();
    }

    public void setSelectedGeometry(Geometry g) {
        this.selectedGeometry = g;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#FDFEFE"));

        canvas.save();
        canvas.concat(matrix);

        drawGrid(canvas);

        float adjustedStroke = 3f / scale;
        linePaint.setStrokeWidth(adjustedStroke);
        selectedPaint.setStrokeWidth(7f / scale);
        previewPaint.setStrokeWidth(adjustedStroke);
        snapIndicatorPaint.setStrokeWidth(3f / scale);
        centerPaint.setStrokeWidth(2f / scale);
        textPaint.setTextSize(35f / scale);

        if (engine != null) {
            for (Geometry geo : engine.getGeometries()) {
                Paint p = (geo == selectedGeometry) ? selectedPaint : linePaint;
                drawJtsGeometry(canvas, geo, p);

                Coordinate center = geo.getCentroid().getCoordinate();
                float cSize = 15f / scale;
                canvas.drawLine((float)center.x - cSize, (float)center.y, (float)center.x + cSize, (float)center.y, centerPaint);
                canvas.drawLine((float)center.x, (float)center.y - cSize, (float)center.x, (float)center.y + cSize, centerPaint);

                if (geo.getUserData() != null) {
                    canvas.drawText(geo.getUserData().toString(), (float)center.x, (float)center.y - (20f/scale), textPaint);
                }
            }
        }

        if (activePolyline != null && !activePolyline.isEmpty()) {
            Path polyPath = new Path();
            polyPath.moveTo(activePolyline.get(0).x, activePolyline.get(0).y);
            for (int i = 1; i < activePolyline.size(); i++) polyPath.lineTo(activePolyline.get(i).x, activePolyline.get(i).y);
            canvas.drawPath(polyPath, linePaint);

            if (previewEndPoint != null) {
                PointF lastPt = activePolyline.get(activePolyline.size() - 1);
                canvas.drawLine(lastPt.x, lastPt.y, previewEndPoint.x, previewEndPoint.y, previewPaint);
            }
            canvas.drawCircle(activePolyline.get(0).x, activePolyline.get(0).y, 10f / scale, startDotPaint);
        }

        if (previewStartPoint != null && previewEndPoint != null && !currentTool.equals("POLYLINE")) {
            drawPreview(canvas);
        }

        if (snapIndicatorPos != null) {
            Paint p = new Paint();
            p.setColor(Color.parseColor("#3498DB"));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f / scale);
            canvas.drawCircle(snapIndicatorPos.x, snapIndicatorPos.y, 20f / scale, p);
        }

        canvas.restore();
    }

    private void drawPreview(Canvas canvas) {
        switch (currentTool) {
            case "RECT":
                float left = Math.min(previewStartPoint.x, previewEndPoint.x);
                float top = Math.min(previewStartPoint.y, previewEndPoint.y);
                float right = Math.max(previewStartPoint.x, previewEndPoint.x);
                float bottom = Math.max(previewStartPoint.y, previewEndPoint.y);
                canvas.drawRect(left, top, right, bottom, previewPaint);
                break;
            case "CIRCLE":
                float radius = (float) Math.hypot(previewEndPoint.x - previewStartPoint.x, previewEndPoint.y - previewStartPoint.y);
                canvas.drawCircle(previewStartPoint.x, previewStartPoint.y, radius, previewPaint);
                break;
            case "LINE":
                canvas.drawLine(previewStartPoint.x, previewStartPoint.y, previewEndPoint.x, previewEndPoint.y, previewPaint);
                break;
        }
    }

    private void drawGrid(Canvas canvas) {
        float gridSize = 100f;
        matrix.invert(inverseMatrix);
        RectF v = new RectF(0, 0, getWidth(), getHeight());
        inverseMatrix.mapRect(v);

        for (float x = (float)Math.floor(v.left/gridSize)*gridSize; x <= v.right; x += gridSize)
            canvas.drawLine(x, v.top, x, v.bottom, gridPaint);
        for (float y = (float)Math.floor(v.top/gridSize)*gridSize; y <= v.bottom; y += gridSize)
            canvas.drawLine(v.left, y, v.right, y, gridPaint);
    }

    private void drawJtsGeometry(Canvas canvas, Geometry geo, Paint paint) {
        Coordinate[] coords = geo.getCoordinates();
        Path path = new Path();
        path.moveTo((float) coords[0].x, (float) coords[0].y);
        for (int i = 1; i < coords.length; i++) path.lineTo((float) coords[i].x, (float) coords[i].y);
        if (geo instanceof Polygon) path.close();
        canvas.drawPath(path, paint);

        float pointRadius = 6f / scale;
        for (Coordinate c : coords) canvas.drawCircle((float) c.x, (float) c.y, pointRadius, vertexPaint);
    }

    public PointF getRawWorldCoords(float screenX, float screenY) {
        matrix.invert(inverseMatrix);
        tempPts[0] = screenX; tempPts[1] = screenY;
        inverseMatrix.mapPoints(tempPts);
        return new PointF(tempPts[0], tempPts[1]);
    }

    // --- UPDATED ZOOM LOGIC ---
    public void applyZoom(float factor, float focusX, float focusY) {
        float newScale = scale * factor;

        if (newScale < 0.1f) {
            factor = 0.1f / scale;
            scale = 0.1f;
        } else if (newScale > 10.0f) {
            factor = 10.0f / scale;
            scale = 10.0f;
        } else {
            scale = newScale;
        }

        matrix.postScale(factor, factor, focusX, focusY);
        invalidate();

        if (zoomListener != null) {
            zoomListener.onZoomChanged(getZoomPercentage());
        }
    }

    public void zoomIn() {
        applyZoom(1.2f, getWidth() / 2f, getHeight() / 2f);
    }

    public void zoomOut() {
        applyZoom(1f / 1.2f, getWidth() / 2f, getHeight() / 2f);
    }
    // ---------------------------

    public void pan(float dx, float dy) {
        matrix.postTranslate(dx, dy);
        invalidate();
    }

    public void setSnapIndicator(float x, float y) { this.snapIndicatorPos = new PointF(x, y); invalidate(); }
    public void clearSnapIndicator() { this.snapIndicatorPos = null; invalidate(); }
    public void setCurrentTool(String tool) { this.currentTool = tool; this.selectedGeometry = null; invalidate(); }
    public void setPreviewPoints(PointF start, PointF end) { this.previewStartPoint = start; this.previewEndPoint = end; invalidate(); }
    public void setActivePolyline(List<PointF> points) { this.activePolyline = points; invalidate(); }
    public int getZoomPercentage() { return (int) (scale * 100); }
}