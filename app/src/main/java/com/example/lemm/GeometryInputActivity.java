package com.example.lemm;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.BlockThreshold;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.GenerationConfig;
import com.google.ai.client.generativeai.type.HarmCategory;
import com.google.ai.client.generativeai.type.SafetySetting;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;

public class GeometryInputActivity extends AppCompatActivity {
    private static final String TAG = "GeometryInput";

    private TextInputEditText etDescription;
    private Button btnSolveProblem;
    private ImageButton btnZoomIn, btnZoomOut, btnResizeCanvas;
    private TextView tvZoomPercent;
    private ProgressBar progressBar;
    private LinearLayout stepsContainer;
    private ScrollView resultScrollView;
    private View canvasContainer;

    private GeometryCanvas geometryCanvas;
    private GeometryCanvas3D geometryCanvas3D;

    private boolean isCanvasMaximized = false;
    private Markwon markwon;

    private GenerativeModelFutures model;
    private final Executor executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);

        initViews();
        setupMarkwon();
        setupListeners();
        setupAI();

        String scannedText = getIntent().getStringExtra("SCANNED_TEXT");
        if (scannedText != null && !scannedText.isEmpty()) {
            etDescription.setText(scannedText);
        }
    }

    private void initViews() {
        etDescription = findViewById(R.id.etDescription);
        btnSolveProblem = findViewById(R.id.btnSolveProblem);
        progressBar = findViewById(R.id.progressBar);
        geometryCanvas = findViewById(R.id.geometryCanvas);
        geometryCanvas3D = findViewById(R.id.geometryCanvas3D);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercent = findViewById(R.id.tvZoomPercent);
        stepsContainer = findViewById(R.id.stepsContainer);
        resultScrollView = findViewById(R.id.resultScrollView);
        canvasContainer = findViewById(R.id.canvasContainer);
        btnResizeCanvas = findViewById(R.id.btnResizeCanvas);
    }

    private void setupMarkwon() {
        markwon = Markwon.builder(this)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(36f))
                .build();
    }

    private void setupAI() {
        try {
            String apiKey = BuildConfig.GEMINI_API_KEY;
            if (apiKey != null) {
                apiKey = apiKey.replace("\"", "").replace("'", "").trim();
            }

            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = "AIzaSyBnCcAMGg4TCa_01Zxvd8Mk3RC5EZtp7yo";
            }

            GenerationConfig.Builder configBuilder = new GenerationConfig.Builder();
            configBuilder.temperature = 0.1f;
            configBuilder.topK = 1;
            configBuilder.topP = 0.95f;

            GenerationConfig config = configBuilder.build();
            
            List<SafetySetting> safetySettings = new ArrayList<>();
            safetySettings.add(new SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH));
            safetySettings.add(new SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH));
            safetySettings.add(new SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.ONLY_HIGH));
            safetySettings.add(new SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.ONLY_HIGH));

            GenerativeModel gm = new GenerativeModel(
                    "gemini-1.5-flash",
                    apiKey,
                    config,
                    safetySettings
            );
            model = GenerativeModelFutures.from(gm);
        } catch (Exception e) {
            Log.e(TAG, "AI Setup Error", e);
        }
    }

    private void setupListeners() {
        geometryCanvas.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));
        geometryCanvas3D.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));

        btnZoomIn.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomIn();
            else geometryCanvas.zoomIn();
            updateZoomText();
        });

        btnZoomOut.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomOut();
            else geometryCanvas.zoomOut();
            updateZoomText();
        });

        btnResizeCanvas.setOnClickListener(v -> toggleCanvasSize());

        btnSolveProblem.setOnClickListener(v -> {
            String text = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";
            if (!text.isEmpty()) processProblem(text);
            else Toast.makeText(this, "Please enter a geometry problem", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateZoomText() {
        int pct = (geometryCanvas3D.getVisibility() == View.VISIBLE) ?
                geometryCanvas3D.getZoomPercentage() : geometryCanvas.getZoomPercentage();
        tvZoomPercent.setText(getString(R.string.zoom_percent, pct));
    }

    private void processProblem(String problem) {
        if (model == null) {
            setupAI();
            if (model == null) {
                handleError("AI Service not available. Check your API key.");
                return;
            }
        }

        btnSolveProblem.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        stepsContainer.removeAllViews();

        runOnUiThread(() -> {
            geometryCanvas.clearPoints();
            geometryCanvas3D.clear();
        });

        String combinedPrompt = "You are an elite Geometry solving engine. Solve precisely.\n\n" +
                "### MANDATORY OUTPUT FORMAT:\n" +
                "1. DRAW3D:Label,x,y,z|... (Scale coords 50-450. NO SPACES).\n" +
                "2. PLANE3D:index1,index2,index3... (Indices match DRAW3D order).\n" +
                "3. Use 'Step X: Title | Content' for explanations.\n" +
                "4. Use Unicode symbols (√, ∠, Δ, °) for math.\n" +
                "5. FINAL ANSWERS: [result]\n\n" +
                "Problem: " + problem;

        Content content = new Content.Builder()
                .addText(combinedPrompt)
                .build();

        ListenableFuture<GenerateContentResponse> responseFuture = model.generateContent(content);

        Futures.addCallback(responseFuture, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnSolveProblem.setEnabled(true);
                    try {
                        String aiText = result.getText();
                        if (aiText != null && !aiText.isEmpty()) handleAIResult(aiText, problem);
                        else handleError("AI Safety Block. Rephrase your question.");
                    } catch (Exception e) {
                        Log.e(TAG, "Parsing error", e);
                        handleError("Error processing AI response.");
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnSolveProblem.setEnabled(true);
                    Log.e(TAG, "Gemini Failure", t);
                    if (t.getMessage() != null && t.getMessage().contains("401")) {
                        handleError("Auth Error (401): API Key is invalid or expired.");
                    } else {
                        handleError("AI SDK Error: " + t.getMessage());
                    }
                });
            }
        }, executor);
    }

    private void handleAIResult(String aiText, String originalProblem) {
        try {
            String cleanText = aiText.replaceAll("(?i)```(json|text)?", "").replace("```", "").trim();
            String[] lines = cleanText.split("\n");

            StringBuilder stepContent = new StringBuilder();
            String stepTitle = "Explanation";

            boolean has3D = cleanText.contains("DRAW3D:") || cleanText.contains("PLANE3D:") || cleanText.contains("SPHERE:");
            geometryCanvas.setVisibility(has3D ? View.GONE : View.VISIBLE);
            geometryCanvas3D.setVisibility(has3D ? View.VISIBLE : View.GONE);

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String command = trimmed.replaceAll("^[\\d\\.\\-\\)]+\\s*", "");

                if (command.startsWith("DRAW3D:")) parseAndDraw3D(command);
                else if (command.startsWith("PLANE3D:")) parsePlane3D(command);
                else if (command.startsWith("SPHERE:")) parseSphere(command);
                else if (command.toUpperCase().startsWith("STEP")) {
                    if (stepContent.length() > 0) {
                        addSolutionCard(stepTitle, stepContent.toString(), false);
                        stepContent.setLength(0);
                    }
                    stepTitle = command;
                } else if (command.startsWith("FINAL ANSWERS:")) {
                    if (stepContent.length() > 0) {
                        addSolutionCard(stepTitle, stepContent.toString(), false);
                        stepContent.setLength(0);
                    }
                    addSolutionCard("Final Result", command.replace("FINAL ANSWERS:", "").trim(), true);
                } else {
                    stepContent.append(line).append("\n");
                }
            }
            if (stepContent.length() > 0) addSolutionCard(stepTitle, stepContent.toString(), false);
            
            // SAVE TO HISTORY OPTION
            addSaveToHistoryCard(originalProblem, aiText);
            
            updateZoomText();
        } catch (Exception e) {
            Log.e(TAG, "Result Handling Error", e);
            handleError("AI Result parsing failed.");
        }
    }

    private void parseAndDraw3D(String line) {
        try {
            int colonIdx = line.indexOf(":");
            if (colonIdx == -1) return;
            String raw = line.substring(colonIdx + 1).trim();
            String[] pts = raw.split("\\|");
            for (String pt : pts) {
                String[] d = pt.split(",");
                if (d.length >= 4) {
                    try {
                        geometryCanvas3D.addPoint(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()), Float.parseFloat(d[3].trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) { Log.e(TAG, "3D Parse Error", e); }
    }

    private void parsePlane3D(String line) {
        try {
            int colonIdx = line.indexOf(":");
            if (colonIdx == -1) return;
            String raw = line.substring(colonIdx + 1).trim();
            String[] indicesStr = raw.split(",");
            List<Integer> indices = new ArrayList<>();
            for (String s : indicesStr) {
                String clean = s.trim();
                if (!clean.isEmpty()) {
                    try {
                        indices.add(Integer.parseInt(clean));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!indices.isEmpty()) geometryCanvas3D.addPlane(indices);
        } catch (Exception e) { Log.e(TAG, "Plane Error", e); }
    }

    private void parseSphere(String line) {
        try {
            int colonIdx = line.indexOf(":");
            if (colonIdx == -1) return;
            String raw = line.substring(colonIdx + 1).trim();
            String[] d = raw.split(",");
            if (d.length >= 5) {
                try {
                    geometryCanvas3D.addSphere(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()), Float.parseFloat(d[3].trim()), Float.parseFloat(d[4].trim()));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) { Log.e(TAG, "Sphere Parse Error", e); }
    }

    private void addSolutionCard(String title, String content, boolean isFinal) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(20, 10, 20, 10);
        card.setLayoutParams(lp);
        card.setRadius(16f);
        card.setCardElevation(4f);
        if (isFinal) {
            card.setCardBackgroundColor(0xFFE8F5E9);
            card.setStrokeColor(0xFF2E7D32);
            card.setStrokeWidth(4);
        }

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(30, 30, 30, 30);

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTypeface(null, Typeface.BOLD);
        tvT.setTextColor(isFinal ? 0xFF1B5E20 : 0xFF0C3D6A);
        lay.addView(tvT);

        TextView tvC = new TextView(this);
        markwon.setMarkdown(tvC, content);
        lay.addView(tvC);

        card.addView(lay);
        stepsContainer.addView(card);
        resultScrollView.post(() -> resultScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void addSaveToHistoryCard(String problem, String rawResponse) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(20, 20, 20, 40);
        card.setLayoutParams(lp);
        card.setRadius(16f);
        card.setCardElevation(6f);
        card.setCardBackgroundColor(0xFFE3F2FD);
        card.setStrokeColor(0xFF1976D2);
        card.setStrokeWidth(2);

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(30, 30, 30, 30);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("Solution generated! Would you like to save it to your history?");
        tvInfo.setTextColor(0xFF1565C0);
        tvInfo.setPadding(0, 0, 0, 20);
        lay.addView(tvInfo);

        Button btnSave = new Button(this);
        btnSave.setText("Save to History");
        btnSave.setAllCaps(false);
        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1976D2));
        btnSave.setTextColor(Color.WHITE);
        
        btnSave.setOnClickListener(v -> {
            DatabaseHelper db = new DatabaseHelper(this);
            android.content.SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
            String user = pref.getString("username", "User");
            db.addHistory(user, problem, "Solved", rawResponse);
            Toast.makeText(this, "Saved to history!", Toast.LENGTH_SHORT).show();
            btnSave.setEnabled(false);
            btnSave.setText("Saved ✓");
        });

        lay.addView(btnSave);
        card.addView(lay);
        stepsContainer.addView(card);
    }

    private void toggleCanvasSize() {
        isCanvasMaximized = !isCanvasMaximized;
        ViewGroup.LayoutParams params = canvasContainer.getLayoutParams();
        if (isCanvasMaximized) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            btnResizeCanvas.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        } else {
            params.height = (int) (300 * getResources().getDisplayMetrics().density);
            btnResizeCanvas.setImageResource(android.R.drawable.ic_menu_add);
        }
        canvasContainer.setLayoutParams(params);
    }

    private void handleError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Log.e(TAG, msg);
    }
}
