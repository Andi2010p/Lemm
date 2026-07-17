package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.biometric.BiometricManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The app-lock: an optional PIN (with fingerprint / face unlock) that gates entry to the app.
 *
 * <p><b>The PIN is never stored.</b> Only a salted, stretched SHA-256 hash of it lives on the device,
 * in a private SharedPreferences file. A 4–6 digit PIN is inherently low-entropy, so this is not
 * pretending to be cryptographic protection of the data — it is a "someone picked up my unlocked
 * phone" barrier, backed by the OS keyguard via biometrics. Attempt-limiting lives in
 * {@link AppLockActivity}.
 *
 * <p>Gating happens once per cold start (see {@link SplashActivity}): the user chose "lock only on
 * full app restart", so there is deliberately no foreground/background re-lock observer.
 */
public final class AppLock {

    private AppLock() {}

    private static final String PREFS = "AppLock";
    private static final String K_ENABLED = "enabled";
    private static final String K_HASH = "pin_hash";
    private static final String K_SALT = "pin_salt";
    private static final String K_BIOMETRIC = "biometric";
    private static final String K_PROMPTED = "setup_prompted";

    /** Key-stretching rounds. Cheap on a phone, but multiplies the cost of an offline PIN sweep. */
    private static final int ROUNDS = 12_000;
    public static final int MIN_PIN = 4;
    public static final int MAX_PIN = 8;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- state ----------

    /** True only when the user turned the lock on AND a PIN is actually set. */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false) && hasPin(c);
    }

    public static boolean hasPin(Context c) {
        return prefs(c).getString(K_HASH, null) != null;
    }

    public static boolean isBiometricPreferred(Context c) {
        return prefs(c).getBoolean(K_BIOMETRIC, true);
    }

    public static void setBiometricPreferred(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_BIOMETRIC, on).apply();
    }

    /** Whether this device has an enrolled fingerprint / face we could actually use. */
    public static boolean canUseBiometric(Context c) {
        int r = BiometricManager.from(c).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return r == BiometricManager.BIOMETRIC_SUCCESS;
    }

    // ---------- set / change / clear the PIN ----------

    /** Sets (or replaces) the PIN and turns the lock on. Returns false if the PIN is the wrong length. */
    public static boolean setPin(Context c, String pin) {
        if (pin == null || pin.length() < MIN_PIN || pin.length() > MAX_PIN) return false;
        String salt = newSalt();
        prefs(c).edit()
                .putString(K_SALT, salt)
                .putString(K_HASH, hash(pin, salt))
                .putBoolean(K_ENABLED, true)
                .apply();
        return true;
    }

    public static boolean verifyPin(Context c, String pin) {
        if (pin == null) return false;
        String salt = prefs(c).getString(K_SALT, null);
        String expected = prefs(c).getString(K_HASH, null);
        if (salt == null || expected == null) return false;
        // Constant-time compare so a wrong PIN can't be timed out digit by digit.
        return constantTimeEquals(expected, hash(pin, salt));
    }

    /** Turns the lock off and forgets the PIN entirely. */
    public static void disable(Context c) {
        prefs(c).edit()
                .remove(K_HASH)
                .remove(K_SALT)
                .putBoolean(K_ENABLED, false)
                .apply();
    }

    // ---------- first-run offer ----------

    /**
     * True the first time we should offer to set the lock up — the user hasn't been asked before and
     * hasn't already turned it on. {@link #markPrompted} flips it off so we only ask once.
     */
    public static boolean shouldOfferSetup(Context c) {
        return !prefs(c).getBoolean(K_PROMPTED, false) && !isEnabled(c);
    }

    public static void markPrompted(Context c) {
        prefs(c).edit().putBoolean(K_PROMPTED, true).apply();
    }

    // ---------- hashing ----------

    private static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    private static String hash(String pin, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] cur = (salt + "|" + pin).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < ROUNDS; i++) {
                md.reset();
                cur = md.digest(cur);
            }
            return toHex(cur);
        } catch (Exception e) {
            // SHA-256 is guaranteed present on Android; this branch is unreachable in practice.
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
