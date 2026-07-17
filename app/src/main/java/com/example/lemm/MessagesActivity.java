package com.example.lemm;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A conversation — a 1:1 DM or a group — with the feature set people expect from a messenger:
 * replies, reactions, edit &amp; unsend, read receipts, a typing indicator, online / last-seen presence,
 * photo / voice / file attachments (via {@link ChatMedia}), in-chat search, and mute. Solution and
 * drawing sharing and theorem links are carried over from the original screen.
 *
 * <p>Rows are keyed by message id so edits, reactions and deletes update in place — the child listener
 * handles add / change / remove rather than only appending.
 */
public class MessagesActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_UID = "PEER_UID";
    public static final String EXTRA_PEER_NAME = "PEER_NAME";
    public static final String EXTRA_GROUP_ID = "GROUP_ID";
    public static final String EXTRA_GROUP_NAME = "GROUP_NAME";

    private static final int HISTORY_LIMIT = 300;
    private static final long TYPING_TIMEOUT_MS = 6000;
    private static final String[] REACTIONS = {"❤️", "👍", "😂", "😮", "😢", "🙏"};

    private Social.Thread thread;
    private String myUid;
    private boolean styleGlass;

    private LinearLayout container;
    private ScrollView scroll;
    private EditText etInput;
    private TextView tvPeerStatus;
    private ImageButton btnSend, btnMic;
    private View replyBar, uploadBar, searchBar;
    private TextView tvReplyName, tvReplyPreview, tvUpload, tvSearchCount;
    private ProgressBar uploadProgress;
    private EditText etSearch;

    // Live listeners.
    private Query msgQuery;
    private ChildEventListener msgListener;
    private DatabaseReference reactionsRoot;
    private ChildEventListener reactionsListener;
    private DatabaseReference typingRef;
    private ValueEventListener typingListener;
    private DatabaseReference presRef;
    private ValueEventListener presListener;
    private DatabaseReference receiptRef;
    private ValueEventListener receiptListener;

    private long newestTs, peerReadTs;
    private boolean peerOnline;
    private long peerLastSeen;
    private String typingLabel;

    private final Set<String> blocked = new HashSet<>();
    private final LinkedHashMap<String, Social.Message> messages = new LinkedHashMap<>();
    private final Map<String, LinearLayout> rows = new java.util.HashMap<>();
    private final Map<String, Map<String, String>> reactions = new java.util.HashMap<>();

    private Social.Message replyDraft;

    // Media.
    private ActivityResultLauncher<String> galleryLauncher, fileLauncher, micPermLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private Uri cameraPhotoUri;

    // Voice recording.
    private MediaRecorder recorder;
    private File voiceFile;
    private boolean recording;
    private long recordStartMs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable recordTick;
    private MediaPlayer player;

    // Typing signal debounce.
    private long lastTypingPing;
    private Runnable typingStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        styleGlass = StyleManager.isGlass(this);
        StyleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_messages);

        container = findViewById(R.id.messagesContainer);
        scroll = findViewById(R.id.scrollMessages);
        etInput = findViewById(R.id.etInput);
        tvPeerStatus = findViewById(R.id.tvPeerStatus);
        btnSend = findViewById(R.id.btnSend);
        btnMic = findViewById(R.id.btnMic);
        replyBar = findViewById(R.id.replyBar);
        uploadBar = findViewById(R.id.uploadBar);
        searchBar = findViewById(R.id.searchBar);
        tvReplyName = findViewById(R.id.tvReplyName);
        tvReplyPreview = findViewById(R.id.tvReplyPreview);
        tvUpload = findViewById(R.id.tvUpload);
        tvSearchCount = findViewById(R.id.tvSearchCount);
        uploadProgress = findViewById(R.id.uploadProgress);
        etSearch = findViewById(R.id.etSearch);

        myUid = Social.uid();
        registerLaunchers();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> sendText());
        btnMic.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.btnAttach).setOnClickListener(v -> showAttachDialog());
        findViewById(R.id.btnSearch).setOnClickListener(v -> openSearch());
        findViewById(R.id.btnSearchClose).setOnClickListener(v -> closeSearch());
        findViewById(R.id.btnMore).setOnClickListener(this::showOverflow);
        findViewById(R.id.btnCancelReply).setOnClickListener(v -> clearReply());

        wireInputWatcher();
        wireSearchWatcher();

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
        Social.startPresence();

        // Load the block list BEFORE the thread, so a blocked sender's messages are never rendered.
        Social.blockedRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot child : snap.getChildren()) blocked.add(child.getKey());
                if (!isFinishing() && !isDestroyed()) startListening();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing() && !isDestroyed()) startListening();
            }
        });
    }

    // ================= lifecycle =================

    @Override protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
    }

    @Override protected void onPause() {
        super.onPause();
        if (thread != null) {
            Social.markSeen(this, thread, newestTs);
            Social.markRead(this, thread, newestTs);
            Social.setTyping(thread, false);
        }
        stopPlayback();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (msgQuery != null && msgListener != null) msgQuery.removeEventListener(msgListener);
        if (reactionsRoot != null && reactionsListener != null) reactionsRoot.removeEventListener(reactionsListener);
        if (typingRef != null && typingListener != null) typingRef.removeEventListener(typingListener);
        if (presRef != null && presListener != null) presRef.removeEventListener(presListener);
        if (receiptRef != null && receiptListener != null) receiptRef.removeEventListener(receiptListener);
        cancelRecording();
        stopPlayback();
    }

    // ================= live thread =================

    private void startListening() {
        listenMessages();
        listenReactions();
        listenTyping();
        if (!thread.isGroup()) { listenPresence(); listenReceipts(); }
    }

    private void listenMessages() {
        msgQuery = Social.threadRef(thread).limitToLast(HISTORY_LIMIT);
        msgListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                Social.Message m = Social.Message.from(snap);
                newestTs = Math.max(newestTs, m.ts);
                if (m.from != null && blocked.contains(m.from)) return;
                if (Social.isHiddenForMe(MessagesActivity.this, thread, m.id)) return;
                messages.put(m.id, m);
                LinearLayout row = new LinearLayout(MessagesActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                rows.put(m.id, row);
                bindRow(row, m);
                container.addView(row);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                Social.markRead(MessagesActivity.this, thread, newestTs);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String prev) {
                Social.Message m = Social.Message.from(snap);
                if (m.from != null && blocked.contains(m.from)) return;
                messages.put(m.id, m);
                LinearLayout row = rows.get(m.id);
                if (row != null) bindRow(row, m);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                String id = snap.getKey();
                LinearLayout row = rows.remove(id);
                if (row != null) container.removeView(row);
                messages.remove(id);
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        msgQuery.addChildEventListener(msgListener);
    }

    private void listenReactions() {
        reactionsRoot = Social.reactionsThreadRef(thread); // .../reactions/{threadId}
        if (reactionsRoot == null) return;
        reactionsListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) { applyReactions(s); }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) { applyReactions(s); }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                reactions.remove(s.getKey());
                rebind(s.getKey());
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        reactionsRoot.addChildEventListener(reactionsListener);
    }

    private void applyReactions(DataSnapshot msgNode) {
        String msgId = msgNode.getKey();
        Map<String, String> byUser = new java.util.HashMap<>();
        for (DataSnapshot u : msgNode.getChildren()) {
            String emoji = u.getValue(String.class);
            if (emoji != null) byUser.put(u.getKey(), emoji);
        }
        reactions.put(msgId, byUser);
        rebind(msgId);
    }

    private void rebind(String msgId) {
        Social.Message m = messages.get(msgId);
        LinearLayout row = rows.get(msgId);
        if (m != null && row != null) bindRow(row, m);
    }

    private void listenTyping() {
        typingRef = Social.typingRef(thread);
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                String who = null;
                int count = 0;
                for (DataSnapshot u : snap.getChildren()) {
                    if (u.getKey() == null || u.getKey().equals(myUid)) continue;
                    Long ts = u.getValue(Long.class);
                    if (ts != null && now - ts < TYPING_TIMEOUT_MS) {
                        count++;
                        who = u.getKey();
                    }
                }
                if (count == 0) typingLabel = null;
                else if (thread.isGroup()) typingLabel = getString(R.string.typing);
                else typingLabel = getString(R.string.typing);
                refreshStatus();
                // Re-evaluate shortly, since a stale ts should stop showing "typing…".
                ui.postDelayed(MessagesActivity.this::expireTyping, TYPING_TIMEOUT_MS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);
    }

    private void expireTyping() {
        // Cheap: the value listener re-fires on any change; this just clears a lingering label.
        if (typingLabel != null) { typingLabel = null; refreshStatus(); }
    }

    private void listenPresence() {
        presRef = Social.presenceRef(thread.peerUid);
        presListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String state = snap.child("state").getValue(String.class);
                Long lc = snap.child("lastChanged").getValue(Long.class);
                peerOnline = "online".equals(state);
                peerLastSeen = lc == null ? 0 : lc;
                refreshStatus();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        presRef.addValueEventListener(presListener);
    }

    private void listenReceipts() {
        receiptRef = Social.receiptsRef(thread);
        if (receiptRef == null) return;
        receiptListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot u : snap.getChildren()) {
                    if (u.getKey() != null && !u.getKey().equals(myUid)) {
                        Long ts = u.getValue(Long.class);
                        if (ts != null) peerReadTs = Math.max(peerReadTs, ts);
                    }
                }
                // Update ticks on my sent messages.
                for (Map.Entry<String, Social.Message> e : messages.entrySet()) {
                    if (e.getValue().mine(myUid)) rebind(e.getKey());
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        receiptRef.addValueEventListener(receiptListener);
    }

    private void refreshStatus() {
        if (tvPeerStatus == null) return;
        String text;
        if (typingLabel != null) text = typingLabel;
        else if (thread.isGroup()) text = null;
        else if (peerOnline) text = getString(R.string.online);
        else if (peerLastSeen > 0) text = getString(R.string.last_seen, formatLastSeen(peerLastSeen));
        else text = null;
        if (text == null) { tvPeerStatus.setVisibility(View.GONE); }
        else { tvPeerStatus.setText(text); tvPeerStatus.setVisibility(View.VISIBLE); }
    }

    // ================= sending =================

    private void wireInputWatcher() {
        etInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean hasText = s.toString().trim().length() > 0;
                btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                btnMic.setVisibility(hasText ? View.GONE : View.VISIBLE);
                pingTyping();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    /** Signals "typing" at most every couple of seconds, and auto-clears after a pause. */
    private void pingTyping() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPing > 2000) {
            lastTypingPing = now;
            Social.setTyping(thread, true);
        }
        if (typingStop != null) ui.removeCallbacks(typingStop);
        typingStop = () -> Social.setTyping(thread, false);
        ui.postDelayed(typingStop, 3000);
    }

    private void sendText() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        Social.sendText(this, thread, text, consumeReply());
    }

    /** Returns the pending reply (and clears the reply bar), or null if not replying. */
    private Social.Reply consumeReply() {
        if (replyDraft == null) return null;
        Social.Reply r = new Social.Reply(replyDraft.id,
                nameOf(replyDraft), previewTextOf(replyDraft));
        clearReply();
        return r;
    }

    private void startReply(Social.Message m) {
        replyDraft = m;
        tvReplyName.setText(nameOf(m));
        tvReplyPreview.setText(previewTextOf(m));
        replyBar.setVisibility(View.VISIBLE);
        etInput.requestFocus();
    }

    private void clearReply() {
        replyDraft = null;
        replyBar.setVisibility(View.GONE);
    }

    private String nameOf(Social.Message m) {
        if (m.mine(myUid)) return getString(R.string.you);
        return (m.fromName == null || m.fromName.isEmpty()) ? getString(R.string.this_user) : m.fromName;
    }

    private String previewTextOf(Social.Message m) {
        if (m.deleted) return getString(R.string.message_deleted);
        if (Social.TYPE_IMAGE.equals(m.type)) return "📷 " + getString(R.string.photo);
        if (Social.TYPE_VOICE.equals(m.type)) return "🎤 " + getString(R.string.voice_note);
        if (Social.TYPE_FILE.equals(m.type)) return "📎 " + (m.mediaName == null ? getString(R.string.file) : m.mediaName);
        if (Social.TYPE_SOLUTION.equals(m.type)) return "📘 " + (m.title == null ? "" : m.title);
        if (Social.TYPE_DRAWING.equals(m.type)) return "✏️ " + (m.title == null ? "" : m.title);
        return m.text == null ? "" : m.text;
    }

    // ================= rendering =================

    private void bindRow(LinearLayout row, Social.Message m) {
        row.removeAllViews();
        boolean mine = m.mine(myUid);

        View bubble = m.deleted ? deletedBubble(mine) : buildBubble(m, mine);
        row.addView(bubble);
        if (!m.deleted) attachLongPressMenu(bubble, m, mine);

        View strip = reactionStrip(m);
        if (strip != null) row.addView(strip);

        row.addView(metaLine(m, mine));
    }

    private View buildBubble(Social.Message m, boolean mine) {
        if (Social.TYPE_SOLUTION.equals(m.type)) return solutionBubble(m, mine);
        if (Social.TYPE_DRAWING.equals(m.type)) return drawingBubble(m, mine);
        if (Social.TYPE_IMAGE.equals(m.type)) return imageBubble(m, mine);
        if (Social.TYPE_VOICE.equals(m.type)) return voiceBubble(m, mine);
        if (Social.TYPE_FILE.equals(m.type)) return fileBubble(m, mine);
        return textBubble(m, mine);
    }

    private MaterialCardView bubbleShell(boolean mine) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(2);
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

    /** Sender label (group incoming) + quoted-reply preview, prepended to a bubble's content column. */
    private void decorateHeader(LinearLayout col, Social.Message m, boolean mine) {
        if (thread.isGroup() && !mine) col.addView(senderLabel(m.fromName));
        if (m.replyTo != null) {
            LinearLayout quote = new LinearLayout(this);
            quote.setOrientation(LinearLayout.VERTICAL);
            quote.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
            qlp.bottomMargin = dp(4);
            quote.setLayoutParams(qlp);
            quote.setBackgroundColor(mine ? 0x33FFFFFF : StyleManager.color(this, R.attr.appCardStroke));

            TextView who = new TextView(this);
            who.setText(m.replyName == null ? "" : m.replyName);
            who.setTextSize(11f);
            who.setTypeface(null, Typeface.BOLD);
            who.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_cyan));
            quote.addView(who);

            TextView what = new TextView(this);
            what.setText(m.replyText == null ? "" : m.replyText);
            what.setTextSize(12f);
            what.setMaxLines(1);
            what.setTextColor(mine ? 0xCCFFFFFF : ContextCompat.getColor(this, R.color.neon_text_dim));
            quote.addView(what);
            col.addView(quote);
        }
    }

    private View textBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));
        decorateHeader(col, m, mine);

        TextView tv = new TextView(this);
        tv.setTextSize(16f);
        tv.setTag("body");
        tv.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
        tv.setText(m.text == null ? "" : m.text);
        TheoremLinker.linkify(this, tv);
        col.addView(tv);
        card.addView(col);
        return card;
    }

    private View imageBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(6), dp(6), dp(6));
        decorateHeader(col, m, mine);

        ImageView img = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(200), dp(200));
        img.setLayoutParams(ilp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(android.R.drawable.ic_menu_gallery);
        ChatMedia.loadImage(m.mediaUrl, bmp -> img.setImageBitmap(bmp));
        img.setOnClickListener(v -> openMediaExternally(m));
        col.addView(img);

        if (m.text != null && !m.text.isEmpty()) {
            TextView cap = new TextView(this);
            cap.setText(m.text);
            cap.setTag("body");
            cap.setPadding(dp(8), dp(6), dp(8), dp(2));
            cap.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
            col.addView(cap);
        }
        card.addView(col);
        return card;
    }

    private View voiceBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(14), dp(10));
        row.setTag("body");

        ImageView play = new ImageView(this);
        play.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        play.setImageResource(android.R.drawable.ic_media_play);
        play.setColorFilter(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_cyan));
        row.addView(play);

        TextView label = new TextView(this);
        label.setText(getString(R.string.voice_note) + "  " + formatDuration(m.mediaDurationMs));
        label.setPadding(dp(10), 0, 0, 0);
        label.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
        row.addView(label);

        row.setOnClickListener(v -> playVoice(m.mediaUrl));
        card.addView(row);
        return card;
    }

    private View fileBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(14), dp(12));
        row.setTag("body");

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        icon.setImageResource(android.R.drawable.ic_menu_save);
        icon.setColorFilter(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_cyan));
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(m.mediaName == null ? getString(R.string.file) : m.mediaName);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        name.setTextColor(mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text));
        col.addView(name);
        if (m.mediaSize > 0) {
            TextView size = new TextView(this);
            size.setText(ChatMedia.humanSize(m.mediaSize));
            size.setTextSize(12f);
            size.setTextColor(mine ? 0xCCFFFFFF : ContextCompat.getColor(this, R.color.neon_text_dim));
            col.addView(size);
        }
        row.addView(col);
        row.setOnClickListener(v -> openMediaExternally(m));
        card.addView(row);
        return card;
    }

    private View deletedBubble(boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        TextView tv = new TextView(this);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setText(getString(R.string.message_deleted));
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        tv.setTypeface(null, Typeface.ITALIC);
        card.addView(tv);
        return card;
    }

    private View reactionStrip(Social.Message m) {
        Map<String, String> byUser = reactions.get(m.id);
        if (byUser == null || byUser.isEmpty()) return null;
        // Aggregate emoji -> count.
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String emoji : byUser.values()) counts.merge(emoji, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(e.getValue());
            sb.append(' ');
        }
        boolean mine = m.mine(myUid);
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.leftMargin = mine ? 0 : dp(6);
        lp.rightMargin = mine ? dp(6) : 0;
        tv.setLayoutParams(lp);
        tv.setText(sb.toString().trim());
        tv.setTextSize(13f);
        tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        tv.setBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
        tv.setOnClickListener(v -> showReactionPicker(m));
        return tv;
    }

    private View metaLine(Social.Message m, boolean mine) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.leftMargin = mine ? 0 : dp(8);
        lp.rightMargin = mine ? dp(8) : 0;
        lp.bottomMargin = dp(4);
        line.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_text_dim));
        StringBuilder s = new StringBuilder(formatTime(m.ts));
        if (m.edited && !m.deleted) s.append("  ").append(getString(R.string.edited_marker));
        tv.setText(s.toString());
        line.addView(tv);

        // Read receipts: DM, my own, non-deleted messages get ✓ / ✓✓.
        if (mine && !thread.isGroup() && !m.deleted) {
            TextView tick = new TextView(this);
            tick.setTextSize(11f);
            tick.setPadding(dp(4), 0, 0, 0);
            boolean seen = peerReadTs > 0 && m.ts > 0 && m.ts <= peerReadTs;
            tick.setText(seen ? "✓✓" : "✓");
            tick.setTextColor(seen ? ContextCompat.getColor(this, R.color.neon_cyan)
                    : ContextCompat.getColor(this, R.color.neon_text_dim));
            line.addView(tick);
        }
        return line;
    }

    private TextView senderLabel(String name) {
        TextView tv = new TextView(this);
        tv.setText(name == null ? "" : name);
        tv.setTextSize(12f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan));
        return tv;
    }

    // ---- attachment (solution / drawing) bubbles carried over ----

    private View solutionBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = attachmentBody(m, getString(R.string.preview_solution), m.title, m.problem,
                getString(R.string.open_solution), mine);
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

    private View drawingBubble(Social.Message m, boolean mine) {
        MaterialCardView card = bubbleShell(mine);
        LinearLayout col = attachmentBody(m, getString(R.string.preview_drawing), m.title, null,
                getString(R.string.open_drawing), mine);
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

    private LinearLayout attachmentBody(Social.Message m, String kind, String title, String subtitle,
                                        String cta, boolean mine) {
        int fg = mine ? Color.WHITE : ContextCompat.getColor(this, R.color.neon_text);
        int dim = mine ? Color.parseColor("#CCFFFFFF") : ContextCompat.getColor(this, R.color.neon_text_dim);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        decorateHeader(col, m, mine);

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

    // ================= long-press actions =================

    private void attachLongPressMenu(View bubble, Social.Message m, boolean mine) {
        View.OnLongClickListener l = v -> { showMessageMenu(m, mine); return true; };
        bubble.setOnLongClickListener(l);
        View inner = bubble.findViewWithTag("body");
        if (inner != null) inner.setOnLongClickListener(l);
    }

    private void showMessageMenu(Social.Message m, boolean mine) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.react));
        items.add(getString(R.string.reply));
        if (Social.TYPE_TEXT.equals(m.type) || m.text != null) items.add(getString(R.string.copy));
        if (mine && Social.TYPE_TEXT.equals(m.type)) items.add(getString(R.string.edit));
        if (mine) items.add(getString(R.string.unsend));
        items.add(getString(R.string.delete_for_me));
        if (!mine) {
            items.add(getString(R.string.report_message));
            items.add(getString(R.string.block_user));
        }
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    String choice = items.get(which);
                    if (choice.equals(getString(R.string.react))) showReactionPicker(m);
                    else if (choice.equals(getString(R.string.reply))) startReply(m);
                    else if (choice.equals(getString(R.string.copy))) copyToClipboard(previewTextOf(m));
                    else if (choice.equals(getString(R.string.edit))) showEditDialog(m);
                    else if (choice.equals(getString(R.string.unsend))) confirmUnsend(m);
                    else if (choice.equals(getString(R.string.delete_for_me))) deleteForMe(m);
                    else if (choice.equals(getString(R.string.report_message))) UserReports.showReportDialog(this, thread, m);
                    else if (choice.equals(getString(R.string.block_user))) confirmBlock(m);
                })
                .show();
    }

    private void showReactionPicker(Social.Message m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(16), dp(12), dp(16));
        row.setGravity(Gravity.CENTER);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(row).create();
        String mineReaction = myReaction(m.id);
        for (String emoji : REACTIONS) {
            TextView t = new TextView(this);
            t.setText(emoji);
            t.setTextSize(28f);
            int pad = dp(8);
            t.setPadding(pad, pad, pad, pad);
            if (emoji.equals(mineReaction)) t.setBackgroundColor(StyleManager.color(this, R.attr.appCardFill));
            t.setOnClickListener(v -> {
                Social.setReaction(thread, m.id, emoji.equals(mineReaction) ? "" : emoji); // tap again to clear
                dialog.dismiss();
            });
            row.addView(t);
        }
        dialog.show();
    }

    private String myReaction(String msgId) {
        Map<String, String> byUser = reactions.get(msgId);
        return byUser == null ? null : byUser.get(myUid);
    }

    private void showEditDialog(Social.Message m) {
        EditText input = new EditText(this);
        input.setText(m.text);
        input.setSelection(m.text == null ? 0 : m.text.length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) ->
                        Social.editText(this, thread, m, input.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmUnsend(Social.Message m) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unsend)
                .setMessage(R.string.unsend_confirm)
                .setPositiveButton(R.string.unsend, (d, w) -> Social.deleteForEveryone(this, thread, m))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteForMe(Social.Message m) {
        Social.hideForMe(this, thread, m.id);
        LinearLayout row = rows.remove(m.id);
        if (row != null) container.removeView(row);
        messages.remove(m.id);
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
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Lemma", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    // ================= overflow (mute / search) =================

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean muted = Social.isMuted(this, thread);
        menu.getMenu().add(0, 1, 0, getString(muted ? R.string.unmute : R.string.mute));
        menu.getMenu().add(0, 2, 1, getString(R.string.chat_search));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                boolean newMuted = !Social.isMuted(this, thread);
                Social.setMuted(this, thread, newMuted);
                Toast.makeText(this, newMuted ? R.string.muted : R.string.unmuted, Toast.LENGTH_SHORT).show();
            } else if (item.getItemId() == 2) {
                openSearch();
            }
            return true;
        });
        menu.show();
    }

    // ================= search =================

    private void wireSearchWatcher() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { runSearch(s.toString().trim()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void openSearch() {
        searchBar.setVisibility(View.VISIBLE);
        etSearch.requestFocus();
    }

    private void closeSearch() {
        searchBar.setVisibility(View.GONE);
        etSearch.setText("");
        for (LinearLayout row : rows.values()) row.setBackgroundColor(Color.TRANSPARENT);
        tvSearchCount.setText("");
    }

    private void runSearch(String query) {
        int matches = 0;
        LinearLayout last = null;
        String q = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Social.Message> e : messages.entrySet()) {
            LinearLayout row = rows.get(e.getKey());
            if (row == null) continue;
            String hay = previewTextOf(e.getValue()).toLowerCase(Locale.ROOT);
            boolean hit = !q.isEmpty() && hay.contains(q);
            row.setBackgroundColor(hit ? 0x3300E5FF : Color.TRANSPARENT);
            if (hit) { matches++; last = row; }
        }
        tvSearchCount.setText(q.isEmpty() ? "" : String.valueOf(matches));
        if (last != null) {
            final View target = last;
            scroll.post(() -> scroll.smoothScrollTo(0, target.getTop()));
        }
    }

    // ================= media: attach / pickers / upload =================

    private void registerLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, Social.TYPE_IMAGE); });
        fileLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, Social.TYPE_FILE); });
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(),
                ok -> { if (ok != null && ok && cameraPhotoUri != null) uploadAndSend(cameraPhotoUri, Social.TYPE_IMAGE); });
        micPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) startRecording(); else Toast.makeText(this, R.string.permission_mic, Toast.LENGTH_LONG).show(); });
    }

    private void showAttachDialog() {
        CharSequence[] items = {
                getString(R.string.attach_photo),
                getString(R.string.attach_camera),
                getString(R.string.attach_file),
                getString(R.string.send_solution),
                getString(R.string.send_drawing)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.attach)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: galleryLauncher.launch("image/*"); break;
                        case 1: launchCamera(); break;
                        case 2: fileLauncher.launch("*/*"); break;
                        case 3: pickSolution(); break;
                        default: pickDrawing(); break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchCamera() {
        try {
            File dir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "");
            if (!dir.exists()) dir.mkdirs();
            File photo = new File(dir, "chat_" + System.currentTimeMillis() + ".jpg");
            cameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            cameraLauncher.launch(cameraPhotoUri);
        } catch (Exception e) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadAndSend(Uri uri, String type) {
        showUpload(true, 0);
        final Social.Reply reply = consumeReply();
        ChatMedia.upload(this, uri, type, new ChatMedia.UploadCallback() {
            @Override public void onProgress(int percent) { showUpload(true, percent); }
            @Override public void onComplete(String url, String name, String mime, long size) {
                showUpload(false, 0);
                Social.sendMedia(MessagesActivity.this, thread, type, url, name, mime, size, 0, null, reply);
            }
            @Override public void onError(String message) {
                showUpload(false, 0);
                Toast.makeText(MessagesActivity.this,
                        getString(R.string.media_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showUpload(boolean show, int percent) {
        uploadBar.setVisibility(show ? View.VISIBLE : View.GONE);
        uploadProgress.setProgress(percent);
    }

    // ================= voice recording =================

    private void toggleRecording() {
        if (recording) { stopRecordingAndSend(); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        try {
            voiceFile = new File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(voiceFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordStartMs = System.currentTimeMillis();
            btnMic.setImageResource(android.R.drawable.ic_media_pause);
            recordTick = new Runnable() {
                @Override public void run() {
                    if (!recording) return;
                    long ms = System.currentTimeMillis() - recordStartMs;
                    tvPeerStatus.setVisibility(View.VISIBLE);
                    tvPeerStatus.setText(getString(R.string.recording, formatDuration(ms)));
                    ui.postDelayed(this, 500);
                }
            };
            ui.post(recordTick);
            Toast.makeText(this, R.string.recording_tap_stop, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            cancelRecording();
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecordingAndSend() {
        long duration = System.currentTimeMillis() - recordStartMs;
        boolean ok = finishRecorder();
        refreshStatus();
        btnMic.setImageResource(android.R.drawable.ic_btn_speak_now);
        if (ok && voiceFile != null && voiceFile.exists() && duration > 700) {
            final long dur = duration;
            final Social.Reply reply = consumeReply();
            showUpload(true, 0);
            ChatMedia.upload(this, Uri.fromFile(voiceFile), Social.TYPE_VOICE, new ChatMedia.UploadCallback() {
                @Override public void onProgress(int percent) { showUpload(true, percent); }
                @Override public void onComplete(String url, String name, String mime, long size) {
                    showUpload(false, 0);
                    Social.sendMedia(MessagesActivity.this, thread, Social.TYPE_VOICE, url,
                            name, mime, size, dur, null, reply);
                }
                @Override public void onError(String message) {
                    showUpload(false, 0);
                    Toast.makeText(MessagesActivity.this, getString(R.string.media_failed, message), Toast.LENGTH_LONG).show();
                }
            });
        } else if (duration <= 700) {
            Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelRecording() {
        finishRecorder();
        recording = false;
        if (btnMic != null) btnMic.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    private boolean finishRecorder() {
        recording = false;
        if (recordTick != null) ui.removeCallbacks(recordTick);
        if (recorder == null) return false;
        try { recorder.stop(); recorder.release(); recorder = null; return true; }
        catch (Exception e) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; return false; }
    }

    private void playVoice(String url) {
        if (url == null) return;
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void openMediaExternally(Social.Message m) {
        if (m.mediaUrl == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl));
            if (m.mediaMime != null) i.setDataAndType(Uri.parse(m.mediaUrl), m.mediaMime);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    // ================= saved solution / drawing pickers (carried over) =================

    private static final class Saved {
        final String title, problem, payload;
        Saved(String title, String problem, String payload) { this.title = title; this.problem = problem; this.payload = payload; }
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
        final Social.Reply reply = consumeReply();
        showPicker(R.string.send_solution, items, s -> {
            if (!Social.sendSolution(this, thread, s.title, s.problem, s.payload)) {
                Toast.makeText(this, R.string.share_too_large, Toast.LENGTH_LONG).show();
            }
        });
        if (reply != null) { /* reply consumed; solution shares don't currently carry it */ }
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

    // ================= time helpers =================

    private String formatTime(long ts) {
        if (ts <= 0) return "";
        java.util.Calendar msg = java.util.Calendar.getInstance();
        msg.setTimeInMillis(ts);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(ts));
        boolean sameYear = msg.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR);
        int dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - msg.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameYear && dayDiff == 0) return time;
        if (sameYear && dayDiff == 1) return getString(R.string.time_yesterday, time);
        String date = android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(ts));
        return date + " " + time;
    }

    private String formatLastSeen(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000) return getString(R.string.just_now);
        return formatTime(ts);
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long total = ms / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
