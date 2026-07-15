/**
 * ONE-TIME MIGRATION — run this BEFORE publishing docs/database.rules.json.
 *
 * WHY THIS EXISTS
 * ---------------
 * `users/{sanitizedUsername}` holds every user's synced history and drawings, but it is keyed by
 * USERNAME, not by uid. The rules can only prove ownership through the claim table
 * `usernames/{sanitizedUsername} = uid`, which the app writes on launch.
 *
 * That leaves a hole: until a given user opens the updated app, THEIR name is unclaimed, and the
 * rules let ANY signed-in user claim an unclaimed name. An attacker can enumerate usernames from the
 * public directory, claim a victim's name, and then read, overwrite, or delete that victim's entire
 * history and drawings — permanently locking the real owner out of their own node.
 *
 * This script closes the hole by claiming every existing username for its rightful uid, server-side,
 * before the rules go live. After it runs there are no unclaimed names to steal.
 *
 * SETUP
 *   npm install firebase-admin
 *   # Firebase console -> Project settings -> Service accounts -> Generate new private key
 *   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json
 *
 * RUN
 *   node tools/backfill-usernames.js            # dry run: reports what it WOULD do
 *   node tools/backfill-usernames.js --apply    # actually writes
 *
 * The dry run is the default on purpose. Read its output — especially the COLLISIONS section —
 * before applying.
 */

const admin = require('firebase-admin');

const DB_URL = 'https://lemma-37061-default-rtdb.europe-west1.firebasedatabase.app';
const APPLY = process.argv.includes('--apply');

admin.initializeApp({
  credential: admin.credential.applicationDefault(),
  databaseURL: DB_URL,
});

/** MUST match FirebaseManager.sanitizeUser() in the app, or the keys won't line up. */
function sanitizeUser(name) {
  if (name == null) return 'guestuser';
  return String(name).replace(/[.#$[\]]/g, '_').toLowerCase();
}

async function main() {
  const db = admin.database();

  const [infoSnap, claimsSnap] = await Promise.all([
    db.ref('users_info').once('value'),
    db.ref('usernames').once('value'),
  ]);

  const info = infoSnap.val() || {};
  const claims = claimsSnap.val() || {};

  const toWrite = {};      // sanitized name -> uid
  const collisions = {};   // sanitized name -> [uid, ...]
  const alreadyOk = [];
  const conflictsWithExistingClaim = [];
  let skippedNoName = 0;

  for (const [uid, rec] of Object.entries(info)) {
    const name = rec && rec.username;
    if (!name || String(name).startsWith('GuestUser')) { skippedNoName++; continue; }

    const key = sanitizeUser(name);
    const existingClaim = claims[key];

    if (existingClaim === uid) { alreadyOk.push(key); continue; }
    if (existingClaim && existingClaim !== uid) {
      conflictsWithExistingClaim.push({ key, claimedBy: existingClaim, shouldBe: uid });
      continue;
    }

    if (toWrite[key] && toWrite[key] !== uid) {
      (collisions[key] = collisions[key] || [toWrite[key]]).push(uid);
      continue;
    }
    toWrite[key] = uid;
  }

  // Two accounts whose usernames differ only by case collapse to the same cloud node. They are
  // ALREADY silently sharing data; the claim can only be granted to one of them.
  for (const key of Object.keys(collisions)) delete toWrite[key];

  console.log(`accounts scanned          : ${Object.keys(info).length}`);
  console.log(`skipped (guest / no name) : ${skippedNoName}`);
  console.log(`already claimed correctly : ${alreadyOk.length}`);
  console.log(`to claim                  : ${Object.keys(toWrite).length}`);

  if (conflictsWithExistingClaim.length) {
    console.log('\n!! CLAIMED BY THE WRONG UID (possible hijack, or a stale claim):');
    for (const c of conflictsWithExistingClaim) {
      console.log(`   ${c.key}: claimed by ${c.claimedBy}, should be ${c.shouldBe}`);
    }
    console.log('   Resolve these by hand. This script will NOT overwrite an existing claim.');
  }

  if (Object.keys(collisions).length) {
    console.log('\n!! COLLISIONS (usernames differing only by case share one cloud node):');
    for (const [key, uids] of Object.entries(collisions)) {
      console.log(`   ${key}: ${uids.join(', ')}`);
    }
    console.log('   Rename all but one of each group BEFORE granting the claim, or the losers');
    console.log('   permanently lose access to data they can currently read.');
  }

  if (!APPLY) {
    console.log('\nDRY RUN — nothing written. Re-run with --apply once the report looks right.');
    return;
  }

  const updates = {};
  for (const [key, uid] of Object.entries(toWrite)) updates[`usernames/${key}`] = uid;

  if (Object.keys(updates).length === 0) {
    console.log('\nNothing to write.');
  } else {
    await db.ref().update(updates);
    console.log(`\nWrote ${Object.keys(updates).length} claims.`);
  }
  console.log('Now publish docs/database.rules.json.');
}

main()
  .then(() => process.exit(0))
  .catch((e) => { console.error(e); process.exit(1); });
