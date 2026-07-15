'use strict';

/**
 * Executable security tests for docs/database.rules.json, run against the real Firebase
 * Realtime Database emulator.
 *
 * These assert the properties we CLAIM the rules have. Anything that fails here is a hole that
 * exists in production, not a theory.
 *
 *   npm test        (see package.json — wraps this in `firebase emulators:exec`)
 */

const path = require('path');
const test = require('node:test');
const assert = require('node:assert');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { ref, set, get, update, remove } = require('firebase/database');
const { loadRules } = require('./strip-comments');

const RULES_PATH = path.resolve(__dirname, '../../docs/database.rules.json');

// Firebase uids are opaque; using readable ones keeps the chatId maths legible.
const ALICE = 'uidalice';
const BOB = 'uidbob';
const MALLORY = 'uidmallory';

/** chatId is the two uids sorted and joined — the same rule the app uses. */
const chatId = (a, b) => (a < b ? `${a}_${b}` : `${b}_${a}`);

let testEnv;

test.before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-lemma',
    database: {
      rules: loadRules(RULES_PATH),
      host: '127.0.0.1',
      port: 9000,
    },
  });
});

test.after(async () => {
  if (testEnv) await testEnv.cleanup();
});

test.beforeEach(async () => {
  await testEnv.clearDatabase();
});

/** Writes seed data with rules DISABLED, the way an admin/backend would. */
const seed = (fn) => testEnv.withSecurityRulesDisabled((ctx) => fn(ctx.database()));

const as = (uid) => testEnv.authenticatedContext(uid).database();
const anon = () => testEnv.unauthenticatedContext().database();

// ─────────────────────────────────────────────────────────────────────────────
// users_info — holds the user's OWN AI API keys. Must never leak.
// ─────────────────────────────────────────────────────────────────────────────

test('users_info: owner can read and write their own node', async () => {
  await assertSucceeds(set(ref(as(ALICE), `users_info/${ALICE}/username`), 'alice'));
  await assertSucceeds(get(ref(as(ALICE), `users_info/${ALICE}`)));
});

test('users_info: a peer CANNOT read someone else\'s API keys', async () => {
  await seed((db) => set(ref(db, `users_info/${ALICE}/ai_keys`), 'AIzaSy-SECRET'));
  await assertFails(get(ref(as(BOB), `users_info/${ALICE}/ai_keys`)));
  await assertFails(get(ref(as(BOB), `users_info/${ALICE}`)));
});

test('users_info: a peer cannot write to it either', async () => {
  await assertFails(set(ref(as(BOB), `users_info/${ALICE}/is_pro`), true));
});

// ─────────────────────────────────────────────────────────────────────────────
// users_public — the searchable directory. Readable by all, writable only by owner.
// ─────────────────────────────────────────────────────────────────────────────

test('users_public: any signed-in user may read the directory (this is how search works)', async () => {
  await seed((db) => set(ref(db, `users_public/${ALICE}`), { username: 'Alice', usernameLower: 'alice' }));
  await assertSucceeds(get(ref(as(BOB), 'users_public')));
});

test('users_public: unauthenticated users get nothing', async () => {
  await assertFails(get(ref(anon(), 'users_public')));
});

test('users_public: cannot write another user\'s directory row', async () => {
  await assertFails(set(ref(as(MALLORY), `users_public/${ALICE}`), { username: 'x', usernameLower: 'x' }));
});

test('users_public: unknown fields are rejected', async () => {
  await assertFails(set(ref(as(ALICE), `users_public/${ALICE}`), {
    username: 'Alice', usernameLower: 'alice', isAdmin: true,
  }));
});

test('users_public: the full profile (role, grade, school) is accepted', async () => {
  // If the rules reject any of these, the profile write fails silently and classmate
  // discovery never works. This is exactly the kind of thing $other:false breaks.
  await assertSucceeds(set(ref(as(ALICE), `users_public/${ALICE}`), {
    username: 'Alice', usernameLower: 'alice',
    displayName: 'Alice A.', role: 'student', grade: 9,
    school: 'Yerevan School 42', schoolLower: 'yerevan school 42',
  }));
});

test('users_public: a teacher profile is accepted (grade 0 = not applicable)', async () => {
  await assertSucceeds(set(ref(as(ALICE), `users_public/${ALICE}`), {
    username: 'Alice', usernameLower: 'alice',
    displayName: '', role: 'teacher', grade: 0, school: '', schoolLower: '',
  }));
});

test('users_public: a bogus role or an out-of-range grade is rejected', async () => {
  const base = { username: 'Alice', usernameLower: 'alice', displayName: '', school: '', schoolLower: '' };
  await assertFails(set(ref(as(ALICE), `users_public/${ALICE}`), { ...base, role: 'admin', grade: 9 }));
  await assertFails(set(ref(as(ALICE), `users_public/${ALICE}`), { ...base, role: 'student', grade: 99 }));
});

test('users_public: SECURITY — a peer can READ a profile (that is how search works) but not write it', async () => {
  await seed((db) => set(ref(db, `users_public/${ALICE}`), {
    username: 'Alice', usernameLower: 'alice', role: 'student', grade: 9,
    school: 'Yerevan School 42', schoolLower: 'yerevan school 42',
  }));
  await assertSucceeds(get(ref(as(BOB), `users_public/${ALICE}`)));
  await assertFails(set(ref(as(BOB), `users_public/${ALICE}/school`), 'Hacked'));
});

// ─────────────────────────────────────────────────────────────────────────────
// usernames — the claim table. This is what proves ownership of users/{name}.
// ─────────────────────────────────────────────────────────────────────────────

test('usernames: SECURITY — a stranger must not be able to claim a name that is not theirs', async () => {
  // Alice is a real user who has not yet opened the updated app, so her name is unclaimed.
  await seed((db) => set(ref(db, `users_public/${ALICE}`), { username: 'Alice', usernameLower: 'alice' }));

  // Mallory enumerates the public directory and grabs Alice's name.
  await assertFails(set(ref(as(MALLORY), 'usernames/alice'), MALLORY));
});

test('usernames: a claimed name cannot be stolen', async () => {
  await seed((db) => set(ref(db, 'usernames/alice'), ALICE));
  await assertFails(set(ref(as(MALLORY), 'usernames/alice'), MALLORY));
});

test('usernames: SERVER-ONLY — not even your own name can be claimed from the client', async () => {
  // The claim goes through the claimUsername Cloud Function, which is the only way to make it
  // atomic and first-come. A client that could write here could seize anyone's history node.
  await assertFails(set(ref(as(ALICE), 'usernames/alice'), ALICE));
  await assertFails(get(ref(as(ALICE), 'usernames/alice')));
});

// ─────────────────────────────────────────────────────────────────────────────
// users/{name} — history + drawings, keyed by username, gated on the claim.
// ─────────────────────────────────────────────────────────────────────────────

test('users: the claim holder can read and write their own node', async () => {
  await seed((db) => set(ref(db, 'usernames/alice'), ALICE));
  await assertSucceeds(set(ref(as(ALICE), 'users/alice/history/k1'), { title: 't' }));
  await assertSucceeds(get(ref(as(ALICE), 'users/alice/history')));
});

test('users: SECURITY — someone who does not hold the claim cannot read the history', async () => {
  await seed(async (db) => {
    await set(ref(db, 'usernames/alice'), ALICE);
    await set(ref(db, 'users/alice/history/k1'), { title: 'private' });
  });
  await assertFails(get(ref(as(MALLORY), 'users/alice/history')));
  await assertFails(set(ref(as(MALLORY), 'users/alice/history/k1'), { title: 'defaced' }));
});

// ─────────────────────────────────────────────────────────────────────────────
// users/{uid} — the FIX for the data-loss bug.
//
// The bug: the username-keyed node above can only be reached by whoever holds the `usernames/`
// claim, and that table is server-only. The backend was never deployed, so the table was EMPTY —
// meaning every user was locked out of their own history, reads and writes alike. The app logged
// the rejection and carried on, so SQLite was the only copy of a user's work, and uninstalling the
// app destroyed it. Keying by uid removes the dependency entirely.
// ─────────────────────────────────────────────────────────────────────────────

test('users/{uid}: REGRESSION — the old username node is unreachable with an empty claim table', async () => {
  // Exactly the production state: backend not deployed => `usernames/` empty. This is the bug.
  await seed((db) => set(ref(db, 'users/alice/history/k1'), { title: 'my solution' }));

  await assertFails(get(ref(as(ALICE), 'users/alice/history')));            // can't read it back
  await assertFails(set(ref(as(ALICE), 'users/alice/history/k2'), { title: 'x' })); // can't back up
});

test('users/{uid}: the owner can back up and restore WITHOUT any claim, backend or network hop', async () => {
  // No seeding of `usernames/`. Nothing deployed. It must still just work.
  await assertSucceeds(set(ref(as(ALICE), `users/${ALICE}/history/k1`), { title: 'my solution' }));
  await assertSucceeds(get(ref(as(ALICE), `users/${ALICE}/history`)));
  await assertSucceeds(set(ref(as(ALICE), `users/${ALICE}/drawings/k1`), { title: 'a circle' }));
  await assertSucceeds(get(ref(as(ALICE), `users/${ALICE}/drawings`)));
});

test('users/{uid}: SECURITY — nobody else can read or write your node', async () => {
  await seed((db) => set(ref(db, `users/${ALICE}/history/k1`), { title: 'private' }));
  await assertFails(get(ref(as(MALLORY), `users/${ALICE}/history`)));
  await assertFails(set(ref(as(MALLORY), `users/${ALICE}/history/k1`), { title: 'defaced' }));
  await assertFails(remove(ref(as(MALLORY), `users/${ALICE}/history/k1`)));
});

test('users/{uid}: SECURITY — a signed-out client gets nothing', async () => {
  await seed((db) => set(ref(db, `users/${ALICE}/history/k1`), { title: 'private' }));
  await assertFails(get(ref(anon(), `users/${ALICE}/history`)));
  await assertFails(set(ref(anon(), `users/${ALICE}/history/k2`), { title: 'x' }));
});

test('users/{uid}: a reinstall (empty local DB) must not be able to wipe the cloud backup', async () => {
  // The app only ever MERGES with updateChildren() and refuses to push an empty batch. But the rule
  // must not be the thing standing between a bug and a wiped backup, so assert the shape the app
  // relies on: a merge of one row leaves the others untouched.
  await seed((db) => set(ref(db, `users/${ALICE}/history`), {
    k1: { title: 'old one' },
    k2: { title: 'old two' },
  }));

  await assertSucceeds(update(ref(as(ALICE), `users/${ALICE}/history`), { k3: { title: 'new' } }));

  const after = await get(ref(as(ALICE), `users/${ALICE}/history`));
  assert.deepStrictEqual(Object.keys(after.val()).sort(), ['k1', 'k2', 'k3']);
});

// ─────────────────────────────────────────────────────────────────────────────
// friends / friend_requests — consent.
// ─────────────────────────────────────────────────────────────────────────────

test('friends: only I can read my own friend list', async () => {
  await seed((db) => set(ref(db, `friends/${ALICE}/${BOB}`), 'Bob'));
  await assertSucceeds(get(ref(as(ALICE), `friends/${ALICE}`)));
  await assertFails(get(ref(as(BOB), `friends/${ALICE}`)));
});

test('friends: SECURITY — a stranger must not be able to add themselves to my friend list', async () => {
  await assertFails(set(ref(as(MALLORY), `friends/${ALICE}/${MALLORY}`), 'Mallory'));
});

test('friends: SERVER-ONLY — even I cannot write my own friend list', async () => {
  // An edge means "both people agreed". Only acceptFriendRequest() can know that, so only it writes.
  await assertFails(set(ref(as(ALICE), `friends/${ALICE}/${BOB}`), 'Bob'));
});

test('friend_requests: a sender may create a request keyed by their own uid', async () => {
  await assertSucceeds(set(ref(as(BOB), `friend_requests/${ALICE}/${BOB}`), 'Bob'));
});

test('friend_requests: SECURITY — a sender cannot forge a request from someone else', async () => {
  await assertFails(set(ref(as(MALLORY), `friend_requests/${ALICE}/${BOB}`), 'Bob'));
});

test('friend_requests: the recipient can delete (accept/decline)', async () => {
  await seed((db) => set(ref(db, `friend_requests/${ALICE}/${BOB}`), 'Bob'));
  await assertSucceeds(remove(ref(as(ALICE), `friend_requests/${ALICE}/${BOB}`)));
});

test('friend_requests: a BLOCKED user cannot send a request (server-enforced, not UI)', async () => {
  await seed((db) => set(ref(db, `blocked/${ALICE}/${MALLORY}`), 'Mallory'));
  await assertFails(set(ref(as(MALLORY), `friend_requests/${ALICE}/${MALLORY}`), 'Mallory'));
});

// ─────────────────────────────────────────────────────────────────────────────
// blocked
// ─────────────────────────────────────────────────────────────────────────────

test('blocked: only the owner reads and writes their block list', async () => {
  await assertSucceeds(set(ref(as(ALICE), `blocked/${ALICE}/${MALLORY}`), 'Mallory'));
  await assertFails(set(ref(as(MALLORY), `blocked/${ALICE}/${MALLORY}`), null));
  await assertFails(get(ref(as(MALLORY), `blocked/${ALICE}`)));
});

// ─────────────────────────────────────────────────────────────────────────────
// dm — append-only, participants only, blocking enforced by the rules.
// ─────────────────────────────────────────────────────────────────────────────

const msg = (from) => ({ from, fromName: 'x', type: 'text', text: 'hi', ts: Date.now() });

test('dm: a participant can post', async () => {
  await assertSucceeds(set(ref(as(ALICE), `dm/${chatId(ALICE, BOB)}/m1`), msg(ALICE)));
});

test('dm: SECURITY — a third party cannot read or write someone else\'s thread', async () => {
  await seed((db) => set(ref(db, `dm/${chatId(ALICE, BOB)}/m1`), msg(ALICE)));
  await assertFails(get(ref(as(MALLORY), `dm/${chatId(ALICE, BOB)}`)));
  await assertFails(set(ref(as(MALLORY), `dm/${chatId(ALICE, BOB)}/m2`), msg(MALLORY)));
});

test('dm: SECURITY — you cannot forge the `from` field', async () => {
  await assertFails(set(ref(as(BOB), `dm/${chatId(ALICE, BOB)}/m1`), msg(ALICE)));
});

test('dm: messages are APPEND-ONLY — no editing or deleting what was said', async () => {
  await seed((db) => set(ref(db, `dm/${chatId(ALICE, BOB)}/m1`), msg(ALICE)));
  await assertFails(set(ref(as(ALICE), `dm/${chatId(ALICE, BOB)}/m1`), msg(ALICE)));
  await assertFails(remove(ref(as(ALICE), `dm/${chatId(ALICE, BOB)}/m1`)));
});

test('dm: SECURITY — a blocked user cannot write into the thread', async () => {
  // Alice blocks Mallory. Mallory tries to message her anyway.
  await seed((db) => set(ref(db, `blocked/${ALICE}/${MALLORY}`), 'Mallory'));
  await assertFails(set(ref(as(MALLORY), `dm/${chatId(ALICE, MALLORY)}/m1`), msg(MALLORY)));
});

test('dm: blocking is one-way — Alice can still write to the thread she blocked', async () => {
  await seed((db) => set(ref(db, `blocked/${ALICE}/${MALLORY}`), 'Mallory'));
  await assertSucceeds(set(ref(as(ALICE), `dm/${chatId(ALICE, MALLORY)}/m1`), msg(ALICE)));
});

test('dm: oversized text is rejected', async () => {
  const big = { ...msg(ALICE), text: 'x'.repeat(4001) };
  await assertFails(set(ref(as(ALICE), `dm/${chatId(ALICE, BOB)}/m1`), big));
});

test('dm: unknown fields are rejected', async () => {
  const weird = { ...msg(ALICE), isAdmin: true };
  await assertFails(set(ref(as(ALICE), `dm/${chatId(ALICE, BOB)}/m1`), weird));
});

// ─────────────────────────────────────────────────────────────────────────────
// groups / gm
// ─────────────────────────────────────────────────────────────────────────────

const seedGroup = (gid, members) =>
  seed((db) =>
    set(ref(db, `groups/${gid}`), {
      meta: { name: 'g', owner: ALICE, createdAt: 1, memberCount: Object.keys(members).length },
      members,
    })
  );

test('groups: a member can read the group; a non-member cannot', async () => {
  await seedGroup('g1', { [ALICE]: 'Alice', [BOB]: 'Bob' });
  await assertSucceeds(get(ref(as(ALICE), 'groups/g1')));
  await assertFails(get(ref(as(MALLORY), 'groups/g1')));
});

test('gm: only members may read and post', async () => {
  await seedGroup('g1', { [ALICE]: 'Alice', [BOB]: 'Bob' });
  await assertSucceeds(set(ref(as(ALICE), 'gm/g1/m1'), msg(ALICE)));
  await assertFails(set(ref(as(MALLORY), 'gm/g1/m1'), msg(MALLORY)));
  await assertFails(get(ref(as(MALLORY), 'gm/g1')));
});

test('gm: group messages are append-only too', async () => {
  await seedGroup('g1', { [ALICE]: 'Alice', [BOB]: 'Bob' });
  await seed((db) => set(ref(db, 'gm/g1/m1'), msg(ALICE)));
  await assertFails(remove(ref(as(ALICE), 'gm/g1/m1')));
});

test('groups: SERVER-ONLY — a member cannot edit membership or the counter', async () => {
  // Rules cannot count children (no numChildren()), and cannot ask "is X a friend of the caller".
  // So the 2..40 cap and the consent check live in createGroup()/addToGroup(). See
  // functions/test/backend.test.js, where both are exercised for real.
  await seedGroup('g1', { [ALICE]: 'Alice', [BOB]: 'Bob' });
  await assertFails(set(ref(as(ALICE), 'groups/g1/meta/memberCount'), 3));
  await assertFails(set(ref(as(ALICE), `groups/g1/members/${MALLORY}`), 'Mallory'));
});

test('groups: SECURITY — nobody can conjure a group they are already "in"', async () => {
  await assertFails(
    set(ref(as(MALLORY), 'groups/g2'), {
      meta: { name: 'evil', owner: MALLORY, createdAt: 1, memberCount: 2 },
      members: { [MALLORY]: 'Mallory', [ALICE]: 'Alice' },
    })
  );
});

test('user_groups: only I can read my own group index, and nobody writes it', async () => {
  await seed((db) => set(ref(db, `user_groups/${ALICE}/g1`), 'g'));
  await assertSucceeds(get(ref(as(ALICE), `user_groups/${ALICE}`)));
  await assertFails(get(ref(as(MALLORY), `user_groups/${ALICE}`)));
  await assertFails(set(ref(as(MALLORY), `user_groups/${MALLORY}/g1`), 'g'));
});

// ─────────────────────────────────────────────────────────────────────────────
// reports — append-only sinks, unreadable from any client.
// ─────────────────────────────────────────────────────────────────────────────

test('ai_reports: a signed-in user can file one, nobody can read them back', async () => {
  await assertSucceeds(set(ref(as(ALICE), 'ai_reports/r1'), { reason: 'bad', content: 'x', ts: 1 }));
  await assertFails(get(ref(as(ALICE), 'ai_reports')));
});

test('ai_reports: an existing report cannot be altered', async () => {
  await seed((db) => set(ref(db, 'ai_reports/r1'), { reason: 'bad', content: 'x', ts: 1 }));
  await assertFails(set(ref(as(MALLORY), 'ai_reports/r1'), { reason: 'ok', content: 'y', ts: 2 }));
});

test('user_reports: the reporter field cannot be forged', async () => {
  await assertSucceeds(
    set(ref(as(ALICE), 'user_reports/r1'), { reporter: ALICE, reason: 'abuse', ts: 1 })
  );
  await assertFails(
    set(ref(as(MALLORY), 'user_reports/r2'), { reporter: ALICE, reason: 'abuse', ts: 1 })
  );
});

// ─────────────────────────────────────────────────────────────────────────────
// Money. The client must never be able to grant itself a plan or credits.
// (These nodes only exist in the v2 rules; the tests document the requirement.)
// ─────────────────────────────────────────────────────────────────────────────

test('entitlements: SECURITY — a user cannot grant themselves a paid plan', async () => {
  await assertFails(set(ref(as(MALLORY), `entitlements/${MALLORY}`), { plan: 'family', expiryMs: 9e15 }));
});

test('entitlements: a user may READ their own entitlement', async () => {
  await seed((db) => set(ref(db, `entitlements/${ALICE}`), { plan: 'plus', expiryMs: 9e15 }));
  await assertSucceeds(get(ref(as(ALICE), `entitlements/${ALICE}`)));
  await assertFails(get(ref(as(MALLORY), `entitlements/${ALICE}`)));
});

test('wallet: SECURITY — a user cannot mint credits for themselves', async () => {
  await assertFails(set(ref(as(MALLORY), `wallet/${MALLORY}/extraCredits`), 999999));
});

test('wallet: a user may read their own balance', async () => {
  await seed((db) => set(ref(db, `wallet/${ALICE}`), { extraCredits: 100 }));
  await assertSucceeds(get(ref(as(ALICE), `wallet/${ALICE}`)));
  await assertFails(get(ref(as(MALLORY), `wallet/${ALICE}`)));
});

test('wallet: SECURITY — a user cannot roll back their own spend counter', async () => {
  await seed((db) => set(ref(db, `wallet/${ALICE}`), { dayUsed: 3000, day: '2026-07-10' }));
  await assertFails(set(ref(as(ALICE), `wallet/${ALICE}/dayUsed`), 0));
});

test('play_tokens: SECURITY — the purchase ledger is invisible and unwritable', async () => {
  await seed((db) => set(ref(db, 'play_tokens/tok123'), { uid: ALICE, granted: true }));
  await assertFails(get(ref(as(MALLORY), 'play_tokens/tok123')));
  await assertFails(set(ref(as(MALLORY), 'play_tokens/tok999'), { uid: MALLORY, granted: true }));
});

test('otp: SECURITY — verification codes are never readable by any client', async () => {
  await seed((db) => set(ref(db, 'otp/deadbeef'), { hash: 'x', salt: 'y', expiresAt: 9e15 }));
  await assertFails(get(ref(as(MALLORY), 'otp/deadbeef')));
  await assertFails(get(ref(anon(), 'otp/deadbeef')));
});

test('usage: I can see what I have spent; nobody else can, and nobody can edit it', async () => {
  await seed((db) => set(ref(db, `usage/${ALICE}`), { tokensIn: 100, microUsd: 45 }));
  await assertSucceeds(get(ref(as(ALICE), `usage/${ALICE}`)));
  await assertFails(get(ref(as(MALLORY), `usage/${ALICE}`)));
  await assertFails(set(ref(as(ALICE), `usage/${ALICE}/microUsd`), 0));
});

test('usage_totals: the developer cost roll-up is not a client\'s business', async () => {
  await seed((db) => set(ref(db, 'usage_totals/2026-07'), { microUsd: 123456 }));
  await assertFails(get(ref(as(ALICE), 'usage_totals/2026-07')));
});

// ─────────────────────────────────────────────────────────────────────────────
// Collective (family) plans
// ─────────────────────────────────────────────────────────────────────────────

const seedFamily = () =>
  seed((db) =>
    set(ref(db, 'families/f1'), {
      owner: ALICE,
      plan: 'family',
      seatLimit: 6,
      expiryMs: 9e15,
      seats: { [ALICE]: 'Alice', [BOB]: 'Bob' },
    })
  );

test('families: the owner and every seat-holder can see who is on the plan', async () => {
  await seedFamily();
  await assertSucceeds(get(ref(as(ALICE), 'families/f1')));
  await assertSucceeds(get(ref(as(BOB), 'families/f1')));
});

test('families: SECURITY — an outsider cannot read the plan, nor add themselves to a seat', async () => {
  await seedFamily();
  await assertFails(get(ref(as(MALLORY), 'families/f1')));
  await assertFails(set(ref(as(MALLORY), `families/f1/seats/${MALLORY}`), 'Mallory'));
});

test('families: SECURITY — a seat-holder cannot extend the plan\'s expiry or seat count', async () => {
  await seedFamily();
  await assertFails(set(ref(as(BOB), 'families/f1/expiryMs'), 9e18));
  await assertFails(set(ref(as(BOB), 'families/f1/seatLimit'), 999));
});

test('family_of + family_invites: readable by me alone, writable by nobody', async () => {
  await seed(async (db) => {
    await set(ref(db, `family_of/${BOB}`), 'f1');
    await set(ref(db, `family_invites/${BOB}/f1`), { fromName: 'Alice', ts: 1 });
  });
  await assertSucceeds(get(ref(as(BOB), `family_of/${BOB}`)));
  await assertSucceeds(get(ref(as(BOB), `family_invites/${BOB}`)));
  await assertFails(get(ref(as(MALLORY), `family_of/${BOB}`)));
  await assertFails(set(ref(as(MALLORY), `family_invites/${MALLORY}/f1`), { fromName: 'x', ts: 1 }));
});

// ─────────────────────────────────────────────────────────────────────────────
// AI tutor chat history — private to the owner
// ─────────────────────────────────────────────────────────────────────────────

test('chat_history: a student can read and write their own tutoring chats', async () => {
  await assertSucceeds(set(ref(as(ALICE), `chat_history/${ALICE}/s1`), '{"id":"s1","ts":1}'));
  await assertSucceeds(get(ref(as(ALICE), `chat_history/${ALICE}`)));
});

test('chat_history: SECURITY — nobody else can read or write your chats', async () => {
  await seed((db) => set(ref(db, `chat_history/${ALICE}/s1`), '{"id":"s1","ts":1,"secret":"my homework"}'));
  await assertFails(get(ref(as(MALLORY), `chat_history/${ALICE}`)));
  await assertFails(get(ref(as(MALLORY), `chat_history/${ALICE}/s1`)));
  await assertFails(set(ref(as(MALLORY), `chat_history/${ALICE}/s2`), '{"id":"s2","ts":2}'));
});

test('chat_history: an oversized session is rejected (tree cannot be bloated)', async () => {
  const huge = '{"x":"' + 'a'.repeat(100001) + '"}';
  await assertFails(set(ref(as(ALICE), `chat_history/${ALICE}/big`), huge));
});

test('SECURITY: an unlisted top-level node is denied by default', async () => {
  await assertFails(set(ref(as(MALLORY), 'admin/isRoot'), true));
  await assertFails(get(ref(as(MALLORY), 'admin')));
});
