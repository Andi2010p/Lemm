package com.example.lemm;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.android.material.card.MaterialCardView;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Lemma" — a friendly AI tutor chat. Reachable from the home screen and from any solution step.
 * Features: saved chat history (drawer) + New chat, attach photos (camera/gallery) and saved
 * drawings/solutions, voice input, and automatic language mirroring (starts in the app language,
 * then replies in whatever language the student writes in). Kept student-simple and never talks
 * about coordinates, matching the solver's teaching style.
 */
public class ChatActivity extends AppCompatActivity {

    private DrawerLayout drawer;
    private LinearLayout messagesContainer, attachRow, historyList, suggestRow;
    private ScrollView scroll;
    private HorizontalScrollView attachScroll, suggestScroll;
    private EditText etInput;
    private ImageButton btnSend;

    private String appLangName; // fallback language when the student's language is unclear

    /** One chat turn kept in memory. */
    private static class Msg {
        final boolean user;
        final String text;
        Msg(boolean user, String text) { this.user = user; this.text = text; }
    }
    private final List<Msg> history = new ArrayList<>();

    /** A saved drawing/solution or a photo the student pinned as context. */
    private static class Attachment {
        String title;
        String solutionText; // for a solution: its cleaned text
        Bitmap image;        // for a drawing/photo: a picture the AI can actually see
    }
    private final List<Attachment> attachments = new ArrayList<>();

    // Optional context passed in from a solution (the per-step / whole-solution "Ask Lemma" buttons).
    private String ctxTitle, ctxProblem, ctxSolution, focusStep;
    private String ctxRaw;            // raw solution text (with DRAW3D/LINE3D/… commands) => the figure
    private String figureDescription; // coordinate-free description of the figure, fed to the prompt
    private String tappedElement;     // the figure part the student last tapped (one-shot focus)

    // Interactive figure panel.
    private View figurePanel;
    private GeometryCanvas3D chatFigure;
    private TextView figureSelection;
    private ImageButton btnToggleFigure;

    private boolean sending = false;
    private String sessionId;

    // Camera / gallery / voice
    private ActivityResultLauncher<Intent> cameraLauncher, imagePickerLauncher, voiceLauncher;
    private ActivityResultLauncher<String> cameraPermLauncher;
    private String pendingCameraPath;

    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_chat);
        styleGlass = StyleManager.isGlass(this);

        setupLanguage();

        drawer = findViewById(R.id.drawerLayout);
        messagesContainer = findViewById(R.id.chatMessages);
        scroll = findViewById(R.id.chatScroll);
        attachScroll = findViewById(R.id.attachScroll);
        attachRow = findViewById(R.id.attachRow);
        suggestScroll = findViewById(R.id.suggestScroll);
        suggestRow = findViewById(R.id.suggestRow);
        historyList = findViewById(R.id.historyList);
        etInput = findViewById(R.id.etChatInput);
        btnSend = findViewById(R.id.btnChatSend);
        figurePanel = findViewById(R.id.figurePanel);
        chatFigure = findViewById(R.id.chatFigure);
        figureSelection = findViewById(R.id.figureSelection);
        btnToggleFigure = findViewById(R.id.btnToggleFigure);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> { Ux.tick(v); onSend(); });
        findViewById(R.id.btnAttach).setOnClickListener(v -> { Ux.tick(v); showAttachChooser(); });
        findViewById(R.id.btnMic).setOnClickListener(v -> { Ux.tick(v); startVoiceInput(); });
        findViewById(R.id.btnChatHistory).setOnClickListener(v -> {
            Ux.tick(v);
            refreshHistoryList();
            drawer.openDrawer(GravityCompat.START);
        });
        findViewById(R.id.btnNewChat).setOnClickListener(v -> { Ux.tick(v); startNewChat(); });
        findViewById(R.id.btnNewChatDrawer).setOnClickListener(v -> {
            Ux.tick(v);
            drawer.closeDrawer(GravityCompat.START);
            startNewChat();
        });

        registerLaunchers();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else finish();
            }
        });

        readContextFromIntent();
        sessionId = UUID.randomUUID().toString();
        applySolutionContext();
        showGreeting();
        showSuggestions();

        // Pull this account's chats from the cloud so history is here on every device the student
        // logs into. Owner-only in the rules, so it's private. Then refresh the drawer if it's open.
        ChatStore.syncFromCloud(this, () -> {
            if (!isFinishing() && !isDestroyed() && drawer.isDrawerOpen(GravityCompat.START)) {
                refreshHistoryList();
            }
        });
    }

    // ---------------- Quick-suggestion chips ----------------

    private void showSuggestions() {
        if (suggestRow == null) return;
        suggestRow.removeAllViews();
        boolean sol = notEmpty(ctxRaw) || notEmpty(ctxSolution);
        int[] ids = sol
                ? new int[]{R.string.chat_sugg_why, R.string.chat_sugg_simpler, R.string.chat_sugg_check}
                : new int[]{R.string.chat_sugg_hint, R.string.chat_sugg_simpler, R.string.chat_sugg_example};
        for (int id : ids) addSuggestionChip(getString(id));
        suggestScroll.setVisibility(View.VISIBLE);
    }

    private void addSuggestionChip(String text) {
        MaterialCardView chip = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setRadius(dp(16));
        chip.setCardElevation(0f);
        chip.setStrokeWidth(dp(1));
        chip.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        chip.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan));
        tv.setTextSize(13f);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.addView(tv);
        chip.setOnClickListener(v -> { Ux.tick(v); etInput.setText(text); onSend(); });
        suggestRow.addView(chip);
    }

    private void hideSuggestions() {
        if (suggestScroll != null) suggestScroll.setVisibility(View.GONE);
    }

    private void setupLanguage() {
        switch (Locale.getDefault().getLanguage()) {
            case "ru": appLangName = "Russian (Русский)"; break;
            case "hy": appLangName = "Armenian (Հայերեն)"; break;
            default:   appLangName = "English"; break;
        }
    }

    private void readContextFromIntent() {
        Intent i = getIntent();
        if (i == null) return;
        ctxTitle = i.getStringExtra("CONTEXT_TITLE");
        ctxProblem = i.getStringExtra("CONTEXT_PROBLEM");
        ctxSolution = i.getStringExtra("CONTEXT_SOLUTION");
        ctxRaw = i.getStringExtra("CONTEXT_RAW");
        focusStep = i.getStringExtra("FOCUS_STEP");
    }

    private void registerLaunchers() {
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && pendingCameraPath != null) {
                Bitmap bmp = decodeScaledBitmapFromFile(pendingCameraPath, 1024);
                if (bmp != null) addPhotoAttachment(bmp);
            }
        });
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                List<Uri> uris = new ArrayList<>();
                ClipData clip = result.getData().getClipData();
                if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
                else if (result.getData().getData() != null) uris.add(result.getData().getData());
                for (Uri u : uris) {
                    Bitmap bmp = decodeScaledBitmapFromUri(u, 1024);
                    if (bmp != null) addPhotoAttachment(bmp);
                }
            }
        });
        voiceLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                ArrayList<String> matches = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (matches != null && !matches.isEmpty()) {
                    String spoken = matches.get(0);
                    String existing = etInput.getText().toString();
                    etInput.setText(existing.trim().isEmpty() ? spoken : existing + " " + spoken);
                    etInput.setSelection(etInput.getText().length());
                }
            }
        });
        cameraPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) launchCamera();
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show();
        });
    }

    private void showGreeting() {
        boolean hasSolution = notEmpty(ctxRaw) || notEmpty(ctxSolution);
        String greeting = hasSolution
                ? getString(R.string.chat_greeting_solution)
                : getString(R.string.chat_greeting);
        addBubble(false, greeting);
    }

    /**
     * When the chat is opened from a solution, wire up everything so the student can ask about ANY
     * detail of the solution or the drawing: pin the solution text + the rendered figure as visible
     * attachments (so Lemma can see the figure), build a coordinate-free description of the figure for
     * the prompt, and show the LIVE, tappable figure — tap any point/segment/angle to ask about it.
     */
    private void applySolutionContext() {
        if (chatFigure != null) { chatFigure.clear(); }
        if (figurePanel != null) figurePanel.setVisibility(View.GONE);
        if (figureSelection != null) figureSelection.setVisibility(View.GONE);
        tappedElement = null;
        figureDescription = null;

        if (!notEmpty(ctxRaw) && !notEmpty(ctxSolution)) return;

        // 1) The readable solution, as a visible chip Lemma reads.
        if (notEmpty(ctxSolution)) {
            Attachment sol = new Attachment();
            sol.title = notEmpty(ctxTitle) ? ctxTitle : getString(R.string.chat_solution_label);
            sol.solutionText = ctxSolution;
            attachments.add(sol);
            addAttachmentChip(sol);
        }

        // 2) The figure: a coordinate-free description for the prompt, a rendered image Lemma can SEE,
        //    and the live tappable figure at the top of the chat.
        if (notEmpty(ctxRaw)) {
            figureDescription = describeFigure(ctxRaw);
            Bitmap fig = HistoryActivity.renderSolutionThumb(this, ctxRaw, 900);
            if (fig != null) {
                Attachment img = new Attachment();
                img.title = getString(R.string.chat_figure);
                img.image = fig;
                attachments.add(img);
                addAttachmentChip(img);
                showFigurePanel();
            }
        }
    }

    private void showFigurePanel() {
        if (figurePanel == null || chatFigure == null) return;
        figurePanel.setVisibility(View.VISIBLE);
        chatFigure.loadFromSolution(ctxRaw);
        chatFigure.setOnElementSelectedListener(info -> {
            if (info == null || info.trim().isEmpty()) {
                figureSelection.setVisibility(View.GONE);
                tappedElement = null;
                return;
            }
            String first = info.split("\n")[0].trim();
            tappedElement = first;
            figureSelection.setText(getString(R.string.chat_selected, first));
            figureSelection.setVisibility(View.VISIBLE);
            // Pre-fill a question so the student can just tap send (or edit it).
            etInput.setText(getString(R.string.chat_tap_prefill, first));
            etInput.setSelection(etInput.getText().length());
        });
        btnToggleFigure.setOnClickListener(v -> {
            boolean showing = chatFigure.getVisibility() == View.VISIBLE;
            chatFigure.setVisibility(showing ? View.GONE : View.VISIBLE);
            if (showing) figureSelection.setVisibility(View.GONE);
            btnToggleFigure.setImageResource(showing
                    ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float);
        });
    }

    /** Parses the raw drawing commands into a short, COORDINATE-FREE description of the figure. */
    private String describeFigure(String raw) {
        java.util.LinkedHashSet<String> points = new java.util.LinkedHashSet<>();
        List<String> segs = new ArrayList<>(), angs = new ArrayList<>(), circs = new ArrayList<>(), faces = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            String[] a = cmdArgs(t);
            if (t.startsWith("DRAW3D:") && a.length >= 1) points.add(a[0].trim());
            else if (t.startsWith("LINE3D:") && a.length >= 2) segs.add(a[0].trim() + a[1].trim());
            else if (t.startsWith("CIRCLE3D:") && a.length >= 1) circs.add(a[0].trim());
            else if (t.startsWith("ANGLE3D:") && a.length >= 3) {
                String s = "∠" + a[1].trim() + a[0].trim() + a[2].trim();
                if (a.length >= 4) { String d = a[3].replaceAll("[^0-9.]", ""); if (!d.isEmpty()) s += " = " + d + "°"; }
                angs.add(s);
            } else if (t.startsWith("PLANE3D:") && a.length >= 2) {
                StringBuilder f = new StringBuilder();
                for (int i = 1; i < a.length; i++) f.append(a[i].trim());
                faces.add(f.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!points.isEmpty()) sb.append("Points: ").append(join(points)).append(". ");
        if (!segs.isEmpty()) sb.append("Segments: ").append(join(segs)).append(". ");
        if (!circs.isEmpty()) sb.append("Circles: ").append(join(circs)).append(". ");
        if (!angs.isEmpty()) sb.append("Marked angles: ").append(join(angs)).append(". ");
        if (!faces.isEmpty()) sb.append("Faces: ").append(join(faces)).append(". ");
        return sb.toString().trim();
    }

    private static String[] cmdArgs(String line) {
        String[] p = line.split(":");
        return (p.length < 2) ? new String[0] : p[1].trim().split(",");
    }

    private static String join(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    // ---------------- New chat / history ----------------

    private void startNewChat() {
        persistCurrentSession();
        history.clear();
        attachments.clear();
        attachRow.removeAllViews();
        attachScroll.setVisibility(View.GONE);
        messagesContainer.removeAllViews();
        // A brand-new chat drops the solution context; it's a fresh, open conversation.
        ctxTitle = ctxProblem = ctxSolution = focusStep = ctxRaw = null;
        sessionId = UUID.randomUUID().toString();
        applySolutionContext(); // resets/hides the figure panel
        showGreeting();
        showSuggestions();
    }

    /** Saves the current conversation (only if it has at least one real exchange). */
    private void persistCurrentSession() {
        boolean hasUser = false;
        for (Msg m : history) if (m.user) { hasUser = true; break; }
        if (!hasUser) return;

        ChatStore.Session s = new ChatStore.Session();
        s.id = sessionId;
        s.ts = System.currentTimeMillis();
        s.ctxProblem = ctxProblem; s.ctxSolution = ctxSolution; s.ctxFocus = focusStep; s.ctxTitle = ctxTitle;
        s.ctxRaw = ctxRaw;
        for (Msg m : history) s.messages.add(new ChatStore.Message(m.user, m.text));
        // Title = first user message, trimmed.
        String title = null;
        for (Msg m : history) if (m.user) { title = m.text; break; }
        if (title == null) title = getString(R.string.chat_new);
        title = title.trim().replaceAll("\\s+", " ");
        if (title.length() > 42) title = title.substring(0, 42) + "…";
        s.title = title;
        ChatStore.saveSession(this, s);
    }

    private void refreshHistoryList() {
        historyList.removeAllViews();
        List<ChatStore.Session> sessions = ChatStore.listSessions(this);
        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.chat_no_history);
            empty.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
            empty.setPadding(dp(20), dp(12), dp(20), dp(12));
            historyList.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        for (ChatStore.Session s : sessions) {
            historyList.addView(buildHistoryRow(s, fmt));
        }
    }

    private View buildHistoryRow(ChatStore.Session s, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(12), dp(12));
        row.setClickable(true);
        row.setBackgroundResource(outValue());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        texts.setLayoutParams(tlp);

        TextView title = new TextView(this);
        title.setText(s.title);
        title.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        title.setTextSize(15f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);

        TextView date = new TextView(this);
        date.setText(fmt.format(new Date(s.ts)));
        date.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        date.setTextSize(12f);
        texts.addView(date);

        row.addView(texts);

        ImageButton del = new ImageButton(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(36), dp(36));
        del.setLayoutParams(dlp);
        del.setBackgroundResource(android.R.color.transparent);
        del.setImageResource(android.R.drawable.ic_menu_delete);
        del.setColorFilter(ContextCompat.getColor(this, R.color.neon_text_dim));
        del.setContentDescription(getString(R.string.delete));
        del.setOnClickListener(v -> {
            ChatStore.deleteSession(this, s.id);
            refreshHistoryList();
        });
        row.addView(del);

        row.setOnClickListener(v -> {
            drawer.closeDrawer(GravityCompat.START);
            loadSession(s);
        });
        return row;
    }

    private int outValue() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private void loadSession(ChatStore.Session s) {
        // Keep whatever the user is currently doing safe first.
        persistCurrentSession();

        history.clear();
        attachments.clear();
        attachRow.removeAllViews();
        attachScroll.setVisibility(View.GONE);
        messagesContainer.removeAllViews();

        sessionId = s.id;
        ctxProblem = s.ctxProblem; ctxSolution = s.ctxSolution; focusStep = s.ctxFocus; ctxTitle = s.ctxTitle;
        ctxRaw = s.ctxRaw;
        hideSuggestions();
        applySolutionContext(); // re-pin the solution + re-draw the tappable figure
        for (ChatStore.Message m : s.messages) {
            history.add(new Msg(m.user, m.text));
            addBubble(m.user, m.text);
        }
    }

    // ---------------- Sending ----------------

    private void onSend() {
        if (sending) return;
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.chat_empty_message, Toast.LENGTH_SHORT).show();
            return;
        }
        // Cloud AI is the paid, server-metered pipe: no client key, the SERVER holds the Gemini key
        // and charges the user's plan credits. This is where a paying user's AI comes from once the
        // backend is deployed. When it's off, we fall back to the built-in key / bring-your-own-key.
        final boolean cloud = AiPrefs.cloudEnabled(this);
        final AiConfig.Provider prov = AiConfig.provider(this);
        String apiKey = cloud ? "" : ((prov == AiConfig.Provider.GEMINI) ? resolveApiKey() : AiConfig.key(this, prov));
        if (!cloud && apiKey.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage(prov == AiConfig.Provider.GEMINI
                            ? getString(R.string.chat_no_ai)
                            : getString(R.string.ext_no_key, AiConfig.label(prov)))
                    .setPositiveButton(R.string.open_settings,
                            (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        // Client-side metering only applies to the built-in key path. On cloud, the server meters.
        if (!cloud && prov == AiConfig.Provider.GEMINI && Entitlements.shouldMeter(this)
                && !TokenWallet.canSpend(this, TokenWallet.estimateTokens(text))) {
            showOutOfCreditsDialog();
            return;
        }

        hideSuggestions();
        etInput.setText("");
        history.add(new Msg(true, text));
        addBubble(true, text);
        persistCurrentSession();

        sending = true;
        btnSend.setEnabled(false);
        final View thinking = addThinkingBubble();

        String prompt = buildPrompt();
        tappedElement = null; // the tapped-figure focus is one-shot: it applied to this message
        List<Bitmap> images = collectAttachedImages();

        if (cloud) {
            sendViaBackend(prompt, images, thinking);
            return;
        }

        if (prov == AiConfig.Provider.GEMINI) {
            GeminiAI ai = new GeminiAI(apiKey, GeminiAI.SOLVE_MODELS[GeminiAI.SOLVE_MODELS.length - 1]); // fast model for chat
            com.google.common.util.concurrent.ListenableFuture<GenerateContentResponse> future =
                    images.isEmpty() ? ai.getSolution(prompt) : ai.getSolutionWithImages(images, prompt);
            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
                @Override public void onSuccess(GenerateContentResponse result) {
                    String reply = (result != null) ? result.getText() : null;
                    // Gemini resolves the future SUCCESSFULLY with no text when a response is empty or
                    // safety-blocked. deliverReply() turns that into an error message — so charging
                    // here unconditionally billed the user for an error. Only bill for real output.
                    boolean gotAnswer = reply != null && !reply.trim().isEmpty();
                    if (gotAnswer && Entitlements.shouldMeter(ChatActivity.this)) {
                        TokenWallet.spend(ChatActivity.this, TokenWallet.estimateTokens(prompt, reply));
                    }
                    runOnUiThread(() -> deliverReply(thinking, reply));
                }
                @Override public void onFailure(Throwable t) {
                    runOnUiThread(() -> deliverError(thinking, getString(R.string.chat_error)));
                }
            }, ContextCompat.getMainExecutor(this));
        } else {
            // OpenAI / Claude (bring-your-own-key) — callbacks already arrive on the main thread.
            ExternalAiClient.generate(this, prompt, images, new ExternalAiClient.Callback() {
                @Override public void onText(String t) { deliverReply(thinking, t); }
                @Override public void onError(String message) { deliverError(thinking, message); }
            });
        }
    }

    /** Sends the message through the Lemma Cloud backend (paid key + server-side credit metering). */
    private void sendViaBackend(String prompt, List<Bitmap> images, View thinking) {
        LemmaBackend.AiRequest req = new LemmaBackend.AiRequest("chat", prompt);
        for (Bitmap b : images) {
            String b64 = toJpegBase64(b);
            if (b64 != null) req.imagesB64.add(b64);
        }
        LemmaBackend.askAI(req, new LemmaBackend.Callback<LemmaBackend.AiReply>() {
            @Override public void onSuccess(LemmaBackend.AiReply v) { deliverReply(thinking, v.text); }
            @Override public void onError(String code, String message) {
                if (LemmaBackend.isOutOfCredits(code)) {
                    if (isFinishing() || isDestroyed()) return;
                    removeThinkingBubble(thinking);
                    finishSending();
                    showOutOfCreditsDialog();
                } else {
                    deliverError(thinking, (message == null || message.isEmpty())
                            ? getString(R.string.chat_error) : message);
                }
            }
        });
    }

    private void showOutOfCreditsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.tokens_out_title)
                .setMessage(R.string.tokens_out_msg)
                // Send them to the PLANS page, not Settings: this is the moment they can see what
                // their money buys, so show them the offer rather than a settings screen.
                .setPositiveButton(R.string.tokens_buy,
                        (d, w) -> startActivity(new Intent(this, PlansActivity.class)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String toJpegBase64(Bitmap b) {
        if (b == null) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Renders the AI's reply bubble (used by every provider). */
    private void deliverReply(View thinking, String reply) {
        if (isFinishing() || isDestroyed()) return;
        removeThinkingBubble(thinking);
        if (reply == null || reply.trim().isEmpty()) reply = getString(R.string.chat_error);
        reply = formatReply(reply.trim());
        history.add(new Msg(false, reply));
        addBubble(false, reply);
        persistCurrentSession();
        finishSending();
    }

    private void deliverError(View thinking, String message) {
        if (isFinishing() || isDestroyed()) return;
        removeThinkingBubble(thinking);
        addBubble(false, (message == null || message.trim().isEmpty()) ? getString(R.string.chat_error) : message);
        finishSending();
    }

    private void finishSending() {
        sending = false;
        btnSend.setEnabled(true);
    }

    private String formatReply(String s) {
        return s.replace("$", "")
                .replace("\\(", "").replace("\\)", "")
                .replace("\\[", "").replace("\\]", "");
    }

    /** Builds the single-shot prompt: teaching style + attached context + running transcript. */
    private String buildPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTEM: You are Lemma, a warm, patient geometry tutor for a school student. ")
                .append("IMPORTANT: reply in the SAME language the student writes their latest message in ")
                .append("(if a student switches language, switch with them). If their language is unclear or mixed, use ")
                .append(appLangName).append(". ")
                .append("Explain things very simply, in small steps, like talking to a curious student. ")
                .append("Use plain Unicode math symbols (√, ×, ÷, ², ³, π, °), NEVER LaTeX or dollar signs. ")
                .append("Do NOT talk about coordinates, a coordinate system or x/y/z — always refer to points, ")
                .append("sides and angles by their letters. Answer the student's question directly and kindly, ")
                .append("and keep it reasonably short.\n\n");

        boolean hasContext = notEmpty(ctxProblem) || notEmpty(figureDescription) || notEmpty(focusStep) || notEmpty(tappedElement);
        if (hasContext) {
            sb.append("The student is looking at this work:\n");
            if (notEmpty(ctxTitle)) sb.append("Title: ").append(ctxTitle.trim()).append("\n");
            if (notEmpty(ctxProblem)) sb.append("Problem: ").append(ctxProblem.trim()).append("\n");
            if (notEmpty(figureDescription)) sb.append("The figure shows — ").append(figureDescription.trim()).append("\n");
            if (notEmpty(focusStep)) sb.append("They are asking mainly about THIS step:\n").append(focusStep.trim()).append("\n");
            if (notEmpty(tappedElement)) sb.append("They just tapped this part of the figure and want to know about it: ").append(tappedElement.trim()).append("\n");
            sb.append("\n");
        }

        for (Attachment a : attachments) {
            if (notEmpty(a.solutionText)) {
                sb.append("The student attached their saved solution \"").append(a.title).append("\":\n")
                        .append(a.solutionText.trim()).append("\n\n");
            } else if (a.image != null) {
                sb.append("The student attached an image \"").append(a.title)
                        .append("\" — look at it carefully.\n\n");
            }
        }

        sb.append("Conversation:\n");
        int start = Math.max(0, history.size() - 16);
        for (int i = start; i < history.size(); i++) {
            Msg m = history.get(i);
            sb.append(m.user ? "Student: " : "Tutor: ").append(m.text).append("\n");
        }
        sb.append("Tutor: ");
        return sb.toString();
    }

    private List<Bitmap> collectAttachedImages() {
        List<Bitmap> out = new ArrayList<>();
        for (Attachment a : attachments) if (a.image != null) out.add(a.image);
        return out;
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    // ---------------- Message bubbles ----------------

    private void addBubble(boolean user, String text) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.leftMargin = user ? dp(52) : 0;
        lp.rightMargin = user ? 0 : dp(52);
        card.setLayoutParams(lp);
        card.setRadius(dp(20));
        card.setCardElevation(0f);
        if (user) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.neon_user_bubble));
        } else {
            card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        }

        TextView tv = new TextView(this);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextSize(16f);
        tv.setTextIsSelectable(true);
        tv.setTextColor(user ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
        tv.setText(text);
        // Long-press: user messages copy; AI messages offer Copy or Report (Play's AI-content policy
        // requires an in-app way to flag offensive AI output).
        final String plain = text;
        tv.setOnLongClickListener(v -> {
            if (user) {
                copyToClipboard(plain);
            } else {
                new AlertDialog.Builder(this)
                        .setItems(new CharSequence[]{getString(R.string.copy), getString(R.string.report_ai)},
                                (d, which) -> {
                                    if (which == 0) copyToClipboard(plain);
                                    else AiReports.showReportDialog(this, plain);
                                })
                        .show();
            }
            return true;
        });
        card.addView(tv);

        messagesContainer.addView(card);
        Ux.revealIn(card, 0);
        scrollToBottom();
    }

    /** The AI "typing…" bubble: three glowing neon dots pulsing in sequence. */
    private View addThinkingBubble() {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        lp.gravity = Gravity.START;
        lp.rightMargin = dp(52);
        card.setLayoutParams(lp);
        card.setRadius(dp(20));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(dp(18), dp(16), dp(18), dp(16));
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dlp.rightMargin = (i < 2) ? dp(7) : 0;
            dot.setLayoutParams(dlp);
            dot.setBackgroundResource(R.drawable.bg_neon_dot);
            AlphaAnimation anim = new AlphaAnimation(0.25f, 1f);
            anim.setDuration(520);
            anim.setStartOffset(i * 160L);
            anim.setRepeatCount(Animation.INFINITE);
            anim.setRepeatMode(Animation.REVERSE);
            dot.startAnimation(anim);
            dots.addView(dot);
        }
        card.addView(dots);

        messagesContainer.addView(card);
        Ux.revealIn(card, 0);
        scrollToBottom();
        return card;
    }

    private void removeThinkingBubble(View card) {
        if (card != null) messagesContainer.removeView(card);
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Lemma", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- Attachments ----------------

    private void showAttachChooser() {
        CharSequence[] items = {
                getString(R.string.img_take_photo),
                getString(R.string.img_choose_gallery),
                getString(R.string.chat_attach_solution),
                getString(R.string.chat_attach_drawing)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_attach_title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startCameraCapture(); break;
                        case 1: openImageGallery(); break;
                        case 2: pickFromHistory(true); break;
                        case 3: pickFromHistory(false); break;
                    }
                })
                .show();
    }

    private void pickFromHistory(boolean solutions) {
        String user = getSharedPreferences("UserPrefs", MODE_PRIVATE).getString("username", "GuestUser");
        List<HistoryRecord> records = ServiceLocator.historyRepository(this).load(user, solutions);
        if (records.isEmpty()) {
            Toast.makeText(this, R.string.chat_no_items, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = new CharSequence[records.size()];
        for (int i = 0; i < records.size(); i++) names[i] = records.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(solutions ? R.string.chat_attach_solution : R.string.chat_attach_drawing)
                .setItems(names, (d, which) -> attachRecord(records.get(which), solutions))
                .show();
    }

    private void attachRecord(HistoryRecord r, boolean solution) {
        Attachment a = new Attachment();
        a.title = r.title;
        if (solution) {
            a.solutionText = SolutionExporter.cleanSolutionText(r.data);
        } else {
            a.image = renderDrawing(r.data);
            if (a.image == null) { Toast.makeText(this, R.string.chat_error, Toast.LENGTH_SHORT).show(); return; }
        }
        attachments.add(a);
        addAttachmentChip(a);
        Toast.makeText(this, getString(R.string.chat_attached, r.title), Toast.LENGTH_SHORT).show();
    }

    private void addPhotoAttachment(Bitmap bmp) {
        Attachment a = new Attachment();
        a.title = getString(R.string.chat_photo);
        a.image = bmp;
        attachments.add(a);
        addAttachmentChip(a);
        Toast.makeText(this, getString(R.string.chat_attached, a.title), Toast.LENGTH_SHORT).show();
    }

    /** Renders a saved drawing (2D CAD or 3D model JSON) into a picture the AI can see. */
    private Bitmap renderDrawing(String data) {
        try {
            if (GeometryCanvas3D.isJson3d(data)) return HistoryActivity.renderDrawing3DThumb(this, data, 900);
            return HistoryActivity.renderCadToBitmap(data, 900);
        } catch (Exception e) {
            return null;
        }
    }

    private void addAttachmentChip(Attachment a) {
        attachScroll.setVisibility(View.VISIBLE);

        MaterialCardView chip = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setRadius(dp(14));
        chip.setCardElevation(0f);
        chip.setStrokeWidth(dp(1));
        chip.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        chip.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(8), dp(6));

        TextView label = new TextView(this);
        label.setText((a.image != null ? "🖼  " : "📄  ") + a.title);
        label.setTextColor(ContextCompat.getColor(this, R.color.neon_text));
        label.setTextSize(13f);
        label.setMaxWidth(dp(180));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label);

        ImageButton remove = new ImageButton(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        rlp.leftMargin = dp(6);
        remove.setLayoutParams(rlp);
        remove.setBackgroundResource(android.R.color.transparent);
        remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        remove.setColorFilter(ContextCompat.getColor(this, R.color.neon_text_dim));
        remove.setContentDescription(getString(R.string.remove_image));
        remove.setOnClickListener(v -> {
            attachments.remove(a);
            attachRow.removeView(chip);
            if (attachments.isEmpty()) attachScroll.setVisibility(View.GONE);
            Toast.makeText(this, R.string.chat_attachment_removed, Toast.LENGTH_SHORT).show();
        });
        row.addView(remove);

        chip.addView(row);
        attachRow.addView(chip);
    }

    // ---------------- Camera / gallery / voice ----------------

    private void startCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        try {
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            File photo = File.createTempFile("CHAT_" + System.currentTimeMillis() + "_", ".jpg", dir);
            pendingCameraPath = photo.getAbsolutePath();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            Intent it = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraLauncher.launch(it);
        } catch (Exception e) {
            Toast.makeText(this, R.string.scan_error_loading_photo, Toast.LENGTH_SHORT).show();
        }
    }

    private void openImageGallery() {
        Intent pick = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { imagePickerLauncher.launch(pick); }
        catch (Exception e) {
            imagePickerLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
        }
    }

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_mic_prompt));
            voiceLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap decodeScaledBitmapFromFile(String path, int maxDim) {
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
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            float ratio = Math.min((float) maxDim / bmp.getWidth(), (float) maxDim / bmp.getHeight());
            if (ratio >= 1f) return bmp;
            return Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * ratio), Math.round(bmp.getHeight() * ratio), true);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap decodeScaledBitmapFromUri(Uri uri, int maxDim) {
        try {
            Bitmap original = android.provider.MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            if (original == null) return null;
            float ratio = Math.min((float) maxDim / original.getWidth(), (float) maxDim / original.getHeight());
            if (ratio >= 1f) return original;
            return Bitmap.createScaledBitmap(original, Math.round(original.getWidth() * ratio), Math.round(original.getHeight() * ratio), true);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- API key ----------------

    /** Resolves a usable Gemini key the same way the rest of the app does; empty string if none. */
    private String resolveApiKey() {
        SharedPreferences p = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean privileged = "Admin_Teacher".equals(p.getString("username", ""))
                || p.getBoolean("is_pro_user", false);

        if (privileged) {
            if (!BuildConfig.GEMINI_API_KEY.isEmpty()) return BuildConfig.GEMINI_API_KEY;
            for (String bk : BuildConfig.GEMINI_BACKUP_KEYS.split(",")) {
                String k = bk.trim();
                if (!k.isEmpty()) return k;
            }
        }
        if (ApiKeyStore.hasUsableKeys(this)) {
            List<String> keys = ApiKeyStore.getKeys(this);
            if (!keys.isEmpty()) return keys.get(0);
        }
        return "";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistCurrentSession();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
