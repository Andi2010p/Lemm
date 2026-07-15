'use strict';

const admin = require('firebase-admin');
const { PLANS } = require('./plans');
const { Denied, usernameOf, sanitizeUser } = require('./social');
const { setEntitlement, clearEntitlement } = require('./entitlements');

const db = () => admin.database();

/**
 * Collective subscriptions — the Duolingo Family Plan shape.
 *
 * Google Play has no native "share this subscription with 5 people" primitive (Apple's Family
 * Sharing does; Play's Family Library covers apps and one-time purchases, not subscription seats).
 * So the seats are OURS to model: ONE person pays, and the plan grants N seats which the payer
 * hands out to real accounts. Each seat-holder keeps their own uid, their own progress, and their
 * own credit quota. Nothing is pooled except the payment.
 *
 * That is also the answer to "what if two of them use the app at the same time, and one of them
 * isn't subscribed": entitlement is resolved per-uid from `entitlements/{uid}`. A seat-holder gets
 * Plus-grade limits; anyone without a seat is simply on Free, in the same group chat, at the same
 * moment. Nothing about sharing a chat shares a subscription.
 *
 *   families/{familyId} = { owner, plan, expiryMs, seatLimit, seats: { uid: username } }
 *   family_invites/{inviteeUid}/{familyId} = { fromName, ts }
 *   family_of/{uid} = familyId
 */

const familyIdFor = (ownerUid) => ownerUid; // one active family per payer; simple and sufficient

async function createOrUpdateFamily(ownerUid, planId, expiryMs) {
  const plan = PLANS[planId];
  const familyId = familyIdFor(ownerUid);
  const ownerName = await usernameOf(ownerUid);

  const ref = db().ref(`families/${familyId}`);
  const existing = (await ref.get()).val();

  const seats = (existing && existing.seats) || { [ownerUid]: ownerName };
  seats[ownerUid] = ownerName; // the payer always holds a seat

  await ref.set({
    owner: ownerUid,
    plan: planId,
    seatLimit: plan.seats,
    expiryMs,
    seats,
    updatedMs: Date.now(),
  });

  // Re-stamp every current seat-holder's entitlement with the new expiry.
  await Promise.all(
    Object.keys(seats).map((uid) =>
      Promise.all([
        setEntitlement(uid, { plan: planId, source: 'family', familyId, expiryMs, autoRenewing: true }),
        db().ref(`family_of/${uid}`).set(familyId),
      ])
    )
  );
  return { familyId, seatLimit: plan.seats, seats: Object.keys(seats).length };
}

/** The owner invites someone by username. The invitee must ACCEPT — no silent enrolment. */
async function inviteToFamily(ownerUid, username) {
  const familyId = familyIdFor(ownerUid);
  const famSnap = await db().ref(`families/${familyId}`).get();
  const fam = famSnap.val();
  if (!fam) throw new Denied('failed-precondition', 'You do not have a family plan.');
  if (fam.owner !== ownerUid) throw new Denied('permission-denied', 'Only the plan owner can invite.');
  if (fam.expiryMs < Date.now()) throw new Denied('failed-precondition', 'That plan has expired.');

  const uidSnap = await db().ref(`usernames/${sanitizeUser(username)}`).get();
  const inviteeUid = uidSnap.val();
  if (!inviteeUid) throw new Denied('not-found', 'No user with that username.');
  if (fam.seats && fam.seats[inviteeUid]) return { ok: true, alreadyMember: true };

  const used = Object.keys(fam.seats || {}).length;
  if (used >= fam.seatLimit) throw new Denied('resource-exhausted', 'All seats are taken.');

  await db().ref(`family_invites/${inviteeUid}/${familyId}`).set({
    fromName: await usernameOf(ownerUid),
    ts: admin.database.ServerValue.TIMESTAMP,
  });
  return { ok: true };
}

/** The invitee accepts. Seats are taken atomically so a race cannot oversubscribe the plan. */
async function acceptFamilyInvite(uid, familyId) {
  const inviteSnap = await db().ref(`family_invites/${uid}/${familyId}`).get();
  if (!inviteSnap.exists()) throw new Denied('not-found', 'No such invitation.');

  const myName = await usernameOf(uid);

  const res = await db().ref(`families/${familyId}`).transaction((fam) => {
    if (!fam) return;                                   // gone
    if (fam.expiryMs < Date.now()) return;              // expired
    fam.seats = fam.seats || {};
    if (fam.seats[uid]) return fam;                     // idempotent
    if (Object.keys(fam.seats).length >= fam.seatLimit) return; // full → abort
    fam.seats[uid] = myName;
    return fam;
  });

  if (!res.committed) throw new Denied('resource-exhausted', 'That plan is full or expired.');

  const fam = res.snapshot.val();
  await Promise.all([
    setEntitlement(uid, { plan: fam.plan, source: 'family', familyId, expiryMs: fam.expiryMs, autoRenewing: true }),
    db().ref(`family_of/${uid}`).set(familyId),
    db().ref(`family_invites/${uid}/${familyId}`).remove(),
  ]);
  return { ok: true, plan: fam.plan };
}

/** Owner removes a seat, or a member leaves. Either way the entitlement dies with the seat. */
async function removeFromFamily(callerUid, familyId, targetUid) {
  const famSnap = await db().ref(`families/${familyId}`).get();
  const fam = famSnap.val();
  if (!fam) throw new Denied('not-found', 'No such family plan.');

  const isOwner = fam.owner === callerUid;
  if (!isOwner && callerUid !== targetUid) {
    throw new Denied('permission-denied', 'Only the owner can remove other members.');
  }
  if (targetUid === fam.owner) throw new Denied('failed-precondition', 'The owner cannot leave their own plan.');

  await db().ref().update({
    [`families/${familyId}/seats/${targetUid}`]: null,
    [`family_of/${targetUid}`]: null,
  });
  await clearEntitlement(targetUid);
  return { ok: true };
}

/** Called when the subscription lapses. Everyone silently drops to Free — no data is destroyed. */
async function expireFamily(familyId) {
  const famSnap = await db().ref(`families/${familyId}`).get();
  const fam = famSnap.val();
  if (!fam) return;
  await Promise.all(Object.keys(fam.seats || {}).map((uid) => clearEntitlement(uid)));
  await db().ref(`families/${familyId}/expiryMs`).set(0);
}

module.exports = { familyIdFor, createOrUpdateFamily, inviteToFamily, acceptFamilyInvite, removeFromFamily, expireFamily };
