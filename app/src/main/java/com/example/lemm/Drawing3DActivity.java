package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A working CAD-style 3D sketcher built on {@link GeometryCanvas3D}.
 *
 * Tools:
 *  - Inspect: tap a point / line / midpoint / angle / face to read all its info.
 *  - Draw: tap the grid to drop lettered points on the sketch plane and connect them by hand; tap
 *    the first point to close the loop into a face (which can then be Extruded into a solid).
 *  - +Line: connect two existing points. +Point: add a point by exact coordinate.
 *  - Angle: tap a corner point then the two arm points to drop a 3D angle arc with its value.
 *  - Extrude / Delete / Clear / orbit-pan-zoom.
 * Every point is auto-lettered and every line shows its midpoint.
 */
public class Drawing3DActivity extends AppCompatActivity {

    private static final int TOOL_INSPECT = 0;
    private static final int TOOL_DRAW = 1;
    private static final int TOOL_LINE = 2;
    private static final int TOOL_ANGLE = 3;

    private GeometryCanvas3D canvas3D;
    private TextView tvInfo;
    private MaterialButton btnModeToggle;

    private int tool = TOOL_INSPECT;
    private boolean panMode = false; // false = rotate (default), true = pan

    private final List<String> sketchChain = new ArrayList<>();
    private final List<String> anglePicks = new ArrayList<>();
    private String pendingLineStart = null;
    private String editId = null; // set when reopened from History (update instead of insert)
    private int roundShapeCount = 0; // auto-labels circles (C1, C2…) and spheres (S1, S2…)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing_3d);

        canvas3D = findViewById(R.id.canvas3D);
        tvInfo = findViewById(R.id.tvInfo);
        btnModeToggle = findViewById(R.id.btnModeToggle);

        ImageButton btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Reopen a saved 3D drawing, OR build an extruded solid from the 2D editor, OR seed a sample.
        String load3d = getIntent().getStringExtra("LOAD_3D_DATA");
        float[] ex = getIntent().getFloatArrayExtra("EXTRUDE_X");
        float[] ez = getIntent().getFloatArrayExtra("EXTRUDE_Z");
        float eh = getIntent().getFloatExtra("EXTRUDE_H", 0f);
        if (load3d != null && !load3d.isEmpty()) {
            canvas3D.loadFromJson(load3d);
            editId = getIntent().getStringExtra("EDIT_ID");
        } else if (ex != null && ez != null && ex.length >= 3 && ex.length == ez.length && eh > 0) {
            canvas3D.extrudeProfileToSolid(ex, ez, -eh / 2f, eh); // real vertices (a rectangle → 8 points)
        } else {
            seedSample();
        }

        canvas3D.setShowDimensions(true); // show edge lengths on the figure, like the 2D editor
        canvas3D.setOnElementSelectedListener(this::onElementSelected);
        canvas3D.setOnSketchPointListener(this::onSketchPoint);

        ((MaterialButton) findViewById(R.id.btnDimToggle)).setOnClickListener(v ->
                canvas3D.setShowDimensions(!canvas3D.isShowingDimensions()));

        ((MaterialButton) findViewById(R.id.btnInspect)).setOnClickListener(v -> setTool(TOOL_INSPECT));
        ((MaterialButton) findViewById(R.id.btnEditValue)).setOnClickListener(v -> promptForEditValue());
        ((MaterialButton) findViewById(R.id.btnDraw)).setOnClickListener(v -> setTool(TOOL_DRAW));
        ((MaterialButton) findViewById(R.id.btnAddLine)).setOnClickListener(v -> setTool(TOOL_LINE));
        ((MaterialButton) findViewById(R.id.btnAngleTool)).setOnClickListener(v -> setTool(TOOL_ANGLE));
        ((MaterialButton) findViewById(R.id.btnFinishSketch)).setOnClickListener(v -> finishSketch());
        ((MaterialButton) findViewById(R.id.btnAddPoint)).setOnClickListener(v -> promptForPoint());
        ((MaterialButton) findViewById(R.id.btnAddCircle)).setOnClickListener(v -> promptForCircle());
        ((MaterialButton) findViewById(R.id.btnAddSphere)).setOnClickListener(v -> promptForSphere());
        ((MaterialButton) findViewById(R.id.btnExtrude)).setOnClickListener(v -> promptForExtrude());
        ((MaterialButton) findViewById(R.id.btnSave3D)).setOnClickListener(v -> promptForSave());
        ((MaterialButton) findViewById(R.id.btnDelete)).setOnClickListener(v -> {
            if (canvas3D.deleteSelected()) { tvInfo.setText(R.string.info_tap_hint); toast(getString(R.string.d3_deleted)); canvas3D.recordHistory(); }
        });
        ((MaterialButton) findViewById(R.id.btnClear)).setOnClickListener(v -> {
            canvas3D.clear();
            sketchChain.clear(); anglePicks.clear(); pendingLineStart = null;
            tvInfo.setText(R.string.info_tap_hint);
            canvas3D.recordHistory();
        });

        ((MaterialButton) findViewById(R.id.btnUndo3D)).setOnClickListener(v -> {
            if (canvas3D.undo()) tvInfo.setText(R.string.info_tap_hint);
        });
        ((MaterialButton) findViewById(R.id.btnRedo3D)).setOnClickListener(v -> {
            if (canvas3D.redo()) tvInfo.setText(R.string.info_tap_hint);
        });
        ((MaterialButton) findViewById(R.id.btnHelp3D)).setOnClickListener(v ->
                HelpDialog.show(this, R.string.help_title, R.string.help_3d_body));

        btnModeToggle.setOnClickListener(v -> {
            panMode = !panMode;
            canvas3D.setMoveMode(panMode);
            btnModeToggle.setText(panMode ? R.string.d3_pan : R.string.d3_rotate);
        });
        ((MaterialButton) findViewById(R.id.btnReset)).setOnClickListener(v -> canvas3D.resetRotation());
        ((MaterialButton) findViewById(R.id.btnZoomIn)).setOnClickListener(v -> canvas3D.zoomIn());
        ((MaterialButton) findViewById(R.id.btnZoomOut)).setOnClickListener(v -> canvas3D.zoomOut());

        setTool(TOOL_INSPECT);
        canvas3D.initHistory(); // baseline for undo/redo
    }

    private void seedSample() {
        canvas3D.addPoint("A", 0, 0, 0);
        canvas3D.addPoint("B", 150, 0, 0);
        canvas3D.addPoint("C", 0, 0, 150);
        canvas3D.addLine("A", "B");
        canvas3D.addLine("B", "C");
        canvas3D.addLine("C", "A");
    }

    private void setTool(int t) {
        tool = t;
        sketchChain.clear();
        anglePicks.clear();
        pendingLineStart = null;
        canvas3D.setInteractionMode(t == TOOL_DRAW ? GeometryCanvas3D.MODE_DRAW : GeometryCanvas3D.MODE_SELECT);
        switch (t) {
            case TOOL_DRAW:  tvInfo.setText(R.string.d3_draw_hint); break;
            case TOOL_LINE:  toast(getString(R.string.d3_tap_first)); break;
            case TOOL_ANGLE: toast(getString(R.string.d3_angle_pick1)); break;
            default:         tvInfo.setText(R.string.info_tap_hint); break;
        }
    }

    /** Selection callback (Inspect / +Line / Angle tools — canvas is in SELECT mode). */
    private void onElementSelected(String info) {
        if (tool == TOOL_LINE) {
            String label = canvas3D.getSelectedPointLabel();
            if (label == null) { toast(getString(R.string.d3_need_point)); return; }
            if (pendingLineStart == null) { pendingLineStart = label; toast(getString(R.string.d3_tap_second)); }
            else { canvas3D.connect(pendingLineStart, label); pendingLineStart = null; toast(getString(R.string.d3_line_added)); canvas3D.recordHistory(); }
            return;
        }
        if (tool == TOOL_ANGLE) {
            String label = canvas3D.getSelectedPointLabel();
            if (label == null) { toast(getString(R.string.d3_need_point)); return; }
            anglePicks.add(label);
            if (anglePicks.size() == 1) toast(getString(R.string.d3_angle_pick2));
            else if (anglePicks.size() == 2) toast(getString(R.string.d3_angle_pick3));
            else {
                canvas3D.addAngle(anglePicks.get(0), anglePicks.get(1), anglePicks.get(2), null);
                anglePicks.clear();
                canvas3D.recordHistory();
                toast(getString(R.string.d3_angle_added));
                toast(getString(R.string.d3_angle_pick1)); // ready for the next angle
            }
            return;
        }
        tvInfo.setText(info != null ? info : getString(R.string.info_tap_hint));
    }

    /** Hand-drawing callback (Draw tool — canvas is in DRAW mode). */
    private void onSketchPoint(String label) {
        if (label == null) { toast(getString(R.string.d3_rotate_to_sketch)); return; }
        if (sketchChain.isEmpty()) { sketchChain.add(label); return; }

        String prev = sketchChain.get(sketchChain.size() - 1);
        if (label.equals(sketchChain.get(0)) && sketchChain.size() >= 3) {
            canvas3D.connect(prev, label);                 // close the loop
            canvas3D.addPlane(new ArrayList<>(sketchChain)); // sketch region / face
            sketchChain.clear();
            toast(getString(R.string.d3_face_added));
            canvas3D.recordHistory();
        } else if (!label.equals(prev)) {
            canvas3D.connect(prev, label);
            sketchChain.add(label);
            canvas3D.recordHistory();
        }
    }

    private void finishSketch() {
        sketchChain.clear();
        setTool(TOOL_INSPECT);
    }

    private void promptForPoint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etX = coordField("X"), etY = coordField("Y"), etZ = coordField("Z");
        box.addView(etX); box.addView(etY); box.addView(etZ);
        new AlertDialog.Builder(this)
                .setTitle(R.string.d3_coords_title)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> { canvas3D.addVertex(parse(etX), parse(etY), parse(etZ)); canvas3D.recordHistory(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptForCircle() {
        promptForRoundShape(R.string.d3_circle_title, true);
    }

    private void promptForSphere() {
        promptForRoundShape(R.string.d3_sphere_title, false);
    }

    /** Shared dialog asking for a centre (X, Y, Z) and radius, then adding a circle or a sphere. */
    private void promptForRoundShape(int titleRes, boolean isCircle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etX = coordField("X"), etY = coordField("Y"), etZ = coordField("Z"), etR = coordField("R");
        etX.setText("0"); etY.setText("0"); etZ.setText("0"); etR.setText("100");
        box.addView(etX); box.addView(etY); box.addView(etZ); box.addView(etR);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    float r = parse(etR);
                    if (r <= 0) r = 100f;
                    String label = (isCircle ? "C" : "S") + (++roundShapeCount);
                    if (isCircle) canvas3D.addCircle(label, parse(etX), parse(etY), parse(etZ), r);
                    else canvas3D.addSphere(label, parse(etX), parse(etY), parse(etZ), r);
                    canvas3D.recordHistory();
                    toast(getString(R.string.d3_shape_added));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptForSave() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String user = pref.getString("username", "GuestUser");
        DatabaseHelper db = new DatabaseHelper(this);
        String suggested = db.nextDefaultName("drawings", user, getString(R.string.default_drawing_name)) + " (3D)";

        final EditText input = new EditText(this);
        input.setText(suggested);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_drawing)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> saveDrawing3D(db, user, input.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveDrawing3D(DatabaseHelper db, String user, String name) {
        if (name.isEmpty()) name = "Drawing (3D)";
        String data = canvas3D.toJson();
        String date = FirebaseManager.getCurrentDate();
        try {
            if (editId != null && !editId.isEmpty()) {
                db.updateDrawing(Integer.parseInt(editId), name, data);
            } else {
                db.addDrawingWithDate(user, name, data, date);
            }
            if (!user.startsWith("GuestUser")) {
                String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");
                HashMap<String, Object> map = new HashMap<>();
                map.put("title", name);
                map.put("data", data);
                map.put("date", date);
                final String u = user;
                FirebaseManager.getUserRef(user).child("drawings").child(cloudKey).setValue(map)
                        .addOnSuccessListener(x -> db.markDrawingSynced(u, date));
            }
            toast(getString(R.string.save) + " ✓");
            finish();
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void promptForExtrude() {
        final EditText input = coordField(getString(R.string.extrude_height));
        input.setText("150");
        new AlertDialog.Builder(this)
                .setTitle(R.string.extrude_height)
                .setView(input)
                .setPositiveButton(R.string.extrude, (d, w) -> {
                    float h = parse(input);
                    if (h <= 0) h = 150f;
                    if (!canvas3D.extrudeGroundProfile(h)) toast(getString(R.string.d3_need_profile));
                    else canvas3D.recordHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Opens the right value editor for whatever is selected (point coords / edge length / angle). */
    private void promptForEditValue() {
        switch (canvas3D.getSelectionKind()) {
            case GeometryCanvas3D.KIND_POINT: editPointValue(); break;
            case GeometryCanvas3D.KIND_EDGE:  editEdgeValue(); break;
            case GeometryCanvas3D.KIND_ANGLE: editAngleValue(); break;
            default: toast(getString(R.string.d3_edit_none)); break;
        }
    }

    private void editPointValue() {
        float[] c = canvas3D.getSelectedPointCoords();
        if (c == null) { toast(getString(R.string.d3_edit_none)); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etX = coordField("X"), etY = coordField("Y"), etZ = coordField("Z");
        etX.setText(num(c[0])); etY.setText(num(c[1])); etZ.setText(num(c[2]));
        box.addView(etX); box.addView(etY); box.addView(etZ);
        new AlertDialog.Builder(this)
                .setTitle(R.string.d3_edit_point)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    canvas3D.setSelectedPointCoords(parse(etX), parse(etY), parse(etZ));
                    canvas3D.recordHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editEdgeValue() {
        final EditText input = coordField(getString(R.string.d3_edit_length));
        input.setText(num(canvas3D.getSelectedEdgeLength()));
        new AlertDialog.Builder(this)
                .setTitle(R.string.d3_edit_length)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (canvas3D.setSelectedEdgeLength(parse(input))) canvas3D.recordHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editAngleValue() {
        final EditText input = coordField(getString(R.string.d3_edit_angle));
        input.setText(num(canvas3D.getSelectedAngleValue()));
        new AlertDialog.Builder(this)
                .setTitle(R.string.d3_edit_angle)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (canvas3D.setSelectedAngleValue(parse(input))) canvas3D.recordHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String num(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private EditText coordField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return et;
    }

    private float parse(EditText et) {
        try { return Float.parseFloat(et.getText().toString().trim().replace(',', '.')); }
        catch (NumberFormatException e) { return 0f; }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
