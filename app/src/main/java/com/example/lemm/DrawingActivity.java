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
import android.util.Log;

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
import java.util.HashMap;
import java.util.List;

public class DrawingActivity extends AppCompatActivity {
    private static final String TAG = "DrawingActivity";
    private CadGeometryCanvas drawingCanvas;
    private org.locationtech.jts.geom.GeometryFactory jtsFactory = new org.locationtech.jts.geom.GeometryFactory();
    private Geometry secondSelectedGeometry = null;
    private CadEngine2d engine;

    private ScaleGestureDetector scaleDetector;
    private String currentTool = "MOVE";
    private boolean isOrthoMode = false;
    private Geometry referenceLine = null;
    private int activePointerId = -1;
    private Coordinate firstPoint = null;
    private float lastX, lastY;
    private boolean isScaling = false;

    private List<Coordinate> activePolylinePoints = new ArrayList<>();
    private List<PointF> activePolylineDraw = new ArrayList<>();

    private DatabaseHelper dbHelper;
    private String editId = "";
    private String originalDate = null;

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

            if (getIntent().hasExtra("EDIT_ID")) editId = getIntent().getStringExtra("EDIT_ID");
            if (getIntent().hasExtra("SAVED_DATE")) originalDate = getIntent().getStringExtra("SAVED_DATE");

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
                    .setMessage(getString(R.string.msg_confirm_exit))
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
        findViewById(R.id.btnOpen3D).setOnClickListener(v -> startActivity(new android.content.Intent(this, Drawing3DActivity.class)));
        findViewById(R.id.btnExtrude3D).setOnClickListener(v -> extrudeSelectedTo3D());
        findViewById(R.id.btnToolMove).setOnClickListener(v -> selectTool("MOVE"));
        findViewById(R.id.btnToolSelect).setOnClickListener(v -> selectTool("SELECT"));
        findViewById(R.id.btnToolLine).setOnClickListener(v -> selectTool("LINE"));
        findViewById(R.id.btnToolRect).setOnClickListener(v -> selectTool("RECT"));
        findViewById(R.id.btnToolCircle).setOnClickListener(v -> selectTool("CIRCLE"));

        ImageButton btnOrtho = findViewById(R.id.btnToolOrtho);
        btnOrtho.setOnClickListener(v -> {
            isOrthoMode = !isOrthoMode;
            int tintColor = isOrthoMode ? Color.parseColor("#E67E22") : Color.parseColor("#7F8C8D");
            btnOrtho.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
            Toast.makeText(this, isOrthoMode ? getString(R.string.tool_ortho_on) : getString(R.string.tool_ortho_off), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnUndo).setOnClickListener(v -> { engine.undo(); resetPolyline(); drawingCanvas.invalidate(); });
        findViewById(R.id.btnRedo).setOnClickListener(v -> { engine.redo(); resetPolyline(); drawingCanvas.invalidate(); });
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

        findViewById(R.id.btnDeleteShape).setOnClickListener(v -> {
            Geometry selected = drawingCanvas.getSelectedGeometry();
            int selectedEdge = drawingCanvas.getSelectedSegmentIndex();

            if (selected != null) {
                if (selected instanceof Polygon && selectedEdge != -1) {
                    String[] options = {"Delete Selected Edge Only", "Delete Entire Shape"};
                    new AlertDialog.Builder(this)
                            .setTitle("Delete Options")
                            .setItems(options, (dialog, which) -> {
                                if (which == 0) {
                                    Geometry openLine = engine.deletePolygonSegment(selected, selectedEdge);
                                    drawingCanvas.setSelectedGeometry(openLine);
                                    drawingCanvas.setSelectedSegmentIndex(-1);
                                    tvShapeInfo.setText(engine.getPropertiesText(this, openLine));
                                } else {
                                    engine.deleteGeometry(selected);
                                    drawingCanvas.setSelectedGeometry(null);
                                    drawingCanvas.setSelectedSegmentIndex(-1);
                                    propertiesPanel.setVisibility(View.GONE);
                                }
                                drawingCanvas.invalidate();
                            }).show();
                } else {
                    engine.deleteGeometry(selected);
                    drawingCanvas.setSelectedGeometry(null);
                    drawingCanvas.setSelectedSegmentIndex(-1);
                    propertiesPanel.setVisibility(View.GONE);
                    drawingCanvas.invalidate();
                }
            }
        });

        findViewById(R.id.btnSetAngleAction).setOnClickListener(v -> {
            Geometry selected = drawingCanvas.getSelectedGeometry();
            if (referenceLine instanceof org.locationtech.jts.geom.LineString &&
                    selected instanceof org.locationtech.jts.geom.LineString) {
                promptForAngle((org.locationtech.jts.geom.LineString) referenceLine,
                        (org.locationtech.jts.geom.LineString) selected);
            } else {
                Toast.makeText(this, "Select a second line first", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void selectTool(String tool) {
        this.currentTool = tool;
        drawingCanvas.setCurrentTool(tool);
        drawingCanvas.setSelectedSegmentIndex(-1); // FIX: Always reset selection index when switching tools!
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

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    activePointerId = event.getPointerId(0);
                    lastX = event.getX();
                    lastY = event.getY();
                    isScaling = false;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isScaling = true;
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    int pointerIndex = event.getActionIndex();
                    int pointerId = event.getPointerId(pointerIndex);
                    if (pointerId == activePointerId) {
                        int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                        if (newPointerIndex < event.getPointerCount()) {
                            lastX = event.getX(newPointerIndex);
                            lastY = event.getY(newPointerIndex);
                            activePointerId = event.getPointerId(newPointerIndex);
                        }
                    }
                    if (event.getPointerCount() <= 2) {
                        isScaling = false;
                    }
                    break;
            }

            if (scaleDetector.isInProgress() || event.getPointerCount() > 1 || isScaling) {
                return true;
            }

            int idx = event.findPointerIndex(activePointerId);
            if (idx == -1) return true;

            float currX = event.getX(idx);
            float currY = event.getY(idx);

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
                if (currentTool.equals("POLYLINE")) {
                    handlePolylineTap(x, y);
                }
                else if (currentTool.equals("SELECT")) {
                    CadEngine2d.NamedPoint np = engine.getNamedPointAt(worldPt.x, worldPt.y, threshold);
                    if (np != null) promptToRenamePoint(np);

                    Geometry tapped = engine.getGeometryAt(worldPt.x, worldPt.y, threshold);
                    Geometry previouslySelected = drawingCanvas.getSelectedGeometry();

                    if (tapped != null) {
                        drawingCanvas.setSelectedGeometry(tapped);

                        int newSegIdx = -1;
                        if (tapped instanceof Polygon) {
                            newSegIdx = engine.getClosestSegmentIndex(tapped, worldPt.x, worldPt.y, threshold);
                            drawingCanvas.setSelectedSegmentIndex(newSegIdx);
                        } else {
                            drawingCanvas.setSelectedSegmentIndex(-1);
                        }

                        // NEW: Extract temporary lines to allow setting angles on rectangle/polygon edges!
                        LineString line1 = getLineFromSelection(previouslySelected, drawingCanvas.getSelectedSegmentIndex());
                        LineString line2 = getLineFromSelection(tapped, newSegIdx);

                        if (line1 != null && line2 != null && previouslySelected != tapped) {
                            referenceLine = line1; // Set the highlighted edge as the reference line
                            findViewById(R.id.btnSetAngleAction).setVisibility(View.VISIBLE);
                        } else {
                            findViewById(R.id.btnSetAngleAction).setVisibility(View.GONE);
                            referenceLine = null;
                        }

                        tvShapeInfo.setText(engine.getPropertiesText(this, tapped));
                        propertiesPanel.setVisibility(View.VISIBLE);
                    } else {
                        propertiesPanel.setVisibility(View.GONE);
                        drawingCanvas.setSelectedGeometry(null);
                        drawingCanvas.setSelectedSegmentIndex(-1);
                        referenceLine = null;
                        findViewById(R.id.btnSetAngleAction).setVisibility(View.GONE);
                    }
                }
                else if (!currentTool.equals("MOVE")) {
                    firstPoint = new Coordinate(x, y);
                }
            }
            else if (action == MotionEvent.ACTION_MOVE) {
                if (currentTool.equals("MOVE")) {
                    drawingCanvas.pan(currX - lastX, currY - lastY);
                } else if (firstPoint != null) {
                    drawingCanvas.setPreviewPoints(new PointF((float)firstPoint.x, (float)firstPoint.y), new PointF(x, y));
                }
                lastX = currX;
                lastY = currY;
            }
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (firstPoint != null && !currentTool.equals("MOVE") && !currentTool.equals("SELECT")) commitShape(x, y);
                drawingCanvas.clearSnapIndicator();
                activePointerId = -1;
                isScaling = false;
            }

            drawingCanvas.invalidate();
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
                Geometry poly = engine.addPolygon(new ArrayList<>(activePolylinePoints));
                if (poly != null) {
                    drawingCanvas.setSelectedGeometry(poly);
                    tvShapeInfo.setText(engine.getPropertiesText(this, poly));
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

    private void promptForAngle(LineString line1, LineString line2) {
        if (isViewOnly) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.title_set_angle));

        final EditText input = new EditText(this);
        input.setHint(getString(R.string.hint_angle));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(60, 20, 60, 20);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            try {
                double angle = Double.parseDouble(input.getText().toString());
                engine.setAngleBetweenLines(line1, line2, angle);
                drawingCanvas.invalidate();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.error_invalid_angle), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
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
            tvShapeInfo.setText(engine.getPropertiesText(this, created));
            propertiesPanel.setVisibility(View.VISIBLE);

            if (!isOrthoMode && !isViewOnly) promptForDimensions(created, currentTool);
            drawingCanvas.invalidate();
        }
    }

    /** Extrudes the selected closed 2D shape into a 3D solid and opens it in the 3D editor. */
    private void extrudeSelectedTo3D() {
        Geometry g = drawingCanvas.getSelectedGeometry();
        if (g == null) return;
        if (!(g instanceof Polygon)) {
            Toast.makeText(this, getString(R.string.extrude_need_closed), Toast.LENGTH_SHORT).show();
            return;
        }
        Coordinate[] ring = g.getCoordinates();
        int n = ring.length;
        if (n > 1 && ring[0].equals2D(ring[n - 1])) n--; // drop the duplicated closing vertex
        if (n < 3) {
            Toast.makeText(this, getString(R.string.extrude_need_closed), Toast.LENGTH_SHORT).show();
            return;
        }
        final float[] xs = new float[n];
        final float[] zs = new float[n];
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += ring[i].x; sumY += ring[i].y; }
        final double cx = sumX / n, cy = sumY / n;
        for (int i = 0; i < n; i++) { // centre the profile on the origin so the solid sits in view
            xs[i] = (float) (ring[i].x - cx);
            zs[i] = (float) (ring[i].y - cy);
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(getString(R.string.extrude_height));
        input.setText("150");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.extrude_height)
                .setView(input)
                .setPositiveButton(R.string.extrude, (d, w) -> {
                    float h;
                    try { h = Float.parseFloat(input.getText().toString().trim().replace(',', '.')); }
                    catch (Exception e) { h = 150f; }
                    if (h <= 0) h = 150f;
                    android.content.Intent it = new android.content.Intent(this, Drawing3DActivity.class);
                    it.putExtra("EXTRUDE_X", xs);
                    it.putExtra("EXTRUDE_Z", zs);
                    it.putExtra("EXTRUDE_H", h);
                    startActivity(it);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String identifyGeometryType(Geometry g) {
        if (g instanceof LineString) return "LINE";
        if (g instanceof Polygon) {
            if (g.getCoordinates().length > 15) {
                return "CIRCLE";
            }
            if (g.getCoordinates().length == 5) {
                // FIX: If a specific edge of the rectangle is selected,
                // treat it as a Polygon edge, so we can resize just that side!
                if (drawingCanvas != null && drawingCanvas.getSelectedSegmentIndex() != -1) {
                    return "POLYGON";
                }
                return "RECT";
            }
            return "POLYGON";
        }
        return "UNKNOWN";
    }
    private LineString getLineFromSelection(Geometry g, int segIdx) {
        if (g == null) return null;
        if (g instanceof LineString) return (LineString) g;
        if (g instanceof Polygon && segIdx != -1) {
            Coordinate[] coords = g.getCoordinates();
            if (segIdx < coords.length - 1) {
                return jtsFactory.createLineString(new Coordinate[]{coords[segIdx], coords[segIdx + 1]});
            }
        }
        return null;
    }
    private void promptToRenamePoint(CadEngine2d.NamedPoint pt) {
        if (isViewOnly) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.title_rename_vertex));

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
        builder.setTitle(getString(R.string.title_smart_dim));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 20);

        EditText input1 = new EditText(this);
        input1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText input2 = new EditText(this);
        input2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        if (type.equals("LINE")) {
            input1.setHint(getString(R.string.hint_exact_length));
            layout.addView(input1);
        } else if (type.equals("CIRCLE")) {
            input1.setHint(getString(R.string.hint_exact_radius));
            layout.addView(input1);
        } else if (type.equals("RECT")) {
            input1.setHint(getString(R.string.hint_width));
            input2.setHint(getString(R.string.hint_height));
            layout.addView(input1);
            layout.addView(input2);
        } else if (type.equals("POLYGON")) {
            input1.setHint(getString(R.string.hint_exact_length));
            layout.addView(input1);
        } else return;

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.update), (dialog, which) -> {
            try {
                double val1 = Double.parseDouble(input1.getText().toString());
                if (val1 <= 0.1) throw new NumberFormatException();

                Geometry updatedGeo = null;

                if (type.equals("LINE")) {
                    updatedGeo = engine.resizeLine(geo, val1);
                } else if (type.equals("CIRCLE")) {
                    updatedGeo = engine.resizeCircle(geo, val1);
                } else if (type.equals("RECT")) {
                    double val2 = Double.parseDouble(input2.getText().toString());
                    if (val2 <= 0.1) throw new NumberFormatException();
                    updatedGeo = engine.resizeRect(geo, val1, val2);
                } else if (type.equals("POLYGON")) {
                    int segIdx = drawingCanvas.getSelectedSegmentIndex();
                    if (segIdx != -1) {
                        updatedGeo = engine.resizePolygonSegment(geo, segIdx, val1);
                    } else {
                        Toast.makeText(this, "Select an edge first to set its dimension", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                if (updatedGeo != null) {
                    drawingCanvas.setSelectedGeometry(updatedGeo);
                    tvShapeInfo.setText(engine.getPropertiesText(this, updatedGeo));
                }

                drawingCanvas.invalidate();
            } catch (NumberFormatException e) {
                Toast.makeText(this, getString(R.string.error_dim_positive), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void showSaveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.save_drawing));

        final EditText input = new EditText(this);

        String base = getString(R.string.default_drawing_name);
        String currentUser = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");
        String defaultName = getIntent().hasExtra("SAVED_NAME")
                ? getIntent().getStringExtra("SAVED_NAME")
                : dbHelper.nextDefaultName("drawings", currentUser, base);
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = dbHelper.nextDefaultName("drawings", currentUser, base);
            saveAndSync(name);
        });

        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void saveAndSync(String drawingName) {
        String serializedData = serializeDrawing();
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String currentUser = pref.getString("username", "GuestUser");

        try {
            String date = (originalDate != null) ? originalDate : FirebaseManager.getCurrentDate();
            String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");

            // 1. Save Locally
            if (editId != null && !editId.isEmpty()) {
                dbHelper.updateDrawing(Integer.parseInt(editId), drawingName, serializedData);
            } else {
                dbHelper.addDrawingWithDate(currentUser, drawingName, serializedData, date);
            }

            // 2. Direct Write to Cloud Database
            if (!currentUser.startsWith("GuestUser")) {
                HashMap<String, Object> map = new HashMap<>();
                map.put("title", drawingName);
                map.put("data", serializedData);
                map.put("date", date);

                FirebaseManager.getUserRef(currentUser).child("drawings").child(cloudKey).setValue(map)
                        .addOnSuccessListener(x -> dbHelper.markDrawingSynced(currentUser, date))
                        .addOnFailureListener(e -> Toast.makeText(getApplicationContext(),
                                "Saved locally. Couldn't sync to cloud — please check your Wi-Fi connection.",
                                Toast.LENGTH_LONG).show());
            }

            if (NetworkUtil.isOnline(this)) {
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Saved locally — you appear to be offline. Check your Wi-Fi; it will sync when you reconnect.", Toast.LENGTH_LONG).show();
            }

            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error in saveAndSync: " + e.getMessage(), e);
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
        } catch (Exception e) {
            Log.e(TAG, "Error serializing drawing: " + e.getMessage(), e);
            return "{}";
        }
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
        } catch (Exception e) {
            Log.e(TAG, "Error deserializing drawing: " + e.getMessage(), e);
            Toast.makeText(this, "Error loading drawing", Toast.LENGTH_SHORT).show();
        }
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