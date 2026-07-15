'use strict';

/**
 * Executable tests for the backend's money + consent logic, run against the real Realtime Database
 * emulator via the Admin SDK (which is exactly how Cloud Functions talk to it).
 *
 *   npm --prefix functions test      (see package.json)
 */

const test = require('node:test');
const assert = require('node:assert');
const admin = require('firebase-admin');

// emulators:exec exports FIREBASE_DATABASE_EMULATOR_HOST for us.
admin.initializeApp({
  projectId: 'demo-lemma',
  databaseURL: 'http://127.0.0.1:9000/?ns=demo-lemma',
});

const { PLANS, MILLI, PRICE, microUsdFor, FREE_MODEL, PAID_MODEL } = require('../lib/plans');
const ent = require('../lib/entitlements');
const social = require('../lib/social');

const db = admin.database();
const ALICE = 'uidalice';
const BOB = 'uidbob';
const MALLORY = 'uidmallory';

const reset = () => db.ref('/').remove();

test.beforeEach(reset);
test.after(async () => { await reset(); await admin.app().delete(); });

// ─────────────────────────────────────────────────────────────────────────────
// Credits
// ─────────────────────────────────────────────────────────────────────────────

test('free plan: 3 solves a day, the 4th is refused', async () => {
  for (let i = 0; i < 3; i++) {
    await ent.chargeCredits(ALICE, PLANS.free, PRICE.solve);
  }
  await assert.rejects(
    () => ent.chargeCredits(ALICE, PLANS.free, PRICE.solve),
    (e) => e.code === 'insufficient-credits'
  );
});

test('free plan: chat is cheaper than a solve, so more of them fit', async () => {
  // 3 credits = 3000 milli; a chat is 250 milli => 12 chats.
  for (let i = 0; i < 12; i++) await ent.chargeCredits(ALICE, PLANS.free, PRICE.chat);
  await assert.rejects(() => ent.chargeCredits(ALICE, PLANS.free, PRICE.chat));
});

test('plus plan: 500 monthly credits, counted per month not per day', async () => {
  const w = await ent.chargeCredits(ALICE, PLANS.plus, PRICE.solve);
  assert.strictEqual(w.remainingAllowance, 499 * MILLI);

  const snap = await db.ref(`wallet/${ALICE}`).get();
  assert.strictEqual(snap.val().dayUsed, 0, 'paid plans must not consume the daily bucket');
  assert.strictEqual(snap.val().periodUsed, PRICE.solve);
});

test('purchased credits are spent only AFTER the plan allowance, and never expire', async () => {
  await ent.grantExtraCredits(ALICE, 10 * MILLI);

  // Burn the whole free daily allowance (3 credits).
  for (let i = 0; i < 3; i++) await ent.chargeCredits(ALICE, PLANS.free, PRICE.solve);
  let snap = await db.ref(`wallet/${ALICE}`).get();
  assert.strictEqual(snap.val().extraCredits, 10 * MILLI, 'allowance must be consumed first');

  // The next solve must come out of the purchased bucket.
  await ent.chargeCredits(ALICE, PLANS.free, PRICE.solve);
  snap = await db.ref(`wallet/${ALICE}`).get();
  assert.strictEqual(snap.val().extraCredits, 9 * MILLI);
});

test('a charge that spans both buckets takes exactly the shortfall from the purchased one', async () => {
  await ent.grantExtraCredits(ALICE, 5 * MILLI);
  // Use 2.5 of 3 free credits via chats (10 x 0.25).
  for (let i = 0; i < 10; i++) await ent.chargeCredits(ALICE, PLANS.free, PRICE.chat);

  // 0.5 credit of allowance left; a solve costs 1.0 => 0.5 must come from extra.
  await ent.chargeCredits(ALICE, PLANS.free, PRICE.solve);
  const w = (await db.ref(`wallet/${ALICE}`).get()).val();
  assert.strictEqual(w.dayUsed, 3 * MILLI, 'allowance fully drained');
  assert.strictEqual(w.extraCredits, 4.5 * MILLI, 'only the shortfall came from purchased credits');
});

test('SECURITY/MONEY: concurrent charges cannot double-spend the last credits', async () => {
  // Free plan = 3 credits. Fire 10 solves at once, as two devices on one account would.
  const attempts = Array.from({ length: 10 }, () =>
    ent.chargeCredits(ALICE, PLANS.free, PRICE.solve).then(() => 'ok', () => 'denied')
  );
  const results = await Promise.all(attempts);
  const ok = results.filter((r) => r === 'ok').length;

  assert.strictEqual(ok, 3, `exactly 3 charges must succeed, got ${ok}`);

  const w = (await db.ref(`wallet/${ALICE}`).get()).val();
  assert.strictEqual(w.dayUsed, 3 * MILLI, 'ledger must not be over-drawn');
});

test('refund returns credits when the model call fails after charging', async () => {
  await ent.chargeCredits(ALICE, PLANS.free, PRICE.solve);
  await ent.refundCredits(ALICE, PLANS.free, PRICE.solve);
  const w = (await db.ref(`wallet/${ALICE}`).get()).val();
  assert.strictEqual(w.dayUsed, 0);
});

test('usage recording captures REAL token cost, not a character estimate', async () => {
  // A realistic solve: the big system prompt dominates the input.
  const micro = await ent.recordUsage(ALICE, PAID_MODEL, 2600, 1500);
  // 2600/1e6*0.30 + 1500/1e6*2.50 = 0.00078 + 0.00375 = 0.00453 USD
  assert.strictEqual(micro, microUsdFor(PAID_MODEL, 2600, 1500));
  assert.strictEqual(micro, 4530, 'about half a US cent per solved problem on the paid model');

  const u = (await db.ref(`usage/${ALICE}`).get()).val();
  assert.strictEqual(u.tokensIn, 2600);
  assert.strictEqual(u.tokensOut, 1500);
});

test('THE FREE TIER IS AFFORDABLE: flash-lite makes a solve ~5x cheaper', async () => {
  const lite = microUsdFor(FREE_MODEL, 2600, 1500);  // 2600*0.10/1e6 + 1500*0.40/1e6
  const paid = microUsdFor(PAID_MODEL, 2600, 1500);

  assert.strictEqual(lite, 860, 'a free-tier solve costs about 0.086 US cents');
  assert.ok(paid / lite > 5, `paid model must be >5x pricier (was ${(paid / lite).toFixed(1)}x)`);

  // The number that decides whether a free tier is viable at all: a free user who maxes out
  // 3 solves a day, every single day, for a month.
  const worstCaseMonthly = (lite * 3 * 30) / 1e6; // micro-USD -> USD
  assert.ok(worstCaseMonthly < 0.10,
    `a maxed-out free user must cost under 10 cents/month (was $${worstCaseMonthly.toFixed(3)})`);
});

test('the plan decides the model — free users cannot get the expensive one', async () => {
  assert.strictEqual(PLANS.free.model, FREE_MODEL);
  assert.strictEqual(PLANS.plus.model, PAID_MODEL);
  assert.strictEqual(PLANS.family.model, PAID_MODEL);
  assert.strictEqual(PLANS.free.smartTier, false, 'free must not be able to name a pricier model');
});

test('cost is tracked per model, so each tier can be priced from real data', async () => {
  await ent.recordUsage(BOB, FREE_MODEL, 1000, 500);
  await ent.recordUsage(BOB, PAID_MODEL, 1000, 500);
  const month = new Date().toISOString().slice(0, 7);
  const byModel = (await db.ref(`usage_totals/${month}/byModel`).get()).val();
  const key = (m) => m.replace(/[.#$/[\]]/g, '_');
  assert.ok(byModel[key(FREE_MODEL)].microUsd > 0);
  assert.ok(byModel[key(PAID_MODEL)].microUsd > byModel[key(FREE_MODEL)].microUsd);
});

test('entitlement resolution: no row => free; expired => free; live => the plan', async () => {
  assert.strictEqual((await ent.resolvePlan(ALICE)).id, 'free');

  await ent.setEntitlement(ALICE, { plan: 'plus', source: 'individual', expiryMs: Date.now() - 1000 });
  assert.strictEqual((await ent.resolvePlan(ALICE)).id, 'free', 'expired subscription must not grant Plus');

  await ent.setEntitlement(ALICE, { plan: 'plus', source: 'individual', expiryMs: Date.now() + 86400e3 });
  assert.strictEqual((await ent.resolvePlan(ALICE)).id, 'plus');
});

test('a family seat grants the seat-holder their OWN quota, not a shared pool', async () => {
  const expiry = Date.now() + 86400e3;
  await ent.setEntitlement(ALICE, { plan: 'family', source: 'family', familyId: 'f1', expiryMs: expiry });
  await ent.setEntitlement(BOB, { plan: 'family', source: 'family', familyId: 'f1', expiryMs: expiry });

  await ent.chargeCredits(ALICE, PLANS.family, PRICE.solve);

  const bobWallet = (await db.ref(`wallet/${BOB}`).get()).val();
  assert.strictEqual(bobWallet, null, "one member's spend must not touch another's wallet");
});

// ─────────────────────────────────────────────────────────────────────────────
// Usernames
// ─────────────────────────────────────────────────────────────────────────────

test('username claim is first-come and cannot be stolen', async () => {
  await social.claimUsername(ALICE, 'Alice');
  assert.strictEqual((await db.ref('usernames/alice').get()).val(), ALICE);

  await assert.rejects(() => social.claimUsername(MALLORY, 'alice'), (e) => e.code === 'already-exists');
});

test('re-claiming your own username is a no-op, not an error', async () => {
  await social.claimUsername(ALICE, 'Alice');
  await social.claimUsername(ALICE, 'Alice');
  assert.strictEqual((await db.ref('usernames/alice').get()).val(), ALICE);
});

test('case-folding: Alice and alice are the same name', async () => {
  await social.claimUsername(ALICE, 'Alice');
  await assert.rejects(() => social.claimUsername(BOB, 'ALICE'));
});

// ─────────────────────────────────────────────────────────────────────────────
// Friends + groups: consent
// ─────────────────────────────────────────────────────────────────────────────

const seedUser = (uid, name) => db.ref(`users_public/${uid}`).set({ username: name, usernameLower: name.toLowerCase() });
const befriend = (a, b) => db.ref().update({ [`friends/${a}/${b}`]: 'x', [`friends/${b}/${a}`]: 'y' });

test('accepting a friend request requires a request to actually exist', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(MALLORY, 'Mallory');
  await assert.rejects(
    () => social.acceptFriendRequest(ALICE, MALLORY),
    (e) => e.code === 'failed-precondition'
  );
});

test('accepting writes both sides of the edge and clears the request', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(BOB, 'Bob');
  await db.ref(`friend_requests/${ALICE}/${BOB}`).set('Bob');

  await social.acceptFriendRequest(ALICE, BOB);

  assert.strictEqual((await db.ref(`friends/${ALICE}/${BOB}`).get()).val(), 'Bob');
  assert.strictEqual((await db.ref(`friends/${BOB}/${ALICE}`).get()).val(), 'Alice');
  assert.strictEqual((await db.ref(`friend_requests/${ALICE}/${BOB}`).get()).exists(), false);
});

test('you cannot accept a request from someone you blocked', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(MALLORY, 'Mallory');
  await db.ref(`friend_requests/${ALICE}/${MALLORY}`).set('Mallory');
  await db.ref(`blocked/${ALICE}/${MALLORY}`).set('Mallory');

  await assert.rejects(() => social.acceptFriendRequest(ALICE, MALLORY), (e) => e.code === 'permission-denied');
});

test('blocking severs the friendship and both pending requests in one write', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(MALLORY, 'Mallory');
  await befriend(ALICE, MALLORY);
  await db.ref(`friend_requests/${MALLORY}/${ALICE}`).set('Alice');

  await social.blockUser(ALICE, MALLORY);

  assert.strictEqual((await db.ref(`friends/${ALICE}/${MALLORY}`).get()).exists(), false);
  assert.strictEqual((await db.ref(`friends/${MALLORY}/${ALICE}`).get()).exists(), false);
  assert.strictEqual((await db.ref(`friend_requests/${MALLORY}/${ALICE}`).get()).exists(), false);
  assert.strictEqual((await db.ref(`blocked/${ALICE}/${MALLORY}`).get()).val(), 'Mallory');
});

test('SECURITY: you cannot force a stranger into a group', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(MALLORY, 'Mallory');
  await assert.rejects(
    () => social.createGroup(ALICE, 'Study', [MALLORY]),
    (e) => e.code === 'permission-denied'
  );
});

test('a group of friends is created, indexed for every member', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(BOB, 'Bob');
  await befriend(ALICE, BOB);

  const { groupId, memberCount } = await social.createGroup(ALICE, 'Study group', [BOB]);
  assert.strictEqual(memberCount, 2);
  assert.strictEqual((await db.ref(`groups/${groupId}/members/${BOB}`).get()).exists(), true);
  assert.strictEqual((await db.ref(`user_groups/${BOB}/${groupId}`).get()).val(), 'Study group');
});

test('a group of one is rejected, and so is a group of 41', async () => {
  await seedUser(ALICE, 'Alice');
  await assert.rejects(() => social.createGroup(ALICE, 'Solo', []), (e) => e.code === 'invalid-argument');

  const many = [];
  for (let i = 0; i < 40; i++) { const u = `u${i}`; many.push(u); await seedUser(u, `u${i}`); await befriend(ALICE, u); }
  await assert.rejects(() => social.createGroup(ALICE, 'Huge', many), (e) => e.code === 'invalid-argument');
});

test('SECURITY: the 40-member cap is a HARD cap when adding, counted server-side', async () => {
  await seedUser(ALICE, 'Alice');
  const members = {};
  for (let i = 0; i < 40; i++) members[`u${i}`] = `u${i}`;
  members[ALICE] = 'Alice';
  delete members.u39; // keep exactly 40 including Alice
  await db.ref('groups/g1').set({ meta: { name: 'g', owner: ALICE, createdAt: 1, memberCount: 40 }, members });

  await seedUser(BOB, 'Bob'); await befriend(ALICE, BOB);
  await assert.rejects(() => social.addToGroup(ALICE, 'g1', BOB), (e) => e.code === 'resource-exhausted');
});

test('you cannot add someone to a group you are not in', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(BOB, 'Bob'); await seedUser(MALLORY, 'Mallory');
  await befriend(MALLORY, BOB);
  await db.ref('groups/g1').set({ meta: { name: 'g', owner: ALICE, createdAt: 1, memberCount: 1 }, members: { [ALICE]: 'Alice' } });

  await assert.rejects(() => social.addToGroup(MALLORY, 'g1', BOB), (e) => e.code === 'permission-denied');
});

test('leaving recomputes memberCount from the truth, and the last member deletes the group', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(BOB, 'Bob'); await befriend(ALICE, BOB);
  const { groupId } = await social.createGroup(ALICE, 'G', [BOB]);

  await social.leaveGroup(BOB, groupId);
  assert.strictEqual((await db.ref(`groups/${groupId}/meta/memberCount`).get()).val(), 1);

  await social.leaveGroup(ALICE, groupId);
  assert.strictEqual((await db.ref(`groups/${groupId}`).get()).exists(), false, 'empty group is removed');
});

test('two members leaving at once cannot corrupt the counter (no read-modify-write)', async () => {
  await seedUser(ALICE, 'Alice'); await seedUser(BOB, 'Bob'); await seedUser(MALLORY, 'Mallory');
  await befriend(ALICE, BOB); await befriend(ALICE, MALLORY);
  const { groupId } = await social.createGroup(ALICE, 'G', [BOB, MALLORY]);

  await Promise.all([social.leaveGroup(BOB, groupId), social.leaveGroup(MALLORY, groupId)]);

  const members = (await db.ref(`groups/${groupId}/members`).get()).val() || {};
  assert.deepStrictEqual(Object.keys(members), [ALICE]);
});
