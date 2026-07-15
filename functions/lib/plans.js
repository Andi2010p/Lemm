'use strict';

/**
 * The single source of truth for what each plan grants. The Android app NEVER decides this —
 * it reads `entitlements/{uid}` which only this backend writes.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY CREDITS AND NOT TOKENS
 * ─────────────────────────────────────────────────────────────────────────────
 * "Tokens" are meaningless to a 14-year-old, and they are ALSO a bad cost proxy:
 *
 *   Gemini 2.5 Flash: $0.30 / 1M input, $2.50 / 1M output  (output is 8.3x input)
 *
 * A Lemma solve is roughly 2,600 input tokens (the big system prompt + exemplars is re-sent on
 * every call) and ~1,500 output tokens:
 *
 *   input : 2,600 x $0.30/1M  = $0.00078
 *   output: 1,500 x $2.50/1M  = $0.00375
 *   ------------------------------------
 *   ≈ $0.0045 per solved problem  (about half a US cent)
 *
 * So we bill the USER in `credits` (1 credit = 1 solved problem — a unit they can reason about),
 * and we separately record the REAL token usage + micro-dollars so the developer can see actual
 * cost. The old client-side meter counted only `problem + answer` characters and so under-counted
 * the billable tokens by roughly 4x, which the developer silently paid for.
 */

const MILLI = 1000; // credits are stored as integer milli-credits to avoid float drift

/**
 * USD per 1,000,000 tokens, per model. Output is what dominates the bill, so the model choice is the
 * single biggest lever on cost — far bigger than any prompt tuning.
 *
 *   flash-lite : $0.10 in / $0.40 out   →  a solve costs ~$0.00086
 *   flash      : $0.30 in / $2.50 out   →  a solve costs ~$0.0045   (5x more)
 *
 * That 5x is why the FREE tier runs on flash-lite: a free user who maxes out 3 solves a day, every
 * day, costs about **8 cents a month**. A free tier is therefore genuinely affordable — and "Plus
 * thinks with our smarter model" becomes a real, honest reason to upgrade rather than a fake wall.
 */
const MODEL_COST = {
  'gemini-2.5-flash-lite': { in: 0.10, out: 0.40 },
  'gemini-2.5-flash': { in: 0.30, out: 2.50 },
  'gemini-3-flash-preview': { in: 0.50, out: 2.50 },
};

const FREE_MODEL = 'gemini-2.5-flash-lite';
const PAID_MODEL = 'gemini-2.5-flash';

/** What each kind of AI request costs the user, in milli-credits. */
const PRICE = {
  solve: 1000,  // 1.00 credit — a full worked solution + figure
  chat: 250,    // 0.25 credit — one tutor chat reply (much shorter prompt & answer)
  scan: 1000,   // 1.00 credit — OCR + solve
};

/**
 * Plans.
 *
 * Fair-use caps are set from the p99 of real usage, not from the break-even point, so that a heavy
 * user is still profitable rather than merely not-fatal:
 *
 *   Plus @ $4.99/mo  →  $4.24 after Google's 15% fee.
 *   500 credits x $0.0045 = $2.25 worst case  →  ≥47% margin even for a user who burns every credit.
 *   A typical user solves 30-60 problems/month → ~$0.20 cost → ~95% margin.
 *
 * FREE is a daily allowance, not monthly: a daily reset builds the habit (this is exactly why
 * Duolingo uses hearts/streaks) and it caps the tail risk of a free user at ~$0.40/month.
 */
const PLANS = {
  free: {
    id: 'free',
    dailyCredits: 3 * MILLI,        // 3 solves a day, resets at midnight UTC
    monthlyCredits: 0,              // unused for free
    seats: 1,
    model: FREE_MODEL,              // cheap + fast: makes the free tier cost ~$0.08/user/month
    modelChoice: false,             // GPT / Claude picker is a paid feature
    smartTier: false,
  },
  plus: {
    id: 'plus',
    dailyCredits: 0,                // no daily gate
    monthlyCredits: 500 * MILLI,
    seats: 1,
    model: PAID_MODEL,              // the smarter model — a real reason to upgrade
    modelChoice: true,
    smartTier: true,
  },
  family: {
    id: 'family',
    dailyCredits: 0,
    monthlyCredits: 500 * MILLI,    // PER SEAT — not a shared pool. See resolve() in entitlements.js
    seats: 6,
    model: PAID_MODEL,
    modelChoice: true,
    smartTier: true,
  },
  classroom: {
    id: 'classroom',
    dailyCredits: 0,
    monthlyCredits: 400 * MILLI,    // per seat
    seats: 30,
    model: PAID_MODEL,
    modelChoice: true,
    smartTier: true,
  },
};

/**
 * Play Console product ids.
 *
 * Subscriptions use ONE product per plan with two base plans (monthly / annual) inside it — that is
 * how Play models subscriptions since Billing v5. The annual base plan is what actually protects the
 * business: it converts a churn-prone monthly payer into 12 months of revenue up front.
 */
const PRODUCTS = {
  SUB_PLUS: 'lemma_plus',            // base plans: plus-monthly, plus-annual
  SUB_FAMILY: 'lemma_family',        // base plans: family-monthly, family-annual
  SUB_CLASSROOM: 'lemma_classroom',  // base plans: classroom-monthly

  // Consumable credit top-ups, for someone who burns their fair-use cap mid-month.
  CREDITS_SMALL: 'lemma_credits_100',
  CREDITS_MEDIUM: 'lemma_credits_400',
  CREDITS_LARGE: 'lemma_credits_1200',
};

/** How many milli-credits each consumable grants. Never trust the client for this. */
const CREDIT_PACKS = {
  [PRODUCTS.CREDITS_SMALL]: 100 * MILLI,
  [PRODUCTS.CREDITS_MEDIUM]: 400 * MILLI,
  [PRODUCTS.CREDITS_LARGE]: 1200 * MILLI,
};

/** Maps a Play subscription product id onto a plan. */
function planForProduct(productId) {
  if (productId === PRODUCTS.SUB_PLUS) return PLANS.plus;
  if (productId === PRODUCTS.SUB_FAMILY) return PLANS.family;
  if (productId === PRODUCTS.SUB_CLASSROOM) return PLANS.classroom;
  return null;
}

/**
 * Real money this request cost us, in micro-USD. Recorded for cost observability, never billed.
 * Model-aware, because a flash-lite call and a flash call differ by ~5x — averaging them would hide
 * exactly the number you need to price the plans correctly.
 */
function microUsdFor(model, promptTokens, outputTokens) {
  const c = MODEL_COST[model] || MODEL_COST[PAID_MODEL];
  const usd = (promptTokens / 1e6) * c.in + (outputTokens / 1e6) * c.out;
  return Math.round(usd * 1e6);
}

module.exports = {
  MILLI, MODEL_COST, FREE_MODEL, PAID_MODEL,
  PRICE, PLANS, PRODUCTS, CREDIT_PACKS, planForProduct, microUsdFor,
};
