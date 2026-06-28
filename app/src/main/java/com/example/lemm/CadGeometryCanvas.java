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
    private boolean showPointLabels = true;
    private String currentTool = "MOVE";
    private CadEngine2d engine;
    private Matrix matrix = new Matrix(), inverseMatrix = new Matrix();
    private Paint linePaint, selectedPaint, gridPaint, vertexPaint, previewPaint, textPaint, dimensionPaint, dashedDimensionPaint;
    private PointF previewEndPoint = null, previewStartPoint = null, snapIndicatorPos = null;
    private List<PointF> activePolyline = new ArrayList<>();
    private Geometry selectedGeometry = null;
    private int selectedSegmentIndex = -1;
    private int colBg, colText, colDimension;

    public interface OnZoomChangeListener { void onZoomChanged(int percentage); }
    private OnZoomChangeListener zoomListener;
    public void setOnZoomChangeListener(OnZoomChangeListener l) { this.zoomListener = l; }

    public CadGeometryCanvas(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        // Theme-aware canvas colors (light "paper" vs dark; see colors.xml / values-night).
        colBg = androidx.core.content.ContextCompat.getColor(getContext(), R.color.canvas_bg);
        colText = androidx.core.content.ContextCompat.getColor(getContext(), R.color.canvas_text);
        colDimension = androidx.core.content.ContextCompat.getColor(getContext(), R.color.canvas_dimension);
        int colLine = androidx.core.content.ContextCompat.getColor(getContext(), R.color.canvas_line);
        int colGrid = androidx.core.content.ContextCompat.getColor(getContext(), R.color.canvas_grid);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setColor(colLine); linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(3f);
        selectedPaint = new Paint(linePaint); selectedPaint.setColor(Color.parseColor("#E67E22")); selectedPaint.setStrokeWidth(6f);
        gridPaint = new Paint(); gridPaint.setColor(colGrid);
        vertexPaint = new Paint(Paint.ANTI_ALIAS_FLAG); vertexPaint.setColor(Color.parseColor("#E74C3C")); vertexPaint.setStyle(Paint.Style.FILL);
        previewPaint = new Paint(linePaint); previewPaint.setColor(Color.parseColor("#3498DB")); previewPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        dimensionPaint = new Paint(Paint.ANTI_ALIAS_FLAG); dimensionPaint.setColor(colDimension); dimensionPaint.setStyle(Paint.Style.STROKE);
        dashedDimensionPaint = new Paint(dimensionPaint); dashedDimensionPaint.setColor(Color.parseColor("#3498DB")); dashedDimensionPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setColor(colText); textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setFakeBoldText(true);
    }

    public void setSelectedSegmentIndex(int idx) { this.selectedSegmentIndex = idx; invalidate(); }
    public int getSelectedSegmentIndex() { return selectedSegmentIndex; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(colBg);
        canvas.save(); canvas.concat(matrix);
        drawGrid(canvas);

        if (engine != null) {
            Paint sp = new Paint(); sp.setColor(Color.LTGRAY); sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1f/scale);
            for (Coordinate sc : engine.getAllSnapPoints()) canvas.drawCircle((float)sc.x, (float)sc.y, 5f/scale, sp);

            for (Geometry geo : engine.getGeometries()) {
                Paint p = (geo == selectedGeometry) ? selectedPaint : linePaint;

                // Crash-proof edge renderer
                if (geo == selectedGeometry && selectedSegmentIndex != -1 && geo instanceof Polygon) {
                    Coordinate[] coords = geo.getCoordinates();
                    if (selectedSegmentIndex < coords.length - 1) {
                        drawJtsGeometry(canvas, geo, linePaint);
                        float x1 = (float) coords[selectedSegmentIndex].x;
                        float y1 = (float) coords[selectedSegmentIndex].y;
                        float x2 = (float) coords[selectedSegmentIndex + 1].x;
                        float y2 = (float) coords[selectedSegmentIndex + 1].y;
                        canvas.drawLine(x1, y1, x2, y2, selectedPaint);
                    } else {
                        drawJtsGeometry(canvas, geo, p);
                    }
                } else {
                    drawJtsGeometry(canvas, geo, p);
                }

                if (geo instanceof Polygon) {
                    if (geo.getCoordinates().length == 5) drawRectDimensions(canvas, (Polygon) geo);
                    else drawVisualRadius(canvas, geo);
                } else if (geo instanceof LineString) {
                    drawLineDimension(canvas, (LineString) geo);
                }
            }
            textPaint.setTextSize(40f/scale);
            for (CadEngine2d.AngleAnnotation ann : engine.getAngleAnnotations()) drawAngleArc(canvas, ann);
            drawAutoAngles(canvas); // every corner shows its value with an arc
        }
        if (showPointLabels && engine != null) {
            textPaint.setTextSize(40f/scale);
            for (CadEngine2d.NamedPoint np : engine.getNamedPoints())
                canvas.drawText(np.label, (float)np.x + 12f/scale, (float)np.y - 12f/scale, textPaint);
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

    private void drawRectDimensions(Canvas canvas, Polygon rect) {
        if ("WORKSPACE".equals(rect.getUserData())) return;

        Coordinate[] c = rect.getCoordinates();
        textPaint.setTextSize(28f / scale);
        textPaint.setColor(colDimension);

        for (int i = 0; i < 4; i++) {
            double x1 = c[i].x, y1 = c[i].y;
            double x2 = c[i+1].x, y2 = c[i+1].y;
            float midX = (float) ((x1 + x2) / 2); float midY = (float) ((y1 + y2) / 2);
            double dist = c[i].distance(c[i+1]);
            String label = String.format("%.1f", dist);
            float offset = 20f / scale;
            if (Math.abs(x1 - x2) < 1.0) canvas.drawText(label, midX + offset, midY, textPaint);
            else canvas.drawText(label, midX, midY - offset, textPaint);
        }
        textPaint.setColor(colText);
    }

    private void drawLineDimension(Canvas canvas, LineString line) {
        Coordinate[] c = line.getCoordinates();
        if (c.length < 2) return;
        double x1 = c[0].x, y1 = c[0].y; double x2 = c[1].x, y2 = c[1].y;
        float midX = (float) ((x1 + x2) / 2); float midY = (float) ((y1 + y2) / 2);
        double dist = c[0].distance(c[1]);
        String label = String.format("%.1f", dist);
        textPaint.setTextSize(28f / scale);
        textPaint.setColor(colDimension);
        float offset = 20f / scale;
        if (Math.abs(x1 - x2) < 1.0) canvas.drawText(label, midX + offset, midY, textPaint);
        else canvas.drawText(label, midX, midY - offset, textPaint);
        textPaint.setColor(colText);
    }

    private void drawAngleArc(Canvas canvas, CadEngine2d.AngleAnnotation ann) {
        Coordinate pivot = engine.findSharedVertex(ann.line1, ann.line2);
        if (pivot == null) return;
        Coordinate p1 = getOtherPoint(ann.line1, pivot), p2 = getOtherPoint(ann.line2, pivot);
        float a1 = (float)Math.toDegrees(Math.atan2(p1.y-pivot.y, p1.x-pivot.x)), a2 = (float)Math.toDegrees(Math.atan2(p2.y-pivot.y, p2.x-pivot.x));
        float sweep = a2 - a1; if (sweep > 180) sweep -= 360; if (sweep < -180) sweep += 360;
        dimensionPaint.setStrokeWidth(2f/scale);
        RectF oval = new RectF((float)pivot.x-60f/scale, (float)pivot.y-60f/scale, (float)pivot.x+60f/scale, (float)pivot.y+60f/scale);
        canvas.drawArc(oval, a1, sweep, false, dimensionPaint);

        // FIXED: Using standard java.lang.Math.cos & Math.sin
        float textX = (float) (pivot.x + (85f / scale) * Math.cos(Math.toRadians(a1 + sweep / 2)));
        float textY = (float) (pivot.y + (85f / scale) * Math.sin(Math.toRadians(a1 + sweep / 2)));

        // Show the ACTUAL angle between the two lines (the arc's own sweep), so the number always
        // matches what's drawn — a stored target value could drift from the real geometry after edits.
        canvas.drawText(String.format("%.1f°", Math.abs(sweep)), textX, textY, textPaint);
    }

    /**
     * Always-on: annotates every corner in the drawing with an arc + its value — polygon vertices
     * (e.g. a rectangle's four right angles), internal corners of multi-segment lines, and junctions
     * where two separate lines share an endpoint. Junctions already carrying a manual angle are skipped.
     */
    private void drawAutoAngles(Canvas canvas) {
        if (engine == null) return;
        List<Geometry> geos = engine.getGeometries();

        for (Geometry geo : geos) {
            if (geo instanceof Polygon) {
                if (isCircleLike(geo)) continue; // circles are buffer polygons — don't annotate every segment
                Coordinate[] c = geo.getCoordinates();
                int n = c.length - 1; // closed ring: last == first
                if (n < 3) continue;
                // Ring winding (signed area): lets us show the true INTERIOR angle, which is reflex (>180°)
                // at a concave corner — otherwise an L-shape's 270° corner would read as 90°.
                double area2 = 0;
                for (int i = 0; i < n; i++) { Coordinate p = c[i], q = c[(i + 1) % n]; area2 += p.x * q.y - q.x * p.y; }
                boolean ccw = area2 > 0;
                for (int i = 0; i < n; i++) {
                    Coordinate prev = c[(i - 1 + n) % n], cur = c[i], next = c[(i + 1) % n];
                    double cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x);
                    boolean reflex = ccw ? (cross < 0) : (cross > 0);
                    drawCornerArc(canvas, cur, prev, next, reflex);
                }
            } else if (geo instanceof LineString) {
                Coordinate[] c = geo.getCoordinates();
                for (int i = 1; i < c.length - 1; i++) // internal corners of an open polyline
                    drawCornerArc(canvas, c[i], c[i - 1], c[i + 1]);
            }
        }

        // Junctions where two separate lines meet at a shared endpoint.
        for (int i = 0; i < geos.size(); i++) {
            if (!(geos.get(i) instanceof LineString)) continue;
            Coordinate[] ci = geos.get(i).getCoordinates();
            if (ci.length < 2) continue;
            for (int j = i + 1; j < geos.size(); j++) {
                if (!(geos.get(j) instanceof LineString)) continue;
                Coordinate[] cj = geos.get(j).getCoordinates();
                if (cj.length < 2) continue;
                if (hasManualAnnotation((LineString) geos.get(i), (LineString) geos.get(j))) continue;
                Coordinate shared = sharedEndpoint(ci, cj);
                if (shared != null) drawCornerArc(canvas, shared, neighborOf(ci, shared), neighborOf(cj, shared));
            }
        }
    }

    /** Draws the smaller (≤180°) angle between pivot→p1 and pivot→p2 — for line junctions & open corners. */
    private void drawCornerArc(Canvas canvas, Coordinate pivot, Coordinate p1, Coordinate p2) {
        drawCornerArc(canvas, pivot, p1, p2, false);
    }

    /**
     * Draws one corner angle: the arc between pivot→p1 and pivot→p2, plus the value in degrees.
     * When {@code reflex} is true the interior angle is the major one (&gt;180°), so the value and the
     * arc are taken the long way round — used for concave polygon corners.
     */
    private void drawCornerArc(Canvas canvas, Coordinate pivot, Coordinate p1, Coordinate p2, boolean reflex) {
        double v1x = p1.x - pivot.x, v1y = p1.y - pivot.y;
        double v2x = p2.x - pivot.x, v2y = p2.y - pivot.y;
        double l1 = Math.hypot(v1x, v1y), l2 = Math.hypot(v2x, v2y);
        if (l1 < 1e-6 || l2 < 1e-6) return;
        float a1 = (float) Math.toDegrees(Math.atan2(v1y, v1x));
        float a2 = (float) Math.toDegrees(Math.atan2(v2y, v2x));
        float sweep = a2 - a1; if (sweep > 180) sweep -= 360; if (sweep < -180) sweep += 360;
        double deg = Math.abs(sweep);
        if (deg < 0.5 || Math.abs(deg - 180) < 0.5) return; // straight / degenerate — nothing to show
        if (reflex) { // take the major arc through the polygon interior
            sweep = sweep > 0 ? sweep - 360 : sweep + 360;
            deg = 360 - deg;
        }

        float radius = 38f / scale;
        double maxR = 0.42 * Math.min(l1, l2);
        if (radius > maxR) radius = (float) maxR;
        dimensionPaint.setStrokeWidth(2f / scale);
        RectF oval = new RectF((float) pivot.x - radius, (float) pivot.y - radius,
                (float) pivot.x + radius, (float) pivot.y + radius);
        canvas.drawArc(oval, a1, sweep, false, dimensionPaint);

        float labelR = radius + 24f / scale;
        float tx = (float) (pivot.x + labelR * Math.cos(Math.toRadians(a1 + sweep / 2)));
        float ty = (float) (pivot.y + labelR * Math.sin(Math.toRadians(a1 + sweep / 2)));
        textPaint.setTextSize(26f / scale);
        textPaint.setColor(colDimension);
        canvas.drawText(String.format("%.0f°", deg), tx, ty, textPaint);
        textPaint.setColor(colText);
    }

    /**
     * Mirrors the engine's circle test: a polygon is treated as a circle (buffer with many segments)
     * when it has more than 12 sides or is tagged "R:" by a dimension, and the WORKSPACE frame is also
     * excluded. Such shapes must NOT get a per-segment angle annotation.
     */
    private boolean isCircleLike(Geometry g) {
        Object ud = g.getUserData();
        if (ud != null) {
            String s = ud.toString().trim();
            if ("WORKSPACE".equals(s)) return true;
            if (s.toUpperCase(java.util.Locale.US).startsWith("R")) return true;
        }
        return Math.max(0, g.getCoordinates().length - 1) > 12;
    }

    /**
     * True if the user already added a manual angle annotation between these two lines. Matched by
     * COORDINATES, not reference: a parametric edit rebuilds line instances (so the annotation may hold
     * a stale object), and matching geometry keeps the auto layer from drawing a duplicate arc on top.
     */
    private boolean hasManualAnnotation(LineString a, LineString b) {
        for (CadEngine2d.AngleAnnotation ann : engine.getAngleAnnotations())
            if ((sameLine(ann.line1, a) && sameLine(ann.line2, b)) || (sameLine(ann.line1, b) && sameLine(ann.line2, a)))
                return true;
        return false;
    }

    /** Two lines are "the same" when their vertices coincide (within tolerance), regardless of instance. */
    private boolean sameLine(LineString p, LineString q) {
        if (p == q) return true;
        if (p == null || q == null) return false;
        Coordinate[] pc = p.getCoordinates(), qc = q.getCoordinates();
        if (pc.length != qc.length) return false;
        for (int i = 0; i < pc.length; i++) if (pc[i].distance(qc[i]) > 0.1) return false;
        return true;
    }

    /** The endpoint shared by two lines (within tolerance), or null if they don't meet at an end. */
    private Coordinate sharedEndpoint(Coordinate[] a, Coordinate[] b) {
        Coordinate[] ae = { a[0], a[a.length - 1] };
        Coordinate[] be = { b[0], b[b.length - 1] };
        for (Coordinate p : ae) for (Coordinate q : be) if (p.distance(q) < 0.1) return p;
        return null;
    }

    /** The vertex adjacent to {@code shared} along line {@code c} (gives the arm direction at the junction). */
    private Coordinate neighborOf(Coordinate[] c, Coordinate shared) {
        if (c[0].distance(shared) < 0.1) return c[1];
        if (c[c.length - 1].distance(shared) < 0.1) return c[c.length - 2];
        return c[1];
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
        if ("WORKSPACE".equals(geo.getUserData())) return;

        Coordinate[] coords = geo.getCoordinates(); Path path = new Path(); path.moveTo((float)coords[0].x, (float)coords[0].y);
        for (int i=1; i<coords.length; i++) path.lineTo((float)coords[i].x, (float)coords[i].y);
        if (geo instanceof Polygon) path.close();
        canvas.drawPath(path, p);
        for (Coordinate c : coords) canvas.drawCircle((float)c.x, (float)c.y, 6f/scale, vertexPaint);
    }
    public void setShowPointLabels(boolean show) {
        this.showPointLabels = show;
        invalidate();
    }
    public void resetView() {
        matrix.reset();
        scale = 1.0f;
        if (zoomListener != null) zoomListener.onZoomChanged(100);
        invalidate();
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
    public void setCurrentTool(String tool) { this.currentTool = tool; this.selectedGeometry = null; this.selectedSegmentIndex = -1; invalidate(); }
    public void setPreviewPoints(PointF s, PointF e) { this.previewStartPoint = s; this.previewEndPoint = e; invalidate(); }
    public void setActivePolyline(List<PointF> p) { this.activePolyline = p; invalidate(); }
    public int getZoomPercentage() { return (int)(scale * 100); }
}