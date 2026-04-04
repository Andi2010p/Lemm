package com.example.lemm;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class DrawingActivity extends AppCompatActivity {

    private GeometryCanvas drawingCanvas;
    private MaterialButton btnToolPen, btnToolCircle, btnToolClear;
    private Button btnSave, btnBack;
    
    private String currentTool = "PEN";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawing);

        initViews();
        setupListeners();
    }

    private void initViews() {
        drawingCanvas = findViewById(R.id.drawingCanvas);
        btnToolPen = findViewById(R.id.btnToolPen);
        btnToolCircle = findViewById(R.id.btnToolCircle);
        btnToolClear = findViewById(R.id.btnToolClear);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
        
        // Highlight active tool
        btnToolPen.setStrokeWidth(4);
        btnToolPen.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF1A237E));
    }

    private void setupListeners() {
        btnToolPen.setOnClickListener(v -> selectTool("PEN", btnToolPen));
        btnToolCircle.setOnClickListener(v -> selectTool("CIRCLE", btnToolCircle));
        
        btnToolClear.setOnClickListener(v -> {
            drawingCanvas.clearPoints();
            Toast.makeText(this, "Canvas Cleared", Toast.LENGTH_SHORT).show();
        });

        drawingCanvas.setOnTouchListener((v, event) -> {
            float x = event.getX();
            float y = event.getY();
            
            // Logic to map screen touch to GeometryCanvas internal 200,200 centered space
            float internalX = (x - drawingCanvas.getWidth() / 2f) / (Math.min(drawingCanvas.getWidth(), drawingCanvas.getHeight()) / 500f) + 200;
            float internalY = 200 - (y - drawingCanvas.getHeight() / 2f) / (Math.min(drawingCanvas.getWidth(), drawingCanvas.getHeight()) / 500f);

            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                if (currentTool.equals("PEN")) {
                    drawingCanvas.addPoint("", internalX, internalY);
                } else if (currentTool.equals("CIRCLE") && event.getAction() == MotionEvent.ACTION_DOWN) {
                    drawingCanvas.addCircle("O", internalX, internalY, 50f);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                drawingCanvas.penUp();
                return true;
            }
            return false;
        });

        btnSave.setOnClickListener(v -> saveDrawing());
        btnBack.setOnClickListener(v -> finish());
    }

    private void selectTool(String tool, MaterialButton button) {
        currentTool = tool;
        
        // Reset styles
        btnToolPen.setStrokeWidth(0);
        btnToolCircle.setStrokeWidth(0);
        
        // Highlight selected
        button.setStrokeWidth(4);
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF1A237E));
        
        Toast.makeText(this, "Tool: " + tool, Toast.LENGTH_SHORT).show();
    }

    private void saveDrawing() {
        Bitmap bitmap = Bitmap.createBitmap(drawingCanvas.getWidth(), drawingCanvas.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawingCanvas.draw(canvas);

        File path = getExternalFilesDir(null);
        File file = new File(path, "drawing_" + System.currentTimeMillis() + ".png");

        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Toast.makeText(this, "Saved: " + file.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show();
        }
    }
}
