package com.example.lemm;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;

public class DrawingActivity extends AppCompatActivity {
    private GeometryCanvas drawingCanvas;
    private ImageButton btnToolMove, btnToolSelect, btnToolPoint, btnToolLine, btnToolCenterline, btnToolCircle, btnToolRect, btnToolArc, btnToolClear, btnUndo, btnDownloadDrawing;
    private ImageButton btnZoomIn, btnZoomOut;
    private ToggleButton toggleSnapPoints, toggleSnapGrid;
    private TextView tvZoomPercent;
    private Button btnSave, btnBack;
    private DatabaseHelper dbHelper;

    private String currentTool = "MOVE";
    private int editId = -1;
    private String originalName = "";

    // For multi-step drawing
    private GeometryCanvas.GeoPoint firstPoint = null;
    private PointF arcCenter = null;
    private float firstX, firstY;

    // For Select/Move logic
    private float lastMoveX, lastMoveY;
    private boolean isDragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing);

        dbHelper = new DatabaseHelper(this);
        initViews();
        setupListeners();

        editId = getIntent().getIntExtra("EDIT_ID", -1);
        originalName = getIntent().getStringExtra("SAVED_NAME");
        String savedData = getIntent().getStringExtra("LOAD_DRAWING_DATA");
        
        if (savedData != null && !savedData.isEmpty()) {
            drawingCanvas.setDrawingData(savedData);
        }

        selectTool("MOVE", btnToolMove);
        updateZoomText();
    }

    private void initViews() {
        drawingCanvas = findViewById(R.id.drawingCanvas);
        btnToolMove = findViewById(R.id.btnToolMove);
        btnToolSelect = findViewById(R.id.btnToolSelect);
        btnToolPoint = findViewById(R.id.btnToolPoint);
        btnToolLine = findViewById(R.id.btnToolLine);
        btnToolCenterline = findViewById(R.id.btnToolCenterline);
        btnToolCircle = findViewById(R.id.btnToolCircle);
        btnToolRect = findViewById(R.id.btnToolRect);
        btnToolArc = findViewById(R.id.btnToolArc);
        btnToolClear = findViewById(R.id.btnToolClear);
        btnDownloadDrawing = findViewById(R.id.btnDownloadDrawing);
        btnUndo = findViewById(R.id.btnUndo);

        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercent = findViewById(R.id.tvZoomPercent);

        toggleSnapPoints = findViewById(R.id.toggleSnapPoints);
        toggleSnapGrid = findViewById(R.id.toggleSnapGrid);

        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
    }

    private void setupListeners() {
        drawingCanvas.setOnZoomChangeListener(pct -> tvZoomPercent.setText(pct + "%"));

        btnZoomIn.setOnClickListener(v -> {
            drawingCanvas.zoomIn();
            updateZoomText();
        });

        btnZoomOut.setOnClickListener(v -> {
            drawingCanvas.zoomOut();
            updateZoomText();
        });

        btnToolMove.setOnClickListener(v -> selectTool("MOVE", btnToolMove));
        btnToolSelect.setOnClickListener(v -> selectTool("SELECT", btnToolSelect));
        btnToolPoint.setOnClickListener(v -> selectTool("POINT", btnToolPoint));
        btnToolLine.setOnClickListener(v -> selectTool("LINE", btnToolLine));
        btnToolCenterline.setOnClickListener(v -> selectTool("CENTERLINE", btnToolCenterline));
        btnToolCircle.setOnClickListener(v -> selectTool("CIRCLE", btnToolCircle));
        btnToolRect.setOnClickListener(v -> selectTool("RECT", btnToolRect));
        btnToolArc.setOnClickListener(v -> selectTool("ARC", btnToolArc));

        toggleSnapPoints.setOnCheckedChangeListener((buttonView, isChecked) -> drawingCanvas.setSnapToPoints(isChecked));
        toggleSnapGrid.setOnCheckedChangeListener((buttonView, isChecked) -> drawingCanvas.setSnapToGrid(isChecked));

        if (btnUndo != null) {
            btnUndo.setOnClickListener(v -> drawingCanvas.undo());
        }

        btnDownloadDrawing.setOnClickListener(v -> showExportDialog());

        btnToolClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Sketch")
                    .setMessage("Are you sure you want to delete everything?")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        drawingCanvas.clearPoints();
                        Toast.makeText(this, "Canvas Cleared", Toast.LENGTH_SHORT).show();
                        updateZoomText();
                        selectTool("MOVE", btnToolMove);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        drawingCanvas.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() > 1) {
                drawingCanvas.onTouchEvent(event);
                return true;
            }

            float x = event.getX();
            float y = event.getY();

            // Get snapped internal coordinates
            PointF snapped = drawingCanvas.getSnappedPoint(x, y);
            float internalX = snapped.x;
            float internalY = snapped.y;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (currentTool.equals("MOVE")) {
                        drawingCanvas.onTouchEvent(event);
                    } else if (currentTool.equals("SELECT")) {
                        Object hit = drawingCanvas.findObjectAt(x, y);
                        drawingCanvas.setSelectedObject(hit);
                        if (hit != null) {
                            drawingCanvas.saveToHistory();
                            isDragging = true;
                            lastMoveX = internalX;
                            lastMoveY = internalY;
                        }
                    } else if (currentTool.equals("POINT")) {
                        drawingCanvas.addPoint("P", internalX, internalY);
                        selectTool("MOVE", btnToolMove);
                    } else if (currentTool.equals("LINE") || currentTool.equals("CENTERLINE")) {
                        if (firstPoint == null) {
                            firstPoint = drawingCanvas.addPointAndReturn("", internalX, internalY);
                            Toast.makeText(this, "Select End Point", Toast.LENGTH_SHORT).show();
                        } else {
                            boolean isCenterline = currentTool.equals("CENTERLINE");
                            GeometryCanvas.GeoPoint secondPoint = drawingCanvas.addPointAndReturn("", internalX, internalY);
                            drawingCanvas.addLine("", firstPoint, secondPoint, isCenterline);
                            firstPoint = null;
                            selectTool("MOVE", btnToolMove);
                        }
                    } else if (currentTool.equals("CIRCLE")) {
                        if (arcCenter == null) {
                            arcCenter = new PointF(internalX, internalY);
                            Toast.makeText(this, "Select Radius", Toast.LENGTH_SHORT).show();
                        } else {
                            float r = (float) Math.hypot(internalX - arcCenter.x, internalY - arcCenter.y);
                            drawingCanvas.addCircle("", arcCenter.x, arcCenter.y, r);
                            arcCenter = null;
                            selectTool("MOVE", btnToolMove);
                        }
                    } else if (currentTool.equals("RECT")) {
                        if (firstPoint == null) {
                            firstX = internalX; firstY = internalY;
                            firstPoint = new GeometryCanvas.GeoPoint("", internalX, internalY, false);
                            Toast.makeText(this, "Select Opposite Corner", Toast.LENGTH_SHORT).show();
                        } else {
                            drawingCanvas.addRect("", Math.min(firstX, internalX), Math.min(firstY, internalY),
                                    Math.max(firstX, internalX), Math.max(firstY, internalY));
                            firstPoint = null;
                            selectTool("MOVE", btnToolMove);
                        }
                    } else if (currentTool.equals("ARC")) {
                        if (arcCenter == null) {
                            arcCenter = new PointF(internalX, internalY);
                            Toast.makeText(this, "Select Start Point", Toast.LENGTH_SHORT).show();
                        } else if (firstPoint == null) {
                            firstX = internalX; firstY = internalY;
                            firstPoint = new GeometryCanvas.GeoPoint("", internalX, internalY, false);
                            Toast.makeText(this, "Select End Point", Toast.LENGTH_SHORT).show();
                        } else {
                            float r = (float) Math.hypot(firstX - arcCenter.x, firstY - arcCenter.y);
                            float startAngle = (float) Math.toDegrees(Math.atan2(firstY - arcCenter.y, firstX - arcCenter.x));
                            float endAngle = (float) Math.toDegrees(Math.atan2(internalY - arcCenter.y, internalX - arcCenter.x));
                            float sweep = endAngle - startAngle;
                            if (sweep < 0) sweep += 360;
                            drawingCanvas.addArc("", arcCenter.x, arcCenter.y, r, startAngle, sweep);
                            arcCenter = null;
                            firstPoint = null;
                            selectTool("MOVE", btnToolMove);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (currentTool.equals("MOVE")) {
                        drawingCanvas.onTouchEvent(event);
                    } else if (currentTool.equals("SELECT") && isDragging) {
                        float dx = internalX - lastMoveX;
                        float dy = internalY - lastMoveY;
                        drawingCanvas.updateSelected(dx, dy);
                        lastMoveX = internalX;
                        lastMoveY = internalY;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (currentTool.equals("SELECT") && isDragging) {
                        isDragging = false;
                        if (Math.abs(internalX - lastMoveX) < 0.1 && Math.abs(internalY - lastMoveY) < 0.1) {
                            showEditDialog(drawingCanvas.findObjectAt(x, y));
                        }
                    }
                    drawingCanvas.onTouchEvent(event);
                    v.performClick();
                    return true;
            }
            return false;
        });

        btnSave.setOnClickListener(v -> showSaveDialog());
        btnBack.setOnClickListener(v -> finish());
    }

    private void showExportDialog() {
        String[] options = {"PNG Image", "JPEG Image", "PDF Document"};
        new AlertDialog.Builder(this)
                .setTitle("Export Drawing")
                .setItems(options, (dialog, which) -> {
                    Bitmap bitmap = drawingCanvas.getBitmap();
                    String baseName = (originalName != null && !originalName.isEmpty() ? originalName : "Drawing_" + System.currentTimeMillis());
                    if (which == 0) saveAsImage(bitmap, baseName + ".png", Bitmap.CompressFormat.PNG, "image/png");
                    else if (which == 1) saveAsImage(bitmap, baseName + ".jpg", Bitmap.CompressFormat.JPEG, "image/jpeg");
                    else saveAsPdf(bitmap, baseName + ".pdf");
                })
                .show();
    }

    private void saveAsImage(Bitmap bitmap, String fileName, Bitmap.CompressFormat format, String mimeType) {
        if (bitmap == null) return;
        try {
            OutputStream fos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LemmCAD");
                Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                fos = getContentResolver().openOutputStream(imageUri);
            } else {
                String imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
                java.io.File image = new java.io.File(imagesDir, fileName);
                fos = new java.io.FileOutputStream(image);
            }

            bitmap.compress(format, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export Failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void saveAsPdf(Bitmap bitmap, String fileName) {
        if (bitmap == null) return;
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);

        android.graphics.Canvas canvas = page.getCanvas();
        canvas.drawBitmap(bitmap, 0, 0, null);
        document.finishPage(page);

        try {
            OutputStream fos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/LemmCAD");
                Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), contentValues);
                fos = getContentResolver().openOutputStream(uri);
            } else {
                java.io.File file = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName);
                fos = new java.io.FileOutputStream(file);
            }
            document.writeTo(fos);
            document.close();
            fos.close();
            Toast.makeText(this, "PDF Saved to Documents", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "PDF Export Failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void updateZoomText() {
        tvZoomPercent.setText(drawingCanvas.getZoomPercentage() + "%");
    }

    private void selectTool(String tool, ImageButton button) {
        currentTool = tool;
        firstPoint = null;
        arcCenter = null;
        isDragging = false;
        ImageButton[] buttons = {btnToolMove, btnToolSelect, btnToolPoint, btnToolLine, btnToolCenterline, btnToolCircle, btnToolRect, btnToolArc};
        for (ImageButton b : buttons) {
            if (b != null) b.setBackgroundColor(Color.TRANSPARENT);
        }
        if (button != null) button.setBackgroundColor(Color.parseColor("#BBDEFB"));
        drawingCanvas.setSelectedObject(null);
    }

    private void showEditDialog(Object obj) {
        if (obj == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Geometry");
        final EditText input = new EditText(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(50, 20, 50, 10);
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        if (obj instanceof GeometryCanvas.GeoCircle) {
            builder.setMessage("Radius:");
            input.setText(String.valueOf(((GeometryCanvas.GeoCircle)obj).radius));
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        } else if (obj instanceof GeometryCanvas.GeoPoint) {
            builder.setMessage("Label:");
            input.setText(((GeometryCanvas.GeoPoint)obj).label);
        } else if (obj instanceof GeometryCanvas.GeoLine) {
            builder.setMessage("Name/Dimension:");
            input.setText(((GeometryCanvas.GeoLine)obj).label);
        }

        builder.setPositiveButton("Update", (dialog, which) -> {
            drawingCanvas.saveToHistory();
            String val = input.getText().toString().trim();
            if (obj instanceof GeometryCanvas.GeoCircle) {
                try { ((GeometryCanvas.GeoCircle)obj).radius = Float.parseFloat(val); } catch(Exception ignored){}
            } else if (obj instanceof GeometryCanvas.GeoPoint) {
                ((GeometryCanvas.GeoPoint)obj).label = val;
            } else if (obj instanceof GeometryCanvas.GeoLine) {
                ((GeometryCanvas.GeoLine)obj).label = val;
            }
            drawingCanvas.invalidate();
        });
        builder.setNeutralButton("Delete", (dialog, which) -> {
            drawingCanvas.saveToHistory();
            drawingCanvas.deleteSelected();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showSaveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Finish Sketch");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);
        final EditText input = new EditText(this);
        input.setText(originalName != null && !originalName.isEmpty() ? originalName : "Sketch_" + (System.currentTimeMillis() % 10000));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSelectAllOnFocus(true);
        layout.addView(input);
        builder.setView(layout);
        builder.setPositiveButton("Save", (dialog, which) -> saveToDb(input.getText().toString().trim(), false));
        if (editId != -1) {
            builder.setNeutralButton("Overwrite", (dialog, which) -> saveToDb(input.getText().toString().trim(), true));
        }
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void saveToDb(String name, boolean overwrite) {
        String data = drawingCanvas.getDrawingData();
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "GuestUser");
        String finalName = name.isEmpty() ? "Sketch_" + (System.currentTimeMillis() % 10000) : name;
        if (overwrite && editId != -1) {
            dbHelper.updateDrawing(editId, finalName, data);
            Toast.makeText(this, "Updated!", Toast.LENGTH_SHORT).show();
        } else {
            dbHelper.addDrawing(username, finalName, data);
            Toast.makeText(this, "Saved to History", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
