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

import java.util.ArrayList;
import java.util.List;

/**
 * Central store for the user's personal Gemini API keys and the
 * "use my own API keys" toggle. Backed by the "AI_Settings" SharedPreferences.
 *
 * Supports multiple keys, each with its own on/off flag so the user can keep a
 * key saved but exclude it from the auto-rotation (e.g. when it has expired).
 * The AI rotation reads {@link #getKeys(Context)}, which returns only the
 * enabled keys; if one runs out of quota the solver falls through to the next.
 *
 * Storage format: one entry per line, "flag\tkey" where flag is 1 (on) or 0
 * (off). Plain lines without a tab, and the old single-key value, are migrated
 * transparently on read.
 */
public final class ApiKeyStore {
    private static final String PREFS = "AI_Settings";
    private static final String KEY_LIST = "user_api_keys";        // newline-separated entries
    private static final String KEY_LEGACY = "user_api_key";       // legacy single key
    private static final String KEY_ENABLED = "use_personal_keys"; // master on/off
    private static final char SEP = '\t';                          // flag<SEP>key

    // Cloud mirror (per signed-in account, keyed by Firebase uid) so keys follow the user.
    private static final String CLOUD_KEYS = "ai_keys";
    private static final String CLOUD_ENABLED = "ai_keys_enabled";

    private ApiKeyStore() {}

    /** One personal key plus whether it takes part in the auto-rotation. */
    public static class Entry {
        public final String key;
        public boolean enabled;
        public Entry(String key, boolean enabled) { this.key = key; this.enabled = enabled; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** All saved keys with their individual on/off state (for the settings UI). */
    public static List<Entry> getEntries(Context c) {
        SharedPreferences p = prefs(c);
        List<Entry> entries = new ArrayList<>();
        String raw = p.getString(KEY_LIST, null);
        if (raw == null) {
            // migrate the very old single-key value
            String legacy = p.getString(KEY_LEGACY, "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                entries.add(new Entry(legacy.trim(), true));
            }
            return entries;
        }
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            boolean enabled = true;
            String key = line;
            int sep = line.indexOf(SEP);
            if (sep >= 0) {                       // new format: flag<TAB>key
                enabled = !"0".equals(line.substring(0, sep));
                key = line.substring(sep + 1);
            }
            key = key.trim();
            if (!key.isEmpty() && !containsKey(entries, key)) {
                entries.add(new Entry(key, enabled));
            }
        }
        return entries;
    }

    /** Persists the given entries (trimmed, de-duplicated). Keeps the legacy key in sync. */
    public static void setEntries(Context c, List<Entry> entries) {
        List<Entry> clean = new ArrayList<>();
        for (Entry e : entries) {
            if (e == null) continue;
            String t = e.key == null ? "" : e.key.trim();
            if (!t.isEmpty() && !containsKey(clean, t)) clean.add(new Entry(t, e.enabled));
        }
        StringBuilder sb = new StringBuilder();
        String firstEnabled = "";
        for (Entry e : clean) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.enabled ? '1' : '0').append(SEP).append(e.key);
            if (firstEnabled.isEmpty() && e.enabled) firstEnabled = e.key;
        }
        prefs(c).edit()
                .putString(KEY_LIST, sb.toString())
                .putString(KEY_LEGACY, firstEnabled) // keep old readers working
                .apply();
    }

    private static boolean containsKey(List<Entry> list, String key) {
        for (Entry e : list) if (e.key.equals(key)) return true;
        return false;
    }

    /** Only the ENABLED keys, in order — this is what the AI rotation uses. */
    public static List<String> getKeys(Context c) {
        List<String> out = new ArrayList<>();
        for (Entry e : getEntries(c)) if (e.enabled) out.add(e.key);
        return out;
    }

    /** Whether personal keys should be used at all (master switch). Defaults to true. */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** True when personal keys are enabled and at least one usable (enabled) key exists. */
    public static boolean hasUsableKeys(Context c) {
        return isEnabled(c) && !getKeys(c).isEmpty();
    }

    // ----- Cross-device cloud sync (only for signed-in accounts) -----

    private static DatabaseReference cloudRef() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null; // guests / username-only accounts stay local
        return FirebaseManager.getDatabase().getReference("users_info").child(u.getUid());
    }

    /** Uploads the current keys + toggle to this account so the user's other devices share them. */
    public static void pushToCloud(Context c) {
        DatabaseReference ref = cloudRef();
        if (ref == null) return;
        ref.child(CLOUD_KEYS).setValue(prefs(c).getString(KEY_LIST, ""));
        ref.child(CLOUD_ENABLED).setValue(isEnabled(c));
    }

    /**
     * Attaches a long-lived realtime listener so that whenever another device of the same account
     * updates the API keys / toggle, this device mirrors the change into local prefs immediately.
     * Returns the listener handle (or null for guests) so the caller can detach later.
     */
    public static ValueEventListener attachRealtimeListener(Context c, Runnable onChange) {
        DatabaseReference ref = cloudRef();
        if (ref == null) return null;
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String raw = snap.child(CLOUD_KEYS).getValue(String.class);
                Boolean enabled = snap.child(CLOUD_ENABLED).getValue(Boolean.class);
                if (raw != null) {
                    prefs(c).edit().putString(KEY_LIST, raw).apply();
                    setEntries(c, getEntries(c)); // normalize + keep the legacy single-key value in sync
                }
                if (enabled != null) setEnabled(c, enabled);
                if (onChange != null) onChange.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(listener);
        return listener;
    }

    public static void detachRealtimeListener(ValueEventListener listener) {
        DatabaseReference ref = cloudRef();
        if (ref != null && listener != null) ref.removeEventListener(listener);
    }

    /** Pulls the keys + toggle saved for this account into local storage, then runs onDone (UI refresh). */
    public static void syncFromCloud(Context c, Runnable onDone) {
        DatabaseReference ref = cloudRef();
        if (ref == null) { if (onDone != null) onDone.run(); return; }
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String raw = snap.child(CLOUD_KEYS).getValue(String.class);
                Boolean enabled = snap.child(CLOUD_ENABLED).getValue(Boolean.class);
                if (raw != null) {
                    prefs(c).edit().putString(KEY_LIST, raw).apply();
                    setEntries(c, getEntries(c)); // normalize + keep the legacy single-key value in sync
                }
                if (enabled != null) setEnabled(c, enabled);
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (onDone != null) onDone.run();
            }
        });
    }
}
