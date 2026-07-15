package com.example.lemm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AI models the app knows about, with a tier / relative cost / "good for" note, plus a simple
 * recommender. Used by the model picker (let the user CHOOSE from a curated list instead of typing a
 * raw id) and by the app to RECOMMEND a model for a given problem. Model ids are editable elsewhere,
 * so this is guidance, not a hard whitelist.
 *
 * Tiers: FAST (cheap, quick, simple problems), BALANCED (the everyday default), SMART (deep reasoning
 * for hard proofs / 3-D). Cost is a relative 1–3 ($ / $$ / $$$), not a real price.
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    public static final int TIER_FAST = 0, TIER_BALANCED = 1, TIER_SMART = 2;

    public static final class Model {
        public final AiConfig.Provider provider;
        public final String id;
        public final String name;
        public final int tier;
        public final int cost;      // 1..3
        public final boolean vision;
        public final String goodFor;
        Model(AiConfig.Provider p, String id, String name, int tier, int cost, boolean vision, String goodFor) {
            this.provider = p; this.id = id; this.name = name; this.tier = tier;
            this.cost = cost; this.vision = vision; this.goodFor = goodFor;
        }
    }

    private static final List<Model> ALL = new ArrayList<>();
    static {
        // OpenAI (GPT)
        ALL.add(new Model(AiConfig.Provider.OPENAI, "gpt-4o-mini", "GPT-4o mini", TIER_FAST, 1, true, "Quick & cheap — simple problems."));
        ALL.add(new Model(AiConfig.Provider.OPENAI, "gpt-4o", "GPT-4o", TIER_BALANCED, 2, true, "Great all-round; reads figures well."));
        // Anthropic (Claude) — ids per Anthropic's current catalog
        ALL.add(new Model(AiConfig.Provider.CLAUDE, "claude-haiku-4-5", "Claude Haiku 4.5", TIER_FAST, 1, true, "Fast & cheap — simple problems."));
        ALL.add(new Model(AiConfig.Provider.CLAUDE, "claude-sonnet-5", "Claude Sonnet 5", TIER_BALANCED, 2, true, "Strong reasoning, cheaper than Opus."));
        ALL.add(new Model(AiConfig.Provider.CLAUDE, "claude-opus-4-8", "Claude Opus 4.8", TIER_SMART, 3, true, "Best reasoning — hard proofs / 3-D."));
        // Google (Gemini) — the app's built-in provider; shown for reference/recommendation
        ALL.add(new Model(AiConfig.Provider.GEMINI, "gemini-2.5-flash", "Gemini 2.5 Flash", TIER_FAST, 1, true, "Fast built-in default."));
        ALL.add(new Model(AiConfig.Provider.GEMINI, "gemini-3-flash-preview", "Gemini 3 Flash", TIER_BALANCED, 2, true, "Balanced built-in reasoning."));
    }

    public static List<Model> forProvider(AiConfig.Provider p) {
        List<Model> out = new ArrayList<>();
        for (Model m : ALL) if (m.provider == p) out.add(m);
        return out;
    }

    /** "$", "$$", "$$$" for a cost level. */
    public static String costLabel(int cost) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(1, Math.min(3, cost)); i++) sb.append('$');
        return sb.toString();
    }

    public static String tierLabel(int tier) {
        if (tier == TIER_FAST) return "Fast";
        if (tier == TIER_SMART) return "Smart";
        return "Balanced";
    }

    /**
     * Recommends a tier for a problem: hard (proof / 3-D / long) → SMART, tiny & plain → FAST, else
     * BALANCED. Pure heuristic on the text, so it works before any AI call.
     */
    public static int recommendTier(String problem) {
        if (problem == null) return TIER_BALANCED;
        String p = problem.toLowerCase(Locale.ROOT);
        int len = p.trim().length();
        boolean hard = len > 380
                || matchesAny(p, "prove", "proof", "докаж", "доказ", "ապացու",
                "tangent", "inscrib", "circumscrib", "locus", "vector",
                "pyramid", "sphere", "cone", "cylinder", "prism", "dihedral", "3d", "3-d",
                "касат", "вписан", "пирамид", "сфер", "շոշափ", "բուրգ", "գունդ");
        if (hard) return TIER_SMART;
        if (len < 110 && !matchesAny(p, "angle", "area", "circle", "triangle")) return TIER_FAST;
        return TIER_BALANCED;
    }

    /** Best model for a provider + problem (prefers vision when an image is attached). */
    public static Model recommend(AiConfig.Provider provider, String problem, boolean hasImage) {
        int wantTier = recommendTier(problem);
        List<Model> pool = forProvider(provider);
        Model best = null;
        for (Model m : pool) {
            if (hasImage && !m.vision) continue;
            if (m.tier == wantTier) return m;
            best = m; // fall back to something in this provider
        }
        // Nearest tier if exact not present.
        Model nearest = null; int nd = 99;
        for (Model m : pool) {
            if (hasImage && !m.vision) continue;
            int d = Math.abs(m.tier - wantTier);
            if (d < nd) { nd = d; nearest = m; }
        }
        return nearest != null ? nearest : best;
    }

    private static boolean matchesAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }
}
