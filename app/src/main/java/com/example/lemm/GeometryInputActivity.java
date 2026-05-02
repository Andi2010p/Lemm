package com.example.lemm;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeometryInputActivity extends AppCompatActivity {

    private static final String TAG = "GeometryInput";
    private GeometryCanvas3D canvas3D;
    private EditText etDescription, etExtra;
    private TextView tvZoom;
    private LinearLayout inputArea, rotationControls, stepsContainer;
    private ProgressBar progressBar;
    private GeminiAI geminiAI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);

        // Bind Views
        canvas3D = findViewById(R.id.geometryCanvas3D);
        etDescription = findViewById(R.id.etDescription);
        etExtra = findViewById(R.id.etExtraCommands);
        tvZoom = findViewById(R.id.tvZoomPercent);
        inputArea = findViewById(R.id.inputExpandableArea);
        rotationControls = findViewById(R.id.rotationControls);
        stepsContainer = findViewById(R.id.stepsContainer);
        progressBar = findViewById(R.id.progressBar);

        // Initialize AI
        geminiAI = new GeminiAI(BuildConfig.GEMINI_API_KEY);

        // --- NEW PROBLEM BUTTON (Trash Icon) ---
        ImageButton btnNewProblem = findViewById(R.id.btnStopAI);
        if (btnNewProblem != null) {
            btnNewProblem.setVisibility(View.VISIBLE);
            btnNewProblem.setOnClickListener(v -> resetAll());
        }

        // --- SOLVE BUTTON (Analyze and Visualize) ---
        findViewById(R.id.btnSolveProblem).setOnClickListener(v -> {
            String problem = etDescription.getText().toString().trim();
            if (!problem.isEmpty()) {
                solveWithAI(problem);
            } else {
                Toast.makeText(this, "Please enter a geometry problem", Toast.LENGTH_SHORT).show();
            }
        });

        // Toggle Input Card
        findViewById(R.id.btnToggleInput).setOnClickListener(v -> {
            inputArea.setVisibility(inputArea.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        // Rotation Controls
        findViewById(R.id.btnRotXPlus).setOnClickListener(v -> canvas3D.rotateX(10f));
        findViewById(R.id.btnRotXMinus).setOnClickListener(v -> canvas3D.rotateX(-10f));
        findViewById(R.id.btnRotYPlus).setOnClickListener(v -> canvas3D.rotateY(10f));
        findViewById(R.id.btnRotYMinus).setOnClickListener(v -> canvas3D.rotateY(-10f));
        findViewById(R.id.btnRotZPlus).setOnClickListener(v -> canvas3D.rotateZ(10f));
        findViewById(R.id.btnRotZMinus).setOnClickListener(v -> canvas3D.rotateZ(-10f));

        // Zoom Controls
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> canvas3D.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> canvas3D.zoomOut());
        canvas3D.setOnZoomChangeListener(pct -> tvZoom.setText(pct + "%"));

        // Set UI Defaults
        canvas3D.setVisibility(View.VISIBLE);
        rotationControls.setVisibility(View.VISIBLE);
    }

    private void solveWithAI(String problem) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnSolveProblem).setEnabled(false);
        
        canvas3D.clear();
        stepsContainer.removeAllViews();

        String extra = etExtra.getText().toString().trim();
        String prompt =
                "You are an expert Geometry Solver and CAD Modeling AI.\n" +
                "Your task is to analyze geometry problems and generate BOTH:\n" +
                "1) 3D Drawing commands for visualization\n" +
                "2) Step-by-step mathematical solution\n\n" +

                "================ 3D DRAWING COMMANDS =================\n" +
                "DRAW3D:Label,x,y,z (Creates a vertex point)\n" +
                "LINE3D:Label1,Label2 (Draws an edge between points)\n" +
                "PLANE3D:Label1,Label2,Label3... (Creates a SHADED FLAT FACE using point labels)\n" +
                "CIRCLE3D:Label,cx,cy,cz,radius (Creates a FILLED/SHADED CIRCULAR FACE/DISK)\n" +
                "SPHERE3D:Label,x,y,z,radius (Wireframe sphere)\n" +
                "CONE3D:Label,cx,cy,cz,radius,height (Creates a SOLID SHADED CONE including base)\n\n" +

                "================ RULES =================\n" +
                "- Output Drawing Commands first, one per line.\n" +
                "- CIRCULAR FACES: If a face is a circle (like a cylinder top or stand-alone disk), use CIRCLE3D to ensure it is shaded/filled.\n" +
                "- CONES: For any cone, use CONE3D. Do not try to draw it with lines/planes.\n" +
                "- PLANE RULE: For polygonal solids (cubes, prisms), define every face using PLANE3D.\n" +
                "- COORDINATES: Use values between -300 and 500.\n" +
                "- SOLUTION: Start with 'Overview: Definitions of variables'.\n" +
                "- Use 'Step X: [Title]' for each step card.\n" +
                "- End with 'Final Answer: [Value]'.\n" +
                "- Use Unicode math symbols (√, ×, ÷, ^, °, ∠, Δ).\n\n" +

                "PROBLEM:\n" + problem + "\n" +
                (extra.isEmpty() ? "" : "\nADDITIONAL INSTRUCTIONS:\n" + extra);

        ListenableFuture<GenerateContentResponse> future = geminiAI.getSolution(prompt);

        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);
                    
                    String aiResponse = result.getText();
                    if (aiResponse != null) {
                        processAIResult(aiResponse);
                        inputArea.setVisibility(View.GONE);
                        findViewById(R.id.solutionControls).setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);
                    Log.e(TAG, "AI ERROR: ", t);
                    Toast.makeText(GeometryInputActivity.this, "AI Work Failed. Try rephrasing.", Toast.LENGTH_SHORT).show();
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processAIResult(String text) {
        String cleanText = text.replaceAll("(?s)```.*?```", "").replace("`", "");

        Pattern commandPattern = Pattern.compile("(?i)(DRAW3D|LINE3D|PLANE3D|SPHERE3D|CONE3D|CIRCLE3D)\\s*:\\s*([^\\n\\r]+)");
        Matcher matcher = commandPattern.matcher(cleanText);

        while (matcher.find()) {
            String command = matcher.group(1).toUpperCase();
            String data = matcher.group(2).trim();
            
            String[] segments = data.split("\\|");
            for (String segment : segments) {
                String[] d = segment.split(",");
                if (d.length < 2) continue;
                for (int i = 0; i < d.length; i++) d[i] = d[i].trim();

                try {
                    switch (command) {
                        case "DRAW3D":   if (d.length >= 4) canvas3D.addPoint(d[0], f(d[1]), f(d[2]), f(d[3])); break;
                        case "LINE3D":   if (d.length >= 2) canvas3D.addLine(d[0], d[1]); break;
                        case "PLANE3D":  if (d.length >= 3) canvas3D.addPlane(new ArrayList<>(Arrays.asList(d))); break;
                        case "SPHERE3D": if (d.length >= 5) canvas3D.addSphere(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4])); break;
                        case "CONE3D":   if (d.length >= 6) canvas3D.addCone(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5])); break;
                        case "CIRCLE3D": if (d.length >= 5) canvas3D.addCircle(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4])); break;
                    }
                } catch (Exception ignored) {}
            }
        }
        canvas3D.invalidate();

        stepsContainer.removeAllViews();
        String solutionOnly = cleanText.replaceAll("(?i)(DRAW3D|LINE3D|PLANE3D|SPHERE3D|CONE3D|CIRCLE3D)\\s*:[^\\n\\r]+", "").trim();

        String[] sections = solutionOnly.split("(?i)(?=Step\\s*\\d+\\s*:|Overview\\s*:)");
        for (String section : sections) {
            String trimmed = section.trim();
            if (!trimmed.isEmpty()) {
                addSolutionCard(trimmed);
            }
        }
    }

    private void addSolutionCard(String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 16, 24, 16);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(8f);
        card.setCardBackgroundColor(Color.WHITE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 32, 40, 32);

        String[] lines = text.split("\n", 2);
        TextView tvHeader = new TextView(this);
        tvHeader.setText(lines[0].trim());
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setTextSize(18f);
        tvHeader.setTextColor(Color.parseColor("#0C3D6A"));
        tvHeader.setPadding(0, 0, 0, 16);
        layout.addView(tvHeader);

        if (lines.length > 1) {
            TextView tvBody = new TextView(this);
            tvBody.setText(lines[1].trim());
            tvBody.setTextSize(16f);
            tvBody.setTextColor(Color.BLACK);
            tvBody.setLineSpacing(0f, 1.2f);
            layout.addView(tvBody);
        }

        card.addView(layout);
        stepsContainer.addView(card);
    }

    private void resetAll() {
        canvas3D.clear();
        etDescription.setText("");
        etExtra.setText("");
        stepsContainer.removeAllViews();
        tvZoom.setText("100%");
        inputArea.setVisibility(View.VISIBLE);
        findViewById(R.id.solutionControls).setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        findViewById(R.id.btnSolveProblem).setEnabled(true);
    }

    private float f(String s) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; }
    }
}
