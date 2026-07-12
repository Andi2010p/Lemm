package com.example.lemm;

import android.content.Context;
import android.graphics.PointF;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.locationtech.jts.geom.Geometry;

import java.util.Arrays;

public class GradeCurriculumActivity extends AppCompatActivity {

    private CadGeometryCanvas canvas2D;
    private GeometryCanvas3D canvas3D;
    private CadEngine2d engine;

    private TextView tvGradeTitle, tvZoomPercent;
    private LinearLayout rotationControls;
    private LinearLayout theoremSections;

    private boolean isMoveMode = true;
    private boolean is3DModeActive = false;

    private ScaleGestureDetector scaleDetector;
    private int activePointerId = -1;
    private float lastX, lastY;
    private boolean isScaling = false;

    private int currentGrade, currentTopic;

    // Multi-question quiz state
    private java.util.List<String> quizQuestions = new java.util.ArrayList<>();
    private java.util.List<String> quizAnswers = new java.util.ArrayList<>();
    private int quizIndex = 0;
    private boolean awaitingNextQuestion = false;

    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_grade_curriculum);
        styleGlass = StyleManager.isGlass(this);

        try {
            canvas2D = findViewById(R.id.curriculumCanvas);
            canvas3D = findViewById(R.id.curriculumCanvas3D);
            rotationControls = findViewById(R.id.rotationControls);

            engine = new CadEngine2d();
            if (canvas2D != null) {
                canvas2D.setEngine(engine);
                canvas2D.setCurrentTool("MOVE");
            }
            if (canvas3D != null) {
                canvas3D.setMoveMode(true);
            }

            tvGradeTitle = findViewById(R.id.tvGradeTitle);
            theoremSections = findViewById(R.id.theoremSections);
            tvZoomPercent = findViewById(R.id.tvZoomPercent);

            View btnBack = findViewById(R.id.btnBack);
            if (btnBack != null) btnBack.setOnClickListener(v -> finish());

            setupButtons();
            setupGestures2D();

            currentGrade = getIntent().getIntExtra("GRADE", 7);
            currentTopic = getIntent().getIntExtra("TOPIC", 1);
            String title = getIntent().getStringExtra("THEOREM_TITLE");

            if (tvGradeTitle != null) tvGradeTitle.setText(title);

            drawTheorem(currentGrade, currentTopic);

        } catch (Exception e) {
            Toast.makeText(this, "Error loading curriculum: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private void switchTo3D(boolean use3D) {
        is3DModeActive = use3D;
        if (use3D) {
            if (canvas2D != null) canvas2D.setVisibility(View.GONE);
            if (canvas3D != null) canvas3D.setVisibility(View.VISIBLE);
            if (rotationControls != null) rotationControls.setVisibility(View.VISIBLE);
        } else {
            if (canvas3D != null) canvas3D.setVisibility(View.GONE);
            if (rotationControls != null) rotationControls.setVisibility(View.GONE);
            if (canvas2D != null) canvas2D.setVisibility(View.VISIBLE);
        }
    }

    private void setupGestures2D() {
        if (canvas2D == null) return;

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if(!is3DModeActive) canvas2D.applyZoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });

        canvas2D.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    activePointerId = event.getPointerId(0);
                    lastX = event.getX(); lastY = event.getY();
                    isScaling = false; break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    isScaling = true; break;
                case MotionEvent.ACTION_POINTER_UP:
                    int pointerIndex = event.getActionIndex();
                    int pointerId = event.getPointerId(pointerIndex);
                    if (pointerId == activePointerId) {
                        int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                        if (newPointerIndex < event.getPointerCount()) {
                            lastX = event.getX(newPointerIndex); lastY = event.getY(newPointerIndex);
                            activePointerId = event.getPointerId(newPointerIndex);
                        }
                    }
                    if (event.getPointerCount() <= 2) isScaling = false; break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 && !isScaling) {
                        int idx = event.findPointerIndex(activePointerId);
                        if (idx != -1) {
                            float currX = event.getX(idx); float currY = event.getY(idx);
                            if (isMoveMode) canvas2D.pan(currX - lastX, currY - lastY);
                            lastX = currX; lastY = currY;
                        }
                    } break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isMoveMode && !isScaling) {
                        PointF worldPt = canvas2D.getRawWorldCoords(event.getX(), event.getY());
                        double threshold = 50.0 / (canvas2D.getZoomPercentage() / 100.0);
                        Geometry tapped = engine.getGeometryAt(worldPt.x, worldPt.y, threshold);
                        canvas2D.setSelectedGeometry(tapped);
                    }
                    activePointerId = -1; isScaling = false; break;
            }
            canvas2D.invalidate();
            return true;
        });
    }

    private void setupButtons() {
        View btnZoomIn = findViewById(R.id.btnZoomIn);
        if (btnZoomIn != null) btnZoomIn.setOnClickListener(v -> {
            if (is3DModeActive && canvas3D != null) canvas3D.zoomIn();
            else if (!is3DModeActive && canvas2D != null) canvas2D.zoomIn();
        });

        View btnZoomOut = findViewById(R.id.btnZoomOut);
        if (btnZoomOut != null) btnZoomOut.setOnClickListener(v -> {
            if (is3DModeActive && canvas3D != null) canvas3D.zoomOut();
            else if (!is3DModeActive && canvas2D != null) canvas2D.zoomOut();
        });

        if (canvas2D != null) canvas2D.setOnZoomChangeListener(pct -> { if(!is3DModeActive && tvZoomPercent != null) tvZoomPercent.setText(pct + "%"); });
        if (canvas3D != null) canvas3D.setOnZoomChangeListener(pct -> { if(is3DModeActive && tvZoomPercent != null) tvZoomPercent.setText(pct + "%"); });

        View btnResetView = findViewById(R.id.btnResetView);
        if (btnResetView != null) {
            btnResetView.setOnClickListener(v -> drawTheorem(currentGrade, currentTopic));
        }

        ImageButton btnToggleTool = findViewById(R.id.btnToggleTool);
        if (btnToggleTool != null) {
            btnToggleTool.setOnClickListener(v -> {
                isMoveMode = !isMoveMode;
                if (isMoveMode) {
                    if (canvas2D != null) canvas2D.setCurrentTool("MOVE");
                    if (canvas3D != null) canvas3D.setMoveMode(true);
                    btnToggleTool.setImageResource(R.drawable.ic_move);
                    btnToggleTool.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.main_blue));
                } else {
                    if (canvas2D != null) canvas2D.setCurrentTool("SELECT");
                    if (canvas3D != null) canvas3D.setMoveMode(false);
                    btnToggleTool.setImageResource(android.R.drawable.ic_menu_directions);
                    btnToggleTool.setColorFilter(android.graphics.Color.parseColor("#E67E22"));
                }
            });
        }

        View rxp = findViewById(R.id.btnRotXPlus); if (rxp != null) rxp.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateX(10); });
        View rxm = findViewById(R.id.btnRotXMinus); if (rxm != null) rxm.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateX(-10); });
        View ryp = findViewById(R.id.btnRotYPlus); if (ryp != null) ryp.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateY(10); });
        View rym = findViewById(R.id.btnRotYMinus); if (rym != null) rym.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateY(-10); });
        View rzp = findViewById(R.id.btnRotZPlus); if (rzp != null) rzp.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateZ(10); });
        View rzm = findViewById(R.id.btnRotZMinus); if (rzm != null) rzm.setOnClickListener(v -> { if(canvas3D!=null) canvas3D.rotateZ(-10); });
    }

    private void drawWorkspaceFrame() {
        if (engine == null) return;
        engine.clear();
        Geometry frame = engine.addRect(-50, -50, 650, 550);
        frame.setUserData("WORKSPACE");
    }

    private void resetCamera(boolean is3D) {
        if (!is3D && canvas2D != null) {
            canvas2D.resetView();
            canvas2D.pan(200, 100);
            canvas2D.applyZoom(0.6f, 0, 0);
            canvas2D.invalidate();
        } else if (is3D && canvas3D != null) {
            canvas3D.resetRotation();
        }
    }

    private void safeAddVisualAngle(Geometry l1, Geometry l2, double angle) {
        try {
            engine.addVisualAngle(l1, l2, angle);
        } catch (Exception | Error ignored) { }
    }

    // THE ONLY setExplanationHtml IN THE FILE
    private void setExplanationHtml(int grade, int topic) {
        if (theoremSections == null) return;
        theoremSections.removeAllViews();

        String def = resolveString("th_def_" + grade + "_" + topic);
        String exp = resolveString("th_exp_" + grade + "_" + topic);
        String proof = resolveString("th_proof_" + grade + "_" + topic);
        String hints = resolveString("th_ex_" + grade + "_" + topic);

        // One clean card per section: coloured accent stripe + icon header + body.
        addSectionCard("📐", getString(R.string.th_header_theorem),    0xFF0C3D6A, def);
        addSectionCard("💡", getString(R.string.th_header_explanation), 0xFF2980B9, exp);
        addSectionCard("✅", getString(R.string.th_header_proof),       0xFF27AE60, proof);
        addSectionCard("🎯", getString(R.string.th_header_hints),       0xFFE67E22, hints);

        setupQuiz(grade, topic);
    }

    /** Adds one explanation section as a card with a coloured left accent, an icon header and the body. */
    private void addSectionCard(String icon, String header, int accent, String bodyHtml) {
        if (bodyHtml == null || bodyHtml.trim().isEmpty()) return;

        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);
        card.setRadius(dp(16));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        card.setStrokeWidth((int) getResources().getDisplayMetrics().density);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT));
        bar.setBackgroundColor(accent);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.setPadding(dp(18), dp(15), dp(18), dp(16));

        TextView tvHeader = new TextView(this);
        tvHeader.setText(icon + "  " + header);
        tvHeader.setTextColor(accent);
        tvHeader.setTextSize(13f);
        tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        tvHeader.setLetterSpacing(0.03f);
        tvHeader.setPadding(0, 0, 0, dp(8));

        TextView tvBody = new TextView(this);
        tvBody.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_main));
        tvBody.setTextSize(15.5f);
        tvBody.setLineSpacing(dp(5), 1f);
        tvBody.setText(fromHtml(bodyHtml));

        col.addView(tvHeader);
        col.addView(tvBody);
        row.addView(bar);
        row.addView(col);
        card.addView(row);
        theoremSections.addView(card);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private android.text.Spanned fromHtml(String html) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                : Html.fromHtml(html);
    }

    /** Returns the string for the given resource name, with newlines converted to HTML breaks, or "" if missing. */
    private String resolveString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id != 0 ? getString(id).replace("\n", "<br>") : "";
    }

    // INTERACTIVE QUIZ LOGIC (supports one or more questions per theorem)
    private void setupQuiz(int grade, int topic) {
        View quizCard = findViewById(R.id.quizCard);
        TextView tvQuizQuestion = findViewById(R.id.tvQuizQuestion);
        TextView tvQuizFeedback = findViewById(R.id.tvQuizFeedback);
        com.google.android.material.textfield.TextInputEditText etQuizAnswer = findViewById(R.id.etQuizAnswer);
        com.google.android.material.button.MaterialButton btnCheckAnswer = findViewById(R.id.btnCheckAnswer);
        if (quizCard == null) return;

        // Collect all available questions: th_q / th_a, then th_q2 / th_a2, th_q3 / th_a3 ...
        quizQuestions.clear();
        quizAnswers.clear();
        for (int n = 1; n <= 5; n++) {
            String suffix = (n == 1) ? "" : String.valueOf(n);
            int qId = getResources().getIdentifier("th_q" + suffix + "_" + grade + "_" + topic, "string", getPackageName());
            int aId = getResources().getIdentifier("th_a" + suffix + "_" + grade + "_" + topic, "string", getPackageName());
            if (qId != 0 && aId != 0) {
                quizQuestions.add(getString(qId));
                quizAnswers.add(getString(aId).trim());
            }
        }

        if (quizQuestions.isEmpty()) {
            quizCard.setVisibility(View.GONE);
            return;
        }

        quizCard.setVisibility(View.VISIBLE);
        quizIndex = 0;
        awaitingNextQuestion = false;
        showQuizQuestion(tvQuizQuestion, tvQuizFeedback, etQuizAnswer, btnCheckAnswer);

        btnCheckAnswer.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

            // "Next question" mode after a correct answer
            if (awaitingNextQuestion) {
                quizIndex++;
                awaitingNextQuestion = false;
                showQuizQuestion(tvQuizQuestion, tvQuizFeedback, etQuizAnswer, btnCheckAnswer);
                return;
            }

            String userAnswer = etQuizAnswer.getText().toString().trim().replace(',', '.');
            tvQuizFeedback.setVisibility(View.VISIBLE);

            if (userAnswer.isEmpty()) {
                tvQuizFeedback.setText(getString(R.string.enter_answer_prompt));
                tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#F59E0B"));
                return;
            }

            String correctAnswer = quizAnswers.get(quizIndex).replace(',', '.');
            if (answersMatch(userAnswer, correctAnswer)) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etQuizAnswer.getWindowToken(), 0);

                boolean hasNext = quizIndex < quizQuestions.size() - 1;
                if (hasNext) {
                    tvQuizFeedback.setText(getString(R.string.correct_next));
                    tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#10B981"));
                    awaitingNextQuestion = true;
                    btnCheckAnswer.setText(getString(R.string.next_question));
                } else {
                    String msg = quizQuestions.size() > 1
                            ? getString(R.string.all_correct)
                            : getString(R.string.correct_answer);
                    tvQuizFeedback.setText(msg);
                    tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#10B981"));
                    btnCheckAnswer.setEnabled(false);
                }
            } else {
                tvQuizFeedback.setText(getString(R.string.incorrect_answer));
                tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#EF4444"));
                etQuizAnswer.selectAll();
            }
        });
    }

    private void showQuizQuestion(TextView tvQuizQuestion, TextView tvQuizFeedback,
                                  com.google.android.material.textfield.TextInputEditText etQuizAnswer,
                                  com.google.android.material.button.MaterialButton btnCheckAnswer) {
        String label = quizQuestions.size() > 1
                ? (quizIndex + 1) + "/" + quizQuestions.size() + ".  " + quizQuestions.get(quizIndex)
                : quizQuestions.get(quizIndex);
        tvQuizQuestion.setText(label);
        etQuizAnswer.setText("");
        tvQuizFeedback.setVisibility(View.GONE);
        btnCheckAnswer.setText(getString(R.string.check_answer));
        btnCheckAnswer.setEnabled(true);
    }

    /** Compares answers numerically when possible (so "5.0" == "5"), otherwise case-insensitive text. */
    private boolean answersMatch(String user, String correct) {
        try {
            return Math.abs(Double.parseDouble(user) - Double.parseDouble(correct)) < 1e-6;
        } catch (NumberFormatException e) {
            return user.equalsIgnoreCase(correct);
        }
    }

    private void drawTheorem(int grade, int topic) {
        boolean use3D = (grade >= 10);
        switchTo3D(use3D);

        if (!use3D) {
            drawWorkspaceFrame();

            // ================= GRADE 7 =================
            if (grade == 7) {
                if (topic == 1) {
                    Geometry l1 = engine.addLine(150, 150, 450, 350); Geometry l2 = engine.addLine(150, 350, 450, 150);
                    safeAddVisualAngle(l1, l2, 68); safeAddVisualAngle(l2, l1, 68);
                    engine.addExplicitLabel(300, 220, "α"); engine.addExplicitLabel(300, 310, "α");
                    engine.addExplicitLabel(130, 130, "A"); engine.addExplicitLabel(470, 370, "B");
                }
                else if (topic == 2) {
                    Geometry l1 = engine.addLine(50, 100, 250, 100); Geometry l2 = engine.addLine(250, 100, 150, 300); engine.addLine(150, 300, 50, 100);
                    Geometry l3 = engine.addLine(350, 100, 550, 100); Geometry l4 = engine.addLine(550, 100, 450, 300); engine.addLine(450, 300, 350, 100);
                    safeAddVisualAngle(l1, l2, 63.4); safeAddVisualAngle(l3, l4, 63.4);
                }
                else if (topic == 3) {
                    Geometry base = engine.addLine(150, 350, 450, 350);
                    Geometry r = engine.addLine(450, 350, 350, 150); Geometry l = engine.addLine(350, 150, 150, 350);
                    Geometry aux = engine.addLine(100, 150, 500, 150);
                    safeAddVisualAngle(base, l, 50); safeAddVisualAngle(aux, l, 50);
                    safeAddVisualAngle(r, base, 60); safeAddVisualAngle(r, aux, 60);
                    engine.addExplicitLabel(350, 120, "B"); engine.addExplicitLabel(130, 370, "A"); engine.addExplicitLabel(470, 370, "C");
                }
                else if (topic == 4) {
                    engine.addLine(100, 350, 500, 350); engine.addLine(500, 350, 400, 150); engine.addLine(400, 150, 100, 350);
                    engine.addExplicitLabel(300, 380, "a"); engine.addExplicitLabel(220, 230, "b"); engine.addExplicitLabel(470, 230, "c");
                }
                else if (topic == 5) { // Exterior angle: triangle with the base extended
                    Geometry left = engine.addLine(150, 350, 300, 150);
                    Geometry right = engine.addLine(450, 350, 300, 150);
                    Geometry base = engine.addLine(150, 350, 450, 350);
                    Geometry ext = engine.addLine(450, 350, 580, 350);
                    safeAddVisualAngle(right, ext, 70);
                    engine.addExplicitLabel(300, 130, "B"); engine.addExplicitLabel(130, 375, "A");
                    engine.addExplicitLabel(450, 380, "C"); engine.addExplicitLabel(585, 375, "D");
                    engine.addExplicitLabel(495, 320, "ext");
                }
                else if (topic == 6) { // Parallel lines cut by a transversal
                    engine.addLine(100, 180, 520, 180);
                    engine.addLine(100, 330, 520, 330);
                    Geometry t = engine.addLine(180, 90, 440, 430);
                    engine.addExplicitLabel(285, 165, "α"); engine.addExplicitLabel(360, 315, "α");
                    engine.addExplicitLabel(530, 180, "m"); engine.addExplicitLabel(530, 330, "n");
                }
                else if (topic == 7) { // Angles on a straight line (linear pair)
                    engine.addLine(300, 330, 110, 330); // O -> A
                    engine.addLine(300, 330, 490, 330); // O -> B
                    engine.addLine(300, 330, 400, 160); // O -> C (ray up)
                    engine.addExplicitLabel(95, 335, "A"); engine.addExplicitLabel(495, 335, "B");
                    engine.addExplicitLabel(405, 150, "C"); engine.addExplicitLabel(300, 355, "O");
                }
                else if (topic == 8) { // Angle bisector (two equal halves)
                    engine.addLine(120, 270, 470, 150); // upper side
                    engine.addLine(120, 270, 470, 390); // lower side
                    engine.addLine(120, 270, 490, 270); // bisector (horizontal, splits evenly)
                    engine.addExplicitLabel(105, 272, "O"); engine.addExplicitLabel(495, 272, "l");
                }
                else if (topic == 9) { // Two congruent triangles (SAS)
                    engine.addLine(100, 360, 250, 360); engine.addLine(250, 360, 130, 200); engine.addLine(130, 200, 100, 360);
                    engine.addLine(330, 360, 480, 360); engine.addLine(480, 360, 360, 200); engine.addLine(360, 200, 330, 360);
                    engine.addExplicitLabel(88, 375, "A"); engine.addExplicitLabel(255, 375, "B"); engine.addExplicitLabel(118, 190, "C");
                    engine.addExplicitLabel(318, 375, "A'"); engine.addExplicitLabel(485, 375, "B'"); engine.addExplicitLabel(348, 190, "C'");
                }
                else if (topic == 10) { // Perpendicular bisector of a segment
                    engine.addLine(160, 330, 300, 330); // A -> M
                    engine.addLine(300, 330, 440, 330); // M -> B
                    engine.addLine(300, 330, 300, 150); // perpendicular up through M
                    engine.addLine(300, 330, 300, 390); // perpendicular down through M
                    engine.addLine(300, 180, 160, 330); // P -> A
                    engine.addLine(300, 180, 440, 330); // P -> B
                    engine.addExplicitLabel(148, 348, "A"); engine.addExplicitLabel(445, 348, "B");
                    engine.addExplicitLabel(305, 352, "M"); engine.addExplicitLabel(300, 170, "P");
                    engine.addExplicitLabel(195, 245, "PA"); engine.addExplicitLabel(390, 245, "PB");
                }
                else if (topic == 11) { // Shortest distance = perpendicular from a point to a line
                    engine.addLine(120, 340, 330, 340); engine.addLine(330, 340, 480, 340); // the line, split at foot H
                    engine.addLine(330, 150, 330, 340); // perpendicular P -> H (right angle at H)
                    engine.addLine(330, 150, 190, 340); // slanted P -> A (longer)
                    engine.addExplicitLabel(322, 140, "P"); engine.addExplicitLabel(332, 362, "H");
                    engine.addExplicitLabel(178, 362, "A");
                    engine.addExplicitLabel(345, 250, "d"); engine.addExplicitLabel(250, 235, "slant");
                }
                else if (topic == 12) { // ASA congruence: two congruent triangles, included side marked
                    engine.addLine(100, 360, 260, 360); engine.addLine(260, 360, 150, 200); engine.addLine(150, 200, 100, 360);
                    engine.addLine(330, 360, 490, 360); engine.addLine(490, 360, 380, 200); engine.addLine(380, 200, 330, 360);
                    engine.addExplicitLabel(88, 375, "A"); engine.addExplicitLabel(265, 375, "B"); engine.addExplicitLabel(140, 190, "C");
                    engine.addExplicitLabel(318, 375, "A'"); engine.addExplicitLabel(495, 375, "B'"); engine.addExplicitLabel(370, 190, "C'");
                    engine.addExplicitLabel(175, 380, "c"); engine.addExplicitLabel(405, 380, "c");
                }
            }
            // ================= GRADE 8 =================
            else if (grade == 8) {
                if (topic == 1) {
                    engine.addLine(100, 100, 400, 100); engine.addLine(400, 100, 500, 400);
                    engine.addLine(500, 400, 200, 400); engine.addLine(200, 400, 100, 100);
                    engine.addLine(100, 100, 500, 400);
                }
                else if (topic == 2) {
                    Geometry base = engine.addLine(150, 350, 350, 350);
                    engine.addLine(350, 350, 250, 150); engine.addLine(250, 150, 150, 350);
                    engine.addLine(250, 150, 450, 150); engine.addLine(450, 150, 350, 350);
                    Geometry h = engine.addLine(250, 150, 250, 350);
                    safeAddVisualAngle(base, h, 90);
                    engine.addExplicitLabel(250, 380, "a"); engine.addExplicitLabel(230, 250, "h");
                }
                else if (topic == 3) {
                    engine.addRect(150, 100, 450, 400);
                    engine.addLine(250, 100, 450, 200); engine.addLine(450, 200, 350, 400);
                    engine.addLine(350, 400, 150, 300); engine.addLine(150, 300, 250, 100);
                    engine.addExplicitLabel(200, 80, "a"); engine.addExplicitLabel(350, 80, "b");
                    engine.addExplicitLabel(300, 250, "c²");
                }
                else if (topic == 4) {
                    engine.addLine(100, 400, 500, 400); engine.addLine(500, 400, 300, 100); engine.addLine(300, 100, 100, 400);
                    Geometry mid = engine.addLine(200, 250, 400, 250);
                    engine.calculateAndSetDrivenDimension(mid, "LINE");
                    engine.addExplicitLabel(300, 80, "B"); engine.addExplicitLabel(170, 250, "M"); engine.addExplicitLabel(430, 250, "N");
                }
                else if (topic == 5) { // Altitude to the hypotenuse of a right triangle (right angle at the apex)
                    engine.addLine(150, 350, 300, 350); engine.addLine(300, 350, 450, 350); // hypotenuse, split at the foot
                    Geometry l2 = engine.addLine(450, 350, 300, 200); Geometry l3 = engine.addLine(300, 200, 150, 350); // legs meet at the right angle
                    engine.addLine(300, 200, 300, 350); // altitude to the hypotenuse
                    safeAddVisualAngle(l2, l3, 90); // a true 90° at the apex
                    engine.addExplicitLabel(310, 275, "h"); engine.addExplicitLabel(210, 372, "a_c"); engine.addExplicitLabel(360, 372, "b_c");
                }
                else if (topic == 6) { // Median to the hypotenuse = half the hypotenuse = circumradius
                    engine.addLine(150, 350, 450, 350); engine.addLine(450, 350, 150, 150); engine.addLine(150, 150, 150, 350); // right angle at (150,350)
                    engine.addLine(150, 350, 300, 250); // median from the right-angle vertex to the hypotenuse midpoint
                    engine.addCircle(300, 250, 180); // circumscribed circle: centre = hypotenuse midpoint, R = half hypotenuse
                    engine.addExplicitLabel(205, 285, "m_c = R"); engine.addExplicitLabel(308, 245, "O");
                }
                else if (topic == 7) {
                    engine.addLine(100, 350, 500, 350); engine.addLine(500, 350, 400, 100); engine.addLine(400, 100, 100, 350);
                    engine.addLine(400, 100, 300, 350); engine.addLine(100, 350, 450, 225); engine.addLine(500, 350, 250, 225);
                    engine.addExplicitLabel(340, 280, "M"); engine.addExplicitLabel(360, 200, "2x"); engine.addExplicitLabel(320, 320, "x");
                }
                else if (topic == 8) { // Thales intercept: a line parallel to the base splits the sides 1:2
                    engine.addLine(300, 120, 150, 400);
                    engine.addLine(300, 120, 450, 400);
                    engine.addLine(150, 400, 450, 400);
                    engine.addLine(250, 213, 350, 213); // parallel line at 1/3 down from the apex -> top:bottom = 1:2
                    engine.addExplicitLabel(300, 105, "A");
                    engine.addExplicitLabel(258, 172, "3"); engine.addExplicitLabel(195, 315, "6");
                    engine.addExplicitLabel(335, 172, "4"); engine.addExplicitLabel(408, 315, "x");
                }
                else if (topic == 9) { // Trapezoid midsegment
                    engine.addLine(220, 180, 380, 180);
                    engine.addLine(150, 360, 450, 360);
                    engine.addLine(220, 180, 150, 360);
                    engine.addLine(380, 180, 450, 360);
                    engine.addLine(185, 270, 415, 270);
                    engine.addExplicitLabel(300, 160, "a"); engine.addExplicitLabel(300, 385, "b");
                    engine.addExplicitLabel(300, 255, "m");
                }
                else if (topic == 10) { // Quadrilateral angle sum: quad split by a diagonal
                    engine.addLine(140, 340, 180, 150); // A -> B
                    engine.addLine(180, 150, 430, 140); // B -> C
                    engine.addLine(430, 140, 470, 350); // C -> D
                    engine.addLine(470, 350, 140, 340); // D -> A
                    engine.addLine(140, 340, 430, 140); // diagonal A -> C
                    engine.addExplicitLabel(123, 352, "A"); engine.addExplicitLabel(168, 140, "B");
                    engine.addExplicitLabel(435, 128, "C"); engine.addExplicitLabel(478, 364, "D");
                }
                else if (topic == 11) { // Rectangle with equal diagonals
                    engine.addRect(150, 160, 470, 360);
                    engine.addLine(150, 160, 470, 360); // diagonal A -> C
                    engine.addLine(470, 160, 150, 360); // diagonal B -> D
                    engine.addExplicitLabel(138, 150, "A"); engine.addExplicitLabel(478, 150, "B");
                    engine.addExplicitLabel(478, 372, "C"); engine.addExplicitLabel(138, 372, "D");
                    engine.addExplicitLabel(305, 268, "O");
                }
                else if (topic == 12) { // Rhombus with perpendicular diagonals
                    engine.addLine(300, 140, 460, 270); // B -> C
                    engine.addLine(460, 270, 300, 400); // C -> D
                    engine.addLine(300, 400, 140, 270); // D -> A
                    engine.addLine(140, 270, 300, 140); // A -> B
                    engine.addLine(300, 140, 300, 270); engine.addLine(300, 270, 300, 400); // vertical diagonal via O
                    engine.addLine(140, 270, 300, 270); engine.addLine(300, 270, 460, 270); // horizontal diagonal via O
                    engine.addExplicitLabel(300, 130, "B"); engine.addExplicitLabel(468, 272, "C");
                    engine.addExplicitLabel(300, 415, "D"); engine.addExplicitLabel(122, 272, "A");
                    engine.addExplicitLabel(312, 262, "O");
                }
                else if (topic == 13) { // Area of a parallelogram: base b, perpendicular height h
                    engine.addLine(140, 350, 220, 350); engine.addLine(220, 350, 420, 350); // base b, split at foot F
                    engine.addLine(220, 180, 500, 180); // top side
                    engine.addLine(140, 350, 220, 180); // left slant
                    engine.addLine(420, 350, 500, 180); // right slant
                    engine.addLine(220, 180, 220, 350); // height (right angle at F)
                    engine.addExplicitLabel(320, 372, "b"); engine.addExplicitLabel(230, 270, "h");
                }
                else if (topic == 14) { // Area of a trapezoid: parallel sides a, b and height h
                    engine.addLine(220, 180, 380, 180); // a (top)
                    engine.addLine(140, 350, 220, 350); engine.addLine(220, 350, 460, 350); // b (bottom), split at foot
                    engine.addLine(220, 180, 140, 350); // left leg
                    engine.addLine(380, 180, 460, 350); // right leg
                    engine.addLine(220, 180, 220, 350); // height (right angle at foot)
                    engine.addExplicitLabel(300, 165, "a"); engine.addExplicitLabel(300, 372, "b"); engine.addExplicitLabel(230, 270, "h");
                }
                else if (topic == 15) { // Similar triangles (AA): a small triangle and its 1.8x copy
                    engine.addLine(120, 350, 240, 350); engine.addLine(240, 350, 160, 230); engine.addLine(160, 230, 120, 350);
                    engine.addLine(320, 360, 536, 360); engine.addLine(536, 360, 392, 144); engine.addLine(392, 144, 320, 360);
                    engine.addExplicitLabel(110, 365, "A"); engine.addExplicitLabel(245, 365, "B"); engine.addExplicitLabel(150, 218, "C");
                    engine.addExplicitLabel(310, 375, "A'"); engine.addExplicitLabel(542, 375, "B'"); engine.addExplicitLabel(384, 134, "C'");
                }
            }
            // ================= GRADE 9 =================
            else if (grade == 9) {
                if (topic == 1) {
                    Geometry base = engine.addLine(150, 400, 450, 400);
                    engine.addLine(450, 400, 350, 150); engine.addLine(350, 150, 150, 400);
                    Geometry h = engine.addLine(350, 150, 350, 400);
                    safeAddVisualAngle(base, h, 90);
                }
                else if (topic == 2) {
                    engine.addCircle(300, 250, 150);
                    engine.addLine(150, 250, 450, 250); engine.addLine(450, 250, 300, 100); engine.addLine(300, 100, 150, 250);
                    engine.addExplicitLabel(300, 270, "O"); engine.addExplicitLabel(300, 230, "2R");
                }
                else if (topic == 3) {
                    engine.addCircle(300, 250, 150);
                    Geometry c1 = engine.addLine(300, 250, 150, 250); Geometry c2 = engine.addLine(300, 250, 300, 400);
                    Geometry i1 = engine.addLine(400, 150, 150, 250); Geometry i2 = engine.addLine(400, 150, 300, 400);
                    engine.addLine(300, 250, 400, 150);
                    safeAddVisualAngle(c1, c2, 90); safeAddVisualAngle(i1, i2, 45);
                }
                else if (topic == 4) {
                    engine.addCircle(350, 250, 100);
                    engine.addLine(100, 150, 350, 150); engine.addLine(100, 150, 420, 320);
                    engine.addLine(350, 150, 275, 185); engine.addLine(350, 150, 420, 320);
                    engine.addExplicitLabel(80, 150, "B"); engine.addExplicitLabel(350, 130, "A");
                    engine.addExplicitLabel(260, 175, "D"); engine.addExplicitLabel(435, 335, "C");
                }
                else if (topic == 5) { // Circumscribed (tangential) quadrilateral: incircle touches all 4 sides
                    engine.addCircle(300, 250, 100);
                    engine.addLine(220, 150, 380, 150); // top a (tangent at y=150)
                    engine.addLine(380, 150, 425, 350); // right leg b (tangent)
                    engine.addLine(425, 350, 175, 350); // bottom c (tangent at y=350)
                    engine.addLine(175, 350, 220, 150); // left leg d (tangent)
                    engine.addExplicitLabel(300, 135, "a"); engine.addExplicitLabel(413, 250, "b");
                    engine.addExplicitLabel(300, 372, "c"); engine.addExplicitLabel(180, 250, "d");
                }
                else if (topic == 6) { // Intersecting chords inside a circle
                    engine.addCircle(300, 250, 150);
                    engine.addLine(170, 180, 430, 320);
                    engine.addLine(200, 380, 400, 130);
                    engine.addExplicitLabel(210, 205, "a"); engine.addExplicitLabel(395, 300, "b");
                    engine.addExplicitLabel(245, 320, "c"); engine.addExplicitLabel(370, 175, "d");
                }
                else if (topic == 7) { // Ptolemy: a general cyclic quadrilateral with both diagonals distinct
                    engine.addCircle(300, 250, 150);
                    engine.addLine(300, 100, 448, 224); // A-B
                    engine.addLine(448, 224, 351, 391); // B-C
                    engine.addLine(351, 391, 152, 276); // C-D
                    engine.addLine(152, 276, 300, 100); // D-A
                    engine.addLine(300, 100, 351, 391); // diagonal A-C
                    engine.addLine(448, 224, 152, 276); // diagonal B-D
                    engine.addExplicitLabel(295, 88, "A"); engine.addExplicitLabel(458, 220, "B");
                    engine.addExplicitLabel(355, 405, "C"); engine.addExplicitLabel(133, 272, "D");
                }
                else if (topic == 8) { // Angle in a semicircle (Thales): right angle at C
                    engine.addCircle(300, 260, 150);
                    engine.addLine(150, 260, 450, 260); // diameter A -> B through centre O
                    engine.addLine(150, 260, 390, 140); // A -> C
                    engine.addLine(390, 140, 450, 260); // C -> B
                    engine.addExplicitLabel(133, 262, "A"); engine.addExplicitLabel(458, 262, "B");
                    engine.addExplicitLabel(300, 280, "O"); engine.addExplicitLabel(392, 125, "C");
                }
                else if (topic == 9) { // Area of a circle: radius labelled r
                    engine.addCircle(300, 260, 150);
                    engine.addLine(300, 260, 450, 260); // radius O -> edge
                    engine.addExplicitLabel(300, 280, "O"); engine.addExplicitLabel(375, 250, "r");
                    engine.addExplicitLabel(250, 185, "A = πr²");
                }
                else if (topic == 10) { // Tangent perpendicular to the radius at the touch point T
                    engine.addCircle(280, 260, 120);
                    engine.addLine(120, 140, 280, 140); // tangent left of T
                    engine.addLine(280, 140, 470, 140); // tangent right of T
                    engine.addLine(280, 260, 280, 140); // radius O -> T
                    engine.addExplicitLabel(280, 280, "O"); engine.addExplicitLabel(285, 130, "T");
                    engine.addExplicitLabel(400, 130, "tangent");
                }
                else if (topic == 11) { // Cyclic quadrilateral ABCD inscribed in a circle
                    engine.addCircle(300, 250, 150);
                    engine.addLine(300, 100, 448, 224); // A -> B
                    engine.addLine(448, 224, 351, 391); // B -> C
                    engine.addLine(351, 391, 152, 276); // C -> D
                    engine.addLine(152, 276, 300, 100); // D -> A
                    engine.addExplicitLabel(295, 88, "A"); engine.addExplicitLabel(458, 220, "B");
                    engine.addExplicitLabel(355, 405, "C"); engine.addExplicitLabel(133, 272, "D");
                }
                else if (topic == 12) { // Two equal tangents PA, PB from an external point P
                    engine.addCircle(250, 250, 110);
                    engine.addLine(520, 250, 295, 150); // P -> A (tangent)
                    engine.addLine(520, 250, 295, 350); // P -> B (tangent)
                    engine.addLine(250, 250, 295, 150); // radius O -> A
                    engine.addLine(250, 250, 295, 350); // radius O -> B
                    engine.addExplicitLabel(230, 262, "O"); engine.addExplicitLabel(527, 245, "P");
                    engine.addExplicitLabel(275, 140, "A"); engine.addExplicitLabel(275, 368, "B");
                }
            }
        }
        // ================= GRADES 10, 11, 12 (3D) =================
        else {
            if (canvas3D != null) {
                canvas3D.clear();

                if (grade == 10 && topic == 1) {
                    canvas3D.addPlane(Arrays.asList("A", "B", "C", "D")); canvas3D.addPoint("A", -150, 0, -150); canvas3D.addPoint("B", 150, 0, -150); canvas3D.addPoint("C", 150, 0, 150); canvas3D.addPoint("D", -150, 0, 150);
                    canvas3D.addLine("A", "D");
                    canvas3D.addPoint("A2", -150, 100, -150); canvas3D.addPoint("D2", -150, 100, 150);
                    canvas3D.addLine("A2", "D2");
                }
                else if (grade == 10 && topic == 2) {
                    canvas3D.addPlane(Arrays.asList("A1", "B1", "C1", "D1")); canvas3D.addPoint("A1", -100, 100, -100); canvas3D.addPoint("B1", 100, 100, -100); canvas3D.addPoint("C1", 100, 100, 100); canvas3D.addPoint("D1", -100, 100, 100);
                    canvas3D.addPlane(Arrays.asList("A2", "B2", "C2", "D2")); canvas3D.addPoint("A2", -100, -100, -100); canvas3D.addPoint("B2", 100, -100, -100); canvas3D.addPoint("C2", 100, -100, 100); canvas3D.addPoint("D2", -100, -100, 100);
                }
                else if (grade == 10 && topic == 3) { // Line perpendicular to a plane
                    canvas3D.addPlane(Arrays.asList("A", "B", "C", "D")); canvas3D.addPoint("A", -150, 0, -150); canvas3D.addPoint("B", 150, 0, -150); canvas3D.addPoint("C", 150, 0, 150); canvas3D.addPoint("D", -150, 0, 150);
                    canvas3D.addPoint("O", 0, 0, 0); canvas3D.addPoint("T", 0, 200, 0);
                    canvas3D.addLine("O", "T"); // the perpendicular line
                    canvas3D.addPoint("P1", -120, 0, 0); canvas3D.addPoint("P2", 120, 0, 0); canvas3D.addLine("P1", "P2");
                    canvas3D.addPoint("Q1", 0, 0, -120); canvas3D.addPoint("Q2", 0, 0, 120); canvas3D.addLine("Q1", "Q2");
                }
                else if (grade == 11 && topic == 1) {
                    canvas3D.addPlane(Arrays.asList("A", "B", "C", "D")); canvas3D.addPoint("A", -150, 0, -150); canvas3D.addPoint("B", 150, 0, -150); canvas3D.addPoint("C", 150, 0, 150); canvas3D.addPoint("D", -150, 0, 150);
                    canvas3D.addPoint("H", 0, 200, 0); canvas3D.addPoint("O", 0, 0, 0); canvas3D.addPoint("P", 100, 0, 80);
                    canvas3D.addLine("H", "O"); canvas3D.addLine("O", "P"); canvas3D.addLine("H", "P");
                    canvas3D.addPoint("L1", 50, 0, 120); canvas3D.addPoint("L2", 150, 0, 40); canvas3D.addLine("L1", "L2");
                }
                else if (grade == 11 && topic == 2) { canvas3D.addCylinder("HexPrism", 0, -100, 0, 100, 200); }
                else if (grade == 11 && topic == 3) { canvas3D.addPyramid("Pyramid", 0, -50, 0, 200, 200, 250); }
                else if (grade == 11 && topic == 4) { // Cube with a space diagonal
                    canvas3D.addPoint("A", -100, -100, -100); canvas3D.addPoint("B", 100, -100, -100); canvas3D.addPoint("C", 100, -100, 100); canvas3D.addPoint("D", -100, -100, 100);
                    canvas3D.addPoint("A2", -100, 100, -100); canvas3D.addPoint("B2", 100, 100, -100); canvas3D.addPoint("C2", 100, 100, 100); canvas3D.addPoint("D2", -100, 100, 100);
                    canvas3D.addLine("A", "B"); canvas3D.addLine("B", "C"); canvas3D.addLine("C", "D"); canvas3D.addLine("D", "A");
                    canvas3D.addLine("A2", "B2"); canvas3D.addLine("B2", "C2"); canvas3D.addLine("C2", "D2"); canvas3D.addLine("D2", "A2");
                    canvas3D.addLine("A", "A2"); canvas3D.addLine("B", "B2"); canvas3D.addLine("C", "C2"); canvas3D.addLine("D", "D2");
                    canvas3D.addLine("A", "C2"); // space diagonal
                }
                else if (grade == 12 && topic == 1) { canvas3D.addCylinder("Cyl", 0, -100, 0, 100, 200); }
                else if (grade == 12 && topic == 2) { canvas3D.addCone("Cone", 0, -100, 0, 120, 250, 1.0f); }
                else if (grade == 12 && topic == 3) {
                    canvas3D.addSphere("Sphere", 0, 0, 0, 150);
                    canvas3D.addCylinder("CylWrap", 0, -150, 0, 150, 300);
                }
                else if (grade == 12 && topic == 4) { canvas3D.addCylinder("CylSide", 0, -100, 0, 100, 200); }
            }
        }

        resetCamera(use3D);
        setExplanationHtml(grade, topic);
    }

}