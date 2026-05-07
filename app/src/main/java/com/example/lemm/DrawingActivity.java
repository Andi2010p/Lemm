package com.example.lemm;

import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.locationtech.jts.geom.Coordinate;
import android.view.View;
import android.widget.Button;

public class DrawingActivity extends AppCompatActivity {
    private CadGeometryCanvas drawingCanvas;
    private CadEngine2d engine;

    private ImageButton btnToolMove, btnToolSelect, btnToolLine, btnToolRect, btnToolCircle, btnToolClear, btnUndo;
    private ImageButton btnZoomIn, btnZoomOut;
    private ToggleButton toggleSnapPoints, toggleSnapGrid;
    private TextView tvZoomPercent;

    private String currentTool = "MOVE";

    // CAD State variables
    private PointF firstPoint = null;
    private float lastMoveX, lastMoveY;
    private io.github.sceneview.SceneView scene3d;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing);
        drawingCanvas = findViewById(R.id.drawingCanvas);
        scene3d = findViewById(R.id.sceneView); // This works here!
        drawingCanvas.setEngine(engine);
        engine = new CadEngine2d();
        initViews();
        setupListeners();

        // Initialize tool
        selectTool("MOVE", btnToolMove);
    }

    private void initViews() {
        drawingCanvas = findViewById(R.id.drawingCanvas);
        scene3d = findViewById(R.id.sceneView); // Fixed ID

        btnToolMove = findViewById(R.id.btnToolMove);
        btnToolSelect = findViewById(R.id.btnToolSelect); // No longer red
        btnToolLine = findViewById(R.id.btnToolLine);
        btnToolRect = findViewById(R.id.btnToolRect);
        btnToolCircle = findViewById(R.id.btnToolCircle);
        btnToolClear = findViewById(R.id.btnToolClear);
        btnUndo = findViewById(R.id.btnUndo);

        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercent = findViewById(R.id.tvZoomPercent);

        toggleSnapPoints = findViewById(R.id.toggleSnapPoints);
        toggleSnapGrid = findViewById(R.id.toggleSnapGrid); // No longer red

        // Extrude Button
        findViewById(R.id.btnExtrude).setOnClickListener(v -> toggle3D());
    }

    private void setupListeners() {
        // Tool Selection
        btnToolMove.setOnClickListener(v -> selectTool("MOVE", btnToolMove));
        btnToolLine.setOnClickListener(v -> selectTool("LINE", btnToolLine));
        btnToolRect.setOnClickListener(v -> selectTool("RECT", btnToolRect));
        btnToolCircle.setOnClickListener(v -> selectTool("CIRCLE", btnToolCircle));

        // Canvas Actions
        btnUndo.setOnClickListener(v -> {
            engine.undo();
            drawingCanvas.invalidate();
        });

        btnToolClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Drawing")
                    .setMessage("Delete all geometry?")
                    .setPositiveButton("Clear", (d, w) -> {
                        engine.clear();
                        drawingCanvas.invalidate();
                    })
                    .setNegativeButton("Cancel", null).show();
        });

        // Zoom Logic
        btnZoomIn.setOnClickListener(v -> {
            drawingCanvas.zoomIn();
            updateZoomText();
        });
        btnZoomOut.setOnClickListener(v -> {
            drawingCanvas.zoomOut();
            updateZoomText();
        });

        // Snap Toggles
        toggleSnapPoints.setOnCheckedChangeListener((bv, isChecked) -> drawingCanvas.setSnapToPoints(isChecked));

        // --- THE MAIN CAD TOUCH LOGIC ---
        drawingCanvas.setOnTouchListener((v, event) -> {
            // 1. Convert Screen pixels to World CAD Math coordinates
            PointF worldPt = drawingCanvas.getRawWorldCoords(event.getX(), event.getY());

            // 2. Apply Snapping (Check if near a vertex)
            Coordinate snapped = engine.getSnapPoint(worldPt.x, worldPt.y, 40); // 40 pixel tolerance
            float finalX = (snapped != null) ? (float) snapped.x : worldPt.x;
            float finalY = (snapped != null) ? (float) snapped.y : worldPt.y;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (currentTool.equals("MOVE")) {
                        lastMoveX = event.getX();
                        lastMoveY = event.getY();
                    } else {
                        handleCadDrawing(finalX, finalY);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (currentTool.equals("MOVE")) {
                        float dx = event.getX() - lastMoveX;
                        float dy = event.getY() - lastMoveY;
                        drawingCanvas.pan(dx, dy);
                        lastMoveX = event.getX();
                        lastMoveY = event.getY();
                    } else if (firstPoint != null) {
                        // Show "Ghost Line" while dragging
                        drawingCanvas.setPreviewPoints(firstPoint, new PointF(finalX, finalY));
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    v.performClick();
                    break;
            }
            return true;
        });
    }

    private void handleCadDrawing(float x, float y) {
        if (firstPoint == null) {
            // First Click: Set Start Point
            firstPoint = new PointF(x, y);
            Toast.makeText(this, "Start point set", Toast.LENGTH_SHORT).show();
        } else {
            // Second Click: Commit to Engine
            switch (currentTool) {
                case "LINE":
                    engine.addLine(firstPoint.x, firstPoint.y, x, y);
                    break;
                case "RECT":
                    engine.addRect(firstPoint.x, firstPoint.y, x, y);
                    break;
                case "CIRCLE":
                    float radius = (float) Math.hypot(x - firstPoint.x, y - firstPoint.y);
                    engine.addCircle(firstPoint.x, firstPoint.y, radius);
                    break;
            }
            // Reset for next shape
            firstPoint = null;
            drawingCanvas.setPreviewPoints(null, null); // Remove ghost line
            drawingCanvas.invalidate();
        }
    }

    private void selectTool(String tool, ImageButton button) {
        currentTool = tool;
        firstPoint = null;
        drawingCanvas.setPreviewPoints(null, null);

        // Reset button colors
        ImageButton[] buttons = {btnToolMove, btnToolSelect, btnToolLine, btnToolRect, btnToolCircle};
        for (ImageButton b : buttons) if (b != null) b.setBackgroundColor(Color.TRANSPARENT);

        // Highlight active tool
        if (button != null) button.setBackgroundColor(Color.parseColor("#BBDEFB"));
    }
    // DELETE THESE LINES from DrawingActivity.java

    private void updateZoomText() {
        tvZoomPercent.setText(drawingCanvas.getZoomPercentage() + "%");
    }
    private void toggle3D() {
        if (drawingCanvas.getVisibility() == View.VISIBLE) {
            // Switch to 3D
            drawingCanvas.setVisibility(View.GONE);
            scene3d.setVisibility(View.VISIBLE);

            // Change button text to indicate we are in 3D
            Button btnExtrude = findViewById(R.id.btnExtrude);
            btnExtrude.setText("Back to Sketch");

            generate3DModel();
        } else {
            // Switch back to 2D
            drawingCanvas.setVisibility(View.VISIBLE);
            scene3d.setVisibility(View.GONE);

            Button btnExtrude = findViewById(R.id.btnExtrude);
            btnExtrude.setText("3D Extrude");
        }
    }

    private void generate3DModel() {
        // This is where the CAD magic happens.
        // For now, we show a toast. Later, you will use SceneView
        // to build a 3D mesh from the 'engine.getGeometries()' list.
        Toast.makeText(this, "Generating 3D Model from Sketch...", Toast.LENGTH_SHORT).show();
    }
}