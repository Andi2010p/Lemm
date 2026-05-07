package com.example.lemm;

import android.content.DialogInterface;
import android.database.Cursor;
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
import com.google.firebase.auth.FirebaseAuth;

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
    private String lastSolutionText = "";
    private String lastAIResponse = "";

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
        findViewById(R.id.btnMinimizeCanvas).setOnClickListener(v -> setCanvasWeight(0.25f, 0.75f));

        findViewById(R.id.btnStopAI).setOnClickListener(v -> resetAll());
        findViewById(R.id.btnSolveProblem).setOnClickListener(v -> {
            String problem = etDescription.getText().toString().trim();
            if (!problem.isEmpty()) solveWithAI(problem);
            else Toast.makeText(this, "Enter a problem", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnToggleInput).setOnClickListener(v -> 
            inputArea.setVisibility(inputArea.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // History Button
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistoryDialog());

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

        // Save / Discard
        findViewById(R.id.btnSaveSolution).setOnClickListener(v -> showSaveDialog());
        findViewById(R.id.btnDiscard).setOnClickListener(v -> resetAll());
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
                        "1. For any CONE use CONE3D. Do NOT use CIRCLE3D + LINE3D.\n" +
                        "2. Coordinates: Y is UP. Center is (0,0,0). Use sizes between 50 and 200.\n" +
                        "3. To make a cone look solid, the AI only needs to output one CONE3D command.\n\n" +
                        "4. Never use CIRCLE3D + LINE3D to describe a cone. Use CONE3D only.\n" +
                        "5. For buildings with 'non-straight' sides, always set curvature > 1.5.\n" +
                        "6. Use coordinates like (0,0,0) and heights around 200.\n\n" +
                        "Example for a curved skyscraper: CONE3D:SkyTower,0,0,0,50,300,2.2\n\n" +
                        "- CURVATURE: 1.0 is a normal cone. 2.5 is a thin 'Shard' building (concave). 0.5 is a rounded 'Bullet' dome (convex).\n" +
                        "- Use CONE3D with curvature > 1.5 to create modern aesthetic skyscrapers.\n" +
                        "Make plane3d for each face."+
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

        for (String line : lines) {
            line = line.trim();
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                String cmd = parts[0].trim().toUpperCase();
                String[] d = parts[1].trim().split(",");
                for(int i=0; i<d.length; i++) d[i] = d[i].trim();

                try {
                    if (cmd.contains("CONE3D") && d.length >= 6) {
                        canvas3D.addCone(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), d.length>=7?f(d[6]):1.0f);
                    } else if (cmd.contains("PYRAMID3D") && d.length >= 7) {
                        canvas3D.addPyramid(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]), f(d[6]));
                    } else if (cmd.contains("CYLINDER3D") && d.length >= 6) {
                        canvas3D.addCylinder(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]), f(d[5]));
                    } else if (cmd.contains("SPHERE3D") && d.length >= 5) {
                        canvas3D.addSphere(d[0], f(d[1]), f(d[2]), f(d[3]), f(d[4]));
                    } else if (cmd.contains("DRAW3D") && d.length >= 4) {
                        canvas3D.addPoint(d[0], f(d[1]), f(d[2]), f(d[3]));
                    } else if (cmd.contains("LINE3D") && d.length >= 2) {
                        canvas3D.addLine(d[0], d[1]);
                    } else {
                        solutionBuilder.append(line).append("\n");
                    }
                } catch (Exception e) { solutionBuilder.append(line).append("\n"); }
            } else {
                solutionBuilder.append(line).append("\n");
            }
        }
        lastSolutionText = solutionBuilder.toString().trim();
        stepsContainer.removeAllViews();
        addSolutionCard(lastSolutionText);
        solutionControls.setVisibility(View.VISIBLE);
        inputArea.setVisibility(View.GONE);
        canvas3D.invalidate();
    }

    private void addSolutionCard(String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 16, 24, 16); card.setLayoutParams(lp);
        card.setRadius(24f); card.setCardElevation(6f);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 32, 40, 32);
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(16f); tv.setTextColor(Color.BLACK);
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
            String currentUser = FirebaseAuth.getInstance().getCurrentUser() != null ? 
                                FirebaseAuth.getInstance().getCurrentUser().getEmail() : "Guest";
            dbHelper.addHistory(currentUser, name, etDescription.getText().toString(), lastSolutionText, lastAIResponse);
            Toast.makeText(this, "Saved as " + name, Toast.LENGTH_SHORT).show();
            resetAll();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showHistoryDialog() {
        String user = FirebaseAuth.getInstance().getCurrentUser() != null ? 
                     FirebaseAuth.getInstance().getCurrentUser().getEmail() : "Guest";
        Cursor cursor = dbHelper.getHistory(user);
        List<String> names = new ArrayList<>();
        List<String> rawResponses = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        
        while (cursor.moveToNext()) {
            names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            rawResponses.add(cursor.getString(cursor.getColumnIndexOrThrow("raw_response")));
            problems.add(cursor.getString(cursor.getColumnIndexOrThrow("problem")));
        }
        cursor.close();

        if (names.isEmpty()) {
            Toast.makeText(this, "No history found", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("History");
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            etDescription.setText(problems.get(which));
            processAIResult(rawResponses.get(which));
        });
        builder.show();
    }

    private void resetAll() {
        canvas3D.clear();
        // etDescription is NOT cleared anymore
        etExtra.setText("");
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);
        setCanvasWeight(0.6f, 0.4f);
    }

    private float f(String s) { try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; } }
}
