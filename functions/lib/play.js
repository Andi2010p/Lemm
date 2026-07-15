'use strict';

const admin = require('firebase-admin');
const { google } = require('googleapis');
const { PRODUCTS, CREDIT_PACKS, planForProduct } = require('./plans');
const { setEntitlement, clearEntitlement, grantExtraCredits } = require('./entitlements');
const { createOrUpdateFamily, expireFamily, familyIdFor } = require('./family');
const { Denied } = require('./social');

const db = () => admin.database();

const PACKAGE_NAME = process.env.ANDROID_PACKAGE_NAME || 'io.github.andi2010p.lemma';

let publisher;
async function androidPublisher() {
  if (publisher) return publisher;
  const auth = new google.auth.GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  publisher = google.androidpublisher({ version: 'v3', auth: await auth.getClient() });
  return publisher;
}

/**
 * The purchase token is the identity of a purchase. We record which uid it belongs to so that a
 * later Real-Time Developer Notification — which carries the token but NOT the user — can be routed
 * back to the right account.
 *
 * It also makes replay impossible: a second account cannot present the same token and be granted a
 * plan, because the token is already bound to someone.
 */
async function bindToken(token, uid, kind, productId) {
  const ref = db().ref(`play_tokens/${token}`);
  const res = await ref.transaction((cur) => {
    if (cur && cur.uid && cur.uid !== uid) return; // bound to someone else → abort
    return { uid, kind, productId, ts: Date.now() };
  });
  if (!res.committed) throw new Denied('permission-denied', 'That purchase belongs to another account.');
}

async function uidForToken(token) {
  const snap = await db().ref(`play_tokens/${token}/uid`).get();
  return snap.val();
}

/**
 * Subscription states we treat as "entitled". See purchases.subscriptionsv2 subscriptionState.
 * IN_GRACE_PERIOD still gets service — the payment failed but the user is being retried, and
 * cutting a paying customer off during a card re-auth is how you earn a 1-star review.
 */
const ACTIVE_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
]);

/**
 * Asks Google what this subscription really is. The client is never believed: it hands us an opaque
 * token, and Google tells us the product, the state and the expiry.
 */
async function verifySubscription(purchaseToken) {
  const ap = await androidPublisher();
  const { data } = await ap.purchases.subscriptionsv2.get({
    packageName: PACKAGE_NAME,
    token: purchaseToken,
  });

  const line = (data.lineItems && data.lineItems[0]) || {};
  const productId = line.productId || null;
  const expiryMs = line.expiryTime ? Date.parse(line.expiryTime) : 0;
  const state = data.subscriptionState || 'SUBSCRIPTION_STATE_UNSPECIFIED';
  const autoRenewing = !!(line.autoRenewingPlan && line.autoRenewingPlan.autoRenewEnabled);

  // Play echoes back whatever obfuscatedAccountId the app set on the billing flow. The Android
  // client MUST set it to the Firebase uid (BillingFlowParams.setObfuscatedAccountId), so we can
  // detect a token being redeemed by a different account.
  const claimedUid =
    (data.externalAccountIdentifiers && data.externalAccountIdentifiers.obfuscatedExternalAccountId) || null;

  return { productId, expiryMs, state, autoRenewing, claimedUid, active: ACTIVE_STATES.has(state) };
}

async function verifyOneTimeProduct(purchaseToken, productId) {
  const ap = await androidPublisher();
  const { data } = await ap.purchases.products.get({
    packageName: PACKAGE_NAME,
    productId,
    token: purchaseToken,
  });
  // purchaseState: 0 = purchased, 1 = cancelled, 2 = pending
  return { purchased: data.purchaseState === 0, obfuscatedAccountId: data.obfuscatedExternalAccountId || null };
}

/**
 * Grants (or revokes) whatever this subscription token currently entitles `uid` to.
 * Idempotent: safe to call from the purchase callback AND from every RTDN about the same token.
 */
async function applySubscription(uid, purchaseToken) {
  const sub = await verifySubscription(purchaseToken);
  if (!sub.productId) throw new Denied('not-found', 'Unknown subscription.');

  if (sub.claimedUid && sub.claimedUid !== uid) {
    throw new Denied('permission-denied', 'That purchase was made by a different account.');
  }

  const plan = planForProduct(sub.productId);
  if (!plan) throw new Denied('invalid-argument', `Unrecognised product ${sub.productId}`);

  await bindToken(purchaseToken, uid, 'subscription', sub.productId);

  const isCollective = plan.id === 'family' || plan.id === 'classroom';

  if (!sub.active) {
    if (isCollective) await expireFamily(familyIdFor(uid));
    else await clearEntitlement(uid);
    return { plan: 'free', reason: sub.state };
  }

  if (isCollective) {
    const r = await createOrUpdateFamily(uid, plan.id, sub.expiryMs);
    return { plan: plan.id, expiryMs: sub.expiryMs, ...r };
  }

  await setEntitlement(uid, {
    plan: plan.id, source: 'individual', expiryMs: sub.expiryMs, autoRenewing: sub.autoRenewing,
  });
  return { plan: plan.id, expiryMs: sub.expiryMs };
}

/** Credits a consumable pack exactly once, no matter how many times the client retries. */
async function applyCreditPack(uid, purchaseToken, productId) {
  const milli = CREDIT_PACKS[productId];
  if (!milli) throw new Denied('invalid-argument', `Unrecognised product ${productId}`);

  const check = await verifyOneTimeProduct(purchaseToken, productId);
  if (!check.purchased) throw new Denied('failed-precondition', 'That purchase is not complete.');
  if (check.obfuscatedAccountId && check.obfuscatedAccountId !== uid) {
    throw new Denied('permission-denied', 'That purchase was made by a different account.');
  }

  // The dedupe gate. `granted` is only ever set once, inside the transaction.
  const res = await db().ref(`play_tokens/${purchaseToken}`).transaction((cur) => {
    if (cur && cur.granted) return;                       // already credited → abort
    if (cur && cur.uid && cur.uid !== uid) return;        // belongs to someone else → abort
    return { uid, kind: 'credits', productId, granted: true, ts: Date.now() };
  });
  if (!res.committed) throw new Denied('already-exists', 'Those credits were already added.');

  await grantExtraCredits(uid, milli);
  return { creditsAdded: milli };
}

/**
 * Real-Time Developer Notification handler.
 *
 * Play tells us when a subscription renews, lapses, is cancelled, refunded or revoked. This is the
 * ONLY thing that keeps entitlement honest over time: without it, a user cancels, Play stops
 * charging them, and the app keeps granting Plus forever because nothing ever revisited the state.
 */
async function handleRtdn(payload) {
  const notif = payload.subscriptionNotification;
  if (!notif) return { ignored: true };

  const token = notif.purchaseToken;
  const uid = await uidForToken(token);
  if (!uid) return { ignored: true, reason: 'unknown token' };

  // We do not trust the notificationType's meaning; we re-ask Google for the truth.
  await applySubscription(uid, token);
  return { ok: true, uid, notificationType: notif.notificationType };
}

module.exports = {
  PACKAGE_NAME, verifySubscription, verifyOneTimeProduct,
  applySubscription, applyCreditPack, handleRtdn, bindToken, uidForToken,
};
