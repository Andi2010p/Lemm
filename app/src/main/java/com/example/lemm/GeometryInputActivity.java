package com.example.lemm;

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

import com.google.android.material.textfield.TextInputEditText;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GeometryInputActivity extends AppCompatActivity {
    private static final String TAG = "GeometryInput";
    private TextInputEditText etDescription;
    private LinearLayout stepsContainer;
    private ScrollView resultScrollView;
    private Button btnSolveProblem;
    private ImageButton btnZoomIn, btnZoomOut, btnResizeCanvas;
    private TextView tvZoomPercent;
    private ProgressBar progressBar;
    private GeometryCanvas geometryCanvas;
    private GeometryCanvas3D geometryCanvas3D;
    private View canvasContainer;
    private Markwon markwon;
    private boolean isCanvasMaximized = false;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);

        initViews();
        setupMarkwon();
        setupListeners();

        if (getIntent().getBooleanExtra("isTestMode", false)) {
            runTestMode();
        }
    }

    private void initViews() {
        etDescription = findViewById(R.id.etDescription);
        stepsContainer = findViewById(R.id.stepsContainer);
        resultScrollView = findViewById(R.id.resultScrollView);
        btnSolveProblem = findViewById(R.id.btnSolveProblem);
        btnResizeCanvas = findViewById(R.id.btnResizeCanvas);
        progressBar = findViewById(R.id.progressBar);
        geometryCanvas = findViewById(R.id.geometryCanvas);
        geometryCanvas3D = findViewById(R.id.geometryCanvas3D);
        canvasContainer = findViewById(R.id.canvasContainer);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercent = findViewById(R.id.tvZoomPercent);
    }

    private void setupMarkwon() {
        markwon = Markwon.builder(this)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(36f))
                .build();
    }

    private void setupListeners() {
        geometryCanvas.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));
        geometryCanvas3D.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));

        btnZoomIn.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomIn();
            else geometryCanvas.zoomIn();
        });

        btnZoomOut.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomOut();
            else geometryCanvas.zoomOut();
        });

        btnResizeCanvas.setOnClickListener(v -> toggleCanvasSize());

        btnSolveProblem.setOnClickListener(v -> {
            String text = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";
            if (!text.isEmpty()) processProblem(text);
            else Toast.makeText(this, "Please enter a problem", Toast.LENGTH_SHORT).show();
        });
    }

    private void processProblem(String problem) {
        btnSolveProblem.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        stepsContainer.removeAllViews();
        geometryCanvas.clearPoints();
        geometryCanvas3D.clear();

        String apiKey = "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";
        String combinedPrompt = "You are a Geometry Engine. Solve and DRAW in 3D.\n\n" +
                "### 1. DRAWING RULES (STRICT):\n" +
                "- Line 1 MUST be 'DRAW3D:Label,x,y,z|Label,x,y,z|...'\n" +
                "- CONNECT DOTS: You MUST repeat the first point at the end of the DRAW3D list.\n" +
                "- PLANES: For every flat surface, add 'PLANE3D:index1,index2,index3'.\n" +
                "- CIRCLES: For any sphere or circle, add 'SPHERE:Label,x,y,z,radius'.\n\n" +
                "### 2. EXPLANATION RULES:\n" +
                "- Split text into cards using 'STEP X: Title'.\n" +
                "- Use Unicode (√, Δ). NO LATEX ($...$).\n" +
                "- End with 'FINAL ANSWERS: [Result]'.\n\n" +"Do not use functions to change texts look. example do not use **bold*** or sqrt()"+
                "You are a Geometry Rendering Engine. \n\n" +
                "### 1. MANDATORY START (LINE 1):\n" +
                "You MUST start the response with 'DRAW3D:Label,x,y,z|Label,x,y,z|...'.\n" +
                "STRETCH: Use coordinates between 50 and 450 to fill the 500x500 screen.\n" +
                "CONNECT: You MUST repeat the first point at the end of the list to close the shape.\n\n" +
                "### 2. GEOMETRY COMMANDS:\n" +
                "- PLANES: Add 'PLANE3D:0,1,2' (using point indices) to fill surfaces.\n" +
                "- CIRCLES: Add 'SPHERE:Label,x,y,z,radius' for any round objects.\n\n" +
                "### 3. EXPLANATION CARDS:\n" +
                "- DO NOT write one big block of text.\n" +
                "- Every new card MUST start with 'STEP X: Title'.\n" +
                "- Keep each STEP card very short (1-2 sentences).\n" +
                "- NO LATEX. Use Unicode (√, Δ, ∠).\n" +
                "- END with 'FINAL ANSWERS: [Result]'.\n\n" +"You are a Geometry Rendering Engine. \n\n" +
                "### 1. MANDATORY START (LINE 1):\n" +
                "You MUST start the response with 'DRAW3D:Label,x,y,z|Label,x,y,z|...'.\n" +
                "STRETCH: Use coordinates between 50 and 450 to fill the 500x500 screen.\n" +
                "CONNECT: You MUST repeat the first point at the end of the list to close the shape.\n\n" +
                "### 2. GEOMETRY COMMANDS:\n" +
                "- PLANES: Add 'PLANE3D:0,1,2' (using point indices) to fill surfaces.\n" +
                "- CIRCLES: Add 'SPHERE:Label,x,y,z,radius' for any round objects.\n\n" +
                "### 3. EXPLANATION CARDS:\n" +
                "- DO NOT write one big block of text.\n" +
                "- Every new card MUST start with 'STEP X: Title'.\n" +
                "- NO LATEX. Use Unicode (√, Δ, ∠).\n" +
                "- END with 'FINAL ANSWERS: [Result]'.\n\n" +"You are a Geometry Rendering Engine. Solve and DRAW in 3D.\n\n" +
                "### 1. DRAWING RULES (STRICT):\n" +
                "- Line 1 MUST be 'DRAW3D:Label,x,y,z|Label,x,y,z|...'\n" +
                "- STRETCH: Use coordinates 50 to 450 to fill the screen.\n" +
                "- CONNECT: Repeat the first point at the end of the DRAW3D list.\n" +
                "- CIRCLES: If a sphere is mentioned, add 'SPHERE:Label,x,y,z,radius'.\n" +
                "- PLANES: Add 'PLANE3D:0,1,2' (indices) to fill surfaces.\n\n" +
                "### 2. CARD RULES:\n" +
                "- Split explanation into short pieces.\n" +
                "- Every new card MUST start with 'STEP X: Title'.\n" +
                "- NO LATEX. Use Unicode symbols (√, Δ, ∠).\n" +
                "- End with 'FINAL ANSWERS: [Result]'.\n\n" +"You are a Geometry Engine. Solve and DRAW in 3D.\n\n" +
                "### 1. DRAWING RULES (STRICT):\n" +
                "- Line 1 MUST be 'DRAW3D:Label,x,y,z|Label,x,y,z|...'\n" +
                "- CONNECT: You MUST repeat the first point at the end of the DRAW3D list.\n" +
                "- SPHERES: If a circle/ball is mentioned, add 'SPHERE:Label,x,y,z,radius'.\n" +
                "- PLANES: Add 'PLANE3D:0,1,2' (indices) to fill surfaces.\n\n" +
                "### 2. EXPLANATION CARDS:\n" +
                "- Split text into cards using 'STEP X: Title'.\n" +
                "- Use Unicode (√, Δ). NO LATEX.\n" +
                "- End with 'FINAL ANSWERS: [Result]'.\n\n" +

                "Problem: " + problem;
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", "google/gemini-2.0-flash-001");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", combinedPrompt));
            requestJson.put("messages", messages);

            RequestBody body = RequestBody.create(requestJson.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    handleError("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    final String bodyStr = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnSolveProblem.setEnabled(true);
                        if (response.isSuccessful()) {
                            try {
                                JSONObject json = new JSONObject(bodyStr);
                                String aiText = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                                handleAIResult(aiText);
                            } catch (Exception e) { Log.e(TAG, "Parse fail", e); }
                        } else { handleError("API Error: " + response.code()); }
                    });
                }
            });
        } catch (Exception e) { handleError("Setup Error"); }
    }

    private void handleAIResult(String aiContent) {
        // 1. Strip markdown code blocks (```) and any hidden bolding (**)
        String cleaned = aiContent.replaceAll("(?s)```.*?```", "")
                .replace("```", "")
                .replace("**", "");
        String[] lines = cleaned.split("\n");

        StringBuilder stepBuffer = new StringBuilder();
        String currentTitle = "Step 1: Problem Overview";

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String upper = trimmed.toUpperCase();

            // --- 1. GEOMETRY ROUTING (FUZZY MATCH) ---
            if (upper.contains("DRAW3D:")) {
                geometryCanvas3D.setVisibility(View.VISIBLE);
                geometryCanvas.setVisibility(View.GONE);
                // Extract from "DRAW3D" to the end of the line
                parseAndDraw3D(trimmed.substring(upper.indexOf("DRAW3D:")));
            }
            else if (upper.contains("PLANE3D:")) {
                parsePlane3D(trimmed.substring(upper.indexOf("PLANE3D:")));
            }
            else if (upper.contains("SPHERE:")) {
                parseSphere(trimmed.substring(upper.indexOf("SPHERE:")));
            }

            // --- 2. CARD SPLITTING (STEP TRIGGER) ---
            else if (upper.contains("STEP")) {
                // Save the previous card if it has content
                if (stepBuffer.length() > 0) {
                    addSolutionCard(currentTitle, stepBuffer.toString().trim(), false);
                    stepBuffer.setLength(0);
                }
                // Start the new card title
                currentTitle = trimmed;
            }

            // --- 3. FINAL RESULT TRIGGER ---
            else if (upper.contains("FINAL ANSWERS:")) {
                if (stepBuffer.length() > 0) {
                    addSolutionCard(currentTitle, stepBuffer.toString().trim(), false);
                    stepBuffer.setLength(0);
                }
                addSolutionCard("FINAL RESULT", trimmed.replace("FINAL ANSWERS:", "").trim(), true);
            }

            // --- 4. TEXT BUFFER (GENERAL EXPLANATION) ---
            else {
                stepBuffer.append(trimmed).append("\n");
            }
        }

        // Catch the final card
        if (stepBuffer.length() > 0) {
            addSolutionCard(currentTitle, stepBuffer.toString().trim(), false);
        }
    }

    private void parseAndDraw3D(String line) {
        try {
            String raw = line.substring(line.indexOf(":") + 1).trim();
            String[] pts = raw.split("\\|");
            List<String[]> pointsData = new ArrayList<>();

            for (String pt : pts) {
                String[] d = pt.split(",");
                if (d.length >= 4) {
                    pointsData.add(d);
                    geometryCanvas3D.addPoint(d[0].trim(),
                            Float.parseFloat(d[1].trim()),
                            Float.parseFloat(d[2].trim()),
                            Float.parseFloat(d[3].trim()));
                }
            }

            // FORCE CONNECTION: If last point label != first point label, close it
            if (pointsData.size() > 2) {
                String[] first = pointsData.get(0);
                String[] last = pointsData.get(pointsData.size() - 1);
                if (!first[0].trim().equalsIgnoreCase(last[0].trim())) {
                    geometryCanvas3D.addPoint(first[0].trim(),
                            Float.parseFloat(first[1].trim()),
                            Float.parseFloat(first[2].trim()),
                            Float.parseFloat(first[3].trim()));
                }
            }
        } catch (Exception e) { Log.e(TAG, "3D Parse error", e); }
    }

    private void parseSphere(String line) {
        try {
            // AI format: SPHERE:Label,x,y,z,radius
            String raw = line.substring(line.indexOf(":") + 1).trim();
            String[] data = raw.split(",");
            if (data.length >= 5) {
                geometryCanvas3D.addSphere(
                        data[0].trim(),
                        Float.parseFloat(data[1].trim()),
                        Float.parseFloat(data[2].trim()),
                        Float.parseFloat(data[3].trim()),
                        Float.parseFloat(data[4].trim())
                );
            }
        } catch (Exception e) { Log.e(TAG, "Sphere Error", e); }
    }

    private void parsePlane3D(String line) {
        try {
            // AI format: PLANE3D:0,1,2
            String raw = line.substring(line.indexOf(":") + 1).trim();
            String[] indicesStr = raw.split(",");
            List<Integer> indices = new ArrayList<>();
            for (String s : indicesStr) {
                indices.add(Integer.parseInt(s.trim()));
            }
            if (!indices.isEmpty()) {
                geometryCanvas3D.addPlane(indices);
            }
        } catch (Exception e) { Log.e(TAG, "Plane Error", e); }
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

        // Auto-scroll to bottom as new cards arrive
        resultScrollView.post(() -> resultScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void toggleCanvasSize() {
        ViewGroup.LayoutParams p = canvasContainer.getLayoutParams();
        isCanvasMaximized = !isCanvasMaximized;
        p.height = (int) ((isCanvasMaximized ? 450 : 220) * getResources().getDisplayMetrics().density);
        canvasContainer.setLayoutParams(p);
        btnResizeCanvas.setImageResource(isCanvasMaximized ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_zoom);
    }

    private void handleError(String msg) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            btnSolveProblem.setEnabled(true);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void runTestMode() {
        handleAIResult("DRAW3D:A,100,100,0|B,400,100,0|C,250,400,200|A,100,100,0\n" +
                "PLANE3D:0,1,2\n" +
                "STEP 1: Introduction | We are drawing a pyramid with base ABC.\n" +
                "STEP 2: Vertices | Vertex A is at 100,100 while C is the peak.\n" +
                "FINAL ANSWERS: Test pyramid rendered successfully.");
    }
}