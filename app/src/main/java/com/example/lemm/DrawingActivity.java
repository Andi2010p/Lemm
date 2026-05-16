package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

import java.util.ArrayList;
import java.util.List;

public class DrawingActivity extends AppCompatActivity {
    private CadGeometryCanvas drawingCanvas;
    private CadEngine2d engine;
    private ScaleGestureDetector scaleDetector;
    private String currentTool = "MOVE";
    private boolean isOrthoMode = false;
    private int activePointerId = -1;
    private Coordinate firstPoint = null;
    private float lastX, lastY;
    private boolean isScaling = false;

    private List<Coordinate> activePolylinePoints = new ArrayList<>();
    private List<PointF> activePolylineDraw = new ArrayList<>();

    private DatabaseHelper dbHelper;
    private int editId = -1;

    private boolean isViewOnly = false;
    private View propertiesPanel;
    private TextView tvShapeInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing);

        engine = new CadEngine2d();
        dbHelper = new DatabaseHelper(this);

        initViews();
        setupGestures();
        setupHardwareBackButton();
        loadIntentData();
    }

    private void setupHardwareBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
    }

    private void loadIntentData() {
        if (getIntent().hasExtra("LOAD_DRAWING_DATA")) {
            String data = getIntent().getStringExtra("LOAD_DRAWING_DATA");
            deserializeDrawing(data);

            if (getIntent().hasExtra("EDIT_ID")) {
                editId = getIntent().getIntExtra("EDIT_ID", -1);
            }

            isViewOnly = getIntent().getBooleanExtra("IS_VIEW_ONLY", false);
            if (isViewOnly) {
                findViewById(R.id.toolbarCard).setVisibility(View.GONE);
                findViewById(R.id.bottomActions).setVisibility(View.GONE);
                findViewById(R.id.btnEditProperties).setVisibility(View.GONE);
            }
        }
    }

    private void confirmExit() {
        if (isViewOnly || engine.getGeometries().isEmpty()) {
            finish();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.save_drawing) + "?")
                    .setMessage("You have an active drawing. Do you want to save it before exiting?")
                    .setPositiveButton(getString(R.string.save), (dialog, which) -> showSaveDialog())
                    .setNegativeButton(getString(R.string.dont_save), (dialog, which) -> finish())
                    .setNeutralButton(getString(R.string.cancel), null)
                    .show();
        }
    }

    private void initViews() {
        drawingCanvas = findViewById(R.id.drawingCanvas);
        drawingCanvas.setEngine(engine);

        propertiesPanel = findViewById(R.id.propertiesPanel);
        tvShapeInfo = findViewById(R.id.tvShapeInfo);

        findViewById(R.id.btnEditProperties).setOnClickListener(v -> {
            if (drawingCanvas.getSelectedGeometry() != null) {
                promptForDimensions(drawingCanvas.getSelectedGeometry(), identifyGeometryType(drawingCanvas.getSelectedGeometry()));
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
        findViewById(R.id.btnToolMove).setOnClickListener(v -> selectTool("MOVE"));
        findViewById(R.id.btnToolSelect).setOnClickListener(v -> selectTool("SELECT"));
        findViewById(R.id.btnToolLine).setOnClickListener(v -> selectTool("LINE"));
        findViewById(R.id.btnToolPoly).setOnClickListener(v -> selectTool("POLYLINE"));
        findViewById(R.id.btnToolRect).setOnClickListener(v -> selectTool("RECT"));
        findViewById(R.id.btnToolCircle).setOnClickListener(v -> selectTool("CIRCLE"));

        ImageButton btnOrtho = findViewById(R.id.btnToolOrtho);
        btnOrtho.setOnClickListener(v -> {
            isOrthoMode = !isOrthoMode;
            int tintColor = isOrthoMode ? Color.parseColor("#E67E22") : Color.parseColor("#7F8C8D");
            btnOrtho.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
            Toast.makeText(this, isOrthoMode ? "Ortho Mode ON" : "Ortho Mode OFF", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnUndo).setOnClickListener(v -> { engine.undo(); resetPolyline(); drawingCanvas.invalidate(); });
        findViewById(R.id.btnToolClear).setOnClickListener(v -> { engine.clear(); resetPolyline(); drawingCanvas.invalidate(); });

        ImageButton btnToolSaveTop = findViewById(R.id.btnToolSaveTop);
        if (btnToolSaveTop != null) {
            btnToolSaveTop.setOnClickListener(v -> showSaveDialog());
            if (isViewOnly) btnToolSaveTop.setVisibility(View.GONE);
        }

        View btnDontSave = findViewById(R.id.btnDontSave);
        if (btnDontSave != null) btnDontSave.setOnClickListener(v -> finish());

        View btnSave = findViewById(R.id.btnSave);
        if (btnSave != null) btnSave.setOnClickListener(v -> showSaveDialog());

        TextView tvZoom = findViewById(R.id.tvZoomPercent);
        drawingCanvas.setOnZoomChangeListener(pct -> {
            if (tvZoom != null) tvZoom.setText(pct + "%");
        });

        findViewById(R.id.btnZoomIn).setOnClickListener(v -> drawingCanvas.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> drawingCanvas.zoomOut());
    }

    private void selectTool(String tool) {
        this.currentTool = tool;
        drawingCanvas.setCurrentTool(tool);
        firstPoint = null;
        propertiesPanel.setVisibility(View.GONE);
        resetPolyline();
    }

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                drawingCanvas.applyZoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });

        drawingCanvas.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                activePointerId = event.getPointerId(0);
                lastX = event.getX(); lastY = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                int pIdx = (event.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                if (event.getPointerId(pIdx) == activePointerId) {
                    int newIdx = pIdx == 0 ? 1 : 0;
                    lastX = event.getX(newIdx); lastY = event.getY(newIdx);
                    activePointerId = event.getPointerId(newIdx);
                }
            }

            // Block drawing while zooming
            if (scaleDetector.isInProgress() || event.getPointerCount() > 1) return true;

            int idx = event.findPointerIndex(activePointerId);
            if (idx == -1) return true;
            float currX = event.getX(idx); float currY = event.getY(idx);

            PointF worldPt = drawingCanvas.getRawWorldCoords(currX, currY);
            double threshold = 50.0 / drawingCanvas.getZoomPercentage() * 100;
            Coordinate snapped = engine.getSnapPoint(worldPt.x, worldPt.y, threshold);
            float x = (snapped != null) ? (float) snapped.x : worldPt.x;
            float y = (snapped != null) ? (float) snapped.y : worldPt.y;

            if (isOrthoMode && firstPoint != null && !currentTool.equals("MOVE")) {
                Coordinate base = currentTool.equals("POLYLINE") && !activePolylinePoints.isEmpty() ? activePolylinePoints.get(activePolylinePoints.size()-1) : firstPoint;
                if (Math.abs(x - base.x) > Math.abs(y - base.y)) y = (float) base.y; else x = (float) base.x;
            }

            drawingCanvas.setSnapIndicator(x, y);

            if (action == MotionEvent.ACTION_DOWN) {
                if (currentTool.equals("POLYLINE")) handlePolylineTap(x, y);
                else if (currentTool.equals("SELECT")) {
                    CadEngine2d.NamedPoint np = engine.getNamedPointAt(worldPt.x, worldPt.y, threshold);
                    if (np != null) promptToRenamePoint(np);
                    else {
                        Geometry tapped = engine.getGeometryAt(worldPt.x, worldPt.y, threshold);
                        drawingCanvas.setSelectedGeometry(tapped);
                        if (tapped != null) propertiesPanel.setVisibility(View.VISIBLE);
                        else propertiesPanel.setVisibility(View.GONE);
                    }
                } else if (!currentTool.equals("MOVE")) { firstPoint = new Coordinate(x, y); }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (currentTool.equals("MOVE")) {
                    drawingCanvas.pan(currX - lastX, currY - lastY);
                } else if (firstPoint != null) {
                    drawingCanvas.setPreviewPoints(new PointF((float)firstPoint.x, (float)firstPoint.y), new PointF(x, y));
                }
                lastX = currX; lastY = currY;
            } else if (action == MotionEvent.ACTION_UP) {
                if (firstPoint != null && !currentTool.equals("MOVE") && !currentTool.equals("SELECT")) commitShape(x, y);
                drawingCanvas.clearSnapIndicator();
            }
            drawingCanvas.invalidate();
            return true;
        });
    }    private void handlePolylineTap(float x, float y) {
        if (activePolylinePoints.isEmpty()) {
            activePolylinePoints.add(new Coordinate(x, y));
            activePolylineDraw.add(new PointF(x, y));
            drawingCanvas.setActivePolyline(activePolylineDraw);
        } else {
            Coordinate startPt = activePolylinePoints.get(0);
            if (Math.hypot(startPt.x - x, startPt.y - y) < 40.0 / drawingCanvas.getZoomPercentage() * 100) {
                Geometry poly = engine.addPolygon(new ArrayList<>(activePolylinePoints));
                if (poly != null) {
                    drawingCanvas.setSelectedGeometry(poly);
                    tvShapeInfo.setText(engine.getPropertiesText(poly));
                    propertiesPanel.setVisibility(View.VISIBLE);
                }
                resetPolyline();
                drawingCanvas.invalidate();
            } else {
                activePolylinePoints.add(new Coordinate(x, y));
                activePolylineDraw.add(new PointF(x, y));
                drawingCanvas.setActivePolyline(activePolylineDraw);
            }
        }
    }

    private void commitShape(float x, float y) {
        Geometry created = null;
        try {
            switch (currentTool) {
                case "LINE": created = engine.addLine(firstPoint.x, firstPoint.y, x, y); break;
                case "RECT": created = engine.addRect(firstPoint.x, firstPoint.y, x, y); break;
                case "CIRCLE": created = engine.addCircle(firstPoint.x, firstPoint.y, Math.hypot(x - firstPoint.x, y - firstPoint.y)); break;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Invalid geometry drawn.", Toast.LENGTH_SHORT).show();
        }

        firstPoint = null;
        drawingCanvas.setPreviewPoints(null, null);
        drawingCanvas.invalidate();

        if (created != null) {
            engine.calculateAndSetDrivenDimension(created, currentTool);
            drawingCanvas.setSelectedGeometry(created);
            tvShapeInfo.setText(engine.getPropertiesText(created));
            propertiesPanel.setVisibility(View.VISIBLE);

            if (!isOrthoMode && !isViewOnly) promptForDimensions(created, currentTool);
            drawingCanvas.invalidate();
        }
    }

    private String identifyGeometryType(Geometry g) {
        if (g instanceof LineString) return "LINE";
        if (g instanceof Polygon) {
            if (g.getCoordinates().length == 5) return "RECT";
            return "CIRCLE";
        }
        return "UNKNOWN";
    }

    private void promptToRenamePoint(CadEngine2d.NamedPoint pt) {
        if (isViewOnly) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Vertex");

        final EditText input = new EditText(this);
        input.setText(pt.label);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 20);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                pt.label = newName;
                drawingCanvas.invalidate();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void promptForDimensions(Geometry geo, String type) {
        if (isViewOnly) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Smart Dimension");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 20);

        EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText input2 = new EditText(this);
        input2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        if (type.equals("LINE")) {
            input1.setHint("Exact Length (e.g. 100)");
            layout.addView(input1);
        } else if (type.equals("CIRCLE")) {
            input1.setHint("Exact Radius (e.g. 50)");
            layout.addView(input1);
        } else if (type.equals("RECT")) {
            input1.setHint("Width");
            input2.setHint("Height");
            layout.addView(input1);
            layout.addView(input2);
        } else return;

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            try {
                double val1 = Double.parseDouble(input1.getText().toString());
                if (val1 <= 0.1) throw new NumberFormatException();

                if (type.equals("LINE")) engine.resizeLine(geo, val1);
                else if (type.equals("CIRCLE")) engine.resizeCircle(geo, val1);
                else if (type.equals("RECT")) {
                    double val2 = Double.parseDouble(input2.getText().toString());
                    if (val2 <= 0.1) throw new NumberFormatException();
                    engine.resizeRect(geo, val1, val2);
                }

                tvShapeInfo.setText(engine.getPropertiesText(geo));
                drawingCanvas.invalidate();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Dimension must be > 0.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void showSaveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.save_drawing));
        final EditText input = new EditText(this);
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        int unnamedCount = pref.getInt("unnamed_drawing_count", 1);
        String defaultName = getIntent().hasExtra("SAVED_NAME") ? getIntent().getStringExtra("SAVED_NAME") : "unnamed" + unnamedCount;
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "unnamed" + unnamedCount;
            if (name.startsWith("unnamed")) pref.edit().putInt("unnamed_drawing_count", unnamedCount + 1).apply();
            saveToDatabase(name);
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void saveToDatabase(String drawingName) {
        String serializedData = serializeDrawing();
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String currentUser = pref.getString("username", "GuestUser");
        try {
            if (editId != -1) dbHelper.deleteDrawing(editId);
            dbHelper.addDrawing(currentUser, drawingName, serializedData);
            Toast.makeText(this, "Drawing Saved to History", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) { Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private String serializeDrawing() {
        try {
            JSONObject root = new JSONObject();
            JSONArray geoArray = new JSONArray();
            WKTWriter writer = new WKTWriter();
            for (Geometry g : engine.getGeometries()) {
                JSONObject obj = new JSONObject();
                obj.put("wkt", writer.write(g));
                if (g.getUserData() != null) obj.put("userData", g.getUserData().toString());
                geoArray.put(obj);
            }

            JSONArray ptsArray = new JSONArray();
            for (CadEngine2d.NamedPoint np : engine.getNamedPoints()) {
                JSONObject obj = new JSONObject();
                obj.put("x", np.x); obj.put("y", np.y); obj.put("label", np.label);
                ptsArray.put(obj);
            }

            root.put("geometries", geoArray);
            root.put("points", ptsArray);
            return root.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void deserializeDrawing(String jsonString) {
        try {
            List<Geometry> loadedGeometries = new ArrayList<>();
            List<CadEngine2d.NamedPoint> loadedPoints = new ArrayList<>();
            WKTReader reader = new WKTReader();

            if (jsonString.trim().startsWith("[")) {
                JSONArray array = new JSONArray(jsonString);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    Geometry g = reader.read(obj.getString("wkt"));
                    if (obj.has("userData")) g.setUserData(obj.getString("userData"));
                    loadedGeometries.add(g);
                }
            } else {
                JSONObject root = new JSONObject(jsonString);
                JSONArray geoArray = root.getJSONArray("geometries");
                for (int i = 0; i < geoArray.length(); i++) {
                    JSONObject obj = geoArray.getJSONObject(i);
                    Geometry g = reader.read(obj.getString("wkt"));
                    if (obj.has("userData")) g.setUserData(obj.getString("userData"));
                    loadedGeometries.add(g);
                }

                JSONArray ptsArray = root.getJSONArray("points");
                for (int i = 0; i < ptsArray.length(); i++) {
                    JSONObject obj = ptsArray.getJSONObject(i);
                    loadedPoints.add(new CadEngine2d.NamedPoint(obj.getDouble("x"), obj.getDouble("y"), obj.getString("label")));
                }
            }

            engine.setGeometriesAndPoints(loadedGeometries, loadedPoints);
            drawingCanvas.invalidate();
        } catch (Exception e) { Toast.makeText(this, "Error loading drawing", Toast.LENGTH_SHORT).show(); }
    }
    private void resetPolyline() {
        activePolylinePoints.clear();
        activePolylineDraw.clear();
        drawingCanvas.setActivePolyline(activePolylineDraw);
        drawingCanvas.setPreviewPoints(null, null);
    }
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}