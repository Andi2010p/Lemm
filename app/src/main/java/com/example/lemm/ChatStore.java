package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for AI tutor chat sessions (the drawer's history + resume).
 *
 * <p>Local store = a single SharedPreferences JSON blob (chats are short and few). On top of that,
 * each session is mirrored to <b>{@code chat_history/{uid}/{sessionId}}</b> in the cloud as one JSON
 * string, so a student's tutoring history follows them to every device they log into.
 *
 * <p><b>Secure by construction.</b> That node is owner-only in the database rules
 * ({@code $uid === auth.uid} for both read and write), so nobody but the signed-in student can read
 * or change their chats — verified in {@code tools/rules-tests}. Guests (no uid) stay purely local.
 */
public final class ChatStore {
    private static final String PREFS = "ChatHistory";
    private static final String KEY = "sessions";
    private static final int MAX_SESSIONS = 50;
    private static final int MAX_MESSAGES = 200;
    /** Matches the per-session length cap in the database rules; oversized sessions stay local-only. */
    private static final int MAX_CLOUD_SESSION_CHARS = 100_000;

    private ChatStore() {}

    /** One chat turn. */
    public static class Message {
        public final boolean user;
        public final String text;
        public Message(boolean user, String text) { this.user = user; this.text = text; }
    }

    /** A whole saved conversation, including the optional solution context it was grounded on. */
    public static class Session {
        public String id;
        public String title;
        public long ts;
        public String ctxProblem, ctxSolution, ctxFocus, ctxTitle, ctxRaw;
        public final List<Message> messages = new ArrayList<>();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** All sessions, newest first. */
    public static List<Session> listSessions(Context c) {
        List<Session> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Session s = fromJson(arr.getJSONObject(i));
                if (s != null) out.add(s);
            }
        } catch (JSONException ignored) {}
        Collections.sort(out, (a, b) -> Long.compare(b.ts, a.ts));
        return out;
    }

    public static Session getSession(Context c, String id) {
        if (id == null) return null;
        for (Session s : listSessions(c)) if (id.equals(s.id)) return s;
        return null;
    }

    /** Inserts or replaces a session (matched by id), then trims to the newest {@link #MAX_SESSIONS}. */
    public static void saveSession(Context c, Session session) {
        if (session == null || session.id == null) return;
        List<Session> all = listSessions(c);
        List<Session> kept = new ArrayList<>();
        for (Session s : all) if (!session.id.equals(s.id)) kept.add(s);
        kept.add(session);
        Collections.sort(kept, (a, b) -> Long.compare(b.ts, a.ts));
        while (kept.size() > MAX_SESSIONS) kept.remove(kept.size() - 1);
        persist(c, kept);
        pushToCloud(session);   // mirror this one session; owner-only, so it stays private
    }

    public static void deleteSession(Context c, String id) {
        if (id == null) return;
        List<Session> all = listSessions(c);
        List<Session> kept = new ArrayList<>();
        for (Session s : all) if (!id.equals(s.id)) kept.add(s);
        persist(c, kept);
        DatabaseReference ref = cloud();
        if (ref != null) ref.child(id).removeValue();  // delete follows to every device
    }

    // ---------- secure cloud mirror ----------

    private static DatabaseReference cloud() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null; // guests: local-only, they have no account to sync to
        return FirebaseManager.getDatabase().getReference("chat_history").child(u.getUid());
    }

    private static void pushToCloud(Session s) {
        DatabaseReference ref = cloud();
        if (ref == null || s == null || s.id == null) return;
        JSONObject o = toJson(s);
        if (o == null) return;
        String json = o.toString();
        if (json.length() > MAX_CLOUD_SESSION_CHARS) return; // too big for one node; keep it local
        ref.child(s.id).setValue(json);
    }

    /**
     * Pulls this account's cloud chats and MERGES them with the local store — newest timestamp wins
     * per session id, so opening the app on a second device restores your history without clobbering
     * anything you started offline. Call on chat open / after login; runs {@code onDone} on the main
     * thread when finished (or immediately for a guest).
     */
    public static void syncFromCloud(Context c, Runnable onDone) {
        DatabaseReference ref = cloud();
        if (ref == null) { if (onDone != null) onDone.run(); return; }

        ref.limitToLast(MAX_SESSIONS).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Map<String, Session> byId = new HashMap<>();
                for (Session s : listSessions(c)) byId.put(s.id, s);

                for (DataSnapshot child : snap.getChildren()) {
                    String json = child.getValue(String.class);
                    if (json == null) continue;
                    try {
                        Session s = fromJson(new JSONObject(json));
                        if (s == null || s.id == null) continue;
                        Session existing = byId.get(s.id);
                        if (existing == null || s.ts >= existing.ts) byId.put(s.id, s);
                    } catch (JSONException ignored) {}
                }

                List<Session> merged = new ArrayList<>(byId.values());
                Collections.sort(merged, (a, b) -> Long.compare(b.ts, a.ts));
                while (merged.size() > MAX_SESSIONS) merged.remove(merged.size() - 1);
                persist(c, merged);
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (onDone != null) onDone.run(); }
        });
    }

    private static void persist(Context c, List<Session> sessions) {
        JSONArray arr = new JSONArray();
        for (Session s : sessions) {
            JSONObject o = toJson(s);
            if (o != null) arr.put(o);
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    private static JSONObject toJson(Session s) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("title", s.title == null ? "" : s.title);
            o.put("ts", s.ts);
            if (s.ctxProblem != null) o.put("cp", s.ctxProblem);
            if (s.ctxSolution != null) o.put("cs", s.ctxSolution);
            if (s.ctxFocus != null) o.put("cf", s.ctxFocus);
            if (s.ctxTitle != null) o.put("ct", s.ctxTitle);
            if (s.ctxRaw != null) o.put("cr", s.ctxRaw);
            JSONArray msgs = new JSONArray();
            int start = Math.max(0, s.messages.size() - MAX_MESSAGES);
            for (int i = start; i < s.messages.size(); i++) {
                Message m = s.messages.get(i);
                JSONObject mo = new JSONObject();
                mo.put("u", m.user);
                mo.put("t", m.text);
                msgs.put(mo);
            }
            o.put("m", msgs);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private static Session fromJson(JSONObject o) {
        try {
            Session s = new Session();
            s.id = o.getString("id");
            s.title = o.optString("title", "");
            s.ts = o.optLong("ts", 0);
            s.ctxProblem = o.has("cp") ? o.getString("cp") : null;
            s.ctxSolution = o.has("cs") ? o.getString("cs") : null;
            s.ctxFocus = o.has("cf") ? o.getString("cf") : null;
            s.ctxTitle = o.has("ct") ? o.getString("ct") : null;
            s.ctxRaw = o.has("cr") ? o.getString("cr") : null;
            JSONArray msgs = o.optJSONArray("m");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject mo = msgs.getJSONObject(i);
                    s.messages.add(new Message(mo.optBoolean("u", false), mo.optString("t", "")));
                }
            }
            return s;
        } catch (JSONException e) {
            return null;
        }
    }
}
