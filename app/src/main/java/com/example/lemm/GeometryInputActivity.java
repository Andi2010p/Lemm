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
        findViewById(R.id.btnMaximizeCanvas).setOnClickListener(v -> {
            // This allows resizing inside a ConstraintLayout without crashing
            androidx.constraintlayout.widget.ConstraintLayout root = (androidx.constraintlayout.widget.ConstraintLayout) findViewById(R.id.canvasCard).getParent();
            androidx.constraintlayout.widget.ConstraintSet set = new androidx.constraintlayout.widget.ConstraintSet();
            set.clone(root);
            // Canvas 85%, Result 15%
            set.setVerticalWeight(R.id.canvasCard, 0.85f);
            set.setVerticalWeight(R.id.resultSection, 0.15f);
            set.applyTo(root);
        });

        findViewById(R.id.btnMinimizeCanvas).setOnClickListener(v -> {
            androidx.constraintlayout.widget.ConstraintLayout root = (androidx.constraintlayout.widget.ConstraintLayout) findViewById(R.id.canvasCard).getParent();
            androidx.constraintlayout.widget.ConstraintSet set = new androidx.constraintlayout.widget.ConstraintSet();
            set.clone(root);
            // Restore original split: Canvas 70%, Result 30%
            set.setVerticalWeight(R.id.canvasCard, 0.7f);
            set.setVerticalWeight(R.id.resultSection, 0.3f);
            set.applyTo(root);
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
                "SYSTEM:TASK: Analyze the problem and output DRAWING COMMANDS followed by the step-by-step solution.\n\n" +
                          "RULE 1: Use ONLY these exact commands. Do NOT use 'point3d' or 'cad' or any other words.\n" +
                          "RULE 2: To draw a Cone, you MUST use CONE3D. Do NOT use Circle and Lines.\n\n" +
                        "COMMAND: CONE3D:Label,cx,cy,cz,radius,height,curvature\n" +
                        "MATH LOGIC:\n" +
                        "1. (cx, cy, cz) is the center of the bottom circle.\n" +
                        "2. height is how far the tip is above the base.\n" +
                        "3. curvature should be 1.0 for a standard cone.\n" +
                        "4. Y is UP. For a cone on the ground, cy=0 and height=200.\n\n" +
                        "EXAMPLE: CONE3D:MyCone,0,0,0,50,150,1.0\n\n" +
                          "COMMAND LIST:\n" +
                          "DRAW3D:Label,x,y,z\n" +
                          "LINE3D:Label1,Label2\n" +

                          "CONE RULES:\n" +
                          "- curvature 1.0 is a sharp cone.\n" +
                          "- Center is (0,0,0). Y is UP. Height should be 100-300.\n\n" +

                          "EXAMPLE RESPONSE:\n" +
                          "DRAW3D:A,0,0,0\n" +
                          "CONE3D:Cone1,0,0,0,50,200,1.0\n" +
                          "Solution: Volume = 1/3 * PI * r^2 * h...\n\n" +

                        "CRITICAL RULES:\n" +
                        "1. For any CONE or pyramid-like shape, use CONE3D. Do NOT use CIRCLE3D + LINE3D.\n" +
                        "2. Coordinates: Y is UP. Center is (0,0,0). Use sizes between 50 and 200.\n" +
                        "3. To make a cone look solid, the AI only needs to output one CONE3D command.\n\n" +
                        "1. For any CONE or pyramid-like shape, use CONE3D. Do NOT use CIRCLE3D + LINE3D.\n" +
                        "2. Coordinates: Y is UP. Center is (0,0,0). Use sizes between 50 and 200.\n" +
                        "3. To make a cone look solid, the AI only needs to output one CONE3D command.\n\n" +
                        "4. Never use CIRCLE3D + LINE3D to describe a cone. Use CONE3D only.\n" +
                        "5. For buildings with 'non-straight' sides, always set curvature > 1.5.\n" +
                        "6. Use coordinates like (0,0,0) and heights around 200.\n\n" +
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
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);

                    String aiResponse = result.getText();
                    if (aiResponse != null && !aiResponse.isEmpty()) {
                        processAIResult(aiResponse);
                    } else {
                        Toast.makeText(GeometryInputActivity.this, "AI returned empty response", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);
                    Toast.makeText(GeometryInputActivity.this, "AI Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private void processAIResult(String text) {
        canvas3D.setVisibility(View.VISIBLE);
        canvas3D.clear();

        // 1. Remove all formatting that breaks parsing
        String cleanText = text.replace("`", "").replace("*", "");

        // 2. Loop through every line of the AI response
        String[] lines = cleanText.split("\n");
        boolean foundAny = false;

        for (String line : lines) {
            line = line.trim();
            if (!line.contains(":")) continue;

            String[] parts = line.split(":", 2);
            String command = parts[0].trim().toUpperCase();
            String data = parts[1].trim();
            String[] d = data.split(",");

            // Clean each data point
            for(int i=0; i<d.length; i++) d[i] = d[i].trim();

            try {
                if (command.contains("CONE3D") && d.length >= 6) {
                    float curv = (d.length >= 7) ? f(d[6]) : 1.0f;
                    canvas3D.addCone(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), curv);
                    foundAny = true;
                } else if (command.contains("DRAW3D") && d.length >= 4) {
                    canvas3D.addPoint(d[0], f(d[1]), f(d[2]), f(d[3]));
                    foundAny = true;
                } else if (command.contains("LINE3D") && d.length >= 2) {
                    canvas3D.addLine(d[0], d[1]);
                    foundAny = true;
                } else if (command.contains("CIRCLE3D") && d.length >= 5) {
                    canvas3D.addCircle(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]));
                    foundAny = true;
                }
            } catch (Exception e) {
                Log.e("GEO", "Line skip: " + line);
            }
        }

        canvas3D.invalidate();

        // 3. Always show the text solution in cards
        stepsContainer.removeAllViews();
        String solutionOnly = cleanText.replaceAll("(?i)(DRAW3D|LINE3D|PLANE3D|SPHERE3D|CONE3D|CIRCLE3D|CYLINDER3D)\\s*:[^\\n\\r]+", "").trim();
        addSolutionCard(solutionOnly);

        if (!foundAny) {
            Toast.makeText(this, "AI spoke but didn't draw. Check logs.", Toast.LENGTH_SHORT).show();
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