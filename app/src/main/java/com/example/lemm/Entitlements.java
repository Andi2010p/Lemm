package com.example.lemm;

import android.content.Context;

/**
 * What the current plan unlocks. "Lemma Plus" IS the existing Pro tier ({@link ProStatusManager}), so
 * this is a thin policy layer on top of it — one place to change the rules.
 *
 * Free  → built-in Gemini with a small monthly token grant, or their own key; no model picker.
 * Plus  → large monthly grant, can buy top-up packs ({@link TokenWallet}), unlocks the model picker
 *         (GPT / Claude, bring-your-own-key) and the heaviest "Smart" model tier.
 */
public final class Entitlements {
    private Entitlements() {}

    // Monthly built-in-AI token grants (~4 chars/token). Tune these to your Gemini cost budget.
    public static final long FREE_MONTHLY_TOKENS = 20_000;
    public static final long PLUS_MONTHLY_TOKENS = 1_000_000;

    // Human framing: tokens are abstract, so everywhere the UI shows them we ALSO show roughly how
    // many solved problems they buy. One full worked solution + drawing averages ~this many tokens
    // (deliberately a bit high, so the "≈ N problems" we promise is conservative). Tune to taste.
    public static final long APPROX_TOKENS_PER_SOLVE = 2_000;

    /** Roughly how many problems a token amount can solve (the number users actually care about). */
    public static long approxSolves(long tokens) {
        return Math.max(0, tokens / APPROX_TOKENS_PER_SOLVE);
    }

    /** Groups a big token count for display, e.g. 1000000 → "1,000,000" (locale-aware). */
    public static String grouped(long n) {
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }

    public static boolean isPlus(Context c) {
        return ProStatusManager.isPro(c);
    }

    public static long monthlyAllowance(Context c) {
        return isPlus(c) ? PLUS_MONTHLY_TOKENS : FREE_MONTHLY_TOKENS;
    }

    /** Choosing an external provider / a specific model is a Plus feature. */
    public static boolean canChooseModel(Context c) {
        return isPlus(c);
    }

    /** The heaviest ("Smart") models are Plus-only. */
    public static boolean canUseTier(Context c, int tier) {
        return isPlus(c) || tier != ModelCatalog.TIER_SMART;
    }

    /**
     * We meter tokens only when the app's OWN key pays for the call — i.e. a Plus user on the built-in
     * Gemini. Free users bring their own key (they pay their provider) and are never metered here.
     */
    public static boolean shouldMeter(Context c) {
        return isPlus(c) && AiConfig.provider(c) == AiConfig.Provider.GEMINI;
    }
}
