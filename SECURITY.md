# Lemma — Security

Written 2026-07-09 after auditing the actual code. Ordered by **real risk**, not by effort.
`[you]` = only you can do it (console / keys / backend). `[done]` = already in the code.

---

## P0 — Do these before anyone else installs the app

### 0. Run the username backfill — this hole is OPEN RIGHT NOW **[you]**
The rules prove ownership of `users/{name}` (a user's entire history + drawings) through the claim
table `usernames/{name} = uid`. The rules let anyone claim a name that is **not yet claimed**, and
only the updated app writes claims — so **every user who has not yet opened the new build has an
unclaimed name**.

The attack, available to any signed-in user today:
1. Read `users_public` (every signed-in user may — that's how search works) and list real usernames.
2. Write `usernames/<victim> = <attacker uid>`. Accepted, because the name is unclaimed.
3. `users/<victim>` now evaluates `root.child('usernames').child(<victim>).val() === auth.uid` →
   **true for the attacker**. They read, overwrite, or delete the victim's history and drawings.
4. The real owner is locked out of their own node **permanently** — their claim is now rejected.

Fix, in this order:
```bash
npm install firebase-admin
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json
node tools/backfill-usernames.js            # dry run — READ the collisions report
node tools/backfill-usernames.js --apply
```
This claims every existing username for its rightful uid server-side, leaving nothing to steal.
Read the dry-run output first: accounts whose usernames differ only by case (`Andi` / `andi`) already
share one cloud node and only one of them can hold the claim.

### 1. Publish the database rules **[you]**
Everything below depends on this. Paste `docs/database.rules.json` into
**Firebase console → Realtime Database → Rules → Publish**.

**Go and look at your current rules right now.** If they still say something like
`".read": "auth != null", ".write": "auth != null"` (the default "test mode"), then **today**:

- every signed-in user can read **every other user's `users_info/{uid}`**, which contains their
  **`ai_keys`** (real Gemini API keys), `is_pro`, and token balance;
- every signed-in user can read and **overwrite anyone's saved solutions and drawings**.

⚠️ **Read the deploy-order note at the top of the rules file.** The `users/` node is keyed by
*username*, not uid, so ownership can only be proven through the new `usernames/` claim table. Ship
the app update first, let people open it once, *then* publish the rules — otherwise existing users
lose access to their own history.

### 2. Turn on Firebase App Check **[you]**
`google-services.json` is inside every APK; anyone can extract it and talk to your database and Auth
directly with a script. Rules are your only defence, and they can't tell your app from `curl`.

Firebase console → **App Check** → register the Android app with the **Play Integrity** provider,
then **enforce** it on Realtime Database and Authentication. This is the single highest-leverage
security control available to you, and it's free.

### 3. Rotate the leaked credentials **[you]**
`GEMINI_API_KEY`, `GEMINI_BACKUP_KEYS`, `MAIL_USER` and `MAIL_APP_PASSWORD` are compiled into
`BuildConfig` and are recoverable from any APK you have ever built or shared in **minutes**
(`unzip` + `strings`). The Gmail **app password** is the worst of them: it grants access to the
mailbox, not just to sending.

Treat all four as **already compromised**:
1. Revoke the Gmail app password (Google Account → Security → App passwords).
2. Delete/regenerate the Gemini API keys in Google AI Studio / Cloud console.
3. Do not ship the replacements in the APK — see P1.

---

## P1 — Before the public launch

### 4. Move the keys and OTP email behind a backend **[you]**
A Cloud Function that holds the Gemini key and sends the OTP email. The app calls your endpoint with
its Firebase ID token; the key never leaves the server. This one change fixes:

- secrets in the APK (P0 #3 permanently),
- **token metering** — the wallet is currently client-side and a determined user can reset their
  balance (see `TokenWallet`'s own class comment),
- the group/friend integrity hole in #7.

It is the same backend that iOS would need. It is the highest-value engineering work left.

### 5. Stop syncing user API keys to the cloud **[you / small code change]**
`ApiKeyStore` writes the user's own Gemini keys to `users_info/{uid}/ai_keys` **in plaintext**. The
new rules make that node owner-only, so this is no longer a peer-visible leak — but it is still
plaintext at rest, and one future rule mistake re-exposes it. Keys are device-local by nature.
Recommendation: drop the cloud sync, or encrypt before writing.

### 6. Chat moderation **[done — but you must action the reports]**
User-to-user chat puts Lemma under Google Play's **User Generated Content** policy (and Apple's
Guideline 1.2): there must be an in-app way to report objectionable content *and users*, a way to
block a user, and you must act on what gets reported.

Shipped: long-press any message → **Copy / Report this message / Block user**. Reports land in
`user_reports/` (append-only, no client can read them, and the `reporter` field can't be forged).
Blocking is **enforced by the database rules**, not merely hidden in the UI — a blocked user cannot
write into your DM or send you a friend request. Unblock from Friends → Blocked.

**Your remaining duty: read `user_reports/` in the Firebase console and act on it.** A reporting
feature nobody reads is a policy violation with extra steps.

---

## P2 — Known, accepted, or lower risk

### 7. A user can add themselves to your friend list
Accepting a request must write `friends/{peer}/{me}`, so the rules necessarily let a signed-in user
write one entry into someone else's friend list. They cannot read your data, read your chats, or
message you without you also having them (the DM read rule is keyed on the chat id). Properly
closing it needs a Cloud Function to perform the accept — see #4.

### 8. Group membership is trusted to members, and the 2..40 cap is a guard-rail
Any member of a group can add or remove other members. Normal for a small messenger, but worth
knowing.

The size cap is **not** hard-enforced, and cannot be: **Realtime Database rules have no way to count
children** — there is no `numChildren()` (that's Firestore's `size()`). So the limit rides on an
explicit `meta/memberCount` field. The rules pin it as tightly as they can: it must be declared
between 2 and 40 at creation, and afterwards may only move by exactly ±1 and never above 40. The app
writes the member and the counter in one atomic `updateChildren`.

A member crafting raw requests could still add people without incrementing the counter. They are
already a trusted party (they can read the whole thread), so the exposure is **data bloat, not
disclosure**. A tamper-proof cap needs a Cloud Function that owns group membership — another item for
the P1 backend.

### 9. Messages are append-only **[done]**
In the rules, `.write` sits on `$msgId` with `!data.exists()`, not on the thread. A granted `.write`
on an ancestor cannot be revoked by a descendant, so this placement is what makes it impossible to
edit or delete what someone said after the fact. Don't "simplify" it upward.

---

## What is already solid **[done]**

- **Passwords**: PBKDF2-HMAC-SHA1, 120,000 iterations, per-password salt (`PasswordHasher`), with
  legacy plaintext rows upgraded on the next successful login. Cloud passwords are handled by
  Firebase Auth and never touch your database.
- **Transport**: `usesCleartextTraffic="false"` — no plain HTTP anywhere in the code.
- **Device**: `allowBackup="false"` plus `data_extraction_rules.xml` excluding `UserPrefs.xml` and
  `AI_Settings.xml` from device-to-device transfer.
- **Release logs**: `Log.v/d/i` are stripped by R8, so problem text, billing state and key handling
  can't be read off a device with `adb logcat`. `Log.w/e` survive for crash reports.
- **Private vs public split**: username search reads `users_public/{uid}` (username only).
  `users_info/{uid}` — API keys, Pro, token wallet — is owner-only and must stay that way.
- **Repo hygiene**: `local.properties`, `*.apk`, and keystores are git-ignored and not tracked.

---

## On end-to-end encryption

I previously said "don't", for two reasons. **One of them no longer holds**, so here is the corrected
position.

**Resolved — moderation.** I claimed E2EE would make it impossible to act on abuse reports. That was
wrong, and `UserReports` is now built the way real E2EE messengers do it: when a user reports a
message, *their own device* uploads the plaintext it just displayed. The reporter can read the
message, so the report carries evidence even if the server never could. WhatsApp does exactly this.

**Still unresolved — key management.** True E2EE means the private key lives only on the device
(Android Keystore, non-exportable). Therefore:

- uninstall / factory reset / new phone ⇒ **every old message is permanently unreadable**;
- a child switching or sharing devices loses their chat history with no recovery path;
- adding someone to a group can't retroactively give them the old messages.

That is not a bug — it's what E2EE *is*. It is a **product decision**, not a security one, and it is
the reason to think twice for a schoolchild audience. The way to have both is an
**encrypted key backup** (private key wrapped with a user passphrase, stored server-side), which
needs the backend from P1 #4 to rate-limit passphrase guessing.

Today, without E2EE, you get: TLS in transit, Firebase encryption at rest, strict per-thread rules,
append-only history, server-enforced blocking, and (once enabled) App Check. `docs/privacy.html`
must keep stating plainly that Lemma's operators can access message content to handle abuse reports —
that sentence is what stops being true the day you ship E2EE.

### Chosen plan: E2EE **with** encrypted key backup (decided 2026-07-09)

Two phases. Phase 1 is the backend you already owe for three other reasons (P1 #4).

**Phase 1 — backend (Cloud Functions).** Holds the Gemini key, sends the OTP email, meters tokens,
and exposes one new pair of endpoints for key backup:
- `PUT /keybackup` — stores `{ wrappedPrivateKey, salt, iterations }` for the caller's uid. The app
  wraps the private key with AES-GCM under a key derived from the user's passphrase (PBKDF2, ≥200k
  iterations, per-user salt). **The server stores only ciphertext and never sees the passphrase.**
- `GET  /keybackup` — returns the blob after **rate-limiting** (e.g. 5 attempts / hour / uid, then
  exponential backoff). Rate limiting is the whole reason this can't be done from the client: without
  it, an attacker with database access brute-forces a child's four-digit passphrase in seconds.

**Phase 2 — E2EE in the app.**
- Keypair: RSA-2048 in Android Keystore, non-exportable, `OAEPWithSHA-256AndMGF1Padding`.
  ⚠️ Android Keystore's OAEP uses **MGF1-SHA1** even when the digest is SHA-256 — pass an explicit
  `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)` on
  **both** encrypt and decrypt, or decryption silently fails. This is the classic trap.
- Public key published to `users_public/{uid}/pubKey` (the rules currently have `$other: false` there
  — that must be relaxed to allow `pubKey`).
- Per message: random AES-256-GCM key + 12-byte IV, encrypt the payload, then wrap that AES key with
  **each recipient's** RSA public key (including your own, or you can't read what you sent). A
  40-person group ⇒ 40 wrapped keys ≈ 14 KB overhead per message.
- The `dm`/`gm` `.validate` rules must change: `text`/`raw`/`data` become opaque base64, so the size
  caps need raising ~35% and the `type` check moves inside the ciphertext.
- Moderation keeps working unchanged: `UserReports` already uploads reporter-side plaintext.

**Do not start Phase 2 before Phase 1.** Shipping E2EE without the key backup silently destroys
users' history on their next reinstall, and it cannot be undone afterwards.
