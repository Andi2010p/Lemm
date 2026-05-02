// D:/codes/Homeworks.Uwc/Lemm/app/src/main/java/com/example/lemm/GeometryCanvas.java
package com.example.lemm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GeometryCanvas extends View {
    private List<GeometricObject> geometricObjects = new ArrayList<>();
    private List<String> history = new ArrayList<>();
    private Paint paint;
    private Matrix transformMatrix = new Matrix();
    private float currentZoom = 1.0f;
    private float translateX = 0, translateY = 0;
    private GeometricObject selectedObject = null;
    private OnZoomChangeListener zoomChangeListener;
    private List<GeoPoint> points = new ArrayList<>();
    private boolean snapToPoints = false;
    private boolean snapToGrid = false;
    private float gridSize = 50f;
    private String currentTool = "MOVE";

    public GeometryCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(5);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        saveToHistory();
    }

    public GeoPoint findPoint(String name) {
        if (name == null) return null;

        // Iterate through your points list (ensure 'points' is the correct variable name)
        for (GeoPoint p : points) {
            if (name.equalsIgnoreCase(p.label)) { // or p.getName() depending on your GeoPoint class
                return p;
            }
        }
        return null; // Return null if the point isn't found
    }
    public static abstract class GeometricObject {
        public abstract void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom);
        public abstract boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance);
        public abstract void move(float dx, float dy);
        public abstract String toJson();
        public abstract String getLabel();
    }

    public static class GeoPoint extends GeometricObject {
        public float x, y;
        public String label;
        public boolean isCenterlinePoint;

        public GeoPoint(String label, float x, float y, boolean isCenterlinePoint) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.isCenterlinePoint = isCenterlinePoint;
        }

        @Override
        public void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom) {
            float[] point = {x, y};
            matrix.mapPoints(point);
            canvas.drawCircle(point[0], point[1], 8 / zoom, paint);
            if (label != null && !label.isEmpty()) {
                paint.setTextSize(24 / zoom);
                canvas.drawText(label, point[0] + 10 / zoom, point[1] - 10 / zoom, paint);
            }
        }
        @Override
        public boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance) {
            float[] mappedPoint = {x, y};
            inverseMatrix.mapPoints(mappedPoint);
            return Math.hypot(mappedPoint[0] - this.x, mappedPoint[1] - this.y) < tolerance;
        }

        @Override
        public void move(float dx, float dy) {
            this.x += dx;
            this.y += dy;
        }

        @Override
        public String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "point");
                json.put("label", label);
                json.put("x", x);
                json.put("y", y);
                json.put("isCenterlinePoint", isCenterlinePoint);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @Override
        public String getLabel() { return label; }
    }

    private float rotationDegrees = 0f; // Variable to store the current rotation
    public void rotateCanvas(float degrees) {
        this.rotationDegrees += degrees;
        // Keep the value within 0-360 for cleanliness, though not strictly required
        this.rotationDegrees %= 360;
        invalidate(); // Redraw the view with the new rotation
    }
    public static class GeoLine extends GeometricObject {
        public GeoPoint p1, p2;
        public String label;
        public boolean isCenterline;

        public GeoLine(String label, GeoPoint p1, GeoPoint p2, boolean isCenterline) {
            this.label = label;
            this.p1 = p1;
            this.p2 = p2;
            this.isCenterline = isCenterline;
        }

        @Override
        public void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom) {
            float[] pts = {p1.x, p1.y, p2.x, p2.y};
            matrix.mapPoints(pts);

            Paint linePaint = new Paint(paint);
            if (isCenterline) {
                linePaint.setColor(Color.BLUE);
            }
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], linePaint);

            if (label != null && !label.isEmpty()) {
                linePaint.setTextSize(24 / zoom);
                float midX = (pts[0] + pts[2]) / 2;
                float midY = (pts[1] + pts[3]) / 2;
                canvas.drawText(label, midX + 10 / zoom, midY - 10 / zoom, linePaint);
            }
        }

        @Override
        public boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance) {
            float[] mappedPoint = {x, y};
            inverseMatrix.mapPoints(mappedPoint);
            float dx = p2.x - p1.x;
            float dy = p2.y - p1.y;
            float lengthSq = dx * dx + dy * dy;
            if (lengthSq == 0) return Math.hypot(mappedPoint[0] - p1.x, mappedPoint[1] - p1.y) < tolerance;
            float t = ((mappedPoint[0] - p1.x) * dx + (mappedPoint[1] - p1.y) * dy) / lengthSq;
            t = Math.max(0, Math.min(1, t));
            float closestX = p1.x + t * dx;
            float closestY = p1.y + t * dy;
            return Math.hypot(mappedPoint[0] - closestX, mappedPoint[1] - closestY) < tolerance;
        }

        @Override
        public void move(float dx, float dy) {
            p1.move(dx, dy);
            p2.move(dx, dy);
        }

        @Override
        public String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "line");
                json.put("label", label);
                json.put("x1", p1.x);
                json.put("y1", p1.y);
                json.put("x2", p2.x);
                json.put("y2", p2.y);
                json.put("isCenterline", isCenterline);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @Override
        public String getLabel() { return label; }
    }

    public static class GeoCircle extends GeometricObject {
        public float centerX, centerY, radius;
        public String label;

        public GeoCircle(String label, float centerX, float centerY, float radius) {
            this.label = label;
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }

        @Override
        public void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom) {
            float[] center = {centerX, centerY};
            matrix.mapPoints(center);
            float scaledRadius = radius * zoom;
            canvas.drawCircle(center[0], center[1], scaledRadius, paint);

            if (label != null && !label.isEmpty()) {
                paint.setTextSize(24 / zoom);
                canvas.drawText(label, center[0] + scaledRadius + 10 / zoom, center[1], paint);
            }
        }

        @Override
        public boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance) {
            float[] mappedPoint = {x, y};
            inverseMatrix.mapPoints(mappedPoint);
            return Math.abs(Math.hypot(mappedPoint[0] - centerX, mappedPoint[1] - centerY) - radius) < tolerance;
        }

        @Override
        public void move(float dx, float dy) {
            this.centerX += dx;
            this.centerY += dy;
        }

        @Override
        public String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "circle");
                json.put("label", label);
                json.put("centerX", centerX);
                json.put("centerY", centerY);
                json.put("radius", radius);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @Override
        public String getLabel() { return label; }
    }

    public static class GeoRect extends GeometricObject {
        public float left, top, right, bottom;
        public String label;

        public GeoRect(String label, float left, float top, float right, float bottom) {
            this.label = label;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom) {
            float[] coords = {left, top, right, bottom};
            matrix.mapPoints(coords);
            canvas.drawRect(coords[0], coords[1], coords[2], coords[3], paint);
            if (label != null && !label.isEmpty()) {
                paint.setTextSize(24 / zoom);
                canvas.drawText(label, coords[0], coords[1] - 10 / zoom, paint);
            }
        }

        @Override
        public boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance) {
            float[] mappedPoint = {x, y};
            inverseMatrix.mapPoints(mappedPoint);
            return mappedPoint[0] >= (left - tolerance) && mappedPoint[0] <= (right + tolerance) &&
                    mappedPoint[1] >= (top - tolerance) && mappedPoint[1] <= (bottom + tolerance);
        }

        @Override
        public void move(float dx, float dy) {
            this.left += dx;
            this.top += dy;
            this.right += dx;
            this.bottom += dy;
        }

        @Override
        public String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "rect");
                json.put("label", label);
                json.put("left", left);
                json.put("top", top);
                json.put("right", right);
                json.put("bottom", bottom);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @Override
        public String getLabel() { return label; }
    }

    public static class GeoArc extends GeometricObject {
        public float centerX, centerY, radius, startAngle, sweepAngle;
        public String label;

        public GeoArc(String label, float centerX, float centerY, float radius, float startAngle, float sweepAngle) {
            this.label = label;
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
            this.startAngle = startAngle;
            this.sweepAngle = sweepAngle;
        }

        @Override
        public void draw(Canvas canvas, Paint paint, Matrix matrix, float zoom) {
            float[] center = {centerX, centerY};
            matrix.mapPoints(center);
            float scaledRadius = radius * zoom;
            canvas.drawArc(center[0] - scaledRadius, center[1] - scaledRadius,
                    center[0] + scaledRadius, center[1] + scaledRadius,
                    startAngle, sweepAngle, false, paint);
            if (label != null && !label.isEmpty()) {
                paint.setTextSize(24 / zoom);
                canvas.drawText(label, center[0] + scaledRadius + 10 / zoom, center[1], paint);
            }
        }

        @Override
        public boolean hitTest(float x, float y, Matrix inverseMatrix, float tolerance) {
            float[] mappedPoint = {x, y};
            inverseMatrix.mapPoints(mappedPoint);
            double distFromCenter = Math.hypot(mappedPoint[0] - centerX, mappedPoint[1] - centerY);
            if (Math.abs(distFromCenter - radius) > tolerance) return false;
            float angle = (float) Math.toDegrees(Math.atan2(mappedPoint[1] - centerY, mappedPoint[0] - centerX));
            if (angle < 0) angle += 360;
            float normalizedStart = startAngle % 360;
            float normalizedEnd = (startAngle + sweepAngle) % 360;
            if (normalizedEnd < normalizedStart) return (angle >= normalizedStart || angle <= normalizedEnd);
            else return (angle >= normalizedStart && angle <= normalizedEnd);
        }

        @Override
        public void move(float dx, float dy) {
            this.centerX += dx;
            this.centerY += dy;
        }

        @Override
        public String toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("type", "arc");
                json.put("label", label);
                json.put("centerX", centerX);
                json.put("centerY", centerY);
                json.put("radius", radius);
                json.put("startAngle", startAngle);
                json.put("sweepAngle", sweepAngle);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json.toString();
        }

        @Override
        public String getLabel() { return label; }
    }

    public GeoPoint addPointAndReturn(String label, float x, float y) {
        GeoPoint point = new GeoPoint(label, x, y, false);
        geometricObjects.add(point);
        saveToHistory();
        invalidate();
        return point;
    }

    public void addPoint(String label, float x, float y) {
        geometricObjects.add(new GeoPoint(label, x, y, false));
        saveToHistory();
        invalidate();
    }

    public void addLine(String label, GeoPoint p1, GeoPoint p2, boolean isCenterline) {
        geometricObjects.add(new GeoLine(label, p1, p2, isCenterline));
        saveToHistory();
        invalidate();
    }

    public void addCircle(String label, float x, float y, float r) {
        geometricObjects.add(new GeoCircle(label, x, y, r));
        saveToHistory();
        invalidate();
    }

    public void addRect(String label, float left, float top, float right, float bottom) {
        geometricObjects.add(new GeoRect(label, left, top, right, bottom));
        saveToHistory();
        invalidate();
    }

    public void addArc(String label, float centerX, float centerY, float radius, float startAngle, float sweepAngle) {
        geometricObjects.add(new GeoArc(label, centerX, centerY, radius, startAngle, sweepAngle));
        saveToHistory();
        invalidate();
    }

    public void setDrawingData(String data) {
        geometricObjects.clear();
        if (data == null || data.isEmpty()) {
            invalidate();
            saveToHistory();
            return;
        }
        try {
            JSONArray jsonArray = new JSONArray(data);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject objJson = jsonArray.getJSONObject(i);
                String type = objJson.getString("type");
                switch (type) {
                    case "point":
                        geometricObjects.add(new GeoPoint(objJson.optString("label", ""), (float) objJson.getDouble("x"), (float) objJson.getDouble("y"), objJson.optBoolean("isCenterlinePoint", false)));
                        break;
                    case "line":
                        GeoPoint p1 = new GeoPoint("", (float) objJson.getDouble("x1"), (float) objJson.getDouble("y1"), false);
                        GeoPoint p2 = new GeoPoint("", (float) objJson.getDouble("x2"), (float) objJson.getDouble("y2"), false);
                        geometricObjects.add(new GeoLine(objJson.optString("label", ""), p1, p2, objJson.optBoolean("isCenterline", false)));
                        break;
                    case "circle":
                        geometricObjects.add(new GeoCircle(objJson.optString("label", ""), (float) objJson.getDouble("centerX"), (float) objJson.getDouble("centerY"), (float) objJson.getDouble("radius")));
                        break;
                    case "rect":
                        geometricObjects.add(new GeoRect(objJson.optString("label", ""), (float) objJson.getDouble("left"), (float) objJson.getDouble("top"), (float) objJson.getDouble("right"), (float) objJson.getDouble("bottom")));
                        break;
                    case "arc":
                        geometricObjects.add(new GeoArc(objJson.optString("label", ""), (float) objJson.getDouble("centerX"), (float) objJson.getDouble("centerY"), (float) objJson.getDouble("radius"), (float) objJson.getDouble("startAngle"), (float) objJson.getDouble("sweepAngle")));
                        break;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        saveToHistory();
        invalidate();
    }

    public String getDrawingData() {
        JSONArray jsonArray = new JSONArray();
        for (GeometricObject obj : geometricObjects) {
            try {
                jsonArray.put(new JSONObject(obj.toJson()));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return jsonArray.toString();
    }

    public void clearPoints() {
        geometricObjects.clear();
        saveToHistory();
        invalidate();
    }

    public void undo() {
        if (history.size() > 1) {
            history.remove(history.size() - 1);
            String previousState = history.get(history.size() - 1);
            setDrawingDataInternal(previousState);
            invalidate();
        } else if (history.size() == 1) {
            history.clear();
            geometricObjects.clear();
            saveToHistory();
            invalidate();
        }
    }

    private void setDrawingDataInternal(String data) {
        geometricObjects.clear();
        if (data == null || data.isEmpty()) {
            invalidate();
            return;
        }
        try {
            JSONArray jsonArray = new JSONArray(data);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject objJson = jsonArray.getJSONObject(i);
                String type = objJson.getString("type");
                switch (type) {
                    case "point":
                        geometricObjects.add(new GeoPoint(objJson.optString("label", ""), (float) objJson.getDouble("x"), (float) objJson.getDouble("y"), objJson.optBoolean("isCenterlinePoint", false)));
                        break;
                    case "line":
                        GeoPoint p1 = new GeoPoint("", (float) objJson.getDouble("x1"), (float) objJson.getDouble("y1"), false);
                        GeoPoint p2 = new GeoPoint("", (float) objJson.getDouble("x2"), (float) objJson.getDouble("y2"), false);
                        geometricObjects.add(new GeoLine(objJson.optString("label", ""), p1, p2, objJson.optBoolean("isCenterline", false)));
                        break;
                    case "circle":
                        geometricObjects.add(new GeoCircle(objJson.optString("label", ""), (float) objJson.getDouble("centerX"), (float) objJson.getDouble("centerY"), (float) objJson.getDouble("radius")));
                        break;
                    case "rect":
                        geometricObjects.add(new GeoRect(objJson.optString("label", ""), (float) objJson.getDouble("left"), (float) objJson.getDouble("top"), (float) objJson.getDouble("right"), (float) objJson.getDouble("bottom")));
                        break;
                    case "arc":
                        geometricObjects.add(new GeoArc(objJson.optString("label", ""), (float) objJson.getDouble("centerX"), (float) objJson.getDouble("centerY"), (float) objJson.getDouble("radius"), (float) objJson.getDouble("startAngle"), (float) objJson.getDouble("sweepAngle")));
                        break;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        invalidate();
    }

    public void saveToHistory() {
        String currentData = getDrawingData();
        if (history.isEmpty() || !history.get(history.size() - 1).equals(currentData)) {
            history.add(currentData);
            if (history.size() > 20) {
                history.remove(0);
            }
        }
    }

    public void zoomIn() {
        if (currentZoom < 5.0f) {
            currentZoom *= 1.1f;
            updateTransformMatrix();
            invalidate();
            if (zoomChangeListener != null) zoomChangeListener.onZoomChanged(getZoomPercentage());
        }
    }

    public void zoomOut() {
        if (currentZoom > 0.2f) {
            currentZoom /= 1.1f;
            updateTransformMatrix();
            invalidate();
            if (zoomChangeListener != null) zoomChangeListener.onZoomChanged(getZoomPercentage());
        }
    }

    public int getZoomPercentage() {
        return (int) (currentZoom * 100);
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public interface OnZoomChangeListener {
        void onZoomChanged(int percentage);
    }

    private void updateTransformMatrix() {
        transformMatrix.reset();
        transformMatrix.postScale(currentZoom, currentZoom);
        transformMatrix.postTranslate(translateX, translateY);
    }

    public PointF getSnappedPoint(float touchX, float touchY) {
        float[] canvasCoords = {touchX, touchY};
        Matrix inverseMatrix = new Matrix();
        transformMatrix.invert(inverseMatrix);
        inverseMatrix.mapPoints(canvasCoords);
        float internalX = canvasCoords[0];
        float internalY = canvasCoords[1];
        if (snapToPoints) {
            float minDistance = Float.MAX_VALUE;
            float closestX = internalX;
            float closestY = internalY;
            float snapTolerance = 20 / currentZoom;
            for (GeometricObject obj : geometricObjects) {
                if (obj instanceof GeoPoint) {
                    GeoPoint p = (GeoPoint) obj;
                    float dist = (float) Math.hypot(internalX - p.x, internalY - p.y);
                    if (dist < minDistance && dist < snapTolerance) {
                        minDistance = dist;
                        closestX = p.x;
                        closestY = p.y;
                    }
                }
            }
            internalX = closestX;
            internalY = closestY;
        }
        if (snapToGrid) {
            internalX = Math.round(internalX / gridSize) * gridSize;
            internalY = Math.round(internalY / gridSize) * gridSize;
        }
        return new PointF(internalX, internalY);
    }

    public void setSnapToPoints(boolean snapToPoints) {
        this.snapToPoints = snapToPoints;
        invalidate();
    }

    public void setSnapToGrid(boolean snapToGrid) {
        this.snapToGrid = snapToGrid;
        invalidate();
    }

    public Object findObjectAt(float x, float y) {
        Matrix inverseMatrix = new Matrix();
        transformMatrix.invert(inverseMatrix);
        float tolerance = 15 / currentZoom;
        for (int i = geometricObjects.size() - 1; i >= 0; i--) {
            GeometricObject obj = geometricObjects.get(i);
            if (obj.hitTest(x, y, inverseMatrix, tolerance)) {
                return obj;
            }
        }
        return null;
    }

    public void setSelectedObject(Object obj) {
        this.selectedObject = (GeometricObject) obj;
        invalidate();
    }

    public void updateSelected(float dx, float dy) {
        if (selectedObject != null) {
            selectedObject.move(dx, dy);
            invalidate();
        }
    }

    public void deleteSelected() {
        if (selectedObject != null) {
            geometricObjects.remove(selectedObject);
            selectedObject = null;
            invalidate();
        }
    }

    public Bitmap getBitmap() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        draw(canvas);
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.concat(transformMatrix);
        if (snapToGrid) {
            drawGrid(canvas);
        }
        canvas.rotate(rotationDegrees, getWidth() / 2f, getHeight() / 2f);
        for (GeometricObject obj : geometricObjects) {
            Paint objectPaint = new Paint(paint);
            objectPaint.setColor(Color.BLACK);
            if (obj == selectedObject) {
                objectPaint.setColor(Color.RED);
                objectPaint.setStrokeWidth(paint.getStrokeWidth() * 1.5f);
            }
            obj.draw(canvas, objectPaint, transformMatrix, currentZoom);
        }
        canvas.restore();
    }

    private void drawGrid(Canvas canvas) {
        Paint gridPaint = new Paint();
        gridPaint.setColor(Color.LTGRAY);
        gridPaint.setStrokeWidth(1);
        for (float x = 0; x < getWidth() / currentZoom; x += gridSize) {
            canvas.drawLine(x, 0, x, getHeight() / currentZoom, gridPaint);
        }
        for (float y = 0; y < getHeight() / currentZoom; y += gridSize) {
            canvas.drawLine(0, y, getWidth() / currentZoom, y, gridPaint);
        }
    }

    private PointF lastTouch = new PointF();
    private static final int INVALID_POINTER_ID = -1;
    private int activePointerId = INVALID_POINTER_ID;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final float x = event.getX();
                final float y = event.getY();
                lastTouch.set(x, y);
                activePointerId = event.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                break;
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() == 1 && currentTool.equals("MOVE")) {
                    final int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex != -1) {
                        final float x = event.getX(pointerIndex);
                        final float y = event.getY(pointerIndex);
                        final float dx = x - lastTouch.x;
                        final float dy = y - lastTouch.y;
                        translateX += dx;
                        translateY += dy;
                        lastTouch.set(x, y);
                        updateTransformMatrix();
                        invalidate();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                activePointerId = INVALID_POINTER_ID;
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = (event.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                final int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    lastTouch.set(event.getX(newPointerIndex), event.getY(newPointerIndex));
                    activePointerId = event.getPointerId(newPointerIndex);
                }
                break;
            }
        }
        return true;
    }
}
