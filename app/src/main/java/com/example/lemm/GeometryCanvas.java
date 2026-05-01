package com.example.lemm;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class GeometryCanvas extends View {
    public static final float VIRTUAL_WIDTH = 3000f;
    public static final float VIRTUAL_HEIGHT = 3000f;

    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 15.0f;

    private Stack<String> history = new Stack<>();
    private Stack<String> redoHistory = new Stack<>();

    private Paint linePaint, dashedPaint, centerlinePaint, textPaint, pointPaint, valuePaint, planePaint, gridPaint, borderPaint, glowPaint, anglePaint;
    private Matrix drawMatrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();

    private List<GeoPoint> pointsList = new ArrayList<>();
    private List<GeoCircle> circlesList = new ArrayList<>();
    private List<GeoLine> linesList = new ArrayList<>();
    private List<GeoPlane> planesList = new ArrayList<>();
    private List<GeoRect> rectsList = new ArrayList<>();
    private List<GeoArc> arcsList = new ArrayList<>();
    private List<GeoText> textsList = new ArrayList<>();
    private List<GeoAngle> anglesList = new ArrayList<>();

    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1.0f;
    private float posX = 0, posY = 0;
    private float lastTouchX, lastTouchY;
    private float rotationDegrees = 0f;

    private float gridSpacing = 100f;
    private int currentColor = Color.parseColor("#0C3D6A");

    private boolean snapToPoints = false;
    private boolean snapToGrid = false;
    private Object selectedObject = null;

    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }
    private OnZoomChangeListener zoomChangeListener;

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public interface GeoObject {}

    public static class GeoPoint implements GeoObject {
        public String label;
        public float x, y;
        public boolean isVertex;
        public int color;
        public GeoPoint(String l, float x, float y, int color) {
            this.label = l; this.x = x; this.y = y; this.color = color;
            this.isVertex = l == null || l.trim().isEmpty() || !l.trim().matches("^-?\\d*(\\.\\d+)?$");
        }
        public GeoPoint(String l, float x, float y, boolean isVertex) {
            this.label = l; this.x = x; this.y = y; this.isVertex = isVertex;
            this.color = Color.parseColor("#0C3D6A");
        }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); json.put("x", x); json.put("y", y);
            json.put("isVertex", isVertex); json.put("color", color);
            return json;
        }
    }

    public static class GeoCircle implements GeoObject {
        public String label; public float cx, cy, radius; public int color;
        GeoCircle(String l, float cx, float cy, float r, int color) { this.label = l; this.cx = cx; this.cy = cy; this.radius = r; this.color = color; }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); json.put("cx", cx); json.put("cy", cy); json.put("radius", radius); json.put("color", color);
            return json;
        }
    }

    public static class GeoRect implements GeoObject {
        public String label; public float left, top, right, bottom; public int color;
        GeoRect(String l, float left, float top, float right, float bottom, int color) { this.label = l; this.left = left; this.top = top; this.right = right; this.bottom = bottom; this.color = color; }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); json.put("left", left); json.put("top", top); json.put("right", right); json.put("bottom", bottom); json.put("color", color);
            return json;
        }
    }

    public static class GeoLine implements GeoObject {
        public String label; public GeoPoint p1, p2; public boolean isDashed = false; public int color;
        GeoLine(String l, GeoPoint p1, GeoPoint p2, boolean dashed, int color) { this.label = l; this.p1 = p1; this.p2 = p2; this.isDashed = dashed; this.color = color; }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); json.put("p1_label", p1.label); json.put("p2_label", p2.label); json.put("isDashed", isDashed); json.put("color", color);
            return json;
        }
    }

    public static class GeoArc implements GeoObject {
        public String label; public float cx, cy, radius, startAngle, sweepAngle; public int color;
        GeoArc(String l, float cx, float cy, float r, float s, float sw, int color) { this.label = l; this.cx = cx; this.cy = cy; this.radius = r; this.startAngle = s; this.sweepAngle = sw; this.color = color; }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); json.put("cx", cx); json.put("cy", cy); json.put("radius", radius); json.put("startAngle", startAngle); json.put("sweepAngle", sweepAngle); json.put("color", color);
            return json;
        }
    }

    public static class GeoPlane implements GeoObject {
        public String label; public List<GeoPoint> points; public int fillColor;
        GeoPlane(String l, List<GeoPoint> points, int color) {
            this.label = l; this.points = new ArrayList<>(points);
            this.fillColor = Color.argb(45, Color.red(color), Color.green(color), Color.blue(color));
        }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label); JSONArray pointLabels = new JSONArray();
            for (GeoPoint p : points) pointLabels.put(p.label);
            json.put("point_labels", pointLabels); json.put("fillColor", fillColor);
            return json;
        }
    }

    public static class GeoText implements GeoObject {
        public String text; public float x, y; public int color; public float size = 28f;
        GeoText(String t, float x, float y, int color) { this.text = t; this.x = x; this.y = y; this.color = color; }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("text", text); json.put("x", x); json.put("y", y); json.put("color", color); json.put("size", size);
            return json;
        }
    }

    public static class GeoAngle implements GeoObject {
        public GeoPoint center, p1, p2; public float radius; public boolean isRightAngle; public int color;
        GeoAngle(GeoPoint center, GeoPoint p1, GeoPoint p2, float radius, boolean right, int color) {
            this.center = center; this.p1 = p1; this.p2 = p2; this.radius = radius; this.isRightAngle = right; this.color = color;
        }
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("center_label", center.label); json.put("p1_label", p1.label); json.put("p2_label", p2.label); json.put("radius", radius); json.put("isRightAngle", isRightAngle); json.put("color", color);
            return json;
        }
    }

    public GeometryCanvas(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init(context); saveToHistory();
    }

    private void init(Context context) {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG); linePaint.setStrokeWidth(5f); linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeJoin(Paint.Join.ROUND); linePaint.setStrokeCap(Paint.Cap.ROUND);
        dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG); dashedPaint.setStrokeWidth(3.5f); dashedPaint.setStyle(Paint.Style.STROKE); dashedPaint.setPathEffect(new DashPathEffect(new float[]{15, 10}, 0));
        centerlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG); centerlinePaint.setColor(Color.parseColor("#78909C")); centerlinePaint.setStrokeWidth(2f); centerlinePaint.setStyle(Paint.Style.STROKE); centerlinePaint.setPathEffect(new DashPathEffect(new float[]{30, 15, 5, 15}, 0));
        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG); pointPaint.setStyle(Paint.Style.FILL);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG); textPaint.setTextSize(32f); textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); textPaint.setShadowLayer(3f, 2f, 2f, Color.argb(120, 0, 0, 0));
        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG); valuePaint.setColor(Color.BLACK); valuePaint.setTextSize(24f);
        planePaint = new Paint(Paint.ANTI_ALIAS_FLAG); planePaint.setStyle(Paint.Style.FILL);
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG); gridPaint.setColor(Color.parseColor("#F5F5F5")); gridPaint.setStrokeWidth(2f);
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG); borderPaint.setColor(Color.parseColor("#E0E0E0")); borderPaint.setStyle(Paint.Style.STROKE); borderPaint.setStrokeWidth(8f);
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG); glowPaint.setStyle(Paint.Style.STROKE); glowPaint.setStrokeWidth(12f); glowPaint.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));
        anglePaint = new Paint(Paint.ANTI_ALIAS_FLAG); anglePaint.setStyle(Paint.Style.STROKE); anglePaint.setStrokeWidth(3f);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor(); float oldScale = scaleFactor;
                scaleFactor = Math.max(MIN_ZOOM, Math.min(scaleFactor * factor, MAX_ZOOM));
                float f = scaleFactor / oldScale;
                posX = detector.getFocusX() - f * (detector.getFocusX() - posX);
                posY = detector.getFocusY() - f * (detector.getFocusY() - posY);
                notifyZoom(); invalidate(); return true;
            }
        });
        post(() -> { posX = getWidth()/2f - VIRTUAL_WIDTH/2f * scaleFactor; posY = getHeight()/2f - VIRTUAL_HEIGHT/2f * scaleFactor; invalidate(); });
    }

    public void setSnapToPoints(boolean snap) { this.snapToPoints = snap; invalidate(); }
    public void setSnapToGrid(boolean snap) { this.snapToGrid = snap; invalidate(); }

    public PointF getSnappedPoint(float x, float y) {
        float ix = screenToInternalX(x), iy = screenToInternalY(y);
        float ox = ix, oy = iy;
        if (snapToGrid) { ox = Math.round(ix / gridSpacing) * gridSpacing; oy = Math.round(iy / gridSpacing) * gridSpacing; }
        if (snapToPoints) {
            float minDist = Float.MAX_VALUE; GeoPoint closest = null;
            for (GeoPoint p : pointsList) {
                float d = (float) Math.hypot(p.x - ix, p.y - iy);
                if (d < 25f / scaleFactor && d < minDist) { minDist = d; closest = p; }
            }
            if (closest != null) { ox = closest.x; oy = closest.y; }
        }
        return new PointF(ox, oy);
    }

    public Object findObjectAt(float x, float y) {
        float ix = screenToInternalX(x), iy = screenToInternalY(y);
        float tol = 30f / scaleFactor;
        for (GeoPoint p : pointsList) if (Math.hypot(p.x - ix, p.y - iy) < tol) return p;
        for (GeoCircle c : circlesList) if (Math.abs(Math.hypot(c.cx - ix, c.cy - iy) - c.radius) < tol) return c;
        for (GeoLine l : linesList) if (distToLine(ix, iy, l.p1.x, l.p1.y, l.p2.x, l.p2.y) < tol) return l;
        for (GeoRect r : rectsList) if (distToRect(ix, iy, r) < tol) return r;
        for (GeoArc a : arcsList) if (Math.abs(Math.hypot(a.cx - ix, a.cy - iy) - a.radius) < tol) return a;
        return null;
    }

    private float distToLine(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1; if (dx == 0 && dy == 0) return (float) Math.hypot(px - x1, py - y1);
        float t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        return (float) Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private float distToRect(float px, float py, GeoRect r) {
        float d1 = Math.abs(px - r.left), d2 = Math.abs(px - r.right), d3 = Math.abs(py - r.top), d4 = Math.abs(py - r.bottom);
        boolean inX = px >= r.left && px <= r.right, inY = py >= r.top && py <= r.bottom;
        if (inX && inY) return Math.min(Math.min(d1, d2), Math.min(d3, d4));
        if (inX) return Math.min(d3, d4); if (inY) return Math.min(d1, d2);
        return Math.min(Math.min((float)Math.hypot(px-r.left, py-r.top), (float)Math.hypot(px-r.right, py-r.top)), Math.min((float)Math.hypot(px-r.left, py-r.bottom), (float)Math.hypot(px-r.right, py-r.bottom)));
    }

    public void setSelectedObject(Object obj) { this.selectedObject = obj; invalidate(); }

    public void updateSelected(float dx, float dy) {
        if (selectedObject == null) return;
        if (selectedObject instanceof GeoPoint) { GeoPoint p = (GeoPoint) selectedObject; p.x += dx; p.y += dy; }
        else if (selectedObject instanceof GeoCircle) { GeoCircle c = (GeoCircle) selectedObject; c.cx += dx; c.cy += dy; }
        else if (selectedObject instanceof GeoRect) { GeoRect r = (GeoRect) selectedObject; r.left += dx; r.top += dy; r.right += dx; r.bottom += dy; }
        else if (selectedObject instanceof GeoLine) { GeoLine l = (GeoLine) selectedObject; l.p1.x += dx; l.p1.y += dy; l.p2.x += dx; l.p2.y += dy; }
        else if (selectedObject instanceof GeoArc) { GeoArc a = (GeoArc) selectedObject; a.cx += dx; a.cy += dy; }
        else if (selectedObject instanceof GeoText) { GeoText t = (GeoText) selectedObject; t.x += dx; t.y += dy; }
        invalidate();
    }

    public void deleteSelected() {
        if (selectedObject == null) return;
        if (selectedObject instanceof GeoPoint) pointsList.remove(selectedObject);
        else if (selectedObject instanceof GeoCircle) circlesList.remove(selectedObject);
        else if (selectedObject instanceof GeoLine) linesList.remove(selectedObject);
        else if (selectedObject instanceof GeoPlane) planesList.remove(selectedObject);
        else if (selectedObject instanceof GeoRect) rectsList.remove(selectedObject);
        else if (selectedObject instanceof GeoArc) arcsList.remove(selectedObject);
        else if (selectedObject instanceof GeoText) textsList.remove(selectedObject);
        else if (selectedObject instanceof GeoAngle) anglesList.remove(selectedObject);
        selectedObject = null; invalidate();
    }

    public void saveToHistory() {
        redoHistory.clear(); history.push(getDrawingData()); if (history.size() > 50) history.remove(0);
    }

    public void undo() {
        if (history.size() > 1) { redoHistory.push(history.pop()); setDrawingData(history.peek()); invalidate(); }
    }

    public String getDrawingData() {
        try {
            JSONObject root = new JSONObject();
            JSONArray pts = new JSONArray(); for (GeoPoint p : pointsList) pts.put(p.toJson()); root.put("points", pts);
            JSONArray circs = new JSONArray(); for (GeoCircle c : circlesList) circs.put(c.toJson()); root.put("circles", circs);
            JSONArray lns = new JSONArray(); for (GeoLine l : linesList) lns.put(l.toJson()); root.put("lines", lns);
            JSONArray plns = new JSONArray(); for (GeoPlane p : planesList) plns.put(p.toJson()); root.put("planes", plns);
            JSONArray rcts = new JSONArray(); for (GeoRect r : rectsList) rcts.put(r.toJson()); root.put("rects", rcts);
            JSONArray arcs = new JSONArray(); for (GeoArc a : arcsList) arcs.put(a.toJson()); root.put("arcs", arcs);
            JSONArray txts = new JSONArray(); for (GeoText t : textsList) txts.put(t.toJson()); root.put("texts", txts);
            JSONArray angs = new JSONArray(); for (GeoAngle a : anglesList) angs.put(a.toJson()); root.put("angles", angs);
            JSONObject cs = new JSONObject(); cs.put("scaleFactor", scaleFactor); cs.put("posX", posX); cs.put("posY", posY); cs.put("rotationDegrees", rotationDegrees); cs.put("currentColor", currentColor);
            root.put("canvasState", cs); return root.toString();
        } catch (JSONException e) { return ""; }
    }

    public void setDrawingData(String data) {
        if (data == null || data.isEmpty()) return;
        try {
            pointsList.clear(); circlesList.clear(); linesList.clear(); planesList.clear(); rectsList.clear(); arcsList.clear(); textsList.clear(); anglesList.clear();
            JSONObject root = new JSONObject(data);
            JSONArray pts = root.optJSONArray("points");
            if (pts != null) for (int i=0; i<pts.length(); i++) { JSONObject j = pts.getJSONObject(i); pointsList.add(new GeoPoint(j.getString("label"), (float)j.getDouble("x"), (float)j.getDouble("y"), j.getInt("color"))); }
            JSONArray circs = root.optJSONArray("circles");
            if (circs != null) for (int i=0; i<circs.length(); i++) { JSONObject j = circs.getJSONObject(i); circlesList.add(new GeoCircle(j.getString("label"), (float)j.getDouble("cx"), (float)j.getDouble("cy"), (float)j.getDouble("radius"), j.getInt("color"))); }
            JSONArray lns = root.optJSONArray("lines");
            if (lns != null) for (int i=0; i<lns.length(); i++) { JSONObject j = lns.getJSONObject(i); GeoPoint p1 = findPoint(j.getString("p1_label")), p2 = findPoint(j.getString("p2_label")); if (p1!=null && p2!=null) linesList.add(new GeoLine(j.getString("label"), p1, p2, j.getBoolean("isDashed"), j.getInt("color"))); }
            JSONArray plns = root.optJSONArray("planes");
            if (plns != null) for (int i=0; i<plns.length(); i++) { JSONObject j = plns.getJSONObject(i); JSONArray pl = j.getJSONArray("point_labels"); List<GeoPoint> l = new ArrayList<>(); for (int k=0; k<pl.length(); k++) { GeoPoint p = findPoint(pl.getString(k)); if (p!=null) l.add(p); } if (!l.isEmpty()) planesList.add(new GeoPlane(j.getString("label"), l, j.getInt("fillColor"))); }
            JSONArray rcts = root.optJSONArray("rects");
            if (rcts != null) for (int i=0; i<rcts.length(); i++) { JSONObject j = rcts.getJSONObject(i); rectsList.add(new GeoRect(j.getString("label"), (float)j.getDouble("left"), (float)j.getDouble("top"), (float)j.getDouble("right"), (float)j.getDouble("bottom"), j.getInt("color"))); }
            JSONArray arcs = root.optJSONArray("arcs");
            if (arcs != null) for (int i=0; i<arcs.length(); i++) { JSONObject j = arcs.getJSONObject(i); arcsList.add(new GeoArc(j.getString("label"), (float)j.getDouble("cx"), (float)j.getDouble("cy"), (float)j.getDouble("radius"), (float)j.getDouble("startAngle"), (float)j.getDouble("sweepAngle"), j.getInt("color"))); }
            JSONArray txts = root.optJSONArray("texts");
            if (txts != null) for (int i=0; i<txts.length(); i++) { JSONObject j = txts.getJSONObject(i); textsList.add(new GeoText(j.getString("text"), (float)j.getDouble("x"), (float)j.getDouble("y"), j.getInt("color"))); }
            JSONArray angs = root.optJSONArray("angles");
            if (angs != null) for (int i=0; i<angs.length(); i++) { JSONObject j = angs.getJSONObject(i); GeoPoint c = findPoint(j.getString("center_label")), p1 = findPoint(j.getString("p1_label")), p2 = findPoint(j.getString("p2_label")); if (c!=null && p1!=null && p2!=null) anglesList.add(new GeoAngle(c, p1, p2, (float)j.getDouble("radius"), j.getBoolean("isRightAngle"), j.getInt("color"))); }
            JSONObject cs = root.optJSONObject("canvasState");
            if (cs != null) { scaleFactor = (float)cs.getDouble("scaleFactor"); posX = (float)cs.getDouble("posX"); posY = (float)cs.getDouble("posY"); rotationDegrees = (float)cs.getDouble("rotationDegrees"); currentColor = cs.getInt("currentColor"); }
            notifyZoom(); invalidate();
        } catch (JSONException e) {}
    }

    public void setCurrentColor(String hex) { try { currentColor = Color.parseColor(hex); } catch (Exception e) { currentColor = Color.parseColor("#0C3D6A"); } saveToHistory(); invalidate(); }
    public void rotateCanvas(float degrees) { this.rotationDegrees += degrees; saveToHistory(); invalidate(); }
    public void addPoint(String name, float x, float y) { pointsList.add(new GeoPoint(name, x, y, currentColor)); saveToHistory(); invalidate(); }
    public GeoPoint addPointAndReturn(String name, float x, float y) { GeoPoint p = new GeoPoint(name, x, y, currentColor); pointsList.add(p); saveToHistory(); invalidate(); return p; }
    public GeoPoint findPoint(String label) { for (GeoPoint p : pointsList) if (p.label != null && p.label.equalsIgnoreCase(label)) return p; return null; }
    public void addCircle(String l, float cx, float cy, float r) { circlesList.add(new GeoCircle(l, cx, cy, r, currentColor)); saveToHistory(); invalidate(); }
    public void addRect(String l, float left, float top, float right, float bottom) { rectsList.add(new GeoRect(l, left, top, right, bottom, currentColor)); saveToHistory(); invalidate(); }
    public void addLine(String l, GeoPoint p1, GeoPoint p2, boolean d) { linesList.add(new GeoLine(l, p1, p2, d, currentColor)); saveToHistory(); invalidate(); }
    public void addPolygon(String l, List<GeoPoint> points) { planesList.add(new GeoPlane(l, points, currentColor)); saveToHistory(); invalidate(); }
    public void addArc(String l, float cx, float cy, float r, float s, float sw) { arcsList.add(new GeoArc(l, cx, cy, r, s, sw, currentColor)); saveToHistory(); invalidate(); }
    public void addAngle(GeoPoint c, GeoPoint p1, GeoPoint p2, float r, boolean right) { anglesList.add(new GeoAngle(c, p1, p2, r, right, currentColor)); saveToHistory(); invalidate(); }
    public void clearPoints() { pointsList.clear(); circlesList.clear(); linesList.clear(); planesList.clear(); rectsList.clear(); arcsList.clear(); textsList.clear(); anglesList.clear(); history.clear(); redoHistory.clear(); scaleFactor = 1.0f; rotationDegrees = 0f; posX = getWidth()/2f - VIRTUAL_WIDTH/2f; posY = getHeight()/2f - VIRTUAL_HEIGHT/2f; currentColor = Color.parseColor("#0C3D6A"); notifyZoom(); saveToHistory(); invalidate(); }
    public void zoomIn() { float old = scaleFactor; scaleFactor = Math.min(scaleFactor * 1.25f, MAX_ZOOM); float f = scaleFactor/old; posX = getWidth()/2f - f*(getWidth()/2f-posX); posY = getHeight()/2f - f*(getHeight()/2f-posY); notifyZoom(); saveToHistory(); invalidate(); }
    public void zoomOut() { float old = scaleFactor; scaleFactor = Math.max(MIN_ZOOM, scaleFactor / 1.25f); float f = scaleFactor/old; posX = getWidth()/2f - f*(getWidth()/2f-posX); posY = getHeight()/2f - f*(getHeight()/2f-posY); notifyZoom(); saveToHistory(); invalidate(); }
    public int getZoomPercentage() { return Math.round(scaleFactor * 100); }
    private void notifyZoom() { if (zoomChangeListener != null) zoomChangeListener.onZoomChanged(getZoomPercentage()); }
    public Bitmap getBitmap() { if (getWidth() <= 0 || getHeight() <= 0) return null; Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b); draw(c); return b; }
    public float screenToInternalX(float sx) { float[] pts = {sx, 0}; updateMatrices(); inverseMatrix.mapPoints(pts); return pts[0]; }
    public float screenToInternalY(float sy) { float[] pts = {0, sy}; updateMatrices(); inverseMatrix.mapPoints(pts); return pts[1]; }
    private void updateMatrices() { drawMatrix.reset(); drawMatrix.postTranslate(-VIRTUAL_WIDTH/2f, -VIRTUAL_HEIGHT/2f); drawMatrix.postRotate(rotationDegrees); drawMatrix.postTranslate(VIRTUAL_WIDTH/2f, VIRTUAL_HEIGHT/2f); drawMatrix.postScale(scaleFactor, scaleFactor); drawMatrix.postTranslate(posX, posY); drawMatrix.invert(inverseMatrix); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e); float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastTouchX = x; lastTouchY = y; getParent().requestDisallowInterceptTouchEvent(true); break;
            case MotionEvent.ACTION_MOVE: if (!scaleDetector.isInProgress()) { posX += (x - lastTouchX); posY += (y - lastTouchY); invalidate(); } lastTouchX = x; lastTouchY = y; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: getParent().requestDisallowInterceptTouchEvent(false); performClick(); saveToHistory(); break;
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); updateMatrices(); canvas.save(); canvas.drawColor(Color.WHITE); canvas.setMatrix(drawMatrix);
        float l = screenToInternalX(0), r = screenToInternalX(getWidth()), t = screenToInternalY(0), b = screenToInternalY(getHeight());
        if (gridSpacing * scaleFactor > 20) { for (float x = (float)Math.floor(l/gridSpacing)*gridSpacing; x <= r; x += gridSpacing) canvas.drawLine(x, t, x, b, gridPaint); for (float y = (float)Math.floor(t/gridSpacing)*gridSpacing; y <= b; y += gridSpacing) canvas.drawLine(l, y, r, y, gridPaint); }
        for (GeoPlane p : planesList) { Path path = new Path(); boolean first = true; for (GeoPoint pt : p.points) { if (first) { path.moveTo(pt.x, pt.y); first = false; } else path.lineTo(pt.x, pt.y); } path.close(); planePaint.setColor(p.fillColor); canvas.drawPath(path, planePaint); }
        for (GeoLine li : linesList) { linePaint.setColor(li.color); dashedPaint.setColor(li.color); canvas.drawLine(li.p1.x, li.p1.y, li.p2.x, li.p2.y, li.isDashed ? dashedPaint : linePaint); }
        for (GeoCircle c : circlesList) { linePaint.setColor(c.color); canvas.drawCircle(c.cx, c.cy, c.radius, linePaint); }
        for (GeoRect re : rectsList) { linePaint.setColor(re.color); canvas.drawRect(re.left, re.top, re.right, re.bottom, linePaint); }
        for (GeoArc a : arcsList) { linePaint.setColor(a.color); canvas.drawArc(new RectF(a.cx-a.radius, a.cy-a.radius, a.cx+a.radius, a.cy+a.radius), a.startAngle, a.sweepAngle, false, linePaint); }
        for (GeoPoint p : pointsList) {
            if (p.isVertex) { glowPaint.setColor(p.color); canvas.drawCircle(p.x, p.y, 10f / scaleFactor, glowPaint); pointPaint.setColor(p.color); canvas.drawCircle(p.x, p.y, 7f / scaleFactor, pointPaint);
                if (p.label != null && !p.label.isEmpty()) { textPaint.setColor(Color.parseColor("#D32F2F")); textPaint.setTextSize(32f / scaleFactor); canvas.drawText(p.label, p.x + 12f/scaleFactor, p.y - 12f/scaleFactor, textPaint); }
            } else if (p.label != null && !p.label.isEmpty()) { valuePaint.setColor(p.color); valuePaint.setTextSize(24f / scaleFactor); canvas.drawText(p.label, p.x, p.y, valuePaint); }
        }
        canvas.drawRect(0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, borderPaint); canvas.restore();
    }
}
