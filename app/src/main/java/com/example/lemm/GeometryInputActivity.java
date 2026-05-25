package com.example.lemm;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class GeometryInputActivity extends AppCompatActivity {

    private static final String TAG = "GeometryInput";
    private GeometryCanvas3D canvas3D;
    private EditText etDescription, etExtra;
    private TextView tvZoom;
    private LinearLayout inputArea, rotationControls, stepsContainer, solutionControls;
    private ProgressBar progressBar;
    private GeminiAI geminiAI;
    private DatabaseHelper dbHelper;
    private ImageButton btnStopAI, btnToggleInput, btnExpandDesc, btnExpandExtra, btnHistory;

    private String lastSolutionText = "";
    private String lastAIResponse = "";
    private String editId = "";
    private String originalDate = null;
    private boolean isFromHistory = false;
    private boolean isSaved = true;
    private String currentLangCode = "en";

    private ActivityResultLauncher<String> notifPermissionLauncher;
    private static final String SOLUTION_CHANNEL_ID = "ai_solution_channel";
    private static final int SOLUTION_NOTIF_ID = 4101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geometry_input);

        currentLangCode = Locale.getDefault().getLanguage();

        // Ask for notification permission (Android 13+) so we can alert when a background solve finishes.
        notifPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        dbHelper = new DatabaseHelper(this);
        canvas3D = findViewById(R.id.geometryCanvas3D);
        etDescription = findViewById(R.id.etDescription);
        etExtra = findViewById(R.id.etExtraCommands);
        tvZoom = findViewById(R.id.tvZoomPercent);
        inputArea = findViewById(R.id.inputExpandableArea);
        stepsContainer = findViewById(R.id.stepsContainer);
        solutionControls = findViewById(R.id.solutionControls);
        progressBar = findViewById(R.id.progressBar);
        btnStopAI = findViewById(R.id.btnStopAI);
        btnToggleInput = findViewById(R.id.btnToggleInput);
        btnExpandDesc = findViewById(R.id.btnExpandDesc);
        btnExpandExtra = findViewById(R.id.btnExpandExtra);
        btnHistory = findViewById(R.id.btnHistory);

        btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        findViewById(R.id.btnResetView).setOnClickListener(v -> {
            canvas3D.resetRotation();
            Toast.makeText(this, "View Reset", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnToggleMoveRotate).setOnClickListener(v -> {
            boolean mode = !canvas3D.isMoveMode();
            canvas3D.setMoveMode(mode);
            ((ImageButton)v).setColorFilter(mode ? Color.RED : ContextCompat.getColor(this, R.color.primary));
        });

        btnToggleInput.setOnClickListener(v -> {
            if (inputArea.getVisibility() == View.VISIBLE) {
                inputArea.setVisibility(View.GONE);
                btnToggleInput.setColorFilter(Color.parseColor("#E67E22"));
            } else {
                inputArea.setVisibility(View.VISIBLE);
                btnToggleInput.setColorFilter(ContextCompat.getColor(this, R.color.primary));
            }
        });

        btnExpandDesc.setOnClickListener(v -> {
            if (etDescription.getMaxLines() == 3) {
                etDescription.setMaxLines(50);
                btnExpandDesc.setImageResource(android.R.drawable.arrow_up_float);
            } else {
                etDescription.setMaxLines(3);
                btnExpandDesc.setImageResource(android.R.drawable.arrow_down_float);
            }
        });

        btnExpandExtra.setOnClickListener(v -> {
            if (etExtra.getMaxLines() == 2) {
                etExtra.setMaxLines(50);
                btnExpandExtra.setImageResource(android.R.drawable.arrow_up_float);
            } else {
                etExtra.setMaxLines(2);
                btnExpandExtra.setImageResource(android.R.drawable.arrow_down_float);
            }
        });

        findViewById(R.id.btnMaximizeCanvas).setOnClickListener(v -> updateCanvasSize(0.85f));
        findViewById(R.id.btnMinimizeCanvas).setOnClickListener(v -> updateCanvasSize(0.60f));
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> canvas3D.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> canvas3D.zoomOut());

        findViewById(R.id.btnRotXPlus).setOnClickListener(v -> canvas3D.rotateX(10));
        findViewById(R.id.btnRotXMinus).setOnClickListener(v -> canvas3D.rotateX(-10));
        findViewById(R.id.btnRotYPlus).setOnClickListener(v -> canvas3D.rotateY(10));
        findViewById(R.id.btnRotYMinus).setOnClickListener(v -> canvas3D.rotateY(-10));
        findViewById(R.id.btnRotZPlus).setOnClickListener(v -> canvas3D.rotateZ(10));
        findViewById(R.id.btnRotZMinus).setOnClickListener(v -> canvas3D.rotateZ(-10));

        findViewById(R.id.btnSolveProblem).setOnClickListener(v -> {
            String prob = etDescription.getText().toString().trim();
            if (!prob.isEmpty()) solveWithAI(prob);
        });

        btnStopAI.setOnClickListener(v -> resetAll());

        findViewById(R.id.btnSave).setOnClickListener(v -> showSaveDialog(false));

        findViewById(R.id.btnDontSave).setOnClickListener(v -> {
            isSaved = true;
            solutionControls.setVisibility(View.GONE);
        });

        canvas3D.setOnZoomChangeListener(pct -> tvZoom.setText(pct + "%"));

        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        handleIntent(getIntent());
    }

    private void confirmExit() {
        if (isSaved || lastAIResponse.isEmpty() || isFromHistory) {
            finish();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Save Solution?")
                    .setMessage("You have an unsaved solution. Do you want to save it before exiting?")
                    .setPositiveButton("Save", (dialog, which) -> showSaveDialog(true))
                    .setNegativeButton("Don't Save", (dialog, which) -> finish())
                    .setNeutralButton("Cancel", null)
                    .show();
        }
    }

    private void updateCanvasSize(float weight) {
        ConstraintLayout root = findViewById(R.id.geometryInputRoot);
        if (root == null) return;
        ConstraintSet set = new ConstraintSet();
        set.clone(root);
        set.setVerticalWeight(R.id.canvasCard, weight);
        set.setVerticalWeight(R.id.resultSection, 1.0f - weight);
        set.applyTo(root);
    }

    private String getTranslatedSystemPrompt(String langCode, String problem) {
        String langName;
        String instructions;

        switch (langCode) {
            case "ru":
                langName = "Russian (Русский)";
                instructions = "Вы — ИИ-репетитор по геометрии. Отвечайте строго на РУССКОМ языке.(Вместо Given пиши Дано)";
                break;
            case "hy":
                langName = "Armenian (Հայերեն)";
                instructions = "Դուք երկրաչափության փորձագետ եք: Պատասխանեք բացառապես ՀԱՅԵՐԵՆՈՎ:(Given-ի փոխարեն գրիր Տրված է)";
                break;
            default:
                langName = "English";
                instructions = "You are a Geometry Tutor. Answer strictly in ENGLISH.";
                break;
        }
        return "SYSTEM: " + instructions + "\n" +
                "CORE RULES:\n" +
                "1. Output drawing commands first: DRAW3D, LINE3D, PLANE3D.\n" +
                "2. Always use PLANE3D for faces of 3D shapes (Pyramids, Cubes).\n" +
                "3. The step-by-step explanation MUST be in " + langName + ".\n" +
                "4. Structure your explanation EXACTLY like this:\n" +
                "   GIVEN:(if in other language translate to that language)\n" +
                "   (Write what is known, which letter is what, etc. Do NOT use the word 'STEP' here.)\n" +
                "   STEP 1: Title\n" +
                "   (Explanation)\n" +
                "   STEP 2: Title\n" +
                "   (Explanation)\n" +
                "   FINAL ANSWER:\n" +
                "   (The final result)\n" +
                "5. End with 'FINAL ANSWER:'.\n\n" +
                "SYSTEM: You are a CAD Geometry Engine. You MUST output DRAWING COMMANDS for any shape mentioned.\n" +
                "TASK: Analyze the problem and output DRAWING COMMANDS followed by the step-by-step solution.\n\n" +
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
                "- radius: Radius of the base.\n" +
                "- height: Vertical height from base to apex.\n" +
                "- curvature: 1.0 (standard cone), 2.5 (concave shard), 0.5 (convex dome).\n\n" +
                "OTHER COMMANDS:\n" +
                "PYRAMID3D:Label,cx,cy,cz,width,depth,height\n" +
                "CYLINDER3D:Label,cx,cy,cz,radius,height\n" +
                "SPHERE3D:Label,x,y,z,radius\n" +
                "PLANE3D:Label,v1,v2,v3,v4 (Four labels for a face)\n\n" +
                "CONE RULES:\n" +
                "- curvature 1.0 is a sharp cone.\n" +
                "- Center is (0,0,0). Y is UP. Height should be 100-300.\n\n" +
                "EXAMPLE RESPONSE:\n" +
                "DRAW3D:A,0,0,0\n" +
                "CONE3D:Cone1,0,0,0,50,200,1.0\n" +
                "GIVEN:\nRadius is 50, Height is 200.\n" +
                "STEP 1: Find Volume\nVolume = 1/3 * PI * r^2 * h...\n" +
                "FINAL ANSWER: 523598\n\n" +
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
                "Make plane3d for each face.\n"+
                "CYLINDER3D:Label,cx,cy,cz,radius,height (SOLID CYLINDER)\n" +
                "SPHERE3D:Label,x,y,z,radius (Wireframe sphere)\n\n" +
                "================ MODELING TIPS =================\n" +
                "- REALIZABLE FACES: To make an object look solid, use PLANE3D or CIRCLE3D.\n" +
                "- CONES: For any cone, use CONE3D. It handles shaded sides and base automatically.\n" +
                "- COORDINATES: Center view is (0,0,0). Use sizes like 50-200 units. Y-axis is UP.\n" +
                "- Output Drawing Commands first, one per line.\n\n" +
                "CRITICAL RULES:\n" +
                "3. Use STRICTLY Unicode math symbols (√, ×, ÷, ^, ², ³, α, β, γ, θ, π). NEVER use LaTeX, backslashes, or dollar signs (No $, no \\cos, no \\alpha, no \\frac, no \\sqrt, no curly braces {}). Write formulas clearly like: cos(α), a² + b² = c², √x, a / b.\n" +
                "1. For any pointed shape with a circular base, use CONE3D. Do NOT use lines/circles.\n" +
                "2. Y is UP. Center base at (0,0,0). Use sizes like 50-200.\n" +
                "3. Use Unicode math symbols (√, ×, ÷, ^). No LaTeX(dont use dolar sign, /sqrt or other things {}etc).\n" +
                "4. Make it look like a solid building using CONE3D/PYRAMID3D.(Cone3D if its cone or its base is circle.\n\n" +
                "SOLUTION FORMAT: Start each step with 'STEP X: ' (e.g., 'STEP 1: Calculate...'). End the solution with 'FINAL ANSWER: ' followed by the result.\n\n" +
                "1. For any pointed shape with a circular base, use CONE3D. Do NOT use lines/circles.\n" +
                "2. Y is UP. Center base at (0,0,0). Use sizes like 50-200.\n" +
                "If there is no circle in that part of stucture use line3d and plane 3d\n"+
                "If there is a face for structure make plane for every face.Example:If you have pyramid you should draw pyramid with planes\n"+
                "3. Use Unicode math symbols (√, ×, ÷, ^). No LaTeX(dont use  /sqrt or other things {}etc).\n" +
                "4. Make it look like a solid building using CONE3D/PYRAMID3D.(Cone3D if its cone or its base is circle.\n\n" +
                "Explain everything carefully every step in a new card,at first say user what letter is what point or line in solution\n"+
                "If solution needs construction in or out of structure you can also do it with plane3d line3d circle3d and other function.\n"+
                "EVERY FACE SHOULD HAVE PLANE\n"+
                "CONSTRUCTION LINES (MANDATORY): If the solution uses ANY auxiliary segment — height/altitude, median, angle bisector, midsegment, diagonal, radius, apothem, perpendicular, tangent, or a segment joining two named points — you MUST draw it with LINE3D so the student can see it. Define EVERY endpoint first with DRAW3D (including feet of perpendiculars, midpoints, centers, and intersection points, e.g. DRAW3D:H,...), THEN connect them with LINE3D. A LINE3D or PLANE3D that references a label you did NOT define with DRAW3D is skipped and will NOT be drawn — so never reference an undefined point. Re-draw every vertex and auxiliary point you mention in the text.\n\n"+
                "PROBLEM:\n" + problem;
    }

    private void solveWithAI(String problem) {
        SharedPreferences apiPrefs = getSharedPreferences("AI_Settings", MODE_PRIVATE);
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        String username = userPrefs.getString("username", "");
        String userKey = apiPrefs.getString("user_api_key", "");
        boolean isProUser = userPrefs.getBoolean("is_pro_user", false);

        String keyToUse = "";

        if (username.equals("Admin_Teacher") || isProUser) {
            keyToUse = BuildConfig.GEMINI_API_KEY;
        }
        else if (!userKey.isEmpty()) {
            keyToUse = userKey;
        }

        if (keyToUse.isEmpty()) {
            showPaymentDialog();
            return;
        }

        isSaved = false;
        // A freshly generated solution is editable/saveable even if we arrived here from
        // History — otherwise processAIResult() would keep the Save controls hidden.
        isFromHistory = false;
        geminiAI = new GeminiAI(keyToUse);
        progressBar.setVisibility(View.VISIBLE);
        setInputLocked(true); // can't edit the problem while the AI is solving

        String systemPrompt = getTranslatedSystemPrompt(currentLangCode, problem);

        // Capture localized notification text now, while the (locale-wrapped) activity context is valid.
        final String readyTitle = getString(R.string.notif_solution_ready_title);
        final String readyBody = getString(R.string.notif_solution_ready_body);
        final String failTitle = getString(R.string.notif_solution_failed_title);
        final String failBody = getString(R.string.notif_solution_failed_body);
        final String problemText = problem;

        Futures.addCallback(geminiAI.getSolution(systemPrompt), new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                final String text = (result != null) ? result.getText() : null;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressBar.setVisibility(View.GONE);
                        setInputLocked(false); // solving done — editing allowed again
                        if (text != null) {
                            lastAIResponse = text;
                            processAIResult(text);
                        }
                    }
                    // If the user left the screen, the solve still finished in the background — tell them.
                    if (!isActivityVisible) {
                        if (text != null) postSolutionNotification(readyTitle, readyBody, problemText, text);
                        else postSolutionNotification(failTitle, failBody, problemText, null);
                    }
                });
            }
            @Override public void onFailure(Throwable t) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressBar.setVisibility(View.GONE);
                        setInputLocked(false); // solving failed — editing allowed again
                        Toast.makeText(GeometryInputActivity.this, "AI Error: Check your API Key or Network Connection.", Toast.LENGTH_LONG).show();
                    }
                    if (!isActivityVisible) postSolutionNotification(failTitle, failBody, problemText, null);
                    Log.e(TAG, "Gemini Error", t);
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean isActivityVisible = false;

    @Override protected void onResume() { super.onResume(); isActivityVisible = true; }
    @Override protected void onPause() { super.onPause(); isActivityVisible = false; }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent); // e.g. tapping the "Solution Ready" notification re-renders the result
    }

    private void postSolutionNotification(String title, String message, String problemText, String rawResponse) {
        Context ctx = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return; // notifications not permitted
        }
        android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    SOLUTION_CHANNEL_ID, "Geometry Solutions", android.app.NotificationManager.IMPORTANCE_HIGH));
        }

        Intent open = new Intent(ctx, GeometryInputActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (rawResponse != null) {
            open.putExtra("SAVED_RAW", rawResponse);
            open.putExtra("SAVED_PROBLEM", problemText);
        }
        int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, open, piFlags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, SOLUTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(SOLUTION_NOTIF_ID, b.build());
    }

    private void processAIResult(String text) {
        canvas3D.clear();
        stepsContainer.removeAllViews();

        btnStopAI.setVisibility(View.GONE);

        if (!isFromHistory && !isSaved) {
            solutionControls.setVisibility(View.VISIBLE);
        } else {
            solutionControls.setVisibility(View.GONE);
        }

        String[] lines = text.split("\n");
        StringBuilder explanationText = new StringBuilder();

        for (String line : lines) {
            String cleanLine = line.trim();
            if (cleanLine.startsWith("DRAW3D:") || cleanLine.startsWith("LINE3D:") ||
                    cleanLine.startsWith("PLANE3D:") || cleanLine.startsWith("CONE3D:") ||
                    cleanLine.startsWith("PYRAMID3D:") || cleanLine.startsWith("CYLINDER3D:") ||
                    cleanLine.startsWith("SPHERE3D:") || cleanLine.startsWith("CIRCLE3D:")) {

                parseCadCommand(cleanLine);
            } else {
                explanationText.append(line).append("\n");
            }
        }

        String fullText = explanationText.toString();

        fullText = fullText.replaceAll("(?i)STEP\\s*(\\d+)", "STEP $1");
        fullText = fullText.replaceAll("(?i)STEP\\s*:\\s*", "");

        String finalAnswerText = "";
        String[] finalSplit = fullText.split("(?i)FINAL\\s*ANSWER\\s*:?");
        if (finalSplit.length > 1) {
            fullText = finalSplit[0];
            finalAnswerText = finalSplit[1].trim();
        }

        String[] sections = fullText.split("(?i)STEP\\s+");
        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isEmpty()) continue;

            if (i == 0 && !fullText.trim().toUpperCase().startsWith("STEP")) {
                addSetupCard(section);
            } else {
                addStepCard(section);
            }
        }

        if (!finalAnswerText.isEmpty()) {
            addFinalAnswerCard(finalAnswerText);
        }

        inputArea.setVisibility(View.GONE);
        btnToggleInput.setColorFilter(Color.parseColor("#E67E22"));
    }

    private void parseCadCommand(String cleanLine) {
        try {
            String[] parts = cleanLine.split(":");
            if (parts.length < 2) return;

            String commandName = parts[0].trim();
            String[] args = parts[1].trim().split(",");

            switch (commandName) {
                case "DRAW3D":
                    if (args.length >= 4) canvas3D.addPoint(args[0].trim(), f(args[1]), f(args[2]), f(args[3]));
                    break;
                case "LINE3D":
                    if (args.length >= 2) canvas3D.addLine(args[0].trim(), args[1].trim());
                    break;
                case "PLANE3D":
                    if (args.length >= 2) {
                        List<String> vertices = new ArrayList<>();
                        for (int i = 1; i < args.length; i++) vertices.add(args[i].trim());
                        canvas3D.addPlane(vertices);
                    }
                    break;
                case "CONE3D":
                    if (args.length >= 7) canvas3D.addCone(args[0].trim(), f(args[1]), f(args[2]), f(args[3]), f(args[4]), f(args[5]), f(args[6]));
                    break;
                case "PYRAMID3D":
                    if (args.length >= 7) canvas3D.addPyramid(args[0].trim(), f(args[1]), f(args[2]), f(args[3]), f(args[4]), f(args[5]), f(args[6]));
                    break;
                case "CYLINDER3D":
                    if (args.length >= 6) canvas3D.addCylinder(args[0].trim(), f(args[1]), f(args[2]), f(args[3]), f(args[4]), f(args[5]));
                    break;
                case "SPHERE3D":
                    if (args.length >= 5) canvas3D.addSphere(args[0].trim(), f(args[1]), f(args[2]), f(args[3]), f(args[4]));
                    break;
                case "CIRCLE3D":
                    if (args.length >= 5) canvas3D.addCircle(args[0].trim(), f(args[1]), f(args[2]), f(args[3]), f(args[4]));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse command: " + cleanLine, e);
        }
    }

    private void addSetupCard(String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 12, 24, 12);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(3f);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_white));

        TextView tv = new TextView(this);
        tv.setPadding(44, 40, 44, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_title));
        tv.setTextSize(14f);

        // Sanitize any ugly LaTeX first!
        text = formatMathSymbols(text);

        // AUTO-BOLD: Find "Տրված է:" or "GIVEN:" and wrap it in bold tags automatically
        if (text.contains("Տրված է:")) {
            text = text.replace("Տրված է:", "<b>Տրված է:</b>");
        } else if (text.contains("GIVEN:")) {
            text = text.replace("GIVEN:", "<b>GIVEN:</b>");
        } else if (text.contains("Дано:")) {
            text = text.replace("Дано:", "<b>Дано:</b>");
        }

        // Render as HTML
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            tv.setText(android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_COMPACT));
        } else {
            tv.setText(android.text.Html.fromHtml(text));
        }

        card.addView(tv);
        stepsContainer.addView(card);
    }

    private void addStepCard(String sectionText) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 12, 24, 12);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(4f);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_white));

        TextView tv = new TextView(this);
        tv.setPadding(44, 40, 44, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_title));
        tv.setTextSize(14f);

        // Sanitize any ugly LaTeX first!
        sectionText = formatMathSymbols(sectionText);

        // AUTO-BOLD: Auto-bold the "STEP X:" header at the beginning of each card
        String processedText = "<b>" + getString(R.string.step_prefix) + "</b>" + sectionText;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            tv.setText(android.text.Html.fromHtml(processedText, android.text.Html.FROM_HTML_MODE_COMPACT));
        } else {
            tv.setText(android.text.Html.fromHtml(processedText));
        }

        card.addView(tv);
        stepsContainer.addView(card);
    }

    private void addFinalAnswerCard(String answerText) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 12, 24, 36);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(6f);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.answer_card_bg));
        card.setStrokeColor(Color.parseColor("#4CAF50"));
        card.setStrokeWidth(4);

        TextView tv = new TextView(this);
        tv.setPadding(44, 40, 44, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.answer_text));
        tv.setTextSize(16f);

        // Sanitize any ugly LaTeX first!
        answerText = formatMathSymbols(answerText);

        // AUTO-BOLD: Auto-bold the "FINAL ANSWER:" text
        String fullText = "<b>" + getString(R.string.final_answer) + "</b> " + answerText;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            tv.setText(android.text.Html.fromHtml(fullText, android.text.Html.FROM_HTML_MODE_COMPACT));
        } else {
            tv.setText(android.text.Html.fromHtml(fullText));
        }

        card.addView(tv);
        stepsContainer.addView(card);
    }

    private void resetAll () {
        canvas3D.clear();
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);
        btnToggleInput.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        btnStopAI.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        setInputLocked(false); // editing allowed again
        isSaved = true;
        updateCanvasSize(0.60f);
    }

    // Lock the problem inputs while the AI is solving; unlock before/after.
    private void setInputLocked(boolean locked) {
        if (etDescription != null) etDescription.setEnabled(!locked);
        if (etExtra != null) etExtra.setEnabled(!locked);
        View solveBtn = findViewById(R.id.btnSolveProblem);
        if (solveBtn != null) solveBtn.setEnabled(!locked);
        if (locked) {
            View focused = getCurrentFocus();
            if (focused != null) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
        }
    }
    // LATE-STAGE SANITIZER: Instantly translates ugly LaTeX into beautiful, readable Unicode
    private String formatMathSymbols(String text) {
        if (text == null) return "";

        // Remove LaTeX inline/displayed math delimiters
        text = text.replace("$", "");
        text = text.replaceAll("\\\\[()\\[\\]]", "");

        // Arrows FIRST (their names contain "left"/"right", which we strip later)
        text = text.replaceAll("\\\\Rightarrow", "⇒");
        text = text.replaceAll("\\\\Leftarrow", "⇐");
        text = text.replaceAll("\\\\leftrightarrow", "↔");
        text = text.replaceAll("\\\\rightarrow", "→");
        text = text.replaceAll("\\\\leftarrow", "←");
        text = text.replaceAll("\\\\to", "→");
        text = text.replaceAll("\\\\mapsto", "↦");

        // Greek letters
        text = text.replaceAll("\\\\alpha", "α");
        text = text.replaceAll("\\\\beta", "β");
        text = text.replaceAll("\\\\gamma", "γ");
        text = text.replaceAll("\\\\Delta", "Δ");
        text = text.replaceAll("\\\\delta", "δ");
        text = text.replaceAll("\\\\theta", "θ");
        text = text.replaceAll("\\\\lambda", "λ");
        text = text.replaceAll("\\\\mu", "μ");
        text = text.replaceAll("\\\\pi", "π");
        text = text.replaceAll("\\\\rho", "ρ");
        text = text.replaceAll("\\\\sigma", "σ");
        text = text.replaceAll("\\\\tau", "τ");
        text = text.replaceAll("\\\\varphi", "φ");
        text = text.replaceAll("\\\\phi", "φ");
        text = text.replaceAll("\\\\omega", "ω");
        text = text.replaceAll("\\\\Omega", "Ω");

        // Inverse + hyperbolic trig BEFORE base trig, then other functions
        text = text.replaceAll("\\\\arcsin", "arcsin");
        text = text.replaceAll("\\\\arccos", "arccos");
        text = text.replaceAll("\\\\arctan", "arctan");
        text = text.replaceAll("\\\\sinh", "sinh");
        text = text.replaceAll("\\\\cosh", "cosh");
        text = text.replaceAll("\\\\tanh", "tanh");
        text = text.replaceAll("\\\\sin", "sin");
        text = text.replaceAll("\\\\cos", "cos");
        text = text.replaceAll("\\\\tan", "tan");
        text = text.replaceAll("\\\\cot", "cot");
        text = text.replaceAll("\\\\sec", "sec");
        text = text.replaceAll("\\\\csc", "csc");
        text = text.replaceAll("\\\\log", "log");
        text = text.replaceAll("\\\\ln", "ln");
        text = text.replaceAll("\\\\lim", "lim");
        text = text.replaceAll("\\\\exp", "exp");

        // Relations / set / logic symbols (longer tokens before their prefixes)
        text = text.replaceAll("\\\\notin", "∉");
        text = text.replaceAll("\\\\in", "∈");
        text = text.replaceAll("\\\\subseteq", "⊆");
        text = text.replaceAll("\\\\subset", "⊂");
        text = text.replaceAll("\\\\cup", "∪");
        text = text.replaceAll("\\\\cap", "∩");
        text = text.replaceAll("\\\\leq", "≤");
        text = text.replaceAll("\\\\geq", "≥");
        text = text.replaceAll("\\\\neq", "≠");
        text = text.replaceAll("\\\\approx", "≈");
        text = text.replaceAll("\\\\equiv", "≡");
        text = text.replaceAll("\\\\forall", "∀");
        text = text.replaceAll("\\\\exists", "∃");
        text = text.replaceAll("\\\\infty", "∞");
        text = text.replaceAll("\\\\angle", "∠");
        text = text.replaceAll("\\\\perp", "⊥");
        text = text.replaceAll("\\\\parallel", "∥");
        text = text.replaceAll("\\\\triangle", "△");
        text = text.replaceAll("\\\\sum", "Σ");
        text = text.replaceAll("\\\\prod", "∏");
        text = text.replaceAll("\\\\int", "∫");

        // Operators
        text = text.replaceAll("\\\\cdot", " · ");
        text = text.replaceAll("\\\\times", " × ");
        text = text.replaceAll("\\\\div", " ÷ ");
        text = text.replaceAll("\\\\pm", " ± ");
        text = text.replaceAll("\\\\mp", " ∓ ");

        // Degrees
        text = text.replaceAll("\\^\\\\circ", "°");
        text = text.replaceAll("\\\\circ", "°");
        text = text.replaceAll("\\\\degree", "°");
        text = text.replaceAll("\\\\deg", "°");

        // Accents / styling wrappers: keep the inner content only
        text = text.replaceAll("\\\\(vec|hat|bar|overline|underline|mathbb|mathrm|mathbf|mathit|text|operatorname)\\s*\\{([^}]*)\\}", "$2");

        // Fractions and roots
        text = text.replaceAll("\\\\frac\\s*\\{([^}]+)\\}\\s*\\{([^}]+)\\}", "($1 / $2)");
        text = text.replaceAll("\\\\sqrt\\s*\\{([^}]+)\\}", "√($1)");
        text = text.replaceAll("\\\\sqrt\\s*([a-zA-Z0-9]+)", "√$1");

        // Powers and subscripts
        text = text.replaceAll("\\^\\{2\\}", "²");
        text = text.replaceAll("\\^\\{3\\}", "³");
        text = text.replaceAll("\\^2", "²");
        text = text.replaceAll("\\^3", "³");
        text = text.replaceAll("_\\{1\\}", "₁");
        text = text.replaceAll("_\\{2\\}", "₂");
        text = text.replaceAll("_1", "₁");
        text = text.replaceAll("_2", "₂");

        // Structural macros (arrows already converted, so safe to strip now)
        text = text.replaceAll("\\\\(left|right|bigg|Bigg|big|Big|displaystyle|quad|qquad)", "");
        text = text.replaceAll("\\\\[,;:!]", " ");

        // Drop any remaining LaTeX braces
        text = text.replaceAll("[{}]", "");

        // Safety net: strip the backslash from any leftover \command so nothing ugly leaks
        text = text.replaceAll("\\\\([a-zA-Z]+)", "$1");
        text = text.replaceAll("\\\\", "");

        // Markdown bold (**text**) -> HTML bold, since the cards render via Html.fromHtml
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        text = text.replace("**", "");

        return text;
    }
    private void showPaymentDialog () {
        new AlertDialog.Builder(this)
                .setTitle(R.string.access_denied)
                .setMessage(R.string.access_denied_msg)
                .setPositiveButton(R.string.upgrade_pro, (dialog, which) -> {
                    startActivity(new Intent(this, SettingsActivity.class));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSaveDialog(boolean exitAfter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Solution");
        final EditText input = new EditText(this);

        String defaultName = getIntent().hasExtra("SAVED_NAME") ? getIntent().getStringExtra("SAVED_NAME") : "Solution";
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) title = "Unnamed Solution";

            String user = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");
            String prob = etDescription.getText().toString();

            try {
                // Determine the correct date so duplicates aren't created across devices
                String date = (originalDate != null) ? originalDate : FirebaseManager.getCurrentDate();
                String cloudKey = date.replaceAll("[^a-zA-Z0-9]", "");

                // 1. Save Locally
                if (editId != null && !editId.isEmpty()) {
                    dbHelper.updateHistory(Integer.parseInt(editId), title, prob, "", lastAIResponse);
                } else {
                    long newId = dbHelper.addHistoryWithDate(user, title, prob, "", lastAIResponse, date);
                    // Remember this row so a second tap of Save updates it instead of creating a duplicate
                    editId = String.valueOf(newId);
                    originalDate = date;
                }

                // 2. Direct Write to Cloud Database
                if (!user.startsWith("GuestUser")) {
                    HashMap<String, Object> map = new HashMap<>();
                    map.put("title", title);
                    map.put("problem", prob);
                    map.put("raw_response", lastAIResponse);
                    map.put("date", date);

                    FirebaseManager.getUserRef(user).child("history").child(cloudKey).setValue(map)
                            .addOnSuccessListener(x -> dbHelper.markHistorySynced(user, date))
                            .addOnFailureListener(e -> Toast.makeText(GeometryInputActivity.this,
                                    "Saved locally. Couldn't sync to cloud — please check your Wi-Fi connection.",
                                    Toast.LENGTH_LONG).show());
                }

                if (NetworkUtil.isOnline(this)) {
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Saved locally — you appear to be offline. Check your Wi-Fi; it will sync when you reconnect.", Toast.LENGTH_LONG).show();
                }

                isSaved = true;
                solutionControls.setVisibility(View.GONE);

                if (exitAfter) finish();
            } catch (Exception e) {
                Log.e(TAG, "Error initiating save: " + e.getMessage(), e);
                Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null).show();
    }

    private float f (String s){
        try {
            return Float.parseFloat(s.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return 0f;
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        if (intent.hasExtra("SCANNED_TEXT")) {
            etDescription.setText(intent.getStringExtra("SCANNED_TEXT"));
        }

        if (intent.hasExtra("SAVED_RAW")) {
            isFromHistory = true;
            isSaved = true;

            btnStopAI.setVisibility(View.GONE);
            solutionControls.setVisibility(View.GONE);

            String savedProblem = intent.getStringExtra("SAVED_PROBLEM");
            String savedRaw = intent.getStringExtra("SAVED_RAW");

            etDescription.setText(savedProblem);
            lastAIResponse = savedRaw;

            if (intent.hasExtra("EDIT_ID")) editId = intent.getStringExtra("EDIT_ID");
            if (intent.hasExtra("SAVED_DATE")) originalDate = intent.getStringExtra("SAVED_DATE");

            if (savedRaw != null && !savedRaw.isEmpty()) {
                processAIResult(savedRaw);
            }
        }
    }

    @Override
    protected void attachBaseContext (android.content.Context newBase){
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}