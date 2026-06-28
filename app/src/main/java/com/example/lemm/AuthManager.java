package com.example.lemm;

import android.content.Context;

/**
 * Authentication side-effects and helpers, split out of {@link LoginActivity} so the activity only
 * orchestrates UI. This owns: session persistence, the security/welcome emails, Google Sign-In
 * error messages, and deriving a safe username from an email.
 */
public final class AuthManager {
    private AuthManager() {}

    /** Persists the logged-in session (used app-wide via the "UserPrefs" SharedPreferences). */
    public static void saveSession(Context c, String username, boolean isGuest) {
        c.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE).edit()
                .putString("username", username)
                .putBoolean("is_guest", isGuest)
                .apply();
    }

    /** A safe username derived from the local part of an email (letters/digits/underscore). */
    public static String fallbackUsernameFromEmail(String email) {
        if (email == null) return "user";
        return email.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /** Emails a "new login detected" security alert. */
    public static void sendLoginAlert(String email, String username) {
        if (email == null || email.isEmpty()) return;
        String body = "Hello <b>" + username + "</b>,\n\n"
                + "A successful login to your <b>Lemma</b> account was detected on <b>"
                + FirebaseManager.getCurrentDate() + "</b>.\n\n"
                + "If this was you, you can safely ignore this email.\n\n"
                + "If you did not authorize this login, please reset your password immediately inside the app.";
        EmailSender.sendOfficialEmail(email, "New Login Detected", "Security Alert", body);
    }

    /** Emails a welcome message after a successful Google Sign-In registration. */
    public static void sendGoogleWelcome(String email, String username) {
        if (email == null || email.isEmpty()) return;
        String body = "Hello <b>" + username + "</b>,\n\n"
                + "Your account via Google Sign-In has been successfully registered!\n\n"
                + "<b>Your Account Details:</b>\n"
                + "Username: " + username + "\n"
                + "Email: " + email + "\n\n"
                + "You can now scan math problems, draw 2D shapes, and sync your solutions securely to the cloud.";
        EmailSender.sendOfficialEmail(email, "Welcome to Lemma!", "Account Linked Successfully", body);
    }

    /** Human-readable explanation for the most common Google Sign-In status codes. */
    public static String googleSignInError(int code) {
        switch (code) {
            case 10: // DEVELOPER_ERROR
                return "Google Sign-In failed (10): this build's SHA-1 or the Web client ID isn't registered for this Firebase project. "
                        + "Add your SHA-1 in Firebase Console, re-download google-services.json, then uninstall & reinstall.";
            case 12500: // SIGN_IN_FAILED
                return "Google Sign-In failed (12500): update Google Play Services and make sure a Google account is added on this device.";
            case 12501: // SIGN_IN_CANCELLED
                return "Google Sign-In was canceled.";
            case 12502: // SIGN_IN_CURRENTLY_IN_PROGRESS
                return "A Google Sign-In is already in progress.";
            case 7:     // NETWORK_ERROR
                return "Network error during Google Sign-In. Check your connection.";
            default:
                return "Google Sign-In failed (code " + code + "): "
                        + com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.getStatusCodeString(code);
        }
    }
}
