'use strict';

const admin = require('firebase-admin');

const db = () => admin.database();

const MAX_GROUP_MEMBERS = 40;
const MIN_GROUP_MEMBERS = 2;

/**
 * Sanitises a username to a database key. MUST stay identical to FirebaseManager.sanitizeUser()
 * in the Android app, or the claim table and the data node point at different places.
 */
function sanitizeUser(name) {
  if (name == null) return 'guestuser';
  return String(name).replace(/[.#$[\]]/g, '_').toLowerCase();
}

class Denied extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

// ─────────────────────────────────────────────────────────────────────────────
// Usernames
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Claims a username for `uid`, atomically and first-come.
 *
 * `usernames/` is server-only in the rules precisely because of this: when clients could write it,
 * anyone could read the public directory, spot a user who had not yet claimed their own name, claim
 * it, and thereby take ownership of that user's entire `users/{name}` history — locking the real
 * owner out permanently. Only this function writes there now, and it only ever writes the caller's
 * own uid.
 */
async function claimUsername(uid, username) {
  const key = sanitizeUser(username);
  const ref = db().ref(`usernames/${key}`);

  const res = await ref.transaction((current) => {
    if (current === null) return uid;   // unclaimed → take it
    if (current === uid) return;        // already ours → no-op (abort, but not an error)
    return;                             // someone else's → abort
  });

  const holder = res.snapshot.val();
  if (holder !== uid) throw new Denied('already-exists', 'That username is taken.');
  return { username, key };
}

async function releaseUsername(uid, username) {
  const key = sanitizeUser(username);
  const ref = db().ref(`usernames/${key}`);
  const snap = await ref.get();
  if (snap.val() === uid) await ref.remove();
}

// ─────────────────────────────────────────────────────────────────────────────
// Friends — the consent primitive
// ─────────────────────────────────────────────────────────────────────────────

async function usernameOf(uid) {
  const snap = await db().ref(`users_public/${uid}/username`).get();
  return snap.val() || '';
}

async function areFriends(a, b) {
  const snap = await db().ref(`friends/${a}/${b}`).get();
  return snap.exists();
}

/**
 * Accepts a pending friend request. `friends/` is server-only, so this is the ONLY way an edge is
 * created — which is what stops a stranger writing themselves into your friend list.
 */
async function acceptFriendRequest(uid, fromUid) {
  const reqSnap = await db().ref(`friend_requests/${uid}/${fromUid}`).get();
  if (!reqSnap.exists()) throw new Denied('failed-precondition', 'No pending request from that user.');

  const blockedSnap = await db().ref(`blocked/${uid}/${fromUid}`).get();
  if (blockedSnap.exists()) throw new Denied('permission-denied', 'You blocked this user.');

  const [myName, theirName] = await Promise.all([usernameOf(uid), usernameOf(fromUid)]);

  await db().ref().update({
    [`friends/${uid}/${fromUid}`]: theirName,
    [`friends/${fromUid}/${uid}`]: myName,
    [`friend_requests/${uid}/${fromUid}`]: null,
  });
  return { ok: true };
}

async function removeFriend(uid, peerUid) {
  await db().ref().update({
    [`friends/${uid}/${peerUid}`]: null,
    [`friends/${peerUid}/${uid}`]: null,
  });
  return { ok: true };
}

/** Blocking must also sever the friendship and any request, in both directions, in one write. */
async function blockUser(uid, peerUid) {
  const theirName = await usernameOf(peerUid);
  await db().ref().update({
    [`blocked/${uid}/${peerUid}`]: theirName || '',
    [`friends/${uid}/${peerUid}`]: null,
    [`friends/${peerUid}/${uid}`]: null,
    [`friend_requests/${uid}/${peerUid}`]: null,
    [`friend_requests/${peerUid}/${uid}`]: null,
  });
  return { ok: true };
}

// ─────────────────────────────────────────────────────────────────────────────
// Groups — consent + a HARD member cap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * You may only put someone in a group if you are already mutual friends with them, and friendship
 * required their explicit accept. That is the consent chain. It is enforced here because Realtime
 * Database rules cannot express "is X a friend of the caller" for an arbitrary set of X.
 */
async function assertAllMutualFriends(uid, memberUids) {
  const checks = await Promise.all(memberUids.map((m) => areFriends(uid, m)));
  const stranger = memberUids.find((_, i) => !checks[i]);
  if (stranger) throw new Denied('permission-denied', 'You can only add your friends to a group.');
}

async function createGroup(uid, name, memberUids) {
  const unique = [...new Set(memberUids.filter((m) => m && m !== uid))];
  const total = unique.length + 1;

  // A HARD cap, not a guard-rail: we can count here, and rules cannot.
  if (total < MIN_GROUP_MEMBERS || total > MAX_GROUP_MEMBERS) {
    throw new Denied('invalid-argument', `A group must have ${MIN_GROUP_MEMBERS}-${MAX_GROUP_MEMBERS} members.`);
  }
  await assertAllMutualFriends(uid, unique);

  const safeName = String(name || 'Group').slice(0, 60);
  const gid = db().ref('groups').push().key;

  const members = { [uid]: await usernameOf(uid) };
  for (const m of unique) members[m] = await usernameOf(m);

  const updates = {
    [`groups/${gid}/meta`]: {
      name: safeName, owner: uid, createdAt: admin.database.ServerValue.TIMESTAMP, memberCount: total,
    },
    [`groups/${gid}/members`]: members,
  };
  for (const m of Object.keys(members)) updates[`user_groups/${m}/${gid}`] = safeName;

  await db().ref().update(updates);
  return { groupId: gid, name: safeName, memberCount: total };
}

async function addToGroup(uid, gid, newMemberUid) {
  const membersSnap = await db().ref(`groups/${gid}/members`).get();
  if (!membersSnap.exists()) throw new Denied('not-found', 'No such group.');
  const members = membersSnap.val() || {};

  if (!members[uid]) throw new Denied('permission-denied', 'You are not in that group.');
  if (members[newMemberUid]) return { ok: true, alreadyMember: true };

  // Counted server-side. This is the cap the rules could only approximate.
  if (Object.keys(members).length >= MAX_GROUP_MEMBERS) {
    throw new Denied('resource-exhausted', `A group can hold at most ${MAX_GROUP_MEMBERS} people.`);
  }
  await assertAllMutualFriends(uid, [newMemberUid]);

  const nameSnap = await db().ref(`groups/${gid}/meta/name`).get();
  const memberName = await usernameOf(newMemberUid);

  await db().ref().update({
    [`groups/${gid}/members/${newMemberUid}`]: memberName,
    [`groups/${gid}/meta/memberCount`]: Object.keys(members).length + 1,
    [`user_groups/${newMemberUid}/${gid}`]: nameSnap.val() || 'Group',
  });
  return { ok: true };
}

/** Leaving always succeeds. The counter is derived from the truth, not read-modify-written. */
async function leaveGroup(uid, gid) {
  const membersSnap = await db().ref(`groups/${gid}/members`).get();
  const members = membersSnap.val() || {};
  if (!members[uid]) return { ok: true };

  delete members[uid];
  const remaining = Object.keys(members).length;

  // A multi-path update may NOT contain both a path and one of its descendants — Firebase rejects
  // the whole write. So the "last member leaves" case deletes the group wholesale, and never also
  // names a child of it.
  const updates = remaining === 0
    ? {
        [`groups/${gid}`]: null,
        [`gm/${gid}`]: null,
        [`user_groups/${uid}/${gid}`]: null,
      }
    : {
        [`groups/${gid}/members/${uid}`]: null,
        [`groups/${gid}/meta/memberCount`]: remaining,
        [`user_groups/${uid}/${gid}`]: null,
      };

  await db().ref().update(updates);
  return { ok: true, remaining };
}

module.exports = {
  Denied, sanitizeUser, MAX_GROUP_MEMBERS, MIN_GROUP_MEMBERS,
  claimUsername, releaseUsername,
  acceptFriendRequest, removeFriend, blockUser, areFriends, usernameOf,
  createGroup, addToGroup, leaveGroup,
};
