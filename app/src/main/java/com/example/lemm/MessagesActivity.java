package com.example.lemm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A one-to-one thread. Messages carry plain text, a solved problem, or a drawing.
 *
 * Text bubbles are run through {@link TheoremLinker}, so any theorem a student names ("by the
 * Pythagorean Theorem…") becomes a tappable link straight to that theorem's page — the same
 * behaviour the AI solution cards already have.
 */
public class MessagesActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_UID = "PEER_UID";
    public static final String EXTRA_PEER_NAME = "PEER_NAME";
    public static final String EXTRA_GROUP_ID = "GROUP_ID";
    public static final String EXTRA_GROUP_NAME = "GROUP_NAME";

    private static final int HISTORY_LIMIT = 300;

    /** The conversation this screen is showing — a 1:1 DM or a group. */
    private Social.Thread thread;
    private LinearLayout container;
    private ScrollView scroll;
    private EditText etInput;

    private Query query;
    private ChildEventListener listener;
    private long newestTs;
    private boolean styleGlass;
    /** uids this user has blocked — their messages are never rendered. */
    private final Set<String> blocked = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        styleGlass = StyleManager.isGlass(this);
        StyleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_messages);

        container = findViewById(R.id.messagesContainer);
        scroll = findViewById(R.id.scrollMessages);
        etInput = findViewById(R.id.etInput);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSend).setOnClickListener(v -> sendText());
        findViewById(R.id.btnAttach).setOnClickListener(v -> showAttachDialog());

        String groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        String peerUid = getIntent().getStringExtra(EXTRA_PEER_UID);
        if (groupId != null) {
            String name = getIntent().getStringExtra(EXTRA_GROUP_NAME);
            thread = Social.Thread.group(groupId, name == null ? "" : name);
        } else if (peerUid != null) {
            String name = getIntent().getStringExtra(EXTRA_PEER_NAME);
            thread = Social.Thread.dm(peerUid, name == null ? "" : name);
        }

        if (!Social.signedIn() || thread == null) {
            Toast.makeText(this, R.string.sign_in_to_chat, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.tvPeer)).setText(thread.title);

        // Load the block list BEFORE the thread, so a blocked sender's messages are never rendered.
        Social.blockedRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot child : snap.getChildren()) blocked.add(child.getKey());
                if (!isFinishing() && !isDestroyed()) listen();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing() && !isDestroyed()) listen();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // thread is null when we bailed out in onCreate (guest / bad intent).
        if (thread != null) Social.markSeen(this, thread, newestTs); // clears the unread dot
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (query != null && listener != null) query.removeEventListener(listener);
    }

    // ---------- live thread ----------

    private void listen() {
        query = Social.threadRef(thread).limitToLast(HISTORY_LIMIT);
        listener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                Social.Message m = Social.Message.from(snap);
                newestTs = Math.max(newestTs, m.ts);
                if (m.from != null && blocked.contains(m.from)) return; // blocked: never shown
                addBubble(m);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        query.addChildEventListener(listener);
    }

    private void sendText() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        Social.sendText(this, thread, text);
    }

    // ---------- bubbles ----------

    private MaterialCardView bubbleShell(boolean mine) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.leftMargin = mine ? dp(52) : 0;
        lp.rightMargin = mine ? 0 : dp(52);
        card.setLayoutParams(lp);
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        if (mine) {
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.neon_user_bubble));
        } else {
            card.setCardBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(StyleManager.color(this, R.attr.appCardStroke));
        }
        return card;
    }

    private void addBubble(Social.Message m) {
        boolean mine = m.mine(Social.uid());
        View bubble;
        if (Social.TYPE_SOLUTION.equals(m.type)) bubble = solutionBubble(m, mine);
        else if (Social.TYPE_DRAWING.equals(m.type)) bubble = drawingBubble(m, mine);
        else bubble = textBubble(m, mine);

        attachLongPressMenu(bubble, m, mine);
        container.addView(bubble);
        container.addView(timestamp(m, mine)); // a chat without times is disorienting
    }

    /** Time under each bubble: "14:32", or "Yesterday 14:32" / "3 Jul 14:32" for older messages. */
    private View timestamp(Social.Message m, boolean mine) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.leftMargin = mine ? 0 : dp(8);
        lp.rightMargin = mine ? dp(8) : 0;
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        tv.setTextSize(11f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        tv.setText(formatTime(m.ts));
        return tv;
    }

    private String formatTime(long ts) {
        if (ts <= 0) return "";
        java.util.Calendar msg = java.util.Calendar.getInstance();
        msg.setTimeInMillis(ts);
        java.util.Calendar now = java.util.Calendar.getInstance();

        String time = android.text.format.DateFormat.getTimeFormat(this)
                .format(new java.util.Date(ts));

        boolean sameYear = msg.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR);
        int dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - msg.get(java.util.Calendar.DAY_OF_YEAR);

        if (sameYear && dayDiff == 0) return time;
        if (sameYear && dayDiff == 1) return getString(R.string.time_yesterday, time);

        String date = android.text.format.DateFormat.getMediumDateFormat(this)
                .format(new java.util.Date(ts));
        return date + " " + time;
    }

    /**
     * Long-press a message: copy it, or — on someone else's message — report it or block them.
     * Play's User Generated Content policy requires both of those to exist in-app.
     */
    private void attachLongPressMenu(View bubble, Social.Message m, boolean mine) {
        View.OnLongClickListener l = v -> {
            if (mine) {
                copyToClipboard(plaintextOf(m));
                return true;
            }
            new AlertDialog.Builder(this)
                    .setItems(new CharSequence[]{
                            getString(R.string.copy),
                            getString(R.string.report_message),
                            getString(R.string.block_user)}, (d, which) -> {
                        if (which == 0) copyToClipboard(plaintextOf(m));
                        else if (which == 1) UserReports.showReportDialog(this, thread, m);
                        else confirmBlock(m);
                    })
                    .show();
            return true;
        };
        bubble.setOnLongClickListener(l);
        // A TextView with a movement method (theorem links) eats the touch, so it needs its own.
        View inner = bubble.findViewWithTag("body");
        if (inner != null) inner.setOnLongClickListener(l);
    }

    private void confirmBlock(Social.Message m) {
        final String uid = m.from;
        final String name = (m.fromName == null || m.fromName.isEmpty()) ? getString(R.string.this_user) : m.fromName;
        if (uid == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.block_user)
                .setMessage(getString(R.string.block_user_confirm, name))
                .setPositiveButton(R.string.block_user, (d, w) -> {
                    Social.blockUser(uid, m.fromName);
                    Toast.makeText(this, getString(R.string.user_blocked, name), Toast.LENGTH_LONG).show();
                    finish(); // the thread is no longer something they should be looking at
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String plaintextOf(Social.Message m) {
        if (Social.TYPE_SOLUTION.equals(m.type)) return (m.title == null ? "" : m.title);
        if (Social.TYPE_DRAWING.equals(m.type)) return (m.title == null ? "" : m.title);
        return m.text == null ? "" : m.text;
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Lemma", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /** In a group, an incoming bubble is labelled with who sent it. DMs don't need it. */
    private TextView senderLabel(String name) {
        TextView tv = new TextView(this);
        tv.setText(name == null ? "" : name);
        tv.setTextSize(12f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan));
        return tv;
    }

    private boolean showSender(boolean mine) { return thread.isGroup() && !mine; }

    private View textBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        if (showSender(mine)) col.addView(senderLabel(m.fromName));

        TextView tv = new TextView(this);
        tv.setTextSize(16f);
        // NOT selectable: text selection would swallow the long-press that opens report/block.
        // Copying is offered in that menu instead.
        tv.setTag("body");
        tv.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
        tv.setText(m.text == null ? "" : m.text);
        // Any theorem named in the message becomes a tappable link to its page.
        TheoremLinker.linkify(this, tv);
        col.addView(tv);

        card.addView(col);
        return card;
    }

    /** A shared solved problem — tapping it opens the solver read-only, figure included. */
    private View solutionBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = attachmentBody(m,
                getString(R.string.preview_solution),
                m.title,
                m.problem,
                getString(R.string.open_solution),
                mine);
        card.addView(col);
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, GeometryInputActivity.class);
            i.putExtra("SAVED_RAW", m.raw);
            i.putExtra("SAVED_PROBLEM", m.problem);
            i.putExtra("SAVED_NAME", m.title);
            startActivity(i);
        });
        return card;
    }

    /** A shared drawing — the payload decides whether the 3-D or 2-D editor opens it. */
    private View drawingBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = attachmentBody(m,
                getString(R.string.preview_drawing),
                m.title,
                null,
                getString(R.string.open_drawing),
                mine);
        card.addView(col);
        card.setOnClickListener(v -> {
            Intent i;
            if (GeometryCanvas3D.isJson3d(m.data)) {
                i = new Intent(this, Drawing3DActivity.class);
                i.putExtra("LOAD_3D_DATA", m.data);
            } else {
                i = new Intent(this, DrawingActivity.class);
                i.putExtra("LOAD_DRAWING_DATA", m.data);
                i.putExtra("IS_VIEW_ONLY", true);
            }
            i.putExtra("SAVED_NAME", m.title);
            startActivity(i);
        });
        return card;
    }

    /** Shared body for the two attachment bubbles: kind • title • optional preview • call to action. */
    private LinearLayout attachmentBody(Social.Message m, String kind, String title, String subtitle,
                                        String cta, boolean mine) {
        int fg = mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text);
        int dim = mine ? Color.parseColor("#CCFFFFFF") : ContextCompat.getColor(this, R.color.neon_text_dim);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));

        if (showSender(mine)) col.addView(senderLabel(m.fromName));

        TextView tvKind = new TextView(this);
        tvKind.setText(kind);
        tvKind.setTextSize(12f);
        tvKind.setTextColor(dim);
        col.addView(tvKind);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title == null ? "" : title);
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(fg);
        col.addView(tvTitle);

        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle.trim());
            tvSub.setTextSize(13f);
            tvSub.setMaxLines(2);
            tvSub.setTextColor(dim);
            col.addView(tvSub);
        }

        TextView tvCta = new TextView(this);
        tvCta.setText(cta);
        tvCta.setTextSize(13f);
        tvCta.setTypeface(null, Typeface.BOLD);
        tvCta.setPadding(0, dp(6), 0, 0);
        tvCta.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_cyan));
        col.addView(tvCta);

        return col;
    }

    // ---------- attaching saved work ----------

    private void showAttachDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.attach)
                .setItems(new CharSequence[]{
                        getString(R.string.send_solution), getString(R.string.send_drawing)},
                        (d, which) -> { if (which == 0) pickSolution(); else pickDrawing(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static final class Saved {
        final String title, problem, payload;
        Saved(String title, String problem, String payload) {
            this.title = title; this.problem = problem; this.payload = payload;
        }
    }

    private void pickSolution() {
        List<Saved> items = new ArrayList<>();
        DatabaseHelper db = new DatabaseHelper(this);
        try (Cursor c = db.getHistory(Social.myUsername(this))) {
            while (c != null && c.moveToNext()) {
                items.add(new Saved(
                        c.getString(c.getColumnIndexOrThrow(DbSchema.KEY_HIST_NAME)),
                        c.getString(c.getColumnIndexOrThrow(DbSchema.KEY_HIST_PROBLEM)),
                        c.getString(c.getColumnIndexOrThrow(DbSchema.KEY_HIST_RAW_RESPONSE))));
            }
        }
        if (items.isEmpty()) { Toast.makeText(this, R.string.no_saved_solutions, Toast.LENGTH_SHORT).show(); return; }

        showPicker(R.string.send_solution, items, s -> {
            if (!Social.sendSolution(this, thread, s.title, s.problem, s.payload)) {
                Toast.makeText(this, R.string.share_too_large, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void pickDrawing() {
        List<Saved> items = new ArrayList<>();
        DatabaseHelper db = new DatabaseHelper(this);
        try (Cursor c = db.getDrawings(Social.myUsername(this))) {
            while (c != null && c.moveToNext()) {
                items.add(new Saved(
                        c.getString(c.getColumnIndexOrThrow(DbSchema.KEY_DRW_NAME)),
                        null,
                        c.getString(c.getColumnIndexOrThrow(DbSchema.KEY_DRW_DATA))));
            }
        }
        if (items.isEmpty()) { Toast.makeText(this, R.string.no_saved_drawings, Toast.LENGTH_SHORT).show(); return; }

        showPicker(R.string.send_drawing, items, s -> {
            if (!Social.sendDrawing(this, thread, s.title, s.payload)) {
                Toast.makeText(this, R.string.share_too_large, Toast.LENGTH_LONG).show();
            }
        });
    }

    private interface OnPick { void pick(Saved s); }

    private void showPicker(int titleRes, List<Saved> items, OnPick onPick) {
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setItems(labels, (d, which) -> onPick.pick(items.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
