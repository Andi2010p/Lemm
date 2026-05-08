package com.example.lemm;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeometryInputActivity extends AppCompatActivity {

    private static final String TAG = "GeometryInput";
    private GeometryCanvas3D canvas3D;
    private EditText etDescription, etExtra;
    private TextView tvZoom;
    private LinearLayout inputArea, rotationControls, stepsContainer, solutionControls;
    private ProgressBar progressBar;
    private GeminiAI geminiAI;
    private DatabaseHelper dbHelper;

    private MaterialCardView canvasCard;
    private LinearLayout resultSection;
    private String lastSolutionText = ""; // Stores only the textual solution, not commands
    private String lastAIResponse = ""; // Stores the raw AI response (commands + solution)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);

        dbHelper = new DatabaseHelper(this);
        canvas3D = findViewById(R.id.geometryCanvas3D);
        etDescription = findViewById(R.id.etDescription);
        etExtra = findViewById(R.id.etExtraCommands);
        tvZoom = findViewById(R.id.tvZoomPercent);
        inputArea = findViewById(R.id.inputExpandableArea);
        rotationControls = findViewById(R.id.rotationControls);
        stepsContainer = findViewById(R.id.stepsContainer);
        solutionControls = findViewById(R.id.solutionControls);
        progressBar = findViewById(R.id.progressBar);
        canvasCard = findViewById(R.id.canvasCard);
        resultSection = findViewById(R.id.resultSection);

        geminiAI = new GeminiAI(BuildConfig.GEMINI_API_KEY);

        // Resize Logic
        findViewById(R.id.btnMaximizeCanvas).setOnClickListener(v -> setCanvasWeight(0.85f, 0.15f));
        findViewById(R.id.btnMinimizeCanvas).setOnClickListener(v -> setCanvasWeight(0.6f, 0.4f));

        findViewById(R.id.btnStopAI).setOnClickListener(v -> resetAll());
        findViewById(R.id.btnSolveProblem).setOnClickListener(v -> {
            String problem = etDescription.getText().toString().trim();
            if (!problem.isEmpty()) solveWithAI(problem);
            else Toast.makeText(this, "Enter a problem", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnToggleInput).setOnClickListener(v -> 
            inputArea.setVisibility(inputArea.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // History Button - Go to HistoryActivity
        findViewById(R.id.btnHistory).setOnClickListener(v -> {
            Intent intent = new Intent(this, HistoryActivity.class);
            startActivity(intent);
        });

        // Rotation
        findViewById(R.id.btnRotXPlus).setOnClickListener(v -> canvas3D.rotateX(10f));
        findViewById(R.id.btnRotXMinus).setOnClickListener(v -> canvas3D.rotateX(-10f));
        findViewById(R.id.btnRotYPlus).setOnClickListener(v -> canvas3D.rotateY(10f));
        findViewById(R.id.btnRotYMinus).setOnClickListener(v -> canvas3D.rotateY(-10f));
        findViewById(R.id.btnRotZPlus).setOnClickListener(v -> canvas3D.rotateZ(10f));
        findViewById(R.id.btnRotZMinus).setOnClickListener(v -> canvas3D.rotateZ(-10f));

        // Zoom
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> canvas3D.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> canvas3D.zoomOut());
        canvas3D.setOnZoomChangeListener(pct -> tvZoom.setText(pct + "%"));

        // Move/Rotate Toggle
        ImageButton btnToggleMode = findViewById(R.id.btnToggleMoveRotate);
        btnToggleMode.setOnClickListener(v -> {
            boolean isMove = !canvas3D.isMoveMode();
            canvas3D.setMoveMode(isMove);
            btnToggleMode.setColorFilter(isMove ? Color.RED : ContextCompat.getColor(this, R.color.primary));
            Toast.makeText(this, isMove ? "Move Mode" : "Rotate Mode", Toast.LENGTH_SHORT).show();
        });

        // Save / Discard
        findViewById(R.id.btnSaveSolution).setOnClickListener(v -> showSaveDialog());
        findViewById(R.id.btnDiscard).setOnClickListener(v -> resetAll());

        // Handle data from History Activity
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("SAVED_RAW")) {
            String raw = intent.getStringExtra("SAVED_RAW");
            String problem = intent.getStringExtra("SAVED_PROBLEM");
            if (problem != null) etDescription.setText(problem);
            processAIResult(raw);
        }
    }

    private void setCanvasWeight(float canvasWeight, float resultWeight) {
        androidx.constraintlayout.widget.ConstraintLayout root = (androidx.constraintlayout.widget.ConstraintLayout) canvasCard.getParent();
        androidx.constraintlayout.widget.ConstraintSet set = new androidx.constraintlayout.widget.ConstraintSet();
        set.clone(root);
        set.setVerticalWeight(R.id.canvasCard, canvasWeight);
        set.setVerticalWeight(R.id.resultSection, resultWeight);
        set.applyTo(root);
    }

    private void solveWithAI(String problem) {
        progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnSolveProblem).setEnabled(false);
        canvas3D.clear();
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);

        String extra = etExtra.getText().toString().trim();
        String prompt =
                "SYSTEM: You are a CAD Geometry Engine. You MUST output DRAWING COMMANDS for any shape mentioned.\n" +
                        "TASK: Analyze the problem and output DRAWING COMMANDS followed by the step-by-step solution.\n\n" +
                        "RULE: To draw a building that is cone-shaped (pyramid with circular base), use CONE3D.\n" +
                        "COMMAND: CONE3D:Label,cx,cy,cz,radius,height,curvature\n" +
                        "- cx,cy,cz: Center of the circular base.\n" +
                        "- radius: Radius of the base.\n" +
                        "- height: Vertical height from base to apex.\n" +
                        "- curvature: 1.0 (standard cone), 2.5 (concave shard), 0.5 (convex dome).\n\n" +
                        "OTHER COMMANDS:\n" +
                        "DRAW3D:Label,x,y,z\n" +
                        "LINE3D:Label1,Label2\n" +
                        "PYRAMID3D:Label,cx,cy,cz,width,depth,height\n" +
                        "CYLINDER3D:Label,cx,cy,cz,radius,height\n" +
                        "SPHERE3D:Label,x,y,z,radius\n" +
                        "PLANE3D:Label,v1,v2,v3,v4 (Four labels for a face)\n\n" +
                        "CRITICAL RULES:\n" +
                        "1. For any pointed shape with a circular base, use CONE3D. Do NOT use lines/circles.\n" +
                        "2. Y is UP. Center base at (0,0,0). Use sizes like 50-200.\n" +
                        "3. Use Unicode math symbols (√, ×, ÷, ^). No LaTeX(dont use dolar sign, /sqrt or other things {}etc).\n" +
                        "4. Make it look like a solid building using CONE3D/PYRAMID3D.(Cone3D if its cone or its base is circle.\n\n" +
                        "SOLUTION FORMAT: Start each step with 'Step X: ' (e.g., 'Step 1: Calculate...'). End the solution with 'FINAL ANSWER: ' followed by the result.\n\n" +
                        "PROBLEM:\n" + problem + "\n" +
                        (extra.isEmpty() ? "" : "\nADDITIONAL INSTRUCTIONS:\n" + extra);

        ListenableFuture<GenerateContentResponse> future = geminiAI.getSolution(prompt);
        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);
                    lastAIResponse = result.getText();
                    if (lastAIResponse != null) processAIResult(lastAIResponse);
                });
            }
            @Override
            public void onFailure(Throwable t) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    findViewById(R.id.btnSolveProblem).setEnabled(true);
                    Toast.makeText(GeometryInputActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processAIResult(String text) {
        canvas3D.clear();
        String cleanText = text.replace("`", "").replace("*", "");
        String[] lines = cleanText.split("\n");
        StringBuilder solutionBuilder = new StringBuilder();

        // Pattern to find drawing commands
        Pattern commandPattern = Pattern.compile("(CONE3D|PYRAMID3D|CYLINDER3D|SPHERE3D|DRAW3D|LINE3D|PLANE3D):(.*)");

        for (String line : lines) {
            line = line.trim();
            Matcher matcher = commandPattern.matcher(line);
            if (matcher.matches()) {
                String cmd = matcher.group(1).toUpperCase();
                String[] d = matcher.group(2).trim().split(",");
                for(int i=0; i<d.length; i++) d[i] = d[i].trim();

                try {
                    if (cmd.equals("CONE3D") && d.length >= 6) {
                        canvas3D.addCone(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), d.length>=7?f(d[6]):1.0f);
                    } else if (cmd.equals("PYRAMID3D") && d.length >= 7) {
                        canvas3D.addPyramid(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), f(d[6]));
                    } else if (cmd.equals("CYLINDER3D") && d.length >= 6) {
                        canvas3D.addCylinder(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]));
                    } else if (cmd.equals("SPHERE3D") && d.length >= 5) {
                        canvas3D.addSphere(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]));
                    } else if (cmd.equals("DRAW3D") && d.length >= 4) {
                        canvas3D.addPoint(d[0], f(d[1]), f(d[2]), f(d[3]));
                    } else if (cmd.equals("LINE3D") && d.length >= 2) {
                        canvas3D.addLine(d[0], d[1]);
                    } else if (cmd.equals("PLANE3D") && d.length >= 4) {
                        List<String> labels = new ArrayList<>();
                        for (int i = 1; i < d.length; i++) labels.add(d[i]);
                        canvas3D.addPlane(labels);
                    }
                } catch (Exception e) { Log.e(TAG, "Error parsing command: " + line, e); }
            } else {
                solutionBuilder.append(line).append("\n");
            }
        }

        lastSolutionText = solutionBuilder.toString().trim();
        stepsContainer.removeAllViews();

        Pattern stepPattern = Pattern.compile("^(Step \\d+: .*)|" + Pattern.quote("FINAL ANSWER:") + ".*", Pattern.MULTILINE);
        Matcher stepMatcher = stepPattern.matcher(lastSolutionText);

        List<String> segments = new ArrayList<>();
        int currentSegmentStart = 0;

        while (stepMatcher.find()) {
            String previousSegment = lastSolutionText.substring(currentSegmentStart, stepMatcher.start()).trim();
            if (!previousSegment.isEmpty()) segments.add(previousSegment);
            segments.add(stepMatcher.group(0).trim());
            currentSegmentStart = stepMatcher.end();
        }
        String finalSegment = lastSolutionText.substring(currentSegmentStart).trim();
        if (!finalSegment.isEmpty()) segments.add(finalSegment);

        boolean firstStepProcessed = false;
        for (String segment : segments) {
            if (segment.startsWith("Step 1:") && !firstStepProcessed) {
                addSolutionCard(segment, false, true);
                firstStepProcessed = true;
            } else if (segment.startsWith("FINAL ANSWER:")) {
                addSolutionCard(segment, true, false);
            } else {
                addSolutionCard(segment, false, false);
            }
        }

        solutionControls.setVisibility(View.VISIBLE);
        inputArea.setVisibility(View.GONE);
        canvas3D.invalidate();
    }

    private void addSolutionCard(String text) {
        addSolutionCard(text, false, false);
    }

    private void addSolutionCard(String text, boolean isFinalResult, boolean isFirstStep) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 16, 24, 16); card.setLayoutParams(lp);
        card.setRadius(24f); card.setCardElevation(6f);

        TextView tv = new TextView(this);
        tv.setText(text); 
        tv.setTextColor(Color.BLACK);

        if (isFinalResult) {
            card.setCardBackgroundColor(Color.parseColor("#E8F5E9"));
            card.setStrokeColor(Color.parseColor("#4CAF50"));
            card.setStrokeWidth(2);
            tv.setTextSize(17f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (isFirstStep) {
            card.setCardBackgroundColor(Color.WHITE);
            card.setStrokeColor(ContextCompat.getColor(this, R.color.primary_light));
            card.setStrokeWidth(1);
            tv.setTextSize(18f);
            tv.setTextColor(ContextCompat.getColor(this, R.color.primary_dark));
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            card.setCardBackgroundColor(Color.WHITE);
            card.setStrokeColor(ContextCompat.getColor(this, R.color.primary_light));
            card.setStrokeWidth(1);
            tv.setTextSize(16f);
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 32, 40, 32);
        layout.addView(tv); card.addView(layout);
        stepsContainer.addView(card);
    }

    private void showSaveDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Solution");
        final EditText input = new EditText(this);
        input.setText("unsave"); 
        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString();
            SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String currentUser = pref.getString("username", "GuestUser");
            dbHelper.addHistory(currentUser, name, etDescription.getText().toString(), lastSolutionText, lastAIResponse);
            Toast.makeText(this, "Saved to History", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, HistoryActivity.class);
            startActivity(intent);
            resetAll();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void resetAll() {
        canvas3D.clear();
        etExtra.setText("");
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);
        setCanvasWeight(0.6f, 0.4f);
        canvas3D.setMoveMode(false);
        ((ImageButton) findViewById(R.id.btnToggleMoveRotate)).setColorFilter(ContextCompat.getColor(this, R.color.primary));    }

    private float f(String s) { try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; } }
}
