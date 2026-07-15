'use strict';

/**
 * Lemma backend.
 *
 * Every function here enforces THREE things before doing anything:
 *   1. App Check   — the caller is a genuine, unmodified Lemma install (Play Integrity).
 *   2. Auth        — there is a real signed-in Firebase user.
 *   3. Entitlement — resolved server-side from `entitlements/{uid}`, never from the client.
 *
 * The Android app cannot grant itself a plan, mint credits, befriend a stranger, or read an API key,
 * because it no longer has the power to write any of those places. The database rules make those
 * nodes server-only; the Admin SDK used here bypasses rules by design.
 */

const { setGlobalOptions } = require('firebase-functions/v2');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onMessagePublished } = require('firebase-functions/v2/pubsub');
const { defineSecret } = require('firebase-functions/params');
const logger = require('firebase-functions/logger');
const admin = require('firebase-admin');

admin.initializeApp();

setGlobalOptions({ region: 'europe-west1', maxInstances: 20 });

const GEMINI_API_KEY = defineSecret('GEMINI_API_KEY');
const MAIL_USER = defineSecret('MAIL_USER');
const MAIL_APP_PASSWORD = defineSecret('MAIL_APP_PASSWORD');

const { PRICE, PLANS, MILLI } = require('./lib/plans');
const ent = require('./lib/entitlements');
const ai = require('./lib/ai');
const social = require('./lib/social');
const family = require('./lib/family');
const play = require('./lib/play');
const otp = require('./lib/otp');

/** Base options for anything a signed-in app calls. */
const CALLABLE = { enforceAppCheck: true };

/** Turns our internal Denied errors into the callable error codes the Android SDK understands. */
function wrap(handler) {
  return async (req) => {
    if (!req.auth || !req.auth.uid) throw new HttpsError('unauthenticated', 'Sign in first.');
    try {
      return await handler(req.auth.uid, req.data || {}, req);
    } catch (e) {
      if (e && e.code && typeof e.code === 'string' && e.message) {
        // Our Denied codes are already valid callable codes.
        throw new HttpsError(e.code === 'insufficient-credits' ? 'resource-exhausted' : e.code, e.message);
      }
      logger.error('unhandled', e);
      throw new HttpsError('internal', 'Something went wrong.');
    }
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// AI — the whole reason the Gemini key can leave the APK
// ─────────────────────────────────────────────────────────────────────────────

const KIND_PRICE = { solve: PRICE.solve, chat: PRICE.chat, scan: PRICE.scan };

exports.askAI = onCall(
  { ...CALLABLE, secrets: [GEMINI_API_KEY], timeoutSeconds: 300, memory: '512MiB' },
  wrap(async (uid, data) => {
    const kind = KIND_PRICE[data.kind] ? data.kind : 'chat';
    const price = KIND_PRICE[kind];

    const prompt = String(data.prompt || '');
    if (!prompt) throw new HttpsError('invalid-argument', 'Empty prompt.');
    if (prompt.length > 200_000) throw new HttpsError('invalid-argument', 'Prompt too large.');

    const images = Array.isArray(data.images) ? data.images.slice(0, 4) : [];

    const plan = await ent.resolvePlan(uid);

    // The model is decided by the PLAN, not the client. Free runs on flash-lite (which is what makes
    // a free tier affordable at all); paid plans run on the smarter model. A paid user with model
    // choice may name one, but only from the server's allow-list.
    const model = (plan.smartTier && data.model) ? data.model : plan.model;

    // Charge BEFORE the call, so a burst of parallel requests can't each see "credits available".
    const balance = await ent.chargeCredits(uid, plan, price);

    let out;
    try {
      out = await ai.generate({ apiKey: GEMINI_API_KEY.value(), prompt, images, model });
    } catch (e) {
      await ent.refundCredits(uid, plan, price); // our key failed, not the user's fault
      logger.error('gemini call failed', { uid, status: e.status });
      throw new HttpsError(e.retryable ? 'unavailable' : 'internal', 'The AI is unavailable right now.');
    }

    // A safety block returns HTTP 200 with no text. That is not an answer — refund it.
    if (!out.text || !out.text.trim()) {
      await ent.refundCredits(uid, plan, price);
      throw new HttpsError('failed-precondition', 'The AI could not answer that. You were not charged.');
    }

    // Real cost, recorded per model for the developer. Never shown to, nor billed to, the user.
    await ent.recordUsage(uid, out.model, out.promptTokens, out.outputTokens);

    return {
      text: out.text,
      plan: plan.id,
      creditsLeft: Math.round((balance.remainingAllowance + balance.extraCredits) / MILLI),
    };
  })
);

/** Cheap, un-metered: lets the app render the paywall + balance without guessing. */
exports.getMyStatus = onCall(CALLABLE, wrap(async (uid) => {
  const plan = await ent.resolvePlan(uid);
  const [walletSnap, entSnap, famSnap] = await Promise.all([
    admin.database().ref(`wallet/${uid}`).get(),
    admin.database().ref(`entitlements/${uid}`).get(),
    admin.database().ref(`family_of/${uid}`).get(),
  ]);
  const w = walletSnap.val() || {};
  const cap = plan.dailyCredits || plan.monthlyCredits;
  const used = plan.dailyCredits ? (w.dayUsed || 0) : (w.periodUsed || 0);

  return {
    plan: plan.id,
    perDay: !!plan.dailyCredits,
    allowanceLeft: Math.max(0, cap - used) / MILLI,
    allowanceTotal: cap / MILLI,
    extraCredits: (w.extraCredits || 0) / MILLI,
    modelChoice: plan.modelChoice,
    expiryMs: (entSnap.val() && entSnap.val().expiryMs) || 0,
    familyId: famSnap.val() || null,
  };
}));

// ─────────────────────────────────────────────────────────────────────────────
// Email OTP — the Gmail app password never ships again
// ─────────────────────────────────────────────────────────────────────────────

exports.sendOtp = onCall(
  { enforceAppCheck: true, secrets: [MAIL_USER, MAIL_APP_PASSWORD] },
  // Deliberately NOT wrapped: registration happens before the user is signed in.
  async (req) => {
    const email = String((req.data && req.data.email) || '').trim();
    if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
      throw new HttpsError('invalid-argument', 'Enter a valid email address.');
    }
    try {
      return await otp.sendOtp({ email, mailUser: MAIL_USER.value(), mailPass: MAIL_APP_PASSWORD.value() });
    } catch (e) {
      if (e.code) throw new HttpsError(e.code, e.message);
      logger.error('otp send failed', e);
      throw new HttpsError('internal', 'Could not send the email.');
    }
  }
);

exports.verifyOtp = onCall({ enforceAppCheck: true }, async (req) => {
  const { email, code } = req.data || {};
  try {
    return await otp.verifyOtp({ email, code });
  } catch (e) {
    if (e.code) throw new HttpsError(e.code, e.message);
    throw new HttpsError('internal', 'Could not verify the code.');
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// Identity + social graph (consent lives here, because rules cannot express it)
// ─────────────────────────────────────────────────────────────────────────────

exports.claimUsername = onCall(CALLABLE, wrap((uid, d) => social.claimUsername(uid, String(d.username || ''))));
exports.acceptFriendRequest = onCall(CALLABLE, wrap((uid, d) => social.acceptFriendRequest(uid, String(d.fromUid || ''))));
exports.removeFriend = onCall(CALLABLE, wrap((uid, d) => social.removeFriend(uid, String(d.peerUid || ''))));
exports.blockUser = onCall(CALLABLE, wrap((uid, d) => social.blockUser(uid, String(d.peerUid || ''))));

exports.createGroup = onCall(CALLABLE, wrap((uid, d) =>
  social.createGroup(uid, String(d.name || ''), Array.isArray(d.memberUids) ? d.memberUids : [])));
exports.addToGroup = onCall(CALLABLE, wrap((uid, d) => social.addToGroup(uid, String(d.groupId || ''), String(d.memberUid || ''))));
exports.leaveGroup = onCall(CALLABLE, wrap((uid, d) => social.leaveGroup(uid, String(d.groupId || ''))));

// ─────────────────────────────────────────────────────────────────────────────
// Money
// ─────────────────────────────────────────────────────────────────────────────

/** Called right after Play reports a purchase. Idempotent — RTDN will confirm it again anyway. */
exports.verifyPurchase = onCall(CALLABLE, wrap(async (uid, d) => {
  const token = String(d.purchaseToken || '');
  if (!token) throw new HttpsError('invalid-argument', 'Missing purchase token.');

  if (d.kind === 'credits') return play.applyCreditPack(uid, token, String(d.productId || ''));
  return play.applySubscription(uid, token);
}));

exports.inviteToFamily = onCall(CALLABLE, wrap((uid, d) => family.inviteToFamily(uid, String(d.username || ''))));
exports.acceptFamilyInvite = onCall(CALLABLE, wrap((uid, d) => family.acceptFamilyInvite(uid, String(d.familyId || ''))));
exports.removeFromFamily = onCall(CALLABLE, wrap((uid, d) =>
  family.removeFromFamily(uid, String(d.familyId || ''), String(d.targetUid || ''))));

/**
 * Play → Pub/Sub → here. Create the topic and point Play Console at it:
 *   Play Console → Monetise → Monetisation setup → Real-time developer notifications
 * Without this, a cancelled subscription keeps working forever.
 */
exports.playRtdn = onMessagePublished({ topic: 'play-rtdn', region: 'europe-west1' }, async (event) => {
  let payload;
  try {
    payload = event.data.message.json;
  } catch {
    logger.warn('RTDN: unparseable message');
    return;
  }
  try {
    const r = await play.handleRtdn(payload);
    logger.info('RTDN handled', r);
  } catch (e) {
    logger.error('RTDN failed', e);
    throw e; // let Pub/Sub retry
  }
});
