package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.List;

public class CadGeometryCanvas extends View {
    private float scale = 1.0f;
    private String currentTool = "MOVE";
    private CadEngine2d engine;
    private Matrix matrix = new Matrix(), inverseMatrix = new Matrix();
    private Paint linePaint, selectedPaint, gridPaint, vertexPaint, previewPaint, textPaint, dimensionPaint, dashedDimensionPaint;
    private PointF previewEndPoint = null, previewStartPoint = null, snapIndicatorPos = null;
    private List<PointF> activePolyline = new ArrayList<>();
    private Geometry selectedGeometry = null;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    public CadGeometryCanvas(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(Color.parseColor("#2C3E50")); linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(3f);
        selectedPaint = new Paint(linePaint); selectedPaint.setColor(Color.parseColor("#E67E22")); selectedPaint.setStrokeWidth(6f);
        gridPaint = new Paint(); gridPaint.setColor(Color.parseColor("#D5D8DC"));
        vertexPaint = new Paint(Paint.ANTI_ALIAS_FLAG); vertexPaint.setColor(Color.parseColor("#E74C3C")); vertexPaint.setStyle(Paint.Style.FILL);
        previewPaint = new Paint(linePaint); previewPaint.setColor(Color.parseColor("#3498DB")); previewPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        dimensionPaint = new Paint(Paint.ANTI_ALIAS_FLAG); dimensionPaint.setColor(Color.parseColor("#8E44AD")); dimensionPaint.setStyle(Paint.Style.STROKE);
        dashedDimensionPaint = new Paint(dimensionPaint); dashedDimensionPaint.setColor(Color.parseColor("#3498DB")); dashedDimensionPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setColor(Color.parseColor("#0C3D6A")); textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#FDFEFE"));
        canvas.save(); canvas.concat(matrix);
        drawGrid(canvas);

        if (engine != null) {
            // Draw Snap Targets
            Paint sp = new Paint(); sp.setColor(Color.LTGRAY); sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1f/scale);
            for (Coordinate sc : engine.getAllSnapPoints()) canvas.drawCircle((float)sc.x, (float)sc.y, 5f/scale, sp);

            for (Geometry geo : engine.getGeometries()) {
                Paint p = (geo == selectedGeometry) ? selectedPaint : linePaint;
                drawJtsGeometry(canvas, geo, p);
                if (geo instanceof Polygon) {
                    if (geo.getCoordinates().length == 5) drawRectDimensions(canvas, (Polygon) geo);
                    else drawVisualRadius(canvas, geo);
                }
            }
            textPaint.setTextSize(40f/scale);
            for (CadEngine2d.NamedPoint np : engine.getNamedPoints()) canvas.drawText(np.label, (float)np.x + 12f/scale, (float)np.y - 12f/scale, textPaint);
            for (CadEngine2d.AngleAnnotation ann : engine.getAngleAnnotations()) drawAngleArc(canvas, ann);
        }

        if (!activePolyline.isEmpty()) {
            Path polyPath = new Path(); polyPath.moveTo(activePolyline.get(0).x, activePolyline.get(0).y);
            for (PointF pt : activePolyline) polyPath.lineTo(pt.x, pt.y);
            canvas.drawPath(polyPath, linePaint);
        }
        if (previewStartPoint != null && previewEndPoint != null) drawPreview(canvas);
        if (snapIndicatorPos != null) {
            Paint p = new Paint(); p.setColor(Color.parseColor("#3498DB")); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f/scale);
            canvas.drawCircle(snapIndicatorPos.x, snapIndicatorPos.y, 20f/scale, p);
        }
        canvas.restore();
    }

    private void drawVisualRadius(Canvas canvas, Geometry circle) {
        Coordinate center = circle.getCentroid().getCoordinate();
        double radius = circle.getEnvelopeInternal().getWidth() / 2.0;
        double angle = engine.getCircleRadiusAngle(circle);
        float endX = (float) (center.x + radius * Math.cos(angle)), endY = (float) (center.y + radius * Math.sin(angle));
        dashedDimensionPaint.setStrokeWidth(2f/scale);
        canvas.drawLine((float)center.x, (float)center.y, endX, endY, dashedDimensionPaint);
        textPaint.setTextSize(30f/scale);
        canvas.drawText(String.format("R:%.1f", radius), (float)(center.x+endX)/2, (float)(center.y+endY)/2, textPaint);
    }

// Inside CadGeometryCanvas.java

    private void drawRectDimensions(Canvas canvas, Polygon rect) {
        Coordinate[] c = rect.getCoordinates();
        textPaint.setTextSize(28f / scale);
        textPaint.setColor(Color.parseColor("#8E44AD")); // Purple color for clarity

        // Loop through the 4 sides of the rectangle
        for (int i = 0; i < 4; i++) {
            double x1 = c[i].x, y1 = c[i].y;
            double x2 = c[i+1].x, y2 = c[i+1].y;

            // Calculate Midpoint
            float midX = (float) ((x1 + x2) / 2);
            float midY = (float) ((y1 + y2) / 2);

            // Calculate distance
            double dist = c[i].distance(c[i+1]);
            String label = String.format("%.1f", dist);

            // Apply a small offset so text isn't directly on top of the line
            float offset = 20f / scale;
            if (Math.abs(x1 - x2) < 1.0) {
                // Vertical line: move text to the right
                canvas.drawText(label, midX + offset, midY, textPaint);
            } else {
                // Horizontal line: move text above
                canvas.drawText(label, midX, midY - offset, textPaint);
            }
        }
        // Reset text color for other drawings
        textPaint.setColor(Color.parseColor("#0C3D6A"));
    }    private void drawAngleArc(Canvas canvas, CadEngine2d.AngleAnnotation ann) {
        Coordinate pivot = engine.findSharedVertex(ann.line1, ann.line2);
        if (pivot == null) return;
        Coordinate p1 = getOtherPoint(ann.line1, pivot), p2 = getOtherPoint(ann.line2, pivot);
        float a1 = (float)Math.toDegrees(Math.atan2(p1.y-pivot.y, p1.x-pivot.x)), a2 = (float)Math.toDegrees(Math.atan2(p2.y-pivot.y, p2.x-pivot.x));
        float sweep = a2 - a1; if (sweep > 180) sweep -= 360; if (sweep < -180) sweep += 360;
        dimensionPaint.setStrokeWidth(2f/scale);
        RectF oval = new RectF((float)pivot.x-60f/scale, (float)pivot.y-60f/scale, (float)pivot.x+60f/scale, (float)pivot.y+60f/scale);
        canvas.drawArc(oval, a1, sweep, false, dimensionPaint);
        canvas.drawText(String.format("%.1f°", Math.abs(ann.angleValue)), (float)(pivot.x + (85f/scale)*Math.cos(Math.toRadians(a1+sweep/2))), (float)(pivot.y + (85f/scale)*Math.sin(Math.toRadians(a1+sweep/2))), textPaint);
    }

    private void drawPreview(Canvas canvas) {
        if (currentTool.equals("RECT")) canvas.drawRect(Math.min(previewStartPoint.x, previewEndPoint.x), Math.min(previewStartPoint.y, previewEndPoint.y), Math.max(previewStartPoint.x, previewEndPoint.x), Math.max(previewStartPoint.y, previewEndPoint.y), previewPaint);
        else if (currentTool.equals("CIRCLE")) canvas.drawCircle(previewStartPoint.x, previewStartPoint.y, (float)Math.hypot(previewEndPoint.x-previewStartPoint.x, previewEndPoint.y-previewStartPoint.y), previewPaint);
        else if (currentTool.equals("LINE")) canvas.drawLine(previewStartPoint.x, previewStartPoint.y, previewEndPoint.x, previewEndPoint.y, previewPaint);
    }

    private void drawGrid(Canvas canvas) {
        float gs = 100f; matrix.invert(inverseMatrix); RectF v = new RectF(0,0,getWidth(),getHeight()); inverseMatrix.mapRect(v);
        for (float x=(float)Math.floor(v.left/gs)*gs; x<=v.right; x+=gs) canvas.drawLine(x, v.top, x, v.bottom, gridPaint);
        for (float y=(float)Math.floor(v.top/gs)*gs; y<=v.bottom; y+=gs) canvas.drawLine(v.left, y, v.right, y, gridPaint);
    }

    private void drawJtsGeometry(Canvas canvas, Geometry geo, Paint p) {
        Coordinate[] coords = geo.getCoordinates(); Path path = new Path(); path.moveTo((float)coords[0].x, (float)coords[0].y);
        for (int i=1; i<coords.length; i++) path.lineTo((float)coords[i].x, (float)coords[i].y);
        if (geo instanceof Polygon) path.close();
        canvas.drawPath(path, p);
        for (Coordinate c : coords) canvas.drawCircle((float)c.x, (float)c.y, 6f/scale, vertexPaint);
    }
    public void zoomIn() { applyZoom(1.2f, getWidth() / 2f, getHeight() / 2f); }
    public void zoomOut() { applyZoom(1f / 1.2f, getWidth() / 2f, getHeight() / 2f); }

    public PointF getRawWorldCoords(float screenX, float screenY) { matrix.invert(inverseMatrix); float[] pts = {screenX, screenY}; inverseMatrix.mapPoints(pts); return new PointF(pts[0], pts[1]); }
    public void applyZoom(float f, float fx, float fy) { matrix.postScale(f, f, fx, fy); scale *= f; invalidate(); if (zoomListener != null) zoomListener.onZoomChanged((int)(scale*100)); }
    public void pan(float dx, float dy) { matrix.postTranslate(dx, dy); invalidate(); }
    public void setEngine(CadEngine2d e) { this.engine = e; invalidate(); }
    public void setSelectedGeometry(Geometry g) { this.selectedGeometry = g; invalidate(); }
    public Geometry getSelectedGeometry() { return selectedGeometry; }
    private Coordinate getOtherPoint(LineString l, Coordinate p) { return l.getCoordinates()[0].distance(p) < 0.1 ? l.getCoordinates()[1] : l.getCoordinates()[0]; }
    public void setSnapIndicator(float x, float y) { this.snapIndicatorPos = new PointF(x, y); invalidate(); }
    public void clearSnapIndicator() { this.snapIndicatorPos = null; invalidate(); }
    public void setCurrentTool(String tool) { this.currentTool = tool; this.selectedGeometry = null; invalidate(); }
    public void setPreviewPoints(PointF s, PointF e) { this.previewStartPoint = s; this.previewEndPoint = e; invalidate(); }
    public void setActivePolyline(List<PointF> p) { this.activePolyline = p; invalidate(); }
    public int getZoomPercentage() { return (int)(scale * 100); }
}