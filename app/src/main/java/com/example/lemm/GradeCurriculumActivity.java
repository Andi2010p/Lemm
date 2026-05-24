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

    private TextView tvGradeTitle, tvTheoremExplanation, tvZoomPercent;
    private LinearLayout rotationControls;

    private boolean isMoveMode = true;
    private boolean is3DModeActive = false;

    private ScaleGestureDetector scaleDetector;
    private int activePointerId = -1;
    private float lastX, lastY;
    private boolean isScaling = false;

    private int currentGrade, currentTopic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grade_curriculum);

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
            tvTheoremExplanation = findViewById(R.id.tvTheoremExplanation);
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
                    btnToggleTool.setColorFilter(android.graphics.Color.parseColor("#0C3D6A"));
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
        if (tvTheoremExplanation == null) return;

        int defId = getResources().getIdentifier("th_def_" + grade + "_" + topic, "string", getPackageName());
        int proofId = getResources().getIdentifier("th_proof_" + grade + "_" + topic, "string", getPackageName());
        int exId = getResources().getIdentifier("th_ex_" + grade + "_" + topic, "string", getPackageName());

        int qId = getResources().getIdentifier("th_q_" + grade + "_" + topic, "string", getPackageName());
        int aId = getResources().getIdentifier("th_a_" + grade + "_" + topic, "string", getPackageName());

        String def = defId != 0 ? getString(defId).replace("\n", "<br>") : "";
        String proof = proofId != 0 ? getString(proofId).replace("\n", "<br>") : "";
        String ex = exId != 0 ? getString(exId).replace("\n", "<br>") : "";

        String html = "<b><font color='#0C3D6A'>" + getString(R.string.th_header_theorem) + "</font></b><br>" + def + "<br><br>" +
                "<b><font color='#27AE60'>" + getString(R.string.th_header_proof) + "</font></b><br>" + proof + "<br><br>" +
                "<b><font color='#E67E22'>" + getString(R.string.th_header_example) + "</font></b><br>" + ex;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvTheoremExplanation.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
        } else {
            tvTheoremExplanation.setText(Html.fromHtml(html));
        }

        // INTERACTIVE QUIZ LOGIC
        View quizCard = findViewById(R.id.quizCard);
        TextView tvQuizQuestion = findViewById(R.id.tvQuizQuestion);
        TextView tvQuizFeedback = findViewById(R.id.tvQuizFeedback);
        com.google.android.material.textfield.TextInputEditText etQuizAnswer = findViewById(R.id.etQuizAnswer);
        View btnCheckAnswer = findViewById(R.id.btnCheckAnswer);

        if (qId != 0 && aId != 0 && quizCard != null) {
            quizCard.setVisibility(View.VISIBLE);
            tvQuizQuestion.setText(getString(qId));
            String correctAnswer = getString(aId).trim();

            // Clear previous answer if user reloads
            etQuizAnswer.setText("");
            tvQuizFeedback.setVisibility(View.GONE);

            btnCheckAnswer.setOnClickListener(v -> {
                String userAnswer = etQuizAnswer.getText().toString().trim();

                // Haptic feedback (small vibration)
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

                if (userAnswer.isEmpty()) {
                    tvQuizFeedback.setVisibility(View.VISIBLE);
                    tvQuizFeedback.setText("Please enter an answer.");
                    tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#F59E0B")); // Orange
                    return;
                }

                tvQuizFeedback.setVisibility(View.VISIBLE);

                if (userAnswer.equals(correctAnswer)) {
                    tvQuizFeedback.setText(getString(R.string.correct_answer));
                    tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#10B981")); // Green

                    // Hide keyboard on success
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(etQuizAnswer.getWindowToken(), 0);
                } else {
                    tvQuizFeedback.setText(getString(R.string.incorrect_answer));
                    tvQuizFeedback.setTextColor(android.graphics.Color.parseColor("#EF4444")); // Red
                    etQuizAnswer.selectAll(); // Select the wrong text so they can easily type over it
                }
            });
        } else if (quizCard != null) {
            quizCard.setVisibility(View.GONE);
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
                else if (topic == 5) {
                    Geometry l1 = engine.addLine(150, 350, 450, 350); Geometry l2 = engine.addLine(450, 350, 250, 150); Geometry l3 = engine.addLine(250, 150, 150, 350);
                    Geometry h = engine.addLine(250, 150, 250, 350);
                    safeAddVisualAngle(l2, l3, 90); safeAddVisualAngle(l1, h, 90);
                    engine.addExplicitLabel(260, 250, "h"); engine.addExplicitLabel(200, 380, "a_c"); engine.addExplicitLabel(350, 380, "b_c");
                }
                else if (topic == 6) {
                    engine.addLine(150, 350, 450, 350); engine.addLine(450, 350, 150, 150); engine.addLine(150, 150, 150, 350);
                    engine.addLine(150, 150, 300, 350);
                    engine.addCircle(300, 350, 150);
                    engine.addExplicitLabel(230, 230, "m_c = R");
                }
                else if (topic == 7) {
                    engine.addLine(100, 350, 500, 350); engine.addLine(500, 350, 400, 100); engine.addLine(400, 100, 100, 350);
                    engine.addLine(400, 100, 300, 350); engine.addLine(100, 350, 450, 225); engine.addLine(500, 350, 250, 225);
                    engine.addExplicitLabel(340, 280, "M"); engine.addExplicitLabel(360, 200, "2x"); engine.addExplicitLabel(320, 320, "x");
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
                else if (topic == 5) {
                    engine.addCircle(300, 250, 100);
                    engine.addLine(200, 150, 400, 150); engine.addLine(400, 150, 450, 350);
                    engine.addLine(450, 350, 150, 350); engine.addLine(150, 350, 200, 150);
                    engine.addExplicitLabel(300, 130, "a"); engine.addExplicitLabel(440, 250, "b");
                    engine.addExplicitLabel(300, 380, "c"); engine.addExplicitLabel(150, 250, "d");
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
                else if (grade == 11 && topic == 1) {
                    canvas3D.addPlane(Arrays.asList("A", "B", "C", "D")); canvas3D.addPoint("A", -150, 0, -150); canvas3D.addPoint("B", 150, 0, -150); canvas3D.addPoint("C", 150, 0, 150); canvas3D.addPoint("D", -150, 0, 150);
                    canvas3D.addPoint("H", 0, 200, 0); canvas3D.addPoint("O", 0, 0, 0); canvas3D.addPoint("P", 100, 0, 80);
                    canvas3D.addLine("H", "O"); canvas3D.addLine("O", "P"); canvas3D.addLine("H", "P");
                    canvas3D.addPoint("L1", 50, 0, 120); canvas3D.addPoint("L2", 150, 0, 40); canvas3D.addLine("L1", "L2");
                }
                else if (grade == 11 && topic == 2) { canvas3D.addCylinder("HexPrism", 0, -100, 0, 100, 200); }
                else if (grade == 11 && topic == 3) { canvas3D.addPyramid("Pyramid", 0, -50, 0, 200, 200, 250); }
                else if (grade == 12 && topic == 1) { canvas3D.addCylinder("Cyl", 0, -100, 0, 100, 200); }
                else if (grade == 12 && topic == 2) { canvas3D.addCone("Cone", 0, -100, 0, 120, 250, 1.0f); }
                else if (grade == 12 && topic == 3) {
                    canvas3D.addSphere("Sphere", 0, 0, 0, 150);
                    canvas3D.addCylinder("CylWrap", 0, -150, 0, 150, 300);
                }
            }
        }

        resetCamera(use3D);
        setExplanationHtml(grade, topic);
    }

}