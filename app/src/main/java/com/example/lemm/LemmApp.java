package com.example.lemm;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

public class LemmApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Record uncaught exceptions to a local file (for support / diagnostics), then hand off to the
        // system handler. We do NOT auto-restart, which could loop on a startup crash. For live field
        // crash reporting, add Firebase Crashlytics (see RELEASE_CHECKLIST.md).
        installCrashLogger();

        initAppCheck();

        // Apply the saved theme preference (defaults to following the device's system setting).
        int nightMode = getSharedPreferences("Settings", MODE_PRIVATE)
                .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(nightMode);

        // Enable Firebase Offline Persistence (Cloud Sync Cache)
        try {
            FirebaseManager.getDatabase().setPersistenceEnabled(true);
        } catch (Exception e) {
            // Expected if persistence was already enabled (DB touched before this call) — log, don't crash.
            Log.d("LemmApp", "Firebase persistence already enabled: " + e.getMessage());
        }

        // Clean up GuestUser history on every app start
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        boolean isGuest = pref.getBoolean("is_guest", false);
        String username = pref.getString("username", "");

        if (isGuest && username.startsWith("GuestUser_")) {
            // Delete guest data from Cloud
            FirebaseManager.getDatabase().getReference("users").child(username).removeValue();
            pref.edit().clear().apply();
        }
    }

    /**
     * Attests that this really is Lemma, not a script holding a copy of google-services.json.
     *
     * That file ships inside every APK, so anyone can extract it and call the database and the
     * backend directly. Security rules can prove WHO you are; only App Check can prove WHAT you are.
     * With Play Integrity enforced in the Firebase console, a request from anything other than a
     * genuine, unmodified install off the Play Store is rejected before it reaches a rule.
     *
     * Debug builds use the debug provider — register the token it prints in logcat under
     * Firebase console → App Check → Apps → Manage debug tokens, or debug builds will be refused.
     */
    private void initAppCheck() {
        try {
            com.google.firebase.FirebaseApp.initializeApp(this);
            com.google.firebase.appcheck.FirebaseAppCheck appCheck =
                    com.google.firebase.appcheck.FirebaseAppCheck.getInstance();

            if (BuildConfig.DEBUG) {
                // The debug provider is a `debugImplementation` dependency, so that class does not
                // exist on the RELEASE classpath at all. Reflection keeps this one file compiling for
                // both variants without splitting it across source sets.
                Class<?> factory = Class.forName(
                        "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");
                Object instance = factory.getMethod("getInstance").invoke(null);
                appCheck.installAppCheckProviderFactory(
                        (com.google.firebase.appcheck.AppCheckProviderFactory) instance);
            } else {
                appCheck.installAppCheckProviderFactory(
                        com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance());
            }
        } catch (Exception e) {
            // Never let attestation setup stop the app from launching.
            Log.e("LemmApp", "App Check init failed", e);
        }
    }

    /** Saves each uncaught crash to files/crashes/ (latest few kept) and chains to the default handler. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File dir = new java.io.File(getFilesDir(), "crashes");
                if (dir.exists() || dir.mkdirs()) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    throwable.printStackTrace(new java.io.PrintWriter(sw));
                    String info = "App " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                            + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                            + ", Android " + android.os.Build.VERSION.RELEASE + "\n\n" + sw;
                    java.io.File f = new java.io.File(dir, "crash_" + System.currentTimeMillis() + ".txt");
                    try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(info); }
                    java.io.File[] files = dir.listFiles();
                    if (files != null && files.length > 8) {
                        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                        for (int i = 0; i < files.length - 8; i++) files[i].delete();
                    }
                }
            } catch (Throwable ignored) {
                // Never let crash logging cause a second crash.
            }
            Log.e("LemmApp", "Uncaught exception on " + thread.getName(), throwable);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}