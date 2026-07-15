'use strict';

const admin = require('firebase-admin');
const { PLANS, microUsdFor } = require('./plans');

const db = () => admin.database();

const dayKey = (now = Date.now()) => new Date(now).toISOString().slice(0, 10);   // yyyy-mm-dd (UTC)
const monthKey = (now = Date.now()) => new Date(now).toISOString().slice(0, 7);  // yyyy-mm  (UTC)

/**
 * Resolves what a user is entitled to RIGHT NOW.
 *
 * This is the answer to "one of them has a subscription and the other doesn't": entitlement is
 * always per-uid. A family seat and an individual subscription both land in `entitlements/{uid}`,
 * written only by this backend, and the better of the two wins. A user with no row is on `free`.
 * Two family members using the app simultaneously therefore each get their own quota — nothing is
 * shared except the payment.
 */
async function resolvePlan(uid) {
  const snap = await db().ref(`entitlements/${uid}`).get();
  const ent = snap.val();
  if (!ent || !ent.plan || ent.plan === 'free') return PLANS.free;

  // An expired subscription silently degrades to free. We do NOT delete the row: RTDN may revive it,
  // and we want to know what they used to have.
  if (typeof ent.expiryMs === 'number' && ent.expiryMs < Date.now()) return PLANS.free;

  return PLANS[ent.plan] || PLANS.free;
}

/**
 * Atomically charges `priceMilli` credits, or throws.
 *
 * MUST be a transaction. Two devices signed into the same account (or two tabs, or a retry storm)
 * would otherwise both read `remaining = 1 credit` and both spend it. RTDB transactions retry on
 * contention, so the second one sees the first one's write.
 *
 * Draw order: the plan's allowance first, then non-expiring purchased credits. Spending the
 * perishable bucket first is the honest order — the user keeps what they paid cash for.
 *
 * @returns {{remainingAllowance:number, extraCredits:number}}
 * @throws  {Error & {code:'insufficient-credits'}}
 */
async function chargeCredits(uid, plan, priceMilli) {
  const today = dayKey();
  const period = monthKey();

  const result = await db().ref(`wallet/${uid}`).transaction((w) => {
    const wallet = w || {};

    // Roll the counters over lazily — no cron needed, and it can't be gamed by changing the clock
    // because these keys come from the SERVER's clock, not the device's.
    if (wallet.day !== today) { wallet.day = today; wallet.dayUsed = 0; }
    if (wallet.period !== period) { wallet.period = period; wallet.periodUsed = 0; }

    const extra = wallet.extraCredits || 0;

    // Free plans are gated per-day; paid plans per-month. Only one is ever non-zero.
    const cap = plan.dailyCredits || plan.monthlyCredits;
    const used = plan.dailyCredits ? (wallet.dayUsed || 0) : (wallet.periodUsed || 0);
    const remaining = Math.max(0, cap - used);

    if (remaining + extra < priceMilli) return; // undefined => abort the transaction, no write

    const fromAllowance = Math.min(remaining, priceMilli);
    const fromExtra = priceMilli - fromAllowance;

    if (plan.dailyCredits) wallet.dayUsed = (wallet.dayUsed || 0) + fromAllowance;
    else wallet.periodUsed = (wallet.periodUsed || 0) + fromAllowance;

    if (fromExtra > 0) wallet.extraCredits = extra - fromExtra;

    return wallet;
  });

  if (!result.committed) {
    const err = new Error('Not enough credits.');
    err.code = 'insufficient-credits';
    throw err;
  }

  const w = result.snapshot.val() || {};
  const cap = plan.dailyCredits || plan.monthlyCredits;
  const used = plan.dailyCredits ? (w.dayUsed || 0) : (w.periodUsed || 0);
  return { remainingAllowance: Math.max(0, cap - used), extraCredits: w.extraCredits || 0 };
}

/** Refunds a charge. Used when the model call fails AFTER we already took the credits. */
async function refundCredits(uid, plan, priceMilli) {
  await db().ref(`wallet/${uid}`).transaction((w) => {
    const wallet = w || {};
    const key = plan.dailyCredits ? 'dayUsed' : 'periodUsed';
    wallet[key] = Math.max(0, (wallet[key] || 0) - priceMilli);
    return wallet;
  });
}

/** Credits a purchased consumable pack. Separate bucket, never expires. */
async function grantExtraCredits(uid, milli) {
  await db().ref(`wallet/${uid}/extraCredits`).transaction((v) => (v || 0) + milli);
}

/**
 * Records what the request REALLY cost us. Not billed to the user — this is how the developer
 * discovers that a plan is priced wrong before it drains the bank account.
 *
 * Broken out PER MODEL as well as per user: free users run on flash-lite and paid users on flash,
 * which differ ~5x, so a single blended number would tell you nothing useful about either.
 */
async function recordUsage(uid, model, promptTokens, outputTokens) {
  const micro = microUsdFor(model, promptTokens, outputTokens);
  const month = monthKey();
  // Model ids contain dots ("gemini-2.5-flash"), and a Firebase key may not contain . # $ / [ ].
  // Writing one raw throws and would take down every AI call.
  const modelKey = String(model).replace(/[.#$/[\]]/g, '_');
  const updates = {
    [`usage/${uid}/tokensIn`]: admin.database.ServerValue.increment(promptTokens),
    [`usage/${uid}/tokensOut`]: admin.database.ServerValue.increment(outputTokens),
    [`usage/${uid}/microUsd`]: admin.database.ServerValue.increment(micro),
    [`usage_totals/${month}/microUsd`]: admin.database.ServerValue.increment(micro),
    [`usage_totals/${month}/calls`]: admin.database.ServerValue.increment(1),
    [`usage_totals/${month}/byModel/${modelKey}/microUsd`]: admin.database.ServerValue.increment(micro),
    [`usage_totals/${month}/byModel/${modelKey}/calls`]: admin.database.ServerValue.increment(1),
  };
  await db().ref().update(updates);
  return micro;
}

/** Writes an entitlement. Only ever called from the Play verification paths. */
async function setEntitlement(uid, { plan, source, familyId, expiryMs, autoRenewing }) {
  await db().ref(`entitlements/${uid}`).set({
    plan,
    source,
    familyId: familyId || null,
    expiryMs: expiryMs || 0,
    autoRenewing: !!autoRenewing,
    updatedMs: Date.now(),
  });
}

async function clearEntitlement(uid) {
  await db().ref(`entitlements/${uid}`).update({ plan: 'free', expiryMs: 0, updatedMs: Date.now() });
}

module.exports = {
  dayKey, monthKey,
  resolvePlan, chargeCredits, refundCredits, grantExtraCredits,
  recordUsage, setEntitlement, clearEntitlement,
};
