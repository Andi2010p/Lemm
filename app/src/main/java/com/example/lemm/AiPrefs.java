package com.example.lemm;

import android.content.Context;

/**
 * Which AI pipe the app uses.
 *
 * <p><b>Why this exists.</b> The Gemini key compiled into the APK is a single, shared, <i>free-tier</i>
 * key. Free-tier Gemini is rate-limited per project, so it cannot serve a paying user base — a busy
 * afternoon of homework would exhaust it for everyone at once — and it is extractable from the APK.
 *
 * <p>The real answer is the Cloud backend ({@link LemmaBackend#askAI}): it holds a <i>paid</i> Gemini
 * key server-side, meters each request against the user's plan credits, and returns only the text.
 * When {@link #cloudEnabled} is on, every AI feature routes there. It defaults <b>off</b> so the app
 * keeps working on the built-in key until you have deployed the functions with a paid key — then it
 * is a one-switch cutover (Settings → "Lemma Cloud AI"), and afterwards the key can be removed from
 * the APK entirely.
 */
public final class AiPrefs {
    private static final String PREFS = "AI_Settings";
    private static final String KEY_CLOUD = "cloud_ai_enabled";

    private AiPrefs() {}

    public static boolean cloudEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLOUD, false);
    }

    public static void setCloudEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOUD, on).apply();
    }
}
