/**
 * ONE-TIME MIGRATION — move saved history + drawings from `users/{username}` to `users/{uid}`.
 *
 * WHY THIS EXISTS
 * ---------------
 * `users/` used to be keyed by sanitized username. The rules could not tell, from the request alone,
 * that `users/andi` belonged to you — so ownership was proven through a claim table,
 * `usernames/{name} = uid`, that only a Cloud Function is allowed to write.
 *
 * The backend was never deployed. So the claim table stayed EMPTY, so nobody owned any name, so
 * every read AND write of every user's own history was denied by the server. The app logged the
 * rejection and carried on as if all were well, which meant the local SQLite database was the only
 * copy of anyone's work — and `allowBackup="false"` means uninstalling the app destroys it.
 * That is how a reinstall lost a user's solutions.
 *
 * The app now keys this node by uid (`$uid === auth.uid` proves itself: no table, no backend). This
 * script moves anything already sitting under the old username keys across to the new uid keys, so
 * work saved back when the rules were still permissive is not stranded.
 *
 * It is SAFE TO RE-RUN. It merges (never overwrites a newer row) and it does not delete the old node
 * unless you explicitly pass --delete-legacy.
 *
 * SETUP
 *   npm install firebase-admin
 *   # Firebase console -> Project settings -> Service accounts -> Generate new private key
 *   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json   # PowerShell: $env:GOOGLE_APPLICATION_CREDENTIALS="..."
 *
 * RUN
 *   node tools/migrate-history-to-uid.js                  # dry run — reports what it WOULD move
 *   node tools/migrate-history-to-uid.js --apply          # move it
 *   node tools/migrate-history-to-uid.js --apply --delete-legacy   # ...and remove the old node
 *
 * Read the dry run first. Especially the UNMATCHED section: those are username nodes with no owner
 * we can identify, and this script will NOT touch them. Do not delete them — that is somebody's
 * data, and it can only be reunited with an account by hand.
 */

const admin = require('firebase-admin');

const DB_URL = 'https://lemma-37061-default-rtdb.europe-west1.firebasedatabase.app';

const APPLY = process.argv.includes('--apply');
const DELETE_LEGACY = process.argv.includes('--delete-legacy');

// Must match FirebaseManager.sanitizeUser() exactly, or names won't line up.
const sanitize = (u) => String(u).replace(/[.#$[\]]/g, '_').toLowerCase();

// A Firebase Auth uid is 28 chars of [A-Za-z0-9]. A sanitized username can't be mistaken for one in
// practice, but check explicitly rather than guess — a false positive here would skip real data.
const looksLikeUid = (k) => /^[A-Za-z0-9]{28}$/.test(k);

async function main() {
  admin.initializeApp({ databaseURL: DB_URL });
  const db = admin.database();

  console.log(APPLY ? '=== APPLY (writing) ===' : '=== DRY RUN (no writes) ===\n');

  // 1. Build sanitized-username -> uid from the public directory, then fill gaps from users_info.
  const nameToUid = new Map();

  const pub = (await db.ref('users_public').get()).val() || {};
  for (const [uid, row] of Object.entries(pub)) {
    const name = row && (row.usernameLower || row.username);
    if (name) nameToUid.set(sanitize(name), uid);
  }

  const info = (await db.ref('users_info').get()).val() || {};
  for (const [uid, row] of Object.entries(info)) {
    const name = row && row.username;
    if (name && !nameToUid.has(sanitize(name))) nameToUid.set(sanitize(name), uid);
  }

  console.log(`Identified ${nameToUid.size} username -> uid mappings.\n`);

  // 2. Walk users/ and move every username-keyed node onto its owner's uid.
  const users = (await db.ref('users').get()).val() || {};
  const unmatched = [];
  let moved = 0;
  let rows = 0;

  for (const [key, node] of Object.entries(users)) {
    if (looksLikeUid(key)) continue;              // already migrated
    if (key === 'guestuser' || key.startsWith('guestuser')) continue; // guests: throwaway by design

    const uid = nameToUid.get(key);
    if (!uid) {
      unmatched.push(key);
      continue;
    }

    const history = node.history || {};
    const drawings = node.drawings || {};
    const n = Object.keys(history).length + Object.keys(drawings).length;
    if (n === 0) continue;

    console.log(`  ${key}  ->  ${uid}   (${Object.keys(history).length} solutions, ` +
                `${Object.keys(drawings).length} drawings)`);

    if (APPLY) {
      // update() MERGES: a row already present under the uid (saved by the fixed app) wins, because
      // we only write keys that are missing there. Never clobber newer data with older data.
      const existing = (await db.ref(`users/${uid}`).get()).val() || {};
      const patch = {};
      for (const [k, v] of Object.entries(history)) {
        if (!(existing.history && existing.history[k])) patch[`history/${k}`] = v;
      }
      for (const [k, v] of Object.entries(drawings)) {
        if (!(existing.drawings && existing.drawings[k])) patch[`drawings/${k}`] = v;
      }
      if (Object.keys(patch).length) {
        await db.ref(`users/${uid}`).update(patch);
        rows += Object.keys(patch).length;
      }
      if (DELETE_LEGACY) await db.ref(`users/${key}`).remove();
    }
    moved++;
  }

  console.log(`\n${moved} account(s) ${APPLY ? 'migrated' : 'would be migrated'}` +
              (APPLY ? `, ${rows} row(s) written.` : '.'));

  if (unmatched.length) {
    console.log(`\nUNMATCHED — ${unmatched.length} node(s) with no identifiable owner. NOT touched:`);
    for (const k of unmatched) console.log(`  users/${k}`);
    console.log('\nThese belong to someone whose users_public / users_info row is missing. Do not');
    console.log('delete them. Find the uid in the Auth tab and move the node by hand.');
  }

  if (!APPLY) console.log('\nNothing was written. Re-run with --apply to do it for real.');
  await admin.app().delete();
}

main().catch((e) => { console.error(e); process.exit(1); });
