package com.example.lemm;

import android.content.Context;
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
    // Add this with your other variables at the top
    private int editId = -1;
    private boolean isViewOnly = false;

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

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
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

        findViewById(R.id.btnHistory).setOnClickListener(v -> {
            startActivity(new Intent(this, HistoryActivity.class));
        });

        findViewById(R.id.btnRotXPlus).setOnClickListener(v -> canvas3D.rotateX(10f));
        findViewById(R.id.btnRotXMinus).setOnClickListener(v -> canvas3D.rotateX(-10f));
        findViewById(R.id.btnRotYPlus).setOnClickListener(v -> canvas3D.rotateY(10f));
        findViewById(R.id.btnRotYMinus).setOnClickListener(v -> canvas3D.rotateY(-10f));
        findViewById(R.id.btnRotZPlus).setOnClickListener(v -> canvas3D.rotateZ(10f));
        findViewById(R.id.btnRotZMinus).setOnClickListener(v -> canvas3D.rotateZ(-10f));

        findViewById(R.id.btnZoomIn).setOnClickListener(v -> canvas3D.zoomIn());
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> canvas3D.zoomOut());
        findViewById(R.id.btnSaveSolution).setOnClickListener(v -> showSaveDialog());
        findViewById(R.id.btnDontSave).setOnClickListener(v -> resetAll());
        canvas3D.setOnZoomChangeListener(pct -> tvZoom.setText(pct + "%"));

        ImageButton btnToggleMode = findViewById(R.id.btnToggleMoveRotate);
        btnToggleMode.setOnClickListener(v -> {
            boolean isMove = !canvas3D.isMoveMode();
            canvas3D.setMoveMode(isMove);
            btnToggleMode.setColorFilter(isMove ? Color.RED : ContextCompat.getColor(this, R.color.primary));
            Toast.makeText(this, isMove ? "Move Mode" : "Rotate Mode", Toast.LENGTH_SHORT).show();
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            // Check if we are editing an existing item
            if (intent.hasExtra("EDIT_ID")) {
                editId = intent.getIntExtra("EDIT_ID", -1);
            }

            // Handle loading saved history
            if (intent.hasExtra("SAVED_RAW")) {
                String raw = intent.getStringExtra("SAVED_RAW");
                lastAIResponse = raw;

                String problem = intent.getStringExtra("SAVED_PROBLEM");
                if (problem != null) etDescription.setText(problem);

                // If it is NOT edit mode, hide the save buttons
                if (!intent.getBooleanExtra("EDIT_MODE", false)) {
                    isViewOnly = true;
                }

                processAIResult(raw);
            }

            // AUTOMATICALLY START SOLVING SCANNED TEXT
            if (intent.hasExtra("SCANNED_TEXT")) {
                String scannedText = intent.getStringExtra("SCANNED_TEXT");
                if (scannedText != null && !scannedText.isEmpty()) {
                    etDescription.setText(scannedText);
                    solveWithAI(scannedText);
                }
            }
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
        isViewOnly = false;

        progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnSolveProblem).setEnabled(false);
        canvas3D.clear();
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);

        SharedPreferences langPref = getSharedPreferences("Settings", MODE_PRIVATE);
        String currentLangCode = langPref.getString("Locale.Helper.Selected.Language", "en");
        String extra = etExtra.getText().toString().trim();
        String extraText = extra.isEmpty() ? "" : "\n\nADDITIONAL INSTRUCTIONS:\n" + extra;

        String prompt;

        if (currentLangCode.equals("ru")) {
            prompt = "СИСТЕМА: Вы - CAD геометрический движок. Вы ДОЛЖНЫ выводить КОМАНДЫ РИСОВАНИЯ для любой упомянутой фигуры.\n" +
                    "Начните объяснение с того, что укажите, что изображено на вашем рисунке: что такое точка, что такое плоскость, что такое линия и т. д.\n\n" +
                    "ГЛОБАЛЬНОЕ ПРАВИЛО: Независимо от текста задачи, вы ДОЛЖНЫ всегда рисовать в стороне две фигуры для масштаба:\n" +
                    "1. Треугольную пирамиду (используйте 4 точки DRAW3D и 4 плоскости PLANE3D).\n" +
                    "2. Прямоугольник на земле (используйте 4 точки DRAW3D и 1 плоскость PLANE3D).\n\n" +
                    "ЗАДАЧА: Проанализируйте задачу и выведите КОМАНДЫ РИСОВАНИЯ, а затем пошаговое решение на русском языке.\n\n" +
                    "ПРАВИЛО 1: Используйте ТОЛЬКО точные команды. НЕ используйте 'point3d', 'cad' и т.д.\n" +
                    "ПРАВИЛО 2: Для любого конуса используйте CONE3D. НЕ используйте круги и линии.\n\n" +
                    "МАТЕМАТИКА КОМАНД:\n" +
                    "CONE3D:Label,cx,cy,cz,radius,height,curvature\n" +
                    "1. (cx, cy, cz) — центр основания. Y направлен ВВЕРХ.\n" +
                    "PLANE3D:Label,v1,v2,v3,v4 (v4 опционально для треугольников).\n\n" +
                    "ВАЖНЫЕ ПРАВИЛА МОДЕЛИРОВАНИЯ:\n" +
                    "- КАЖДАЯ ГРАНЬ ДОЛЖНА ИМЕТЬ PLANE3D.\n" +
                    "- Центр (0,0,0). Размеры 50-200.\n\n" +
                    "КРИТИЧЕСКИ ВАЖНО: Используйте ТОЛЬКО символы Юникода (√, ×, ÷, ^). НИКАКОГО LaTeX!\n\n" +
                    "ФОРМАТ РЕШЕНИЯ: Начинайте каждый шаг с 'Шаг X: '. Заканчивайте фразой 'ОТВЕТ: '.\n\n" +
                    "ЗАДАЧА:\n" + problem + extraText;

        } else if (currentLangCode.equals("hy")) {
            prompt = "ՀԱՄԱԿԱՐԳ: Դուք CAD երկրաչափական շարժիչ եք: Դուք ՊԵՏՔ Է արտածեք ԳԾԱԳՐՄԱՆ ՀՐԱՄԱՆՆԵՐ նշված ցանկացած պատկերի համար:\n" +
                    "Բացատրությունը սկսիր նշելով որն ինչ է քո գծագրում՝ որ կետն ինչն է, հարթությունը, գիծը և այլն:\n\n" +
                    "ԳԼՈԲԱԼ ԿԱՆՈՆ. Անկախ խնդրի բովանդակությունից, դու ՄԻՇՏ պետք է կողքը գծագրես երկու պատկեր.\n" +
                    "1. Եռանկյուն բուրգ (օգտագործիր 4 հատ DRAW3D և 4 հատ PLANE3D):\n" +
                    "2. Ուղղանկյուն հիմքի վրա (օգտագործիր 4 հատ DRAW3D և 1 հատ PLANE3D):\n\n" +
                    "ԱՌԱՋԱԴՐԱՆՔ: Վերլուծեք խնդիրը և արտածեք ԳԾԱԳՐՄԱՆ ՀՐԱՄԱՆՆԵՐ (անգլերենով), որին կհետևի քայլ առ քայլ լուծումը հայերենով:\n\n" +
                    "ԿԱՆՈՆ 1: Օգտագործեք ՄԻԱՅՆ այս ճշգրիտ հրամանները: ՄԻ օգտագործեք 'point3d' կամ 'cad':\n" +
                    "ԿԱՆՈՆ 2: Ցանկացած կոնի համար ՊԵՏՔ Է օգտագործել CONE3D:\n\n" +
                    "ՄՈԴԵԼԱՎՈՐՄԱՆ ԿԱՆՈՆՆԵՐ:\n" +
                    "- ՅՈՒՐԱՔԱՆՉՅՈՒՐ ՆԻՍՏ ՊԵՏՔ Է ՈՒՆԵՆԱ PLANE3D:\n" +
                    "- Կենտրոնը (0,0,0): Չափսերը՝ 50-200:\n\n" +
                    "ԽԻՍՏ ԿԱՐԵՎՈՐ: Օգտագործեք ՄԻԱՅՆ Յունիկոդ սիմվոլներ (√, ×, ÷, ^): ՈՉ ՄԻ LaTeX:\n\n" +
                    "ԼՈՒԾՄԱՆ ՁԵՎԱՉԱՓ: Յուրաքանչյուր քայլ սկսեք 'Քայլ X: '-ով: Ավարտեք 'ՊԱՏԱՍԽԱՆ: '-ով:\n\n" +
                    "ԽՆԴԻՐ:\n" + problem + extraText;

        } else {
            prompt = "SYSTEM: You are a CAD Geometry Engine. You MUST output DRAWING COMMANDS for any shape mentioned.\n" +
                    "Start your explanation by stating what is what in your drawing: points, planes, lines, etc.\n\n" +
                    "GLOBAL RULE: Regardless of the problem, you MUST always draw two extra shapes for reference:\n" +
                    "1. A Triangle Pyramid (use 4 DRAW3D points and 4 PLANE3D faces).\n" +
                    "2. A Rectangle on the ground plane (use 4 DRAW3D points and 1 PLANE3D face).\n\n" +
                    "TASK: Analyze the problem and output DRAWING COMMANDS followed by the step-by-step solution.\n\n" +
                    "RULE 1: Use ONLY exact commands: DRAW3D, LINE3D, PLANE3D, CONE3D, PYRAMID3D.\n" +
                    "RULE 2: For Cones, use CONE3D only.\n\n" +
                    "MODELING TIPS:\n" +
                    "- EVERY FACE MUST HAVE A PLANE3D command.\n" +
                    "- Center everything around (0,0,0). Use sizes 50-200.\n\n" +
                    "CRITICAL: Use Unicode math symbols (√, ×, ÷, ^). NO LaTeX! No dollar signs.\n\n" +
                    "SOLUTION FORMAT: Start each step with 'Step X: '. End with 'FINAL ANSWER: '.\n\n" +
                    "PROBLEM:\n" + problem + extraText;
        }
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
                    Toast.makeText(GeometryInputActivity.this, "AI Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }    private void processAIResult(String text) {
        canvas3D.clear();
        String cleanText = text.replace("`", "").replace("*", "");
        String[] lines = cleanText.split("\n");
        StringBuilder solutionBuilder = new StringBuilder();

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
                } catch (Exception e) { Log.e(TAG, "Error parsing command", e); }
            } else {
                solutionBuilder.append(line).append("\n");
            }
        }

        lastSolutionText = solutionBuilder.toString().trim();
        stepsContainer.removeAllViews();

        // --- FIX: THIS REGEX NOW SUPPORTS RUSSIAN AND ARMENIAN WORDS ---
        Pattern stepPattern = Pattern.compile("^(Step \\d+:.*|Шаг \\d+:.*|Քայլ \\d+:.*|FINAL ANSWER:.*|ОТВЕТ:.*|ՊԱՏԱՍԽԱՆ:.*)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
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

        // --- FIX: SAFELY COLOR-CODE CARDS IN ANY LANGUAGE ---
        for (String segment : segments) {
            String checkStr = segment.toUpperCase();

            boolean isFirst = (checkStr.contains("STEP 1:") || checkStr.contains("ШАГ 1:") || checkStr.contains("ՔԱՅԼ 1:")) && !firstStepProcessed;
            boolean isFinal = checkStr.contains("FINAL ANSWER:") || checkStr.contains("ОТВЕТ:") || checkStr.contains("ՊԱՏԱՍԽԱՆ:");

            if (isFirst) {
                addSolutionCard(segment, false, true);
                firstStepProcessed = true;
            } else if (isFinal) {
                addSolutionCard(segment, true, false);
            } else {
                addSolutionCard(segment, false, false);
            }
        }

         if (!isViewOnly) {
            solutionControls.setVisibility(View.VISIBLE);
        } else {
            solutionControls.setVisibility(View.GONE);
        }

        inputArea.setVisibility(View.GONE);
        canvas3D.invalidate();
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
        builder.setTitle(getString(R.string.save_solution));

        final EditText input = new EditText(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);

        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        int unnamedCount = pref.getInt("unnamed_solution_count", 1);
        String defaultName = getIntent().hasExtra("SAVED_NAME") ? getIntent().getStringExtra("SAVED_NAME") : "unnamed" + unnamedCount;

        input.setText(defaultName);
        input.setSelectAllOnFocus(true);
        builder.setView(layout);

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "unnamed" + unnamedCount;

            if (name.startsWith("unnamed")) {
                pref.edit().putInt("unnamed_solution_count", unnamedCount + 1).apply();
            }

            String currentUser = pref.getString("username", "GuestUser");

            // Update if editing, Add if new
            if (editId != -1) {
                dbHelper.updateHistory(editId, name, etDescription.getText().toString(), lastSolutionText, lastAIResponse);
                Toast.makeText(this, "Updated in History", Toast.LENGTH_SHORT).show();
            } else {
                dbHelper.addHistory(currentUser, name, etDescription.getText().toString(), lastSolutionText, lastAIResponse);
                Toast.makeText(this, "Saved to History", Toast.LENGTH_SHORT).show();
            }

            startActivity(new Intent(this, HistoryActivity.class));
            resetAll();
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
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
        ((ImageButton) findViewById(R.id.btnToggleMoveRotate)).setColorFilter(ContextCompat.getColor(this, R.color.primary));

        // Reset tracking variables
        editId = -1;
        lastAIResponse = "";
        lastSolutionText = "";
    }
    private float f(String s) { try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; } }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}