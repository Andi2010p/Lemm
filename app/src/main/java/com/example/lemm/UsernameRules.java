package com.example.lemm;

import java.util.regex.Pattern;

/**
 * One place that decides what a valid username looks like, so registration, profile-rename,
 * and Google-derived names all behave the same way:
 *   • 3–20 characters
 *   • letters, digits, and underscore only
 *   • can't start with "guestuser" or equal "admin_teacher" (reserved app accounts)
 */
public final class UsernameRules {
    public static final int MIN_LEN = 3;
    public static final int MAX_LEN = 20;
    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9_]{" + MIN_LEN + "," + MAX_LEN + "}");

    private UsernameRules() {}

    /** True for the app's two reserved-account namespaces. */
    public static boolean isReserved(String s) {
        if (s == null) return false;
        String low = s.toLowerCase();
        return low.startsWith("guestuser") || low.equals("admin_teacher");
    }

    /**
     * Validates a user-supplied username.
     * @return null if OK, otherwise a string resource id describing the error.
     */
    public static Integer validate(String s) {
        if (s == null || s.trim().isEmpty()) return R.string.enter_all_fields;
        String t = s.trim();
        if (isReserved(t)) return R.string.restricted_prefix;
        if (!VALID.matcher(t).matches()) return R.string.username_invalid;
        return null;
    }

    /**
     * Coerces any raw string (Google display name, email prefix, etc.) into a safe username:
     * replaces non-allowed chars with "_", collapses runs of "_", trims them at the edges,
     * enforces length, and avoids reserved namespaces. Always returns something usable.
     */
    public static String sanitize(String raw) {
        if (raw == null) raw = "";
        String t = raw.trim()
                .replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        if (t.length() < MIN_LEN) t = (t + "_user");
        if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        if (isReserved(t)) {
            t = ("u_" + t);
            if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        }
        return t;
    }
}
