package com.example.lemm;

import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted, iterated password hashing for the local account store, so the {@code users.password}
 * column never holds a recoverable plaintext password.
 *
 * Uses PBKDF2-HMAC-SHA1 (available on all supported API levels, minSdk 24) with a per-password
 * random salt. The encoded form is self-describing so parameters can change later without breaking
 * existing rows:  {@code pbkdf2sha1$<iterations>$<saltB64>$<hashB64>}
 *
 * Verification is constant-time. {@link #isHashed(String)} lets callers detect and transparently
 * migrate legacy plaintext rows on the next successful login.
 */
public final class PasswordHasher {
    private static final String ALGO = "PBKDF2WithHmacSHA1";
    private static final String PREFIX = "pbkdf2sha1$";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private PasswordHasher() {}

    /** Returns an encoded salted hash of the password, or the input unchanged if it's null/empty. */
    public static String hash(String password) {
        if (password == null || password.isEmpty()) return password;
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] dk = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + b64(salt) + "$" + b64(dk);
    }

    /** True if {@code stored} is in our hashed format (vs. a legacy plaintext value). */
    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * Verifies a plaintext password against a stored value.
     * - If {@code stored} is hashed, compares via PBKDF2 (constant-time).
     * - If {@code stored} is legacy plaintext, falls back to a direct equals so existing accounts
     *   keep working until they're migrated.
     */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        if (!isHashed(stored)) return password.equals(stored); // legacy plaintext row
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = unb64(parts[2]);
            byte[] expected = unb64(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iter);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(pw, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static String b64(byte[] data) { return Base64.encodeToString(data, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
