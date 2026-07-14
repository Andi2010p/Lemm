package com.example.lemm;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
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
    private boolean proCloudRefreshed = false; // guards the one-shot cloud Pro re-check per solve
    private boolean styleGlass; // the app style this screen was built with (Glass vs Basic)
    private EditText etDescription, etExtra;
    private TextView tvZoom;
    private LinearLayout inputArea, rotationControls, stepsContainer, solutionControls;
    private ProgressBar progressBar;
    private GeminiAI geminiAI;
    private DatabaseHelper dbHelper;
    private ImageButton btnStopAI, btnToggleInput, btnExpandDesc, btnExpandExtra, btnHistory;

    private String lastSolutionText = "";
    private String lastAIResponse = "";
    private View resultActions;
    private View btnAskLemma;
    // Plain text of each rendered solution card, in order — used for "Copy all".
    private final List<String> solutionCardTexts = new ArrayList<>();
    private String editId = "";
    private String originalDate = null;
    private boolean isFromHistory = false;
    private boolean isSaved = true;
    private String currentLangCode = "en";

    private ActivityResultLauncher<String> notifPermissionLauncher;
    private static final String SOLUTION_CHANNEL_ID = "ai_solution_channel";
    private static final int SOLUTION_NOTIF_ID = 4101;

    // API-key rotation: when one key is out of quota, the next is tried automatically.
    private List<String> solveKeys;
    // Parallel to solveKeys: what each key is, so we can explain fallbacks/exhaustion to the user.
    private List<String> solveKeyKinds;
    private static final String KIND_SUB = "sub";       // built-in subscription/Pro key
    private static final String KIND_USER = "user";     // the user's own personal key
    private static final String KIND_BACKUP = "backup"; // app backup key (last resort)
    private String solvePrompt, solveProblemText;
    private String nReadyTitle, nReadyBody, nFailTitle, nFailBody, nNotGeoTitle, nNotGeoMsg, nQuotaBody;

    // Attached images (problem text and/or figure photos) sent with the solve.
    private final List<android.graphics.Bitmap> selectedImages = new ArrayList<>();
    private List<android.graphics.Bitmap> solveImages;
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> voiceLauncher;
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermLauncher;
    private String pendingCameraPath;
    private EditText voiceTarget;
    private LinearLayout imageStrip;
    private View imageScroll, btnAddImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_geometry_input);
        styleGlass = StyleManager.isGlass(this);

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
        resultActions = findViewById(R.id.resultActions);
        btnAskLemma = findViewById(R.id.btnAskLemma);
        findViewById(R.id.btnCopyAll).setOnClickListener(v -> copyAllSolution());
        findViewById(R.id.btnSaveImage).setOnClickListener(v -> exportSolution());
        btnAskLemma.setOnClickListener(v -> openSolutionChat());
        progressBar = findViewById(R.id.progressBar);
        btnStopAI = findViewById(R.id.btnStopAI);
        btnToggleInput = findViewById(R.id.btnToggleInput);
        btnExpandDesc = findViewById(R.id.btnExpandDesc);
        btnExpandExtra = findViewById(R.id.btnExpandExtra);
        btnHistory = findViewById(R.id.btnHistory);
        imageStrip = findViewById(R.id.imageStrip);
        imageScroll = findViewById(R.id.imageScroll);
        btnAddImage = findViewById(R.id.btnAddImage);

        // Pick one or more images from the gallery (problem text and/or figures).
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                List<android.net.Uri> uris = new ArrayList<>();
                android.content.ClipData clip = result.getData().getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
                } else if (result.getData().getData() != null) {
                    uris.add(result.getData().getData());
                }
                if (!uris.isEmpty()) addImagesFromUris(uris);
            }
        });
        // Capture a drawing with the camera and attach it (the AI reads the figure via vision).
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && pendingCameraPath != null) {
                if (addImageFromFile(pendingCameraPath)) {
                    Toast.makeText(this, R.string.scan_drawing_attached, Toast.LENGTH_SHORT).show();
                }
            }
        });
        cameraPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) launchCamera();
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show();
        });

        // "Add image": let the user photograph a drawing OR pick an existing image.
        btnAddImage.setOnClickListener(v -> {
            CharSequence[] items = { getString(R.string.img_take_photo), getString(R.string.img_choose_gallery) };
            new AlertDialog.Builder(this)
                    .setTitle(R.string.img_add_title)
                    .setItems(items, (d, which) -> {
                        if (which == 0) startCameraCapture();
                        else openImageGallery();
                    })
                    .show();
        });

        // Voice input: spoken text is appended into whichever field requested it.
        voiceLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                java.util.ArrayList<String> matches =
                        result.getData().getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (matches != null && !matches.isEmpty()) {
                    EditText target = (voiceTarget != null) ? voiceTarget : etDescription;
                    String spoken = matches.get(0);
                    String existing = target.getText().toString();
                    target.setText(existing.trim().isEmpty() ? spoken : existing + " " + spoken);
                    target.setSelection(target.getText().length());
                }
            }
        });
        findViewById(R.id.btnVoiceInput).setOnClickListener(v -> { voiceTarget = etDescription; startVoiceInput(); });
        findViewById(R.id.btnVoiceExtra).setOnClickListener(v -> { voiceTarget = etExtra; startVoiceInput(); });

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
            Ux.tick(v);
            String prob = etDescription.getText().toString().trim();
            proCloudRefreshed = false; // allow one fresh cloud Pro check for this attempt
            if (!prob.isEmpty() || !selectedImages.isEmpty()) solveWithAI(prob);
            else Toast.makeText(this, getString(R.string.enter_problem_or_image), Toast.LENGTH_SHORT).show();
        });

        btnStopAI.setOnClickListener(v -> resetAll());

        findViewById(R.id.btnSave).setOnClickListener(v -> showSaveDialog(false));

        findViewById(R.id.btnDontSave).setOnClickListener(v -> {
            isSaved = true;
            solutionControls.setVisibility(View.GONE);
        });

        canvas3D.setOnZoomChangeListener(pct -> tvZoom.setText(pct + "%"));

        // Tap a point/line/plane/angle in the 3D figure to inspect it — a "stock" card slides out
        // with everything about that element. Tapping empty space hides it.
        final android.widget.TextView tvElementInfo = findViewById(R.id.tvElementInfo);
        canvas3D.setOnElementSelectedListener(info -> {
            if (tvElementInfo == null) return;
            if (info == null || info.isEmpty()) {
                tvElementInfo.setVisibility(View.GONE);
            } else {
                tvElementInfo.setText(info);
                tvElementInfo.setVisibility(View.VISIBLE);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());
        findViewById(R.id.btnHelpInput).setOnClickListener(v -> HelpDialog.show(this, R.string.help_title, R.string.help_input_body));

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
                "You are a warm, patient geometry tutor for school students. Solve the problem the user sends. " +
                "If it isn't a geometry/math problem, still help, but skip the drawing commands.\n\n" +

                "════════ FIRST — UNDERSTAND & PICTURE THE PROBLEM ════════\n" +
                "This is the MOST important part: really imagine the situation before you draw or solve. Do this " +
                "thinking PRIVATELY, above a line that reads exactly ===SOLUTION=== . Everything above that line is " +
                "hidden from the student; everything below it is shown. In the private part:\n" +
                "  1. Re-read the problem and restate, in your own words, what is happening.\n" +
                "  2. Name every shape and EXACTLY how the parts relate: which point is ON vs INSIDE vs OUTSIDE a " +
                "shape; what is tangent, inscribed, circumscribed, perpendicular, parallel, equal, a midpoint, a " +
                "diameter, an altitude, etc. Get the configuration right — this is where mistakes happen.\n" +
                "  3. List every given number and exactly what the question asks for.\n" +
                "  4. Choose concrete positions/coordinates that make ALL of those relationships TRUE AT ONCE, then " +
                "CHECK each given fact against them (a point said to be on a circle is exactly the radius away; a " +
                "tangent touches at one point; a right angle is really 90°). Fix anything that doesn't match.\n" +
                "  5. Work the answer out, then SANITY-CHECK it before showing it: is it the right formula, the right " +
                "units, and a reasonable size, and does it satisfy every given fact? If a check fails, redo it.\n" +
                "Then output the line:\n===SOLUTION===\n" +
                "and BELOW it give the drawing commands and the student explanation described next. (If you are very " +
                "sure and need no private notes you may start directly, but ALWAYS make the figure match every " +
                "given fact.)\n\n" +

                "════════ PART 1 — DRAW THE FIGURE ════════\n" +
                "The app draws your figure from the special commands below. Put ALL drawing commands FIRST, " +
                "one per line, BEFORE the explanation.\n\n" +
                "MOST problems are FLAT (2D): triangles, quadrilaterals, circles, angles, inscribed shapes, etc. " +
                "Draw a flat problem in ONE single flat plane, like a picture on paper:\n" +
                "  • Every point uses z = 0:  DRAW3D:Name,x,y,0   (the THIRD number is ALWAYS 0 for a 2D problem).\n" +
                "  • Bigger y = higher up, bigger x = further right. Use sizes about 50–300.\n" +
                "  • Draw circles with CIRCLE3D — a CIRCLE3D lies in that SAME flat plane, so it stays round and " +
                "in the plane of the figure. Give it z = 0 too. NEVER build a circle out of little line segments, " +
                "and NEVER place the circle on a different plane than the rest of the figure.\n" +
                "  • Do NOT tilt a 2D figure and do NOT use any 3D solid command for it.\n\n" +
                "Use 3D ONLY when the problem is really about a solid body (cube, box, prism, pyramid, cone, " +
                "cylinder, sphere). Then Y is UP, the base sits at y = 0, and you may use the solid commands. " +
                "For a solid, draw every face with PLANE3D.\n\n" +
                "COMMANDS (use these names EXACTLY, one per line):\n" +
                "DRAW3D:Name,x,y,z\n" +
                "LINE3D:Name1,Name2[,color]   (segment between two points; the optional color — red, blue, green, " +
                "orange, purple, cyan, etc., or a hex like #FF0000 — highlights an auxiliary line such as a height. " +
                "Example: LINE3D:A,H,red)\n" +
                "CIRCLE3D:Name,cx,cy,cz,radius   (a circle in the flat drawing plane)\n" +
                "ANGLE3D:Vertex,A,B[,degrees]   (marks angle A-Vertex-B with an arc and its value, and auto-draws " +
                "the two arms if missing. ALWAYS mark every angle you talk about, e.g. ANGLE3D:B,A,C,90)\n" +
                "PLANE3D:Name,v1,v2,v3,v4   (a flat face — for 3D solids)\n" +
                "PYRAMID3D:Name,cx,cy,cz,width,depth,height\n" +
                "CONE3D:Name,cx,cy,cz,radius,height,curvature   (curvature 1.0 = a normal cone; use CONE3D for any " +
                "cone, never lines+circle)\n" +
                "CYLINDER3D:Name,cx,cy,cz,radius,height\n" +
                "SPHERE3D:Name,x,y,z,radius\n" +
                "PRISM3D:Name,height,x1,z1,x2,z2,x3,z3[,...]   (a flat base of (x,z) pairs pushed straight up into " +
                "a solid; use for boxes, triangular/hexagonal prisms, L-shapes. Y is UP; base at y = 0)\n\n" +

                "CONSTRUCTIONS — let the app place DERIVED points EXACTLY (this is the #1 way to avoid wrong figures):\n" +
                "For a point you get FROM other points — a midpoint, the foot of a height, or where two lines cross — do " +
                "NOT compute its coordinates yourself (that is where drawings go wrong). DECLARE it instead and the app " +
                "places it perfectly. Define the points it refers to with DRAW3D FIRST, then:\n" +
                "MIDPOINT:M,A,B          (M = the exact midpoint of segment AB — for medians, midsegments, the centre of a diameter)\n" +
                "FOOT:H,P,A,B            (H = the foot of the perpendicular from point P onto line AB — e.g. the foot of an altitude/height)\n" +
                "INTERSECT:X,A,B,C,D     (X = the point where line AB crosses line CD — e.g. where diagonals or two medians meet)\n" +
                "After declaring it, use the new point in LINE3D/ANGLE3D like any other. ALWAYS prefer these over guessing " +
                "coordinates for a midpoint, an altitude foot, or an intersection.\n\n" +

                "DRAWING RULES:\n" +
                "  • Define EVERY point with DRAW3D BEFORE you use it in a LINE3D/PLANE3D/ANGLE3D — including feet of " +
                "heights, midpoints, centres and intersection points (e.g. DRAW3D:H,...). A command that uses a name " +
                "you never defined is skipped and won't appear, so never reference an undefined point.\n" +
                "  • If the solution uses ANY extra segment — height/altitude, median, bisector, midsegment, diagonal, " +
                "radius, apothem, perpendicular, tangent, or a segment joining two named points — you MUST draw it with " +
                "LINE3D so the student can see it.\n\n" +

                "GEOMETRIC ACCURACY — a relationship stated in words MUST be real in the picture, not approximate. " +
                "Compute the coordinates so shapes actually meet:\n" +
                "  • A point that lies ON a circle must be EXACTLY the radius away from the centre. For centre (cx,cy) " +
                "and radius r, place it at (cx + r·cosθ, cy + r·sinθ) for some angle θ — never just near the circle.\n" +
                "  • TANGENT line (touches a circle at exactly ONE point T): put T on the circle, and remember the tangent " +
                "is PERPENDICULAR to the radius at T. Draw T with DRAW3D on the circle and run the visible tangent segment " +
                "along that perpendicular THROUGH T, so the line and the circle really touch at T and nowhere else. If the " +
                "tangent comes from an outside point P, make T an END of the segment: LINE3D:P,T.\n" +
                "  • A CHORD / SECANT / DIAMETER must have the point(s) where it meets the circle placed exactly on the circle.\n" +
                "  • An INSCRIBED polygon has EVERY vertex exactly on the circle; a circle inscribed inside a shape touches " +
                "each side at one point that is radius-distance from the centre.\n" +
                "  • ALWAYS mark the touch / meeting point with DRAW3D and route the line THROUGH it — a line and a circle " +
                "that share a point must be drawn actually meeting, never with a gap between them.\n" +
                "WORKED TANGENT EXAMPLE (circle centre O radius 60, tangent from outside point P touching at T):\n" +
                "CIRCLE3D:c,0,0,0,60\n" +
                "DRAW3D:O,0,0,0\n" +
                "DRAW3D:T,0,60,0\n" +
                "DRAW3D:P,140,60,0\n" +
                "LINE3D:P,T\n" +
                "LINE3D:O,T\n" +
                "ANGLE3D:T,O,P,90\n\n" +

                "════════ PART 2 — EXPLAIN IT (very important) ════════\n" +
                "The explanation is for a STUDENT. Make it SUPER simple and SUPER step-by-step, like a kind teacher " +
                "talking slowly and patiently. The step-by-step explanation MUST be written in " + langName + ".\n" +
                "Structure it EXACTLY like this (keep the labels GIVEN, STEP and FINAL ANSWER):\n" +
                "   GIVEN:\n" +
                "   (In plain, friendly words say what we already know, and which letter is which point / side / angle. " +
                "Do NOT use the word 'STEP' here.)\n" +
                "   STEP 1: short title\n" +
                "   (one small idea, explained very simply)\n" +
                "   STEP 2: short title\n" +
                "   (the next small idea)\n" +
                "   ... as many small steps as needed ...\n" +
                "   FINAL ANSWER:\n" +
                "   (the clean final result with its unit)\n\n" +
                "RULES FOR A GREAT, EASY EXPLANATION:\n" +
                " 0. FORMAT (critical): put EACH step on its OWN new line and begin it with the word STEP + its " +
                "number + a colon — 'STEP 1:', 'STEP 2:', 'STEP 3:', … The app turns every 'STEP n:' line into a " +
                "separate card, so NEVER merge two steps into one paragraph and NEVER drop the STEP number. Put " +
                "'FINAL ANSWER:' on its own line at the end. Keep these labels (GIVEN, STEP, FINAL ANSWER) at the " +
                "start of their line.\n" +
                " 1. Keep every step TINY — ONE idea per step. If a step feels big, split it into two smaller steps.\n" +
                " 2. In each step: FIRST name the rule in words (e.g. 'By the Pythagorean theorem'), THEN write the " +
                "formula, THEN put the numbers in, THEN compute — each on its own short line.\n" +
                " 3. Use simple everyday words a student understands. If you must use a hard term, explain it in a few words.\n" +
                " 4. Carry UNITS through the working and the final answer (cm, cm², °, …). Round the final answer sensibly.\n" +
                " 5. Do NOT invent missing data. If something needed is missing, say the assumption you make.\n" +
                " 6. The FINAL ANSWER must be the clean final value, not an expression left to simplify.\n\n" +
                "NEVER mention coordinates, a coordinate system, axes, or x / y / z anywhere in the explanation, and " +
                "never quote the numbers used inside the drawing commands. The student only sees the picture — always " +
                "talk about points, sides, angles and lengths by their LETTERS and their real values, never by their " +
                "position numbers.\n\n" +
                "MATH SYMBOLS: use plain Unicode only (√, ×, ÷, ^, ², ³, π, α, β, γ, θ, °). NEVER use LaTeX or dollar " +
                "signs (no $, no \\frac, no \\sqrt, no \\cos, no curly braces { }). Write like: cos(α), a² + b² = c², √x, a / b.\n\n" +

                "COMMON MISTAKES TO AVOID: confusing radius and diameter; using the wrong measure (area vs perimeter " +
                "vs volume); assuming a right angle, or that a triangle is isosceles/equilateral, without a given or a " +
                "reason; forgetting units; rounding too early; mixing degrees and radians; and drawing a shape that " +
                "doesn't actually match the givens.\n\n" +

                "════════ GOLD EXAMPLE ════════\n" +
                "Study this worked example — copy its FORMAT and its careful quality, then solve the REAL problem the " +
                "same way (adapt it to the actual problem; write your shown answer in " + langName + "):\n\n" +
                SolutionExemplars.pickFor(problem) + "\n" +
                "════════ NOW SOLVE THE REAL PROBLEM ════════\n\n" +

                "PROBLEM:\n" + problem;
    }

    private void solveWithAI(String problem) {
        // Cloud AI: route the solve through the Lemma backend, which holds a PAID Gemini key and
        // meters the user's plan credits server-side. This is the pipe a paying user's AI comes from.
        if (AiPrefs.cloudEnabled(this)) { solveViaBackend(problem); return; }

        // Non-Gemini providers (OpenAI / Claude) chosen in Settings use their own bring-your-own-key path.
        if (AiConfig.provider(this) != AiConfig.Provider.GEMINI) { solveWithExternal(problem); return; }

        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);

        String username = userPrefs.getString("username", "");
        boolean isProUser = userPrefs.getBoolean("is_pro_user", false);
        boolean privileged = username.equals("Admin_Teacher") || isProUser;

        // A user who activated Pro elsewhere may not have had the flag synced to THIS device yet
        // (the cloud pull on launch is async). Before refusing, pull the account's Pro status once
        // and retry — so a logged-in Pro user is recognised instead of being told to upgrade.
        if (!privileged && !proCloudRefreshed) {
            proCloudRefreshed = true;
            ProStatusManager.syncFromCloud(this, () -> {
                if (!isFinishing() && !isDestroyed()) solveWithAI(problem);
            });
            return;
        }

        // Build the ordered rotation list (the gate is "do we have any primary key?").
        // Subscribers start on the built-in subscription key; if it's out of quota the solver
        // automatically falls through to the user's own personal key(s), then app backups.
        // Free users use their personal key(s) directly (gated by the "use my own keys" toggle).
        solveKeys = new ArrayList<>();
        solveKeyKinds = new ArrayList<>();

        if (privileged && !BuildConfig.GEMINI_API_KEY.isEmpty()) {
            solveKeys.add(BuildConfig.GEMINI_API_KEY);
            solveKeyKinds.add(KIND_SUB);
        }

        // Personal keys: primary for free users (gated by the master toggle), and an automatic
        // fallback for subscribers when their subscription quota runs out.
        boolean usePersonal = privileged || ApiKeyStore.isEnabled(this);
        if (usePersonal) {
            for (String k : ApiKeyStore.getKeys(this)) {
                if (!solveKeys.contains(k)) { solveKeys.add(k); solveKeyKinds.add(KIND_USER); }
            }
        }

        // Pro/privileged users are entitled to the app's keys. The built-in subscription key may be
        // empty in some builds (the app keys live in GEMINI_BACKUP_KEYS instead), so grant the backup
        // keys to privileged users BEFORE the gate — otherwise a Pro user with no personal key would
        // be wrongly told to pay even though Settings shows Pro active.
        if (privileged) {
            for (String bk : BuildConfig.GEMINI_BACKUP_KEYS.split(",")) {
                String k = bk.trim();
                if (!k.isEmpty() && !solveKeys.contains(k)) { solveKeys.add(k); solveKeyKinds.add(KIND_BACKUP); }
            }
        }

        if (solveKeys.isEmpty()) {
            showPaymentDialog();
            return;
        }

        // Token metering: a Plus user on the built-in Gemini spends from their monthly allowance (plus
        // any purchased top-ups). When it's exhausted, send them to buy more instead of solving.
        //
        // Gate on what a solve actually COSTS, not on the problem text alone. estimateTokens(problem)
        // is only ~430 tokens for a short problem, but the deduction afterwards also counts the
        // model's multi-thousand-token answer — so a near-empty wallet used to sail through the gate
        // and get one full solve for free.
        if (Entitlements.shouldMeter(this)) {
            long cost = Math.max(TokenWallet.estimateTokens(problem), Entitlements.APPROX_TOKENS_PER_SOLVE);
            if (!TokenWallet.canSpend(this, cost)) {
                showOutOfTokensDialog();
                return;
            }
        }

        // App backup keys as a last-resort fallback once the user is past the gate.
        for (String bk : BuildConfig.GEMINI_BACKUP_KEYS.split(",")) {
            String k = bk.trim();
            if (!k.isEmpty() && !solveKeys.contains(k)) { solveKeys.add(k); solveKeyKinds.add(KIND_BACKUP); }
        }

        isSaved = false;
        // A freshly generated solution is editable/saveable even if we arrived here from
        // History — otherwise processAIResult() would keep the Save controls hidden.
        isFromHistory = false;
        progressBar.setVisibility(View.VISIBLE);
        setInputLocked(true); // can't edit the problem while the AI is solving

        // Snapshot any attached images for this solve.
        solveImages = selectedImages.isEmpty() ? null : new ArrayList<>(selectedImages);
        String effectiveProblem = problem;
        if (solveImages != null) {
            effectiveProblem = "The problem statement and/or the figure are provided in the attached image(s). "
                    + "Look VERY carefully at the image(s): identify every labelled point, line, angle and mark, "
                    + "and exactly how the shapes are arranged, then treat them as the problem.\n" + problem;
        }
        solvePrompt = getTranslatedSystemPrompt(currentLangCode, effectiveProblem);
        solveProblemText = problem;

        // Capture localized notification text now, while the (locale-wrapped) activity context is valid.
        nReadyTitle = getString(R.string.notif_solution_ready_title);
        nReadyBody = getString(R.string.notif_solution_ready_body);
        nFailTitle = getString(R.string.notif_solution_failed_title);
        nFailBody = getString(R.string.notif_solution_failed_body);
        nNotGeoTitle = getString(R.string.scan_not_geometry_title);
        nNotGeoMsg = getString(R.string.solve_not_geometry_msg);
        nQuotaBody = getString(R.string.notif_quota_body);

        runSolveAttempt(0, 0);
    }

    /** Solve path through the Lemma Cloud backend (server-side paid key + credit metering). */
    private void solveViaBackend(String problem) {
        isSaved = false;
        isFromHistory = false;
        progressBar.setVisibility(View.VISIBLE);
        setInputLocked(true);

        solveImages = selectedImages.isEmpty() ? null : new ArrayList<>(selectedImages);
        String effectiveProblem = problem;
        if (solveImages != null) {
            effectiveProblem = "The problem statement and/or the figure are provided in the attached image(s). "
                    + "Look VERY carefully at the image(s): identify every labelled point, line, angle and mark, "
                    + "and exactly how the shapes are arranged, then treat them as the problem.\n" + problem;
        }
        solvePrompt = getTranslatedSystemPrompt(currentLangCode, effectiveProblem);
        solveProblemText = problem;

        nNotGeoTitle = getString(R.string.scan_not_geometry_title);
        nNotGeoMsg = getString(R.string.solve_not_geometry_msg);

        LemmaBackend.AiRequest req = new LemmaBackend.AiRequest("solve", solvePrompt);
        if (solveImages != null) {
            for (Bitmap b : solveImages) {
                String b64 = toJpegBase64(b);
                if (b64 != null) req.imagesB64.add(b64);
            }
        }

        LemmaBackend.askAI(req, new LemmaBackend.Callback<LemmaBackend.AiReply>() {
            @Override public void onSuccess(LemmaBackend.AiReply v) {
                if (isFinishing() || isDestroyed()) return;
                progressBar.setVisibility(View.GONE);
                setInputLocked(false);
                String text = v.text;
                if (text != null && text.toUpperCase().contains("NOT_GEOMETRY")) {
                    isSaved = true;
                    solutionControls.setVisibility(View.GONE);
                    new AlertDialog.Builder(GeometryInputActivity.this)
                            .setTitle(nNotGeoTitle).setMessage(nNotGeoMsg)
                            .setPositiveButton(android.R.string.ok, null).show();
                    return;
                }
                if (text != null) { lastAIResponse = text; processAIResult(text); }
            }
            @Override public void onError(String code, String message) {
                if (isFinishing() || isDestroyed()) return;
                progressBar.setVisibility(View.GONE);
                setInputLocked(false);
                if (LemmaBackend.isOutOfCredits(code)) { showOutOfTokensDialog(); return; }
                new AlertDialog.Builder(GeometryInputActivity.this)
                        .setMessage(getString(R.string.chat_error))
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
    }

    private static String toJpegBase64(Bitmap b) {
        if (b == null) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Solve path for a bring-your-own-key provider (OpenAI / Anthropic) chosen in Settings. */
    private void solveWithExternal(String problem) {
        final AiConfig.Provider p = AiConfig.provider(this);
        if (AiConfig.key(this, p).isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.ext_no_key, AiConfig.label(p)))
                    .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        isSaved = false;
        isFromHistory = false;
        progressBar.setVisibility(View.VISIBLE);
        setInputLocked(true);

        solveImages = selectedImages.isEmpty() ? null : new ArrayList<>(selectedImages);
        String effectiveProblem = problem;
        if (solveImages != null) {
            effectiveProblem = "The problem statement and/or the figure are provided in the attached image(s). "
                    + "Look VERY carefully at the image(s): identify every labelled point, line, angle and mark, "
                    + "and exactly how the shapes are arranged, then treat them as the problem.\n" + problem;
        }
        solvePrompt = getTranslatedSystemPrompt(currentLangCode, effectiveProblem);
        solveProblemText = problem;

        nReadyTitle = getString(R.string.notif_solution_ready_title);
        nReadyBody = getString(R.string.notif_solution_ready_body);
        nFailTitle = getString(R.string.notif_solution_failed_title);
        nFailBody = getString(R.string.notif_solution_failed_body);

        ExternalAiClient.generate(this, solvePrompt, solveImages, new ExternalAiClient.Callback() {
            @Override public void onText(String text) {
                boolean alive = !isFinishing() && !isDestroyed();
                if (alive) {
                    progressBar.setVisibility(View.GONE);
                    setInputLocked(false);
                    lastAIResponse = text;
                    processAIResult(text);
                }
                if (!isActivityVisible) postSolutionNotification(nReadyTitle, nReadyBody, solveProblemText, text);
            }
            @Override public void onError(String message) {
                boolean alive = !isFinishing() && !isDestroyed();
                if (alive) {
                    progressBar.setVisibility(View.GONE);
                    setInputLocked(false);
                    Toast.makeText(GeometryInputActivity.this, message, Toast.LENGTH_LONG).show();
                }
                if (!isActivityVisible) postSolutionNotification(nFailTitle, nFailBody, solveProblemText, null);
            }
        });
    }

    private void runSolveAttempt(int keyIndex) { runSolveAttempt(keyIndex, 0, 0); }
    private void runSolveAttempt(int keyIndex, int retry) { runSolveAttempt(keyIndex, retry, 0); }

    /**
     * @param retry      how many transient (503-class) retries on THIS key+model have been attempted.
     *                   Reset to 0 when rotating to a different key or model.
     * @param modelIndex which entry of {@link GeminiAI#SOLVE_MODELS} to use. We bump this when the
     *                   preferred (Gemini 3) model is persistently overloaded, falling back to 2.5.
     */
    private void runSolveAttempt(int keyIndex, int retry, int modelIndex) {
        int safeModel = Math.min(modelIndex, GeminiAI.SOLVE_MODELS.length - 1);
        geminiAI = new GeminiAI(solveKeys.get(keyIndex), GeminiAI.SOLVE_MODELS[safeModel]);
        com.google.common.util.concurrent.ListenableFuture<GenerateContentResponse> future =
                (solveImages != null && !solveImages.isEmpty())
                        ? geminiAI.getSolutionWithImages(solveImages, solvePrompt)
                        : geminiAI.getSolution(solvePrompt);
        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                final String text = (result != null) ? result.getText() : null;
                final boolean notGeometry = text != null && text.toUpperCase().contains("NOT_GEOMETRY");
                runOnUiThread(() -> {
                    boolean alive = !isFinishing() && !isDestroyed();
                    if (alive) {
                        progressBar.setVisibility(View.GONE);
                        setInputLocked(false); // solving done — editing allowed again
                    }

                    if (notGeometry) {
                        // The AI judged this isn't a geometry problem — tell the user, don't render a "solution".
                        isSaved = true;
                        if (alive) {
                            solutionControls.setVisibility(View.GONE);
                            new AlertDialog.Builder(GeometryInputActivity.this)
                                    .setTitle(nNotGeoTitle)
                                    .setMessage(nNotGeoMsg)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }
                        if (!isActivityVisible) postSolutionNotification(nNotGeoTitle, nNotGeoMsg, solveProblemText, null);
                        return;
                    }

                    if (alive && text != null) {
                        lastAIResponse = text;
                        processAIResult(text);
                    }
                    // If the user left the screen, the solve still finished in the background — tell them.
                    if (!isActivityVisible) {
                        if (text != null) postSolutionNotification(nReadyTitle, nReadyBody, solveProblemText, text);
                        else postSolutionNotification(nFailTitle, nFailBody, solveProblemText, null);
                    }
                });
            }
            @Override public void onFailure(Throwable t) {
                Log.e(TAG, "Gemini Error (key #" + (keyIndex + 1) + " of " + solveKeys.size()
                        + ", retry " + retry + ")", t);
                final boolean transient_ = isTransientError(t);
                final boolean exhausted = !transient_ && isKeyExhausted(t);

                // 503/overload/timeout is server-side — rotating keys hits the same overloaded model.
                // Retry the SAME key with exponential backoff (1s, 2s, 4s).
                if (transient_ && retry < MAX_TRANSIENT_RETRIES) {
                    long delay = 1000L * (1L << retry);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed())
                            Toast.makeText(GeometryInputActivity.this, R.string.servers_busy_retry, Toast.LENGTH_SHORT).show();
                    });
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> { if (!isFinishing() && !isDestroyed()) runSolveAttempt(keyIndex, retry + 1, modelIndex); },
                            delay);
                    return;
                }

                // Preferred model failed for a non-key reason (overloaded 503, OR the model id isn't
                // available/supported on this key) -> fall back to the next model (Gemini 3 -> 2.5) on
                // the same key. This is NOT gated on "transient" so an unavailable preview model id
                // can't hard-fail the whole solve.
                if (!exhausted && modelIndex + 1 < GeminiAI.SOLVE_MODELS.length) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed())
                            Toast.makeText(GeometryInputActivity.this, R.string.servers_busy_retry, Toast.LENGTH_SHORT).show();
                        runSolveAttempt(keyIndex, 0, modelIndex + 1); // fresh retry counter on the fallback model
                    });
                    return;
                }

                // Key out of quota -> automatically roll over to the next key in the chain.
                if (exhausted && keyIndex + 1 < solveKeys.size()) {
                    final String nextKind = solveKeyKinds.get(keyIndex + 1);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            int msgRes = KIND_USER.equals(nextKind)
                                    ? R.string.switching_to_user_key
                                    : R.string.switching_backup_key;
                            Toast.makeText(GeometryInputActivity.this, getString(msgRes), Toast.LENGTH_SHORT).show();
                        }
                        runSolveAttempt(keyIndex + 1, 0, 0); // fresh retry counter + preferred model on a different key
                    });
                    return;
                }

                // Out of options.
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressBar.setVisibility(View.GONE);
                        setInputLocked(false);
                        if (transient_) {
                            showServersBusyDialog();        // overloaded servers, NOT a key problem
                        } else if (exhausted) {
                            showQuotaExpiredDialog();
                        } else {
                            Toast.makeText(GeometryInputActivity.this, "AI Error: Check your API Key or Network Connection.", Toast.LENGTH_LONG).show();
                        }
                    }
                    if (!isActivityVisible) {
                        if (exhausted) postSolutionNotification(nFailTitle, nQuotaBody, solveProblemText, null);
                        else postSolutionNotification(nFailTitle, nFailBody, solveProblemText, null);
                    }
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** True when an error means THIS KEY can't be used (quota/rate/billing/permission/invalid).
     *  Server-side overload (503) is NOT here — switching keys to the same overloaded model
     *  doesn't help. See {@link #isTransientError}. */
    private boolean isKeyExhausted(Throwable t) {
        String m = (t == null || t.getMessage() == null) ? "" : t.getMessage().toLowerCase();
        return m.contains("quota") || m.contains("resource_exhausted") || m.contains("429")
                || m.contains("rate") || m.contains("exhaust") || m.contains("api key")
                || m.contains("api_key") || m.contains("permission") || m.contains("billing")
                || m.contains("invalid");
    }

    /** True when the error is a transient SERVER-side problem (overload, timeout, 5xx).
     *  Same key should be retried after a short delay; rotating keys is pointless. */
    private boolean isTransientError(Throwable t) {
        String m = (t == null || t.getMessage() == null) ? "" : t.getMessage().toLowerCase();
        return m.contains("503") || m.contains("502") || m.contains("504") || m.contains("500")
                || m.contains("unavailable") || m.contains("overload") || m.contains("overloaded")
                || m.contains("deadline") || m.contains("timeout") || m.contains("timed out")
                || m.contains("temporarily") || m.contains("try again");
    }

    private static final int MAX_TRANSIENT_RETRIES = 3;

    /**
     * Every key in the chain is out of quota / expired. Explain what happened based on which kinds
     * of keys we tried, and offer a shortcut to Settings so the user can add/update an API key.
     */
    /** Shown when the AI returned 503/overload repeatedly — this is a server problem, not a quota
     *  or key problem. Offers a Retry that re-runs the whole rotation. */
    private void showServersBusyDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.servers_busy_title)
                .setMessage(R.string.servers_busy_msg)
                .setPositiveButton(R.string.retry, (d, w) -> {
                    if (solveProblemText != null && !solveProblemText.isEmpty())
                        solveWithAI(solveProblemText);
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void showQuotaExpiredDialog() {
        // Pro / Admin = unlimited access. Skip the upgrade-flavored "quota expired" dialog and
        // let them simply retry — the AI servers must just be transiently busy.
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean privileged = userPrefs.getString("username", "").equals("Admin_Teacher")
                || userPrefs.getBoolean("is_pro_user", false);
        if (privileged) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pro_busy_title)
                    .setMessage(R.string.pro_busy_msg)
                    .setPositiveButton(R.string.retry, (d, w) -> {
                        if (solveProblemText != null && !solveProblemText.isEmpty())
                            solveWithAI(solveProblemText);
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
            return;
        }

        boolean hadSub = solveKeyKinds != null && solveKeyKinds.contains(KIND_SUB);
        boolean hadUser = solveKeyKinds != null && solveKeyKinds.contains(KIND_USER);

        int msgRes;
        if (hadSub && hadUser)      msgRes = R.string.quota_expired_both; // subscription AND your keys
        else if (hadSub)            msgRes = R.string.quota_expired_sub;  // subscription only
        else                        msgRes = R.string.quota_expired_user; // your keys only

        new AlertDialog.Builder(this)
                .setTitle(R.string.quota_expired_title)
                .setMessage(msgRes)
                .setPositiveButton(R.string.open_settings,
                        (d, w) -> startActivity(new Intent(GeometryInputActivity.this, SettingsActivity.class)))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private boolean isActivityVisible = false;

    @Override protected void onResume() {
        super.onResume();
        isActivityVisible = true;
        StyleManager.recreateIfChanged(this, styleGlass);
    }
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
        // Strip the model's private "work out the configuration" scratchpad, then keep the clean answer
        // as the stored/saved/chat copy so students, History and exports never see the raw reasoning.
        // Full model output (may start with a private "===SOLUTION===" scratchpad); the student-facing
        // part is everything after that marker (or all of it if the model started directly).
        String rawResponse = (text == null) ? "" : text;
        String solutionPart = extractSolution(rawResponse);

        // Meter a fresh built-in-Gemini solve against the wallet (never when just re-opening History).
        if (!isFromHistory && Entitlements.shouldMeter(this)) {
            TokenWallet.spend(this, TokenWallet.estimateTokens(solveProblemText, rawResponse));
        }

        canvas3D.clear();
        stepsContainer.removeAllViews();
        solutionCardTexts.clear();

        btnStopAI.setVisibility(View.GONE);

        if (!isFromHistory && !isSaved) {
            solutionControls.setVisibility(View.VISIBLE);
        } else {
            solutionControls.setVisibility(View.GONE);
        }

        // Draw the figure from EVERY command line in the whole output, so a command the model happened to
        // put in its private notes is never lost. Explanation CARDS, however, come ONLY from the
        // student-facing part — the private reasoning is never shown.
        StringBuilder commandsOnly = new StringBuilder();
        for (String line : rawResponse.split("\n")) {
            String cleanLine = line.trim();
            if (isCadCommand(cleanLine)) {
                parseCadCommand(cleanLine);
                commandsOnly.append(cleanLine).append("\n");
            }
        }
        StringBuilder explanationText = new StringBuilder();
        for (String line : solutionPart.split("\n")) {
            if (!isCadCommand(line.trim())) explanationText.append(line).append("\n");
        }

        // Clean copy for save / History / chat / export = commands + student explanation, no scratchpad.
        lastAIResponse = (commandsOnly.toString() + explanationText.toString()).trim();

        // Make points that should lie on a circle (tangent/intersection/inscribed) actually touch it,
        // then show flat 2D problems face-on so the circle and the rest sit in the SAME plane.
        canvas3D.snapPointsToCircles();
        canvas3D.autoOrientForContent();

        String fullText = explanationText.toString();

        // The AI may write step / final-answer headers in the student's language (ШАГ, Քայլ, ОТВЕТ,
        // ՊԱՏԱՍԽԱՆ…) or with markdown, which broke the English-only split below and dumped every step
        // into ONE card. Normalize all of those to the English markers first, so each step gets its card.
        fullText = normalizeSolutionMarkers(fullText);

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

        // Copy / Save-image / Ask-Lemma are available whenever a solution is on screen (incl. from History).
        boolean hasSolution = stepsContainer.getChildCount() > 0;
        if (resultActions != null) resultActions.setVisibility(hasSolution ? View.VISIBLE : View.GONE);
        if (btnAskLemma != null) btnAskLemma.setVisibility(hasSolution ? View.VISIBLE : View.GONE);

        inputArea.setVisibility(View.GONE);
        btnToggleInput.setColorFilter(Color.parseColor("#E67E22"));
    }

    /**
     * Rewrites step / final-answer headers written in any supported language (or with markdown/■bullets)
     * to the exact English markers the card splitter needs ("STEP n" and "FINAL ANSWER:"). Without this,
     * a Russian ("ШАГ 1", "ОТВЕТ:") or Armenian ("Քայլ 1", "Պատասխան:") solution never split and every
     * step showed up crammed into a single card. Flags: i = case-insensitive, m = ^ per line, u = Unicode
     * case folding (so ШАГ/Шаг/шаг and ՔԱՅԼ/Քայլ all match).
     */
    /**
     * Drops the AI's PRIVATE reasoning scratchpad — it is told to work out the configuration above a
     * line reading "===SOLUTION===" and put the student-facing answer below it. We keep only the part
     * after the last such marker, so the extra "imagine the problem" thinking improves accuracy without
     * ever being shown to the child. No marker (the model started directly) → return the text unchanged.
     */
    private static String extractSolution(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)={2,}\\s*SOLUTION\\s*={2,}").matcher(text);
        int end = -1;
        while (m.find()) end = m.end(); // take the LAST marker, in case the word appears in the notes
        return (end >= 0) ? text.substring(end).trim() : text;
    }

    private String normalizeSolutionMarkers(String text) {
        if (text == null) return "";
        // "STEP 1" / "Шаг 1" / "ՔԱՅԼ 1" / "**Step 2:**" ... -> "STEP <n>"
        text = text.replaceAll(
                "(?imu)^[\\s>*#.\\-]*(?:STEP|ШАГ|ЭТАП|ՔԱՅԼ|Քայլ)\\s*[:.#)\\-]?\\s*(\\d+)",
                "STEP $1");
        // Final-answer header in EN/RU/HY -> "FINAL ANSWER:"
        text = text.replaceAll(
                "(?imu)^[\\s>*#.\\-]*(?:FINAL\\s*ANSWER|ИТОГОВЫЙ\\s*ОТВЕТ|ОТВЕТ|ИТОГ|ՎԵՐՋՆԱԿԱՆ\\s*ՊԱՏԱՍԽԱՆ|ՊԱՏԱՍԽԱՆ)\\s*:",
                "FINAL ANSWER:");
        return text;
    }

    /** True if a line is one of the figure drawing commands (vs. student explanation text). */
    private static boolean isCadCommand(String cleanLine) {
        return cleanLine.startsWith("DRAW3D:") || cleanLine.startsWith("LINE3D:")
                || cleanLine.startsWith("PLANE3D:") || cleanLine.startsWith("CONE3D:")
                || cleanLine.startsWith("PYRAMID3D:") || cleanLine.startsWith("CYLINDER3D:")
                || cleanLine.startsWith("SPHERE3D:") || cleanLine.startsWith("CIRCLE3D:")
                || cleanLine.startsWith("PRISM3D:") || cleanLine.startsWith("ANGLE3D:")
                || cleanLine.startsWith("MIDPOINT:") || cleanLine.startsWith("FOOT:")
                || cleanLine.startsWith("INTERSECT:");
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
                    if (args.length >= 2) {
                        // Optional 3rd arg = color (e.g. "red", "#FF0000") so the AI can highlight
                        // construction lines like a height or auxiliary segment in another color.
                        Integer col = (args.length >= 3) ? GeometryCanvas3D.parseColorArg(args[2]) : null;
                        canvas3D.addLine(args[0].trim(), args[1].trim(), col);
                    }
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
                case "PRISM3D":
                    // PRISM3D:Label,height,x1,z1,x2,z2,... -> extrude a flat base polygon upward.
                    if (args.length >= 8) {
                        float height = f(args[1]);
                        int pairs = (args.length - 2) / 2;
                        float[] xs = new float[pairs];
                        float[] zs = new float[pairs];
                        for (int i = 0; i < pairs; i++) {
                            xs[i] = f(args[2 + i * 2]);
                            zs[i] = f(args[3 + i * 2]);
                        }
                        canvas3D.addPrism(args[0].trim(), xs, zs, 0f, height);
                    }
                    break;
                case "ANGLE3D":
                    // ANGLE3D:Vertex,A,B[,degrees] -> draw an arc + value at the angle A-Vertex-B.
                    if (args.length >= 3) {
                        Float deg = (args.length >= 4) ? f(args[3]) : null;
                        canvas3D.addAngle(args[0].trim(), args[1].trim(), args[2].trim(), deg);
                    }
                    break;
                case "MIDPOINT":
                    // MIDPOINT:M,A,B -> place M exactly at the midpoint of A–B (engine computes it).
                    if (args.length >= 3) canvas3D.addMidpoint(args[0].trim(), args[1].trim(), args[2].trim());
                    break;
                case "FOOT":
                    // FOOT:H,P,A,B -> H = foot of the perpendicular from P onto line A–B (altitude foot).
                    if (args.length >= 4) canvas3D.addFoot(args[0].trim(), args[1].trim(), args[2].trim(), args[3].trim());
                    break;
                case "INTERSECT":
                    // INTERSECT:X,A,B,C,D -> X = where line A–B crosses line C–D.
                    if (args.length >= 5) canvas3D.addIntersection(args[0].trim(), args[1].trim(), args[2].trim(), args[3].trim(), args[4].trim());
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
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        card.setStrokeWidth((int)(getResources().getDisplayMetrics().density));

        TextView tv = new TextView(this);
        tv.setPadding(44, 40, 44, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
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

        finishCard(card, tv);
    }

    private void addStepCard(String sectionText) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(24, 12, 24, 12);
        card.setLayoutParams(lp);
        card.setRadius(24f);
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        card.setStrokeWidth((int)(getResources().getDisplayMetrics().density));

        TextView tv = new TextView(this);
        tv.setPadding(44, 40, 44, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
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

        finishCard(card, tv);
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

        finishCard(card, tv);
    }

    /**
     * Wraps a card's text in a vertical box and adds a small "copy" button at the bottom-right that
     * copies just that card. Also records the plain text so "Copy all" can rebuild the full solution.
     */
    private void finishCard(MaterialCardView card, TextView tv) {
        final String plain = tv.getText().toString();
        solutionCardTexts.add(plain);

        // Make any theorem names in this card tappable → open the theorem's page.
        TheoremLinker.linkify(this, tv);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tv);

        // Action row: "Ask AI about this" + copy, bottom-right of the card.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        actions.setLayoutParams(alp);

        int sz = (int) (36 * getResources().getDisplayMetrics().density);

        // Ask AI: opens the tutor chat focused on THIS step, carrying the whole solution as context.
        ImageButton ask = new ImageButton(this);
        ask.setImageResource(android.R.drawable.sym_action_chat);
        ask.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        ask.setBackgroundResource(android.R.color.transparent);
        ask.setContentDescription(getString(R.string.ask_about_step));
        LinearLayout.LayoutParams asklp = new LinearLayout.LayoutParams(sz, sz);
        asklp.rightMargin = 8;
        asklp.bottomMargin = 6;
        ask.setLayoutParams(asklp);
        ask.setOnClickListener(v -> openStepChat(plain));
        actions.addView(ask);

        ImageButton copy = new ImageButton(this);
        copy.setImageResource(R.drawable.ic_copy);
        copy.setColorFilter(ContextCompat.getColor(this, R.color.text_subtitle));
        copy.setBackgroundResource(android.R.color.transparent);
        copy.setContentDescription(getString(R.string.copy));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(sz, sz);
        clp.rightMargin = 12;
        clp.bottomMargin = 6;
        copy.setLayoutParams(clp);
        copy.setOnClickListener(v -> copyToClipboard(plain));
        actions.addView(copy);

        // Report: Play's AI-Generated Content policy requires an in-app way to flag offensive AI output.
        ImageButton report = new ImageButton(this);
        report.setImageResource(android.R.drawable.ic_dialog_alert);
        report.setColorFilter(ContextCompat.getColor(this, R.color.text_subtitle));
        report.setBackgroundResource(android.R.color.transparent);
        report.setContentDescription(getString(R.string.report_ai));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(sz, sz);
        rlp.rightMargin = 12;
        rlp.bottomMargin = 6;
        report.setLayoutParams(rlp);
        report.setOnClickListener(v -> AiReports.showReportDialog(this, plain));
        actions.addView(report);

        box.addView(actions);

        card.addView(box);
        stepsContainer.addView(card);
        // Futuristic reveal: each card fades + rises in, staggered one after another.
        Ux.revealIn(card, (stepsContainer.getChildCount() - 1) * 60L);
    }

    /** Opens the Lemma chat focused on one solution step; the figure + full solution ride along as context. */
    private void openStepChat(String stepText) {
        Intent i = buildChatIntent();
        i.putExtra("FOCUS_STEP", stepText);
        startActivity(i);
    }

    /** Opens the Lemma chat about the WHOLE solution (no single step focused). */
    private void openSolutionChat() {
        startActivity(buildChatIntent());
    }

    /** Common chat context: the problem, the readable solution, AND the raw text so the chat can
     *  re-draw the figure and let the student ask about any detail in the solution or the drawing. */
    private Intent buildChatIntent() {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("CONTEXT_PROBLEM", etDescription.getText().toString());
        i.putExtra("CONTEXT_SOLUTION", SolutionExporter.cleanSolutionText(lastAIResponse));
        i.putExtra("CONTEXT_RAW", lastAIResponse); // carries the DRAW3D/LINE3D/… commands => the figure
        if (getIntent().hasExtra("SAVED_NAME")) i.putExtra("CONTEXT_TITLE", getIntent().getStringExtra("SAVED_NAME"));
        return i;
    }

    private void copyAllSolution() {
        if (solutionCardTexts.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : solutionCardTexts) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s);
        }
        copyToClipboard(sb.toString());
    }

    private void copyToClipboard(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Lemma Solution", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Snapshot of the live 3D figure (its own view only), or null if there's no figure. */
    private Bitmap renderFigureBitmap() {
        if (canvas3D == null || canvas3D.isEmpty()
                || canvas3D.getWidth() <= 0 || canvas3D.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(canvas3D.getWidth(), canvas3D.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(ContextCompat.getColor(this, R.color.canvas_bg));
        canvas3D.draw(c);
        return b;
    }

    /** Offers Image (figure only) / PDF (figure + solution text) export of the current solution. */
    private void exportSolution() {
        if (solutionCardTexts.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : solutionCardTexts) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s);
        }
        String title = getIntent().hasExtra("SAVED_NAME")
                ? getIntent().getStringExtra("SAVED_NAME")
                : getString(R.string.default_solution_name);
        SolutionExporter.showExportDialog(this, renderFigureBitmap(), sb.toString(), title);
    }

    private void resetAll () {
        canvas3D.clear();
        stepsContainer.removeAllViews();
        solutionControls.setVisibility(View.GONE);
        if (resultActions != null) resultActions.setVisibility(View.GONE);
        inputArea.setVisibility(View.VISIBLE);
        btnToggleInput.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        btnStopAI.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        setInputLocked(false); // editing allowed again
        selectedImages.clear();
        refreshImageStrip();
        isSaved = true;
        updateCanvasSize(0.60f);
    }

    // --- Voice input ---
    private void startVoiceInput() {
        try {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, voiceLocaleTag());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt));
            voiceLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show();
        }
    }

    private String voiceLocaleTag() {
        switch (currentLangCode) {
            case "ru": return "ru-RU";
            case "hy": return "hy-AM";
            default:   return "en-US";
        }
    }

    // --- Image attachments ---
    private void addImagesFromUris(List<android.net.Uri> uris) {
        for (android.net.Uri uri : uris) {
            android.graphics.Bitmap bmp = decodeScaledBitmap(uri, 1280);
            if (bmp != null) selectedImages.add(bmp);
        }
        refreshImageStrip();
    }

    /** Loads a photo from an absolute file path (scanned/captured drawing) and attaches it. */
    private boolean addImageFromFile(String path) {
        android.graphics.Bitmap bmp = decodeScaledBitmapFromFile(path, 1280);
        if (bmp == null) return false;
        selectedImages.add(bmp);
        refreshImageStrip();
        return true;
    }

    /** Opens the system gallery picker for one or more images. */
    private void openImageGallery() {
        Intent pick = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { imagePickerLauncher.launch(pick); }
        catch (Exception e) { imagePickerLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)); }
    }

    /** Checks/requests CAMERA permission, then opens the camera to photograph a drawing. */
    private void startCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        try {
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            java.io.File photo = java.io.File.createTempFile("SCAN_" + System.currentTimeMillis() + "_", ".jpg", dir);
            pendingCameraPath = photo.getAbsolutePath();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            Intent it = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraLauncher.launch(it);
        } catch (Exception e) {
            Log.e(TAG, "Camera launch failed", e);
            Toast.makeText(this, R.string.scan_error_loading_photo, Toast.LENGTH_SHORT).show();
        }
    }

    /** Decodes a (possibly large) camera photo from disk, downsampling first to avoid OOM. */
    private android.graphics.Bitmap decodeScaledBitmapFromFile(String path, int maxDim) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (Math.max(w, h) / sample > maxDim * 2) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            float ratio = Math.min((float) maxDim / bw, (float) maxDim / bh);
            if (ratio >= 1f) return bmp;
            return android.graphics.Bitmap.createScaledBitmap(bmp, Math.round(bw * ratio), Math.round(bh * ratio), true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load image from file", e);
            return null;
        }
    }

    private android.graphics.Bitmap decodeScaledBitmap(android.net.Uri uri, int maxDim) {
        try {
            android.graphics.Bitmap original = android.provider.MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            if (original == null) return null;
            int w = original.getWidth(), h = original.getHeight();
            float ratio = Math.min((float) maxDim / w, (float) maxDim / h);
            if (ratio >= 1f) return original; // already small enough
            return android.graphics.Bitmap.createScaledBitmap(original, Math.round(w * ratio), Math.round(h * ratio), true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load image", e);
            Toast.makeText(this, "Couldn't load that image.", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private void refreshImageStrip() {
        if (imageStrip == null || imageScroll == null) return;
        imageStrip.removeAllViews();
        imageScroll.setVisibility(selectedImages.isEmpty() ? View.GONE : View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        int sizePx = Math.round(d * 88), delPx = Math.round(d * 24), gapPx = Math.round(d * 10), padPx = Math.round(d * 4);
        for (int i = 0; i < selectedImages.size(); i++) {
            final int index = i;
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(sizePx, sizePx);
            flp.setMargins(0, padPx, gapPx, padPx);
            frame.setLayoutParams(flp);

            // Rounded card so the thumbnail has clean corners.
            MaterialCardView card = new MaterialCardView(this);
            card.setLayoutParams(new android.widget.FrameLayout.LayoutParams(sizePx, sizePx));
            card.setRadius(d * 12);
            card.setCardElevation(d * 2);
            card.setStrokeWidth(0);
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_white));

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(selectedImages.get(index));
            card.addView(iv);
            frame.addView(card);

            // Circular delete badge in the top corner.
            ImageButton del = new ImageButton(this);
            android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(
                    delPx, delPx, android.view.Gravity.TOP | android.view.Gravity.END);
            dlp.setMargins(0, padPx, padPx, 0);
            del.setLayoutParams(dlp);
            del.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            del.setBackgroundResource(R.drawable.circle_delete_bg);
            del.setColorFilter(Color.WHITE);
            del.setPadding(padPx, padPx, padPx, padPx);
            del.setScaleType(ImageView.ScaleType.FIT_CENTER);
            del.setContentDescription(getString(R.string.remove_image));
            del.setOnClickListener(v -> {
                if (index < selectedImages.size()) {
                    selectedImages.remove(index);
                    refreshImageStrip();
                }
            });
            frame.addView(del);
            imageStrip.addView(frame);
        }
    }

    // Lock the problem inputs while the AI is solving; unlock before/after.
    private void setInputLocked(boolean locked) {
        if (etDescription != null) etDescription.setEnabled(!locked);
        if (etExtra != null) etExtra.setEnabled(!locked);
        if (btnAddImage != null) btnAddImage.setEnabled(!locked);
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
        SharedPreferences userPrefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean privileged = userPrefs.getString("username", "").equals("Admin_Teacher")
                || userPrefs.getBoolean("is_pro_user", false);

        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.access_denied);
        if (privileged) {
            // Already Pro, but there's no usable key (the app's built-in AI key isn't configured in
            // this build). Don't tell them to "upgrade" — point them at adding a personal key.
            b.setMessage(R.string.access_no_key_pro)
                    .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(new Intent(this, SettingsActivity.class)));
        } else {
            b.setMessage(R.string.access_denied_msg)
                    .setPositiveButton(R.string.upgrade_pro, (d, w) -> startActivity(new Intent(this, SettingsActivity.class)));
        }
        b.setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * They've used up their problems for now. Show them the PLANS page rather than a settings screen:
     * this is the exact moment they can see what more would buy them, so make the offer legible.
     */
    private void showOutOfTokensDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.tokens_out_title)
                .setMessage(R.string.tokens_out_msg)
                .setPositiveButton(R.string.tokens_buy, (d, w) -> startActivity(new Intent(this, PlansActivity.class)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSaveDialog(boolean exitAfter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Solution");
        final EditText input = new EditText(this);

        String currentUser = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");
        String base = getString(R.string.default_solution_name);
        String defaultName = getIntent().hasExtra("SAVED_NAME")
                ? getIntent().getStringExtra("SAVED_NAME")
                : dbHelper.nextDefaultName("history", currentUser, base);
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) title = dbHelper.nextDefaultName("history", currentUser, base);

            String user = currentUser;
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
                com.google.firebase.database.DatabaseReference userRef = FirebaseManager.getUserRef();
                if (userRef != null && !user.startsWith("GuestUser")) {
                    HashMap<String, Object> map = new HashMap<>();
                    map.put("title", title);
                    map.put("problem", prob);
                    map.put("raw_response", lastAIResponse);
                    map.put("date", date);

                    userRef.child("history").child(cloudKey).setValue(map)
                            .addOnSuccessListener(x -> dbHelper.markHistorySynced(user, date))
                            .addOnFailureListener(e -> Toast.makeText(GeometryInputActivity.this,
                                    getString(R.string.history_backup_broken),
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

        // A drawing scanned from the home screen: attach the photo so the AI reads the figure itself.
        if (intent.hasExtra("SCANNED_IMAGE_PATH")) {
            String scanPath = intent.getStringExtra("SCANNED_IMAGE_PATH");
            if (scanPath != null && !scanPath.isEmpty() && addImageFromFile(scanPath)) {
                Toast.makeText(this, R.string.scan_drawing_attached, Toast.LENGTH_SHORT).show();
            }
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