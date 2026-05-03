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

    // Views for resizing
    private MaterialCardView canvasCard;
    private LinearLayout resultSection;

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

        canvasCard = findViewById(R.id.canvasCard);
        resultSection = findViewById(R.id.resultSection);

        // Initialize AI
        geminiAI = new GeminiAI(BuildConfig.GEMINI_API_KEY);

        // --- RESIZE BUTTONS LOGIC ---
        findViewById(R.id.btnMaximizeCanvas).setOnClickListener(v -> {
            View canvasCard = findViewById(R.id.canvasCard);
            View resultSection = findViewById(R.id.resultSection);

            // Canvas takes 85% of screen
            ((LinearLayout.LayoutParams) canvasCard.getLayoutParams()).weight = 0.85f;
            ((LinearLayout.LayoutParams) resultSection.getLayoutParams()).weight = 0.15f;

            canvasCard.requestLayout();
            resultSection.requestLayout();
        });

        findViewById(R.id.btnMinimizeCanvas).setOnClickListener(v -> {
            View canvasCard = findViewById(R.id.canvasCard);
            View resultSection = findViewById(R.id.resultSection);

            // Original 60/40 split
            ((LinearLayout.LayoutParams) canvasCard.getLayoutParams()).weight = 0.6f;
            ((LinearLayout.LayoutParams) resultSection.getLayoutParams()).weight = 0.4f;

            canvasCard.requestLayout();
            resultSection.requestLayout();
        });

        // --- NEW PROBLEM BUTTON ---
        ImageButton btnNewProblem = findViewById(R.id.btnStopAI);
        if (btnNewProblem != null) {
            btnNewProblem.setVisibility(View.VISIBLE);
            btnNewProblem.setOnClickListener(v -> resetAll());
        }

        // --- SOLVE BUTTON ---
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

    private void updateWeights(float canvasW, float resultW) {
        LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) canvasCard.getLayoutParams();
        p1.weight = canvasW;
        canvasCard.setLayoutParams(p1);

        LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) resultSection.getLayoutParams();
        p2.weight = resultW;
        resultSection.setLayoutParams(p2);
    }

    private void solveWithAI(String problem) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnSolveProblem).setEnabled(false);

        canvas3D.clear();
        stepsContainer.removeAllViews();

        String extra = etExtra.getText().toString().trim();
        String prompt =
                "SYSTEM: You are a CAD Geometry Engine. You MUST output DRAWING COMMANDS for any shape mentioned in the problem.\n\n" +
                        "You are an expert Geometry Solver and CAD Modeling AI.\n" +
                        "Your task is to analyze geometry problems and generate BOTH:\n" +
                        "1) 3D Drawing commands for visualization\n" +
                        "2) Step-by-step mathematical solution in cards\n\n" +

                        "================ 3D COMMANDS =================\n" +
                        "DRAW3D:Label,x,y,z (Vertex point)\n" +
                        "LINE3D:Label1,Label2 (Draws an edge between points)\n" +
                        "PLANE3D:L1,L2,L3... (SHADED FACE: Use point labels to fill a flat polygon)\n" +
                        "CIRCLE3D:Label,cx,cy,cz,radius (SHADED CIRCLE)\n" +
                        "CONE3D:Label,cx,cy,cz,radius,height,curvature\n" +
                        "- curvature 2.5: Side faces curve INWARD (Concave/Spire look).\n" +
                        "- curvature 0.6: Side faces curve OUTWARD (Convex/Dome look).\n" +
                        "- height: The vertical size of the building.\n\n" +

                        "CRITICAL RULES:\n" +
                        "1. Never use CIRCLE3D + LINE3D to describe a cone. Use CONE3D only.\n" +
                        "2. For buildings with 'non-straight' sides, always set curvature > 1.5.\n" +
                        "3. Use coordinates like (0,0,0) and heights around 200.\n\n" +
                        "CRITICAL: Do NOT draw a cone using LINE3D or CIRCLE3D. Use CONE3D ONLY.\n" +
                        "Example for a curved skyscraper: CONE3D:SkyTower,0,0,0,50,300,2.2\n\n" +
                        "- CURVATURE: 1.0 is a normal cone. 2.5 is a thin 'Shard' building (concave). 0.5 is a rounded 'Bullet' dome (convex).\n" +
                        "- Use CONE3D with curvature > 1.5 to create modern aesthetic skyscrapers.\n" +
                        "CYLINDER3D:Label,cx,cy,cz,radius,height (SOLID CYLINDER)\n" +
                        "SPHERE3D:Label,x,y,z,radius (Wireframe sphere)\n\n" +

                        "================ MODELING TIPS =================\n" +
                        "- REALIZABLE FACES: To make an object look solid, use PLANE3D or CIRCLE3D.\n" +
                        "- CONES: For any cone, use CONE3D. It handles shaded sides and base automatically.\n" +
                        "- COORDINATES: Center view is (0,0,0). Use sizes like 50-200 units. Y-axis is UP.\n" +
                        "- Output Drawing Commands first, one per line.\n\n" +

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
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processAIResult(String text) {
        // 1. Force 3D Canvas visibility
        canvas3D.setVisibility(View.VISIBLE);
        View g2d = findViewById(R.id.geometryCanvas);
        if (g2d != null) g2d.setVisibility(View.GONE);

        // 2. Clear previous drawings to avoid overlap
        canvas3D.clear();

        String cleanText = text.replaceAll("(?s)```.*?```", "").replace("`", "");
        Pattern commandPattern = Pattern.compile("(?i)(DRAW3D|LINE3D|PLANE3D|SPHERE3D|CONE3D|CIRCLE3D|CYLINDER3D)\\s*:\\s*([^\\n\\r]+)");
        Matcher matcher = commandPattern.matcher(cleanText);

        while (matcher.find()) {
            String command = matcher.group(1).toUpperCase();
            String data = matcher.group(2).trim();
            String[] segments = data.split("\\|"); // Support multiple commands per line

            for (String segment : segments) {
                String[] d = segment.split(",");
                if (d.length < 2) continue;
                for (int i = 0; i < d.length; i++) d[i] = d[i].trim();

                try {
                    switch (command) {
                        case "CONE3D":
                            if (d.length >= 6) {
                                float curv = (d.length >= 7) ? f(d[6]) : 1.8f;
                                canvas3D.addCone(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), curv);
                            }
                            break;
                        case "DRAW3D":
                            if (d.length >= 4) canvas3D.addPoint(d[0], f(d[1]), f(d[2]), f(d[3]));
                            break;
                        case "LINE3D":
                            if (d.length >= 2) canvas3D.addLine(d[0], d[1]);
                            break;
                        case "CIRCLE3D":
                            if (d.length >= 5) canvas3D.addCircle(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]));
                            break;
                        case "CYLINDER3D":
                            if (d.length >= 6) canvas3D.addCylinder(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]));
                            break;
                        case "PLANE3D":
                            if (d.length >= 3) canvas3D.addPlane(new ArrayList<>(Arrays.asList(d)));
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing: " + segment);
                }
            }
        }
        canvas3D.invalidate(); // Request redraw

        // Logic for solution cards
        stepsContainer.removeAllViews();
        String solutionOnly = cleanText.replaceAll("(?i)(DRAW3D|LINE3D|PLANE3D|SPHERE3D|CONE3D|CIRCLE3D|CYLINDER3D)\\s*:[^\\n\\r]+", "").trim();
        String[] sections = solutionOnly.split("(?i)(?=Step\\s*\\d+\\s*:|Overview\\s*:)");
        for (String section : sections) {
            if (!section.trim().isEmpty()) addSolutionCard(section.trim());
        }
    }

    private void addSolutionCard(String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 16, 24, 16);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(6f);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 32, 40, 32);

        TextView tvBody = new TextView(this);
        tvBody.setText(text);
        tvBody.setTextSize(16f);
        tvBody.setTextColor(Color.BLACK);
        layout.addView(tvBody);

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
        updateWeights(0.6f, 0.4f);
    }

    private float f(String s) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; }
    }
}