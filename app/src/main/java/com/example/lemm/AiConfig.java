package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which AI provider/model the app uses. Gemini stays the default (built-in key + subscription + the
 * user's own keys via {@link ApiKeyStore}); OpenAI (GPT) and Anthropic (Claude) are "bring your own key"
 * — the user pastes their own API key and picks a model. Stored in the same "AI_Settings" prefs.
 */
public final class AiConfig {

    private AiConfig() {}

    public enum Provider { GEMINI, OPENAI, CLAUDE }

    private static final String PREFS = "AI_Settings";
    private static final String KEY_PROVIDER = "ai_provider";
    private static final String KEY_OPENAI_KEY = "openai_api_key";
    private static final String KEY_OPENAI_MODEL = "openai_model";
    private static final String KEY_CLAUDE_KEY = "claude_api_key";
    private static final String KEY_CLAUDE_MODEL = "claude_model";

    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o";
    public static final String DEFAULT_CLAUDE_MODEL = "claude-opus-4-8";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Provider provider(Context c) {
        String p = prefs(c).getString(KEY_PROVIDER, "gemini");
        if ("openai".equals(p)) return Provider.OPENAI;
        if ("claude".equals(p)) return Provider.CLAUDE;
        return Provider.GEMINI;
    }

    public static void setProvider(Context c, Provider p) {
        String v = (p == Provider.OPENAI) ? "openai" : (p == Provider.CLAUDE) ? "claude" : "gemini";
        prefs(c).edit().putString(KEY_PROVIDER, v).apply();
    }

    /** The user's own API key for a bring-your-own-key provider (empty for Gemini — it uses ApiKeyStore). */
    public static String key(Context c, Provider p) {
        if (p == Provider.OPENAI) return prefs(c).getString(KEY_OPENAI_KEY, "").trim();
        if (p == Provider.CLAUDE) return prefs(c).getString(KEY_CLAUDE_KEY, "").trim();
        return "";
    }

    public static void setKey(Context c, Provider p, String key) {
        String k = key == null ? "" : key.trim();
        if (p == Provider.OPENAI) prefs(c).edit().putString(KEY_OPENAI_KEY, k).apply();
        else if (p == Provider.CLAUDE) prefs(c).edit().putString(KEY_CLAUDE_KEY, k).apply();
    }

    public static String model(Context c, Provider p) {
        if (p == Provider.OPENAI) return prefs(c).getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL).trim();
        if (p == Provider.CLAUDE) return prefs(c).getString(KEY_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL).trim();
        return "";
    }

    public static void setModel(Context c, Provider p, String model) {
        String m = (model == null || model.trim().isEmpty())
                ? (p == Provider.OPENAI ? DEFAULT_OPENAI_MODEL : DEFAULT_CLAUDE_MODEL)
                : model.trim();
        if (p == Provider.OPENAI) prefs(c).edit().putString(KEY_OPENAI_MODEL, m).apply();
        else if (p == Provider.CLAUDE) prefs(c).edit().putString(KEY_CLAUDE_MODEL, m).apply();
    }

    /** True when the chosen provider is ready to use (Gemini is gated elsewhere; external needs a key). */
    public static boolean isExternalReady(Context c) {
        Provider p = provider(c);
        return p != Provider.GEMINI && !key(c, p).isEmpty();
    }

    /** Human label for the current provider (for UI). */
    public static String label(Provider p) {
        if (p == Provider.OPENAI) return "OpenAI (GPT)";
        if (p == Provider.CLAUDE) return "Anthropic (Claude)";
        return "Google (Gemini)";
    }
}
