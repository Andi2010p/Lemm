package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView; // <--- THIS WAS MISSING
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

    private Coordinate firstPoint = null;
    private float lastX, lastY;
    private boolean isStartSnapped = false;
    private boolean isScaling = false;

    private List<Coordinate> activePolylinePoints = new ArrayList<>();
    private List<PointF> activePolylineDraw = new ArrayList<>();

    private DatabaseHelper dbHelper;
    private int editId = -1;

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

    private void loadIntentData() {
        if (getIntent().hasExtra("LOAD_DRAWING_DATA")) {
            String data = getIntent().getStringExtra("LOAD_DRAWING_DATA");
            deserializeDrawing(data);

            if (getIntent().hasExtra("EDIT_ID")) {
                editId = getIntent().getIntExtra("EDIT_ID", -1);
            }

            boolean isViewOnly = getIntent().getBooleanExtra("IS_VIEW_ONLY", false);
            if (isViewOnly) {
                findViewById(R.id.toolbarCard).setVisibility(View.GONE);
                findViewById(R.id.bottomActions).setVisibility(View.GONE);
            }
        }
    }

    private void setupHardwareBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { finish(); }
        });
    }

    private void initViews() {
        drawingCanvas = findViewById(R.id.drawingCanvas);
        drawingCanvas.setEngine(engine);

        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        findViewById(R.id.btnToolMove).setOnClickListener(v -> selectTool("MOVE"));
        findViewById(R.id.btnToolSelect).setOnClickListener(v -> selectTool("SELECT"));
        findViewById(R.id.btnToolLine).setOnClickListener(v -> selectTool("LINE"));
        findViewById(R.id.btnToolPoly).setOnClickListener(v -> selectTool("POLYLINE"));
        findViewById(R.id.btnToolRect).setOnClickListener(v -> selectTool("RECT"));
        findViewById(R.id.btnToolCircle).setOnClickListener(v -> selectTool("CIRCLE"));

        findViewById(R.id.btnUndo).setOnClickListener(v -> { engine.undo(); resetPolyline(); drawingCanvas.invalidate(); });
        findViewById(R.id.btnToolClear).setOnClickListener(v -> { engine.clear(); resetPolyline(); drawingCanvas.invalidate(); });

        findViewById(R.id.btnDontSave).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> showSaveDialog());

        // --- NEW ZOOM BUTTONS LOGIC ---
        TextView tvZoom = findViewById(R.id.tvZoomPercent);

        drawingCanvas.setOnZoomChangeListener(pct -> {
            if (tvZoom != null) tvZoom.setText(pct + "%");
        });

        findViewById(R.id.btnZoomIn).setOnClickListener(v -> drawingCanvas.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> drawingCanvas.zoomOut());
    }

    private void showSaveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.save_drawing));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

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

            if (name.startsWith("unnamed")) {
                pref.edit().putInt("unnamed_drawing_count", unnamedCount + 1).apply();
            }
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
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String serializeDrawing() {
        try {
            JSONArray array = new JSONArray();
            WKTWriter writer = new WKTWriter();
            for (Geometry g : engine.getGeometries()) {
                JSONObject obj = new JSONObject();
                obj.put("wkt", writer.write(g));
                if (g.getUserData() != null) obj.put("userData", g.getUserData().toString());
                array.put(obj);
            }
            return array.toString();
        } catch (Exception e) { return "[]"; }
    }

    private void deserializeDrawing(String jsonString) {
        try {
            JSONArray array = new JSONArray(jsonString);
            WKTReader reader = new WKTReader();
            List<Geometry> loadedGeometries = new ArrayList<>();

            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Geometry g = reader.read(obj.getString("wkt"));
                if (obj.has("userData")) g.setUserData(obj.getString("userData"));
                loadedGeometries.add(g);
            }
            engine.setGeometries(loadedGeometries);
            drawingCanvas.invalidate();
        } catch (Exception e) { Toast.makeText(this, "Error loading drawing", Toast.LENGTH_SHORT).show(); }
    }

    private void resetPolyline() {
        activePolylinePoints.clear();
        activePolylineDraw.clear();
        drawingCanvas.setActivePolyline(activePolylineDraw);
        drawingCanvas.setPreviewPoints(null, null);
    }

    private void selectTool(String tool) {
        this.currentTool = tool;
        drawingCanvas.setCurrentTool(tool);
        firstPoint = null;
        resetPolyline();
    }

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                return true;
            }
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                drawingCanvas.applyZoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
            }
        });

        drawingCanvas.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);

            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                isScaling = true;
            }

            if (event.getPointerCount() > 1 || isScaling) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                    isScaling = false;
                }
                lastX = event.getX(0);
                lastY = event.getY(0);
                return true;
            }

            PointF worldPt = drawingCanvas.getRawWorldCoords(event.getX(), event.getY());
            double threshold = 50.0 / drawingCanvas.getZoomPercentage() * 100;
            Coordinate snapped = engine.getSnapPoint(worldPt.x, worldPt.y, threshold);
            float x = (snapped != null) ? (float) snapped.x : worldPt.x;
            float y = (snapped != null) ? (float) snapped.y : worldPt.y;
            drawingCanvas.setSnapIndicator(x, y);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY();

                    if (currentTool.equals("POLYLINE")) {
                        handlePolylineTap(x, y);
                    } else if (currentTool.equals("SELECT")) {
                        Geometry tapped = engine.getGeometryAt(worldPt.x, worldPt.y, threshold);
                        drawingCanvas.setSelectedGeometry(tapped);
                        if (tapped != null) promptForDimensions(tapped, identifyGeometryType(tapped));
                    } else if (!currentTool.equals("MOVE")) {
                        isStartSnapped = (snapped != null);
                        firstPoint = new Coordinate(x, y);
                    }
                    drawingCanvas.invalidate();
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (currentTool.equals("MOVE")) {
                        drawingCanvas.pan(event.getX() - lastX, event.getY() - lastY);
                        lastX = event.getX(); lastY = event.getY();
                    } else if (currentTool.equals("POLYLINE") && !activePolylinePoints.isEmpty()) {
                        drawingCanvas.setPreviewPoints(null, new PointF(x, y));
                    } else if (firstPoint != null) {
                        drawingCanvas.setPreviewPoints(new PointF((float)firstPoint.x, (float)firstPoint.y), new PointF(x, y));
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (firstPoint != null && !currentTool.equals("MOVE") && !currentTool.equals("POLYLINE") && !currentTool.equals("SELECT")) {
                        boolean isEndSnapped = (snapped != null);
                        commitShape(x, y, isStartSnapped, isEndSnapped);
                    }
                    drawingCanvas.clearSnapIndicator();
                    break;
            }
            return true;
        });
    }

    private void handlePolylineTap(float x, float y) {
        if (activePolylinePoints.isEmpty()) {
            activePolylinePoints.add(new Coordinate(x, y));
            activePolylineDraw.add(new PointF(x, y));
            drawingCanvas.setActivePolyline(activePolylineDraw);
        } else {
            Coordinate startPt = activePolylinePoints.get(0);
            if (Math.hypot(startPt.x - x, startPt.y - y) < 40.0 / drawingCanvas.getZoomPercentage() * 100) {
                engine.addPolygon(new ArrayList<>(activePolylinePoints));
                resetPolyline();
                drawingCanvas.invalidate();
            } else {
                activePolylinePoints.add(new Coordinate(x, y));
                activePolylineDraw.add(new PointF(x, y));
                drawingCanvas.setActivePolyline(activePolylineDraw);
            }
        }
    }

    private void commitShape(float x, float y, boolean startSnap, boolean endSnap) {
        Geometry created = null;
        switch (currentTool) {
            case "LINE": created = engine.addLine(firstPoint.x, firstPoint.y, x, y); break;
            case "RECT": created = engine.addRect(firstPoint.x, firstPoint.y, x, y); break;
            case "CIRCLE": created = engine.addCircle(firstPoint.x, firstPoint.y, Math.hypot(x - firstPoint.x, y - firstPoint.y)); break;
        }
        firstPoint = null;
        drawingCanvas.setPreviewPoints(null, null);
        drawingCanvas.invalidate();

        if (created != null) {
            drawingCanvas.setSelectedGeometry(created);
            if (startSnap && endSnap) {
                engine.calculateAndSetDrivenDimension(created, currentTool);
            } else {
                promptForDimensions(created, currentTool);
            }
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

    private void promptForDimensions(Geometry geo, String type) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Dimensions");

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
                if (type.equals("LINE")) engine.resizeLine(geo, Double.parseDouble(input1.getText().toString()));
                else if (type.equals("CIRCLE")) engine.resizeCircle(geo, Double.parseDouble(input1.getText().toString()));
                else if (type.equals("RECT")) engine.resizeRect(geo, Double.parseDouble(input1.getText().toString()), Double.parseDouble(input2.getText().toString()));
                drawingCanvas.invalidate();
            } catch (Exception e) { Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show(); }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}