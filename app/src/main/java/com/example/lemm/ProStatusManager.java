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

/**
 * Single source of truth for the user's Pro / subscription status.
 *
 * The status lives in the "UserPrefs" SharedPreferences (key {@code is_pro_user}) so all the
 * existing gating code keeps working unchanged. On top of that it is mirrored to the cloud under
 * {@code users_info/{uid}/is_pro} — keyed by the signed-in account's Firebase uid, exactly like
 * {@link ApiKeyStore} — so Pro follows the user to every device they log into.
 *
 * The cloud value is authoritative when present: {@code is_pro=true} subscribes every device,
 * {@code is_pro=false} (set by Unsubscribe) un-subscribes every device. A MISSING value (the node
 * was never written) means "unknown", so a device that already knows it's Pro pushes that up rather
 * than getting downgraded. {@code pro_bypass} (the 5-tap unlock) and {@code pro_cloud} (cloud-backed)
 * protect the local flag from Google Play's "no purchase on this device" result between syncs.
 */
public final class ProStatusManager {
    private static final String PREFS = "UserPrefs";
    private static final String KEY_PRO = "is_pro_user";
    private static final String KEY_BYPASS = "pro_bypass";
    private static final String KEY_CLOUD = "pro_cloud";

    private static final String CLOUD_PRO = "is_pro";
    private static final String CLOUD_PRO_SINCE = "pro_since";

    private ProStatusManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The flag every gating check already reads. */
    public static boolean isPro(Context c) {
        return prefs(c).getBoolean(KEY_PRO, false);
    }

    /**
     * Grants Pro on this device and pushes it to the account in the cloud so every device sees it.
     * @param bypass true for the 5-tap cheat unlock, which must survive Google Play "not purchased".
     */
    public static void grant(Context c, boolean bypass) {
        setLocalPro(c, true);
        if (bypass) prefs(c).edit().putBoolean(KEY_BYPASS, true).apply();
        pushToCloud(c); // writes is_pro = true
    }

    /**
     * Cancels Pro on this device AND in the cloud (so the "unsubscribe" propagates to every device).
     * Note: this only changes Lemma's access flag — it does not refund a Google Play purchase.
     */
    public static void revoke(Context c) {
        setLocalPro(c, false);
        DatabaseReference ref = cloudRef();
        if (ref != null) ref.child(CLOUD_PRO).setValue(false);
    }

    /** Sets the local flag and the protection flags consistently. */
    private static void setLocalPro(Context c, boolean pro) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY_PRO, pro);
        if (pro) e.putBoolean(KEY_CLOUD, true);            // cloud-backed → don't let billing flip it off
        else e.putBoolean(KEY_CLOUD, false).putBoolean(KEY_BYPASS, false);
        e.apply();
    }

    /** True when Pro must not be cleared by Google Play reporting no purchase on THIS device. */
    public static boolean isProtected(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean(KEY_BYPASS, false) || p.getBoolean(KEY_CLOUD, false);
    }

    // ----- Cross-device cloud mirror (only for signed-in accounts) -----

    private static DatabaseReference cloudRef() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null; // guests / username-only accounts stay local
        return FirebaseManager.getDatabase().getReference("users_info").child(u.getUid());
    }

    /** Uploads Pro=true to this account so the user's other devices pick it up. Never writes false. */
    public static void pushToCloud(Context c) {
        DatabaseReference ref = cloudRef();
        if (ref == null || !isPro(c)) return;
        ref.child(CLOUD_PRO).setValue(true);
        ref.child(CLOUD_PRO_SINCE).setValue(FirebaseManager.getCurrentDate());
    }

    /** Pulls the account's Pro status into local prefs, then runs onDone (e.g. refresh UI). */
    public static void syncFromCloud(Context c, Runnable onDone) {
        DatabaseReference ref = cloudRef();
        if (ref == null) { if (onDone != null) onDone.run(); return; }
        ref.child(CLOUD_PRO).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean cloudPro = snap.getValue(Boolean.class);
                // The cloud is authoritative when it has a value (true = subscribed, false = unsubscribed).
                // A missing value (null) means "never set" — so push this device's status up instead.
                if (cloudPro != null) setLocalPro(c, cloudPro);
                else if (isPro(c)) pushToCloud(c);
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (onDone != null) onDone.run(); }
        });
    }

    /** Live updates: when another device of the same account changes Pro, mirror it here in ~1s. */
    public static ValueEventListener attachRealtimeListener(Context c, Runnable onChange) {
        DatabaseReference ref = cloudRef();
        if (ref == null) return null;
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean v = snap.getValue(Boolean.class);
                if (v != null) setLocalPro(c, v);
                if (onChange != null) onChange.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.child(CLOUD_PRO).addValueEventListener(listener);
        return listener;
    }

    public static void detachRealtimeListener(ValueEventListener listener) {
        DatabaseReference ref = cloudRef();
        if (ref != null && listener != null) ref.child(CLOUD_PRO).removeEventListener(listener);
    }
}
