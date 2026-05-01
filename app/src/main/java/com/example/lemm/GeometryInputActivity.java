package com.example.lemm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

public class GeometryInputActivity extends AppCompatActivity {
    private static final String TAG = "GeometryInput";
    private static final int MAX_RETRIES = 3;

    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    private DatabaseHelper db;

    private TextInputEditText etDescription, etExtraCommands;
    private Button btnSolveProblem, btnEnterEditMode;
    private ImageButton btnZoomIn, btnZoomOut, btnMaximizeCanvas, btnMinimizeCanvas, btnToggleInput, btnResizeText, btnStopAI;
    private ImageButton btnCopySolution, btnDownloadImage, btnDownloadPdf;
    private Button btnRotLeft2D, btnRotRight2D;
    private TextView tvZoomPercent, tvVisionTitle;
    private ProgressBar progressBar;
    private LinearLayout stepsContainer, inputExpandableArea, solutionControls;
    private ScrollView resultScrollView;
    private View headerCard, visionBar, rotationControls, rotationControls2D;

    private GeometryCanvas geometryCanvas;
    private GeometryCanvas3D geometryCanvas3D;

    private boolean isInputExpanded = true;
    private boolean isTextExpanded = false;
    private Markwon markwon;
    private OkHttpClient client;
    private String googleApiKey;
    private boolean isFromHistory = false;

    private Call currentAICall = null;

    private boolean editMode = false;
    private int editId = -1;
    private String originalName = "";
    private String lastFullResponse = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);
        db = new DatabaseHelper(this);
        initViews();
        setupMarkwon();
        setupListeners();
        setupAI();

        editMode = getIntent().getBooleanExtra("EDIT_MODE", false);
        editId = getIntent().getIntExtra("EDIT_ID", -1);
        originalName = getIntent().getStringExtra("SAVED_NAME");

        String savedRaw = getIntent().getStringExtra("SAVED_RAW");
        String savedProblem = getIntent().getStringExtra("SAVED_PROBLEM");

        if (savedRaw != null) {
            isFromHistory = true;
            etDescription.setText(savedProblem);
            headerCard.setVisibility(View.GONE);
            visionBar.setVisibility(View.VISIBLE);
            tvVisionTitle.setText(originalName != null ? originalName : getString(R.string.saved_solution));
            handleAIResult(savedRaw, savedProblem, true, true);
        } else {
            String scannedText = getIntent().getStringExtra("SCANNED_TEXT");
            if (scannedText != null && !scannedText.isEmpty()) {
                etDescription.setText(scannedText);
            }
        }
    }

    private void initViews() {
        etDescription = findViewById(R.id.etDescription);
        etExtraCommands = findViewById(R.id.etExtraCommands);
        btnSolveProblem = findViewById(R.id.btnSolveProblem);
        btnStopAI = findViewById(R.id.btnStopAI);
        progressBar = findViewById(R.id.progressBar);

        geometryCanvas = findViewById(R.id.geometryCanvas);
        geometryCanvas3D = findViewById(R.id.geometryCanvas3D);

        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        tvZoomPercent = findViewById(R.id.tvZoomPercent);
        stepsContainer = findViewById(R.id.stepsContainer);
        resultScrollView = findViewById(R.id.resultScrollView);
        btnMaximizeCanvas = findViewById(R.id.btnMaximizeCanvas);
        btnMinimizeCanvas = findViewById(R.id.btnMinimizeCanvas);
        rotationControls = findViewById(R.id.rotationControls);
        rotationControls2D = findViewById(R.id.rotationControls2D);
        headerCard = findViewById(R.id.headerCard);
        visionBar = findViewById(R.id.visionBar);
        tvVisionTitle = findViewById(R.id.tvVisionTitle);
        btnEnterEditMode = findViewById(R.id.btnEnterEditMode);
        btnToggleInput = findViewById(R.id.btnToggleInput);
        inputExpandableArea = findViewById(R.id.inputExpandableArea);
        btnResizeText = findViewById(R.id.btnResizeText);

        solutionControls = findViewById(R.id.solutionControls);
        btnCopySolution = findViewById(R.id.btnCopySolution);
        btnDownloadImage = findViewById(R.id.btnDownloadImage);
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf);

        btnRotLeft2D = findViewById(R.id.btnRotLeft2D);
        btnRotRight2D = findViewById(R.id.btnRotRight2D);
    }

    private void setupMarkwon() {
        markwon = Markwon.builder(this)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(36f))
                .build();
    }

    private void setupAI() {
        googleApiKey = "AIzaSyABoE2tH6f6mJcDl0lsdl78LslM2-X8leg";
        client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    private void setupListeners() {
        if (geometryCanvas != null) {
            geometryCanvas.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));
        }
        if (geometryCanvas3D != null) {
            geometryCanvas3D.setOnZoomChangeListener(pct -> tvZoomPercent.setText(getString(R.string.zoom_percent, pct)));
        }

        btnZoomIn.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomIn();
            else if (geometryCanvas != null && geometryCanvas.getVisibility() == View.VISIBLE) geometryCanvas.zoomIn();
            updateZoomText();
        });

        btnZoomOut.setOnClickListener(v -> {
            if (geometryCanvas3D.getVisibility() == View.VISIBLE) geometryCanvas3D.zoomOut();
            else if (geometryCanvas != null && geometryCanvas.getVisibility() == View.VISIBLE) geometryCanvas.zoomOut();
            updateZoomText();
        });

        btnMaximizeCanvas.setOnClickListener(v -> setCanvasWeight(0.85f));
        btnMinimizeCanvas.setOnClickListener(v -> setCanvasWeight(0.2f));

        btnSolveProblem.setOnClickListener(v -> {
            String text = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";
            if (!text.isEmpty()) {
                isFromHistory = false;
                solveProblem(text);
                if (isInputExpanded) toggleInputArea();
            } else {
                Toast.makeText(this, getString(R.string.enter_problem_toast), Toast.LENGTH_SHORT).show();
            }
        });

        if (btnStopAI != null) {
            btnStopAI.setOnClickListener(v -> stopAIProcess());
        }

        btnEnterEditMode.setOnClickListener(v -> {
            visionBar.setVisibility(View.GONE);
            headerCard.setVisibility(View.VISIBLE);
            if (!isInputExpanded) toggleInputArea();
        });

        btnToggleInput.setOnClickListener(v -> toggleInputArea());
        btnResizeText.setOnClickListener(v -> toggleTextSize());

        btnCopySolution.setOnClickListener(v -> copySolutionToClipboard());
        btnDownloadImage.setOnClickListener(v -> downloadCanvasImage());
        btnDownloadPdf.setOnClickListener(v -> downloadSolutionAsPdf());

        btnRotLeft2D.setOnClickListener(v -> geometryCanvas.rotateCanvas(-15f));
        btnRotRight2D.setOnClickListener(v -> geometryCanvas.rotateCanvas(15f));

        setupRotationButton(R.id.btnRotXPlus, 0, 5);
        setupRotationButton(R.id.btnRotXMinus, 0, -5);
        setupRotationButton(R.id.btnRotYPlus, 1, 5);
        setupRotationButton(R.id.btnRotYMinus, 1, -5);
        setupRotationButton(R.id.btnRotZPlus, 2, 5);
        setupRotationButton(R.id.btnRotZMinus, 2, -5);
    }

    private void copySolutionToClipboard() {
        if (lastFullResponse.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Geometry Solution", lastFullResponse);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Solution copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void downloadCanvasImage() {
        Bitmap bitmap = null;
        if (geometryCanvas3D.getVisibility() == View.VISIBLE) {
            bitmap = geometryCanvas3D.getBitmap();
        } else if (geometryCanvas.getVisibility() == View.VISIBLE) {
            bitmap = geometryCanvas.getBitmap();
        }

        if (bitmap == null) {
            Toast.makeText(this, "No canvas content to save", Toast.LENGTH_SHORT).show();
            return;
        }

        saveBitmapToGallery(bitmap, "Geometry_Canvas_" + System.currentTimeMillis());
    }

    private void saveBitmapToGallery(Bitmap bitmap, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lemm");
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                Toast.makeText(this, "Canvas saved to Gallery", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "Failed to save image", e);
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void downloadSolutionAsPdf() {
        if (lastFullResponse.isEmpty()) {
            Toast.makeText(this, "No solution to save", Toast.LENGTH_SHORT).show();
            return;
        }

        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);

        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();
        paint.setTextSize(12);
        paint.setColor(Color.BLACK);

        int x = 50, y = 50;
        String[] lines = lastFullResponse.split("\n");
        for (String line : lines) {
            if (y > 800) {
                document.finishPage(page);
                pageInfo = new PdfDocument.PageInfo.Builder(595, 842, document.getPages().size() + 1).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = 50;
            }
            canvas.drawText(line, x, y, paint);
            y += 20;
        }

        document.finishPage(page);

        String fileName = "Geometry_Solution_" + System.currentTimeMillis() + ".pdf";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lemm");
        }

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                document.writeTo(out);
                Toast.makeText(this, "Solution saved as PDF in Downloads", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "Failed to save PDF", e);
                Toast.makeText(this, "Failed to save PDF", Toast.LENGTH_SHORT).show();
            }
        }
        document.close();
    }

    private void stopAIProcess() {
        if (currentAICall != null) {
            currentAICall.cancel();
            currentAICall = null;
        }
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            btnSolveProblem.setEnabled(true);
            if (btnStopAI != null) btnStopAI.setVisibility(View.GONE);
            Toast.makeText(this, "AI Analysis Stopped", Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleInputArea() {
        isInputExpanded = !isInputExpanded;
        inputExpandableArea.setVisibility(isInputExpanded ? View.VISIBLE : View.GONE);
        btnToggleInput.setImageResource(isInputExpanded ?
                android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_edit);
    }

    private void toggleTextSize() {
        isTextExpanded = !isTextExpanded;
        if (isTextExpanded) {
            etDescription.setMinLines(5);
            etDescription.setMaxLines(10);
            etExtraCommands.setMinLines(3);
            etExtraCommands.setMaxLines(5);
            btnResizeText.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        } else {
            etDescription.setMinLines(1);
            etDescription.setMaxLines(2);
            etExtraCommands.setMinLines(1);
            etExtraCommands.setMaxLines(1);
            btnResizeText.setImageResource(android.R.drawable.ic_menu_zoom);
        }
    }

    private void setupRotationButton(int btnId, final int axis, final float delta) {
        View btn = findViewById(btnId);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (axis == 0) geometryCanvas3D.rotateX(delta);
                else if (axis == 1) geometryCanvas3D.rotateY(delta);
                else geometryCanvas3D.rotateZ(delta);
                handler.postDelayed(this, 50);
            }
        };
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) handler.post(runnable);
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                handler.removeCallbacks(runnable);
            return false;
        });
    }

    private void updateZoomText() {
        int pct = 100;
        if (geometryCanvas3D != null && geometryCanvas3D.getVisibility() == View.VISIBLE) {
            pct = geometryCanvas3D.getZoomPercentage();
        } else if (geometryCanvas != null && geometryCanvas.getVisibility() == View.VISIBLE) {
            pct = geometryCanvas.getZoomPercentage();
        }
        tvZoomPercent.setText(getString(R.string.zoom_percent, pct));
    }

    private void solveProblem(String problem) {
        btnSolveProblem.setEnabled(false);
        if (btnStopAI != null) btnStopAI.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);

        String extra = etExtraCommands.getText() != null ? etExtraCommands.getText().toString().trim() : "";

        String prompt =
                "You are an expert Geometry Solver and CAD Modeling AI.\n" +
                        "Your task is to analyze geometry problems and generate BOTH:\n" +
                        "1) Drawing commands for a geometry engine\n" +
                        "2) Step-by-step mathematical solution\n\n" +

                        "================ RULES =================\n" +
                        "STEP RULES:\n" +
                        "- Always start from Step 1\n" +
                        "- Steps must be sequential (Step 1, Step 2, Step 3...)\n" +
                        "- Never skip numbers\n" +
                        "- Never start from Step 5 or higher\n\n" +

                        "FORMAT RULES:\n" +
                        "- Do NOT use sections like 'Part 1', 'Part 2', 'Overview'\n" +
                        "- Output ONLY drawing commands first, then solution steps\n\n" +

                        "MATH RULES (STRICT):\n" +
                        "- NEVER use LaTeX (NO \\sqrt, \\frac, \\cdot, $)\n" +
                        "- Use ONLY Unicode math symbols:\n" +
                        "  √ instead of sqrt\n" +
                        "  × instead of multiplication\n" +
                        "  ÷ or / for division\n" +
                        "  ^ for powers\n" +
                        "  ° for degrees\n" +
                        "  ∠ for angles\n" +
                        "  Δ for triangle\n\n" +

                        "================ DRAWING =================\n" +
                        "2D FORMAT:\n" +
                        "DRAW2D:Label,x,y|Label,x,y\n" +
                        "LINE2D:Label1,Label2|Label2,Label3\n" +
                        "CIRCLE2D:Label,cx,cy,r\n" +
                        "RECT2D:Label,left,top,right,bottom\n\n" +

                        "3D FORMAT:\n" +
                        "DRAW3D:Label,x,y,z|Label,x,y,z\n" +
                        "LINE3D:Label1,Label2|Label2,Label3\n" +
                        "PLANE3D:P1,P2,P3,P4\n" +
                        "CIRCLE3D:Label,cx,cy,cz,r\n" +
                        "SPHERE3D:Label,x,y,z,r\n\n" +

                        "PLANE RULE (VERY IMPORTANT):\n" +
                        "- If ANY 3D solid exists (cube, prism, pyramid), you MUST define at least ONE PLANE3D.EVERY FACE SHOULD HAVE PLAIN\n" +
                        "- Never skip PLANE3D if shape is 3D solid\n\n" +

                        "================ SOLUTION =================\n" +
                        "- Start steps from Step 1\n" +
                        "- Explain clearly and logically\n" +
                        "- End with FINAL ANSWERS\n\n" +

                        "PROBLEM:\n" + problem + "\n" +
                        (extra.isEmpty() ? "" : "\nADDITIONAL INSTRUCTIONS:\n" + extra);
        callAI(prompt, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                handleStageError(getString(R.string.stage_error, "network"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (call.isCanceled()) return;
                String responseData = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    try {
                        JSONObject json = new JSONObject(responseData);
                        String aiText = json.getJSONArray("candidates")
                                .getJSONObject(0).getJSONObject("content")
                                .getJSONArray("parts").getJSONObject(0).getString("text");

                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            btnSolveProblem.setEnabled(true);
                            if (btnStopAI != null) btnStopAI.setVisibility(View.GONE);
                            if (geometryCanvas != null) geometryCanvas.clearPoints();
                            if (geometryCanvas3D != null) geometryCanvas3D.clear();
                            handleAIResult(aiText, problem, true, true);
                        });
                    } catch (Exception e) {
                        handleStageError(getString(R.string.stage_error, "parse"));
                    }
                } else {
                    handleStageError("API Error " + response.code() + ": " + response.message());
                }
            }
        });
    }

    private void callAI(String prompt, Callback callback) {
        callAIWithRetry(prompt, callback, 0);
    }

    private void callAIWithRetry(String prompt, Callback originalCallback, int attempt) {
        try {
            JSONObject part = new JSONObject(); part.put("text", prompt);
            JSONArray parts = new JSONArray(); parts.put(part);
            JSONObject content = new JSONObject(); content.put("parts", parts);
            JSONArray contents = new JSONArray(); contents.put(content);
            JSONObject body = new JSONObject(); body.put("contents", contents);

            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(API_URL + googleApiKey)
                    .post(requestBody)
                    .build();

            currentAICall = client.newCall(request);
            currentAICall.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (call.isCanceled()) return;
                    if (attempt < MAX_RETRIES) {
                        long delay = (long) Math.pow(2, attempt) * 1000;
                        Log.w(TAG, "Network fail, retry " + (attempt + 1) + " in " + delay + "ms");
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> callAIWithRetry(prompt, originalCallback, attempt + 1), delay);
                    } else {
                        originalCallback.onFailure(call, e);
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (call.isCanceled()) return;
                    if ((response.code() == 503 || response.code() == 429) && attempt < MAX_RETRIES) {
                        response.close();
                        long delay = (long) Math.pow(2, attempt) * 2000;
                        Log.w(TAG, "HTTP " + response.code() + ", retry " + (attempt + 1) + " in " + delay + "ms");
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> callAIWithRetry(prompt, originalCallback, attempt + 1), delay);
                    } else {
                        originalCallback.onResponse(call, response);
                    }
                }
            });
        } catch (Exception e) {
            handleStageError("API Setup Error: " + e.getMessage());
        }
    }

    private void handleAIResult(String aiText, String originalProblem, boolean clearSteps, boolean showCards) {
        try {
            lastFullResponse = aiText;
            if (clearSteps) {
                stepsContainer.removeAllViews();
                if (geometryCanvas3D != null) geometryCanvas3D.clear();
                if (geometryCanvas != null) geometryCanvas.clearPoints();
            }

            String cleanText = aiText
                    .replace("**", "")
                    .replace("`", "")
                    .replace("$", "")
                    .trim();

            parseAllCommands(cleanText);

            if (!showCards) return;
            solutionControls.setVisibility(View.VISIBLE);

            String[] lines = cleanText.split("\n");
            StringBuilder currentStepBody = new StringBuilder();
            String currentStepTitle = getString(R.string.solution_overview);

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || isAnyCommand(trimmed)) continue;

                String upper = trimmed.toUpperCase();
                if (upper.startsWith("STEP")) {
                    if (currentStepBody.length() > 0) {
                        addSolutionCard(currentStepTitle, currentStepBody.toString().trim(), false);
                        currentStepBody.setLength(0);
                    }
                    currentStepTitle = trimmed;
                } else if (upper.contains("FINAL ANSWERS:")) {
                    if (currentStepBody.length() > 0) {
                        addSolutionCard(currentStepTitle, currentStepBody.toString().trim(), false);
                        currentStepBody.setLength(0);
                    }
                    String finalContent = trimmed.replaceAll("(?i)FINAL ANSWERS:", "").trim();
                    addSolutionCard(getString(R.string.final_result), finalContent, true);
                } else {
                    currentStepBody.append(line).append("\n");
                }
            }
            if (currentStepBody.length() > 0) {
                addSolutionCard(currentStepTitle, currentStepBody.toString().trim(), false);
            }

            if (!isFromHistory) addSaveToHistoryCard(originalProblem, aiText);
            updateZoomText();
        } catch (Exception e) {
            Log.e(TAG, "UI Update failed", e);
        }
    }

    private boolean isAnyCommand(String line) {
        String u = line.toUpperCase();
        return u.startsWith("DRAW2D:") || u.startsWith("LINE2D:") || u.startsWith("CIRCLE2D:") || u.startsWith("RECT2D:") ||
                u.startsWith("DRAW3D:") || u.startsWith("LINE3D:") || u.startsWith("PLANE3D:") || u.startsWith("CIRCLE3D:") || u.startsWith("SPHERE3D:");
    }

    private void parseAllCommands(String text) {
        Pattern p = Pattern.compile("(?i)(DRAW2D|LINE2D|CIRCLE2D|RECT2D|DRAW3D|LINE3D|PLANE3D|CIRCLE3D|SPHERE3D):\\s*([^\\n\\r]+)");
        Matcher m = p.matcher(text);

        boolean has3D = text.toUpperCase().contains("DRAW3D:");

        runOnUiThread(() -> {
            if (geometryCanvas != null) geometryCanvas.setVisibility(has3D ? View.GONE : View.VISIBLE);
            if (geometryCanvas3D != null) geometryCanvas3D.setVisibility(has3D ? View.VISIBLE : View.GONE);
            if (rotationControls != null) rotationControls.setVisibility(has3D ? View.VISIBLE : View.GONE);
            if (rotationControls2D != null) rotationControls2D.setVisibility(has3D ? View.GONE : View.VISIBLE);
        });

        while (m.find()) {
            String cmd = m.group(1).toUpperCase();
            String data = m.group(2).trim();
            // Stop at next command if any
            data = data.split("(?i)\\s+(DRAW2D|LINE2D|CIRCLE2D|RECT2D|DRAW3D|LINE3D|PLANE3D|CIRCLE3D|SPHERE3D):")[0].trim();

            switch (cmd) {
                case "DRAW2D":
                    for (String pt : data.split("\\|")) {
                        String[] d = pt.trim().split(",");
                        if (d.length >= 3 && geometryCanvas != null) {
                            try {
                                geometryCanvas.addPoint(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()));
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                case "LINE2D":
                    for (String seg : data.split("\\|")) {
                        String[] pts = seg.trim().split(",");
                        if (pts.length >= 2 && geometryCanvas != null) {
                            GeometryCanvas.GeoPoint p1 = geometryCanvas.findPoint(pts[0].trim());
                            GeometryCanvas.GeoPoint p2 = geometryCanvas.findPoint(pts[1].trim());
                            if (p1 != null && p2 != null) geometryCanvas.addLine("", p1, p2, false);
                        }
                    }
                    break;
                case "CIRCLE2D":
                    String[] cd = data.split(",");
                    if (cd.length >= 4 && geometryCanvas != null) {
                        try {
                            geometryCanvas.addCircle(cd[0].trim(), Float.parseFloat(cd[1].trim()), Float.parseFloat(cd[2].trim()), Float.parseFloat(cd[3].trim()));
                        } catch (Exception ignored) {}
                    }
                    break;
                case "RECT2D":
                    String[] rd = data.split(",");
                    if (rd.length >= 5 && geometryCanvas != null) {
                        try {
                            geometryCanvas.addRect(rd[0].trim(), Float.parseFloat(rd[1].trim()), Float.parseFloat(rd[2].trim()), Float.parseFloat(rd[3].trim()), Float.parseFloat(rd[4].trim()));
                        } catch (Exception ignored) {}
                    }
                    break;
                case "DRAW3D":
                    for (String pt : data.split("\\|")) {
                        String[] d = pt.trim().split(",");
                        if (d.length >= 4 && geometryCanvas3D != null) {
                            try {
                                geometryCanvas3D.addPoint(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()), Float.parseFloat(d[3].trim()));
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                case "LINE3D":
                    for (String seg : data.split("\\|")) {
                        String[] pts = seg.trim().split(",");
                        if (pts.length >= 2 && geometryCanvas3D != null) {
                            geometryCanvas3D.addLine(pts[0].trim(), pts[1].trim(), false);
                        }
                    }
                    break;
                case "PLANE3D":
                    String[] labels = data.split(",");
                    List<Integer> indices = new ArrayList<>();
                    for (String label : labels) {
                        int idx = findPointIndex3D(label.trim());
                        if (idx != -1) indices.add(idx);
                    }
                    if (indices.size() >= 3 && geometryCanvas3D != null) {
                        geometryCanvas3D.addPlane(indices);
                    }
                    break;
                case "CIRCLE3D":
                    for (String seg : data.split("\\|")) {
                        String[] d = seg.trim().split(",");
                        if (d.length >= 5 && geometryCanvas3D != null) {
                            try {
                                geometryCanvas3D.addCircle(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()), Float.parseFloat(d[3].trim()), Float.parseFloat(d[4].trim()));
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                case "SPHERE3D":
                    for (String seg : data.split("\\|")) {
                        String[] d = seg.trim().split(",");
                        if (d.length >= 5 && geometryCanvas3D != null) {
                            try {
                                geometryCanvas3D.addSphere(d[0].trim(), Float.parseFloat(d[1].trim()), Float.parseFloat(d[2].trim()), Float.parseFloat(d[3].trim()), Float.parseFloat(d[4].trim()));
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
            }
        }
    }

    private int findPointIndex3D(String label) {
        if (geometryCanvas3D == null) return -1;
        List<GeometryCanvas3D.Point3D> pts = geometryCanvas3D.getPoints();
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i).label.equalsIgnoreCase(label)) return i;
        }
        return -1;
    }

    private void addSolutionCard(String title, String content, boolean isFinal) {
        if (content == null || content.isEmpty()) return;

        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
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
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
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
        tvInfo.setText(getString(R.string.solution_generated));
        tvInfo.setTextColor(0xFF1565C0);
        tvInfo.setPadding(0, 0, 0, 20);
        lay.addView(tvInfo);

        LinearLayout btnCol = new LinearLayout(this);
        btnCol.setOrientation(LinearLayout.VERTICAL);

        Button btnSaveNew = new Button(this);
        btnSaveNew.setText(editMode ? getString(R.string.save_as_copy) : getString(R.string.save_new));
        btnSaveNew.setAllCaps(false);
        btnSaveNew.setOnClickListener(v -> showSaveDialog(problem, rawResponse, card, false));
        btnCol.addView(btnSaveNew);

        if (editMode) {
            Button btnReplace = new Button(this);
            btnReplace.setText(getString(R.string.replace_previous));
            btnReplace.setAllCaps(false);
            btnReplace.setOnClickListener(v -> {
                db.updateHistory(editId, originalName, problem, "Solved", rawResponse);
                Toast.makeText(this, getString(R.string.original_replaced), Toast.LENGTH_SHORT).show();
                stepsContainer.removeView(card);
            });
            btnCol.addView(btnReplace);
        }

        Button btnDontSave = new Button(this);
        btnDontSave.setText(getString(R.string.dont_save));
        btnDontSave.setAllCaps(false);
        btnDontSave.setOnClickListener(v -> stepsContainer.removeView(card));
        btnCol.addView(btnDontSave);

        lay.addView(btnCol);
        card.addView(lay);
        stepsContainer.addView(card);
    }

    private void showSaveDialog(String problem, String rawResponse, View cardView, boolean isReplace) {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "GuestUser");

        String defaultName = editMode && !isReplace ? originalName + " (Copy)" :
                (originalName == null || originalName.isEmpty() ?
                        "unnamed(" + System.currentTimeMillis() % 1000 + ")" : originalName);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.save_solution));

        final EditText input = new EditText(this);
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(50, 20, 50, 20);
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = defaultName;

            boolean isTestMode = pref.getBoolean("is_test_mode", false);
            String finalUser = isTestMode ? "TEMP_" + username : username;

            db.addHistory(finalUser, name, problem, "Solved", rawResponse);
            Toast.makeText(this, getString(R.string.saved_msg), Toast.LENGTH_SHORT).show();
            stepsContainer.removeView(cardView);
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void setCanvasWeight(float canvasWeight) {
        ConstraintLayout.LayoutParams canvasParams =
                (ConstraintLayout.LayoutParams) findViewById(R.id.canvasCard).getLayoutParams();
        ConstraintLayout.LayoutParams scrollParams =
                (ConstraintLayout.LayoutParams) findViewById(R.id.resultSection).getLayoutParams();

        canvasParams.verticalWeight = canvasWeight;
        scrollParams.verticalWeight = 1.0f - canvasWeight;

        findViewById(R.id.canvasCard).setLayoutParams(canvasParams);
        findViewById(R.id.resultSection).setLayoutParams(scrollParams);
    }

    private void handleStageError(String msg) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            btnSolveProblem.setEnabled(true);
            if (btnStopAI != null) btnStopAI.setVisibility(View.GONE);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            Log.e(TAG, msg);
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
