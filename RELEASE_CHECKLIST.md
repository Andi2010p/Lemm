# Lemma — everything YOU have to do (Firebase, Play, site)

Verified against the repo on **2026-07-14**. Nothing here can be automated from code — it all needs
your Google account, your bank, a keystore, or a paid Firebase plan.

**Do it in this order.** Later steps depend on earlier ones, and two of them will *destroy user data*
if done out of order (marked ⛔).

---

## PART A — Broken right now. Fix today. (~20 minutes, free)

### A1. ⛔ Publish the database rules — **your cloud backup is currently OFF**

This is the bug that lost your solutions. Until you do this, **nobody's work is being backed up**,
including yours, and every reinstall loses everything.

1. Firebase console → **Realtime Database** → **Rules** tab.
2. Delete what is there. Paste the whole of **`docs/database.rules.json`**.
3. **Publish**.

Verify it worked: open the app → History. The banner must say *"Backed up to your account"* — not the
red *"Cloud backup is not working"*.

> Why it broke: the old rules proved you owned `users/{yourname}` via a claim table that only a Cloud
> Function may write — and the functions were never deployed, so the table was empty and the server
> refused every read and write of your own history. The new rules key data by `uid`, which proves
> itself with no backend at all. 63 emulator tests cover this (`cd tools/rules-tests && npm test`).

### A2. The in-app **Terms** link is a 404 right now

`terms.html` was never committed. I just checked the live site:

| | |
|---|---|
| `privacy.html` | **HTTP 200** ✅ (your new version is live) |
| `terms.html` | **HTTP 404** ❌ (never pushed) |

Settings → Terms of Service opens a dead page. Fix:

```powershell
git add terms.html sitemap.xml docs/ RELEASE_CHECKLIST.md SECURITY.md
git commit -m "Add Terms of Service; publish updated rules and docs"
git push origin master
```

Wait ~1 minute, then confirm `https://andi2010p.github.io/Lemm/terms.html` loads.

### A3. Google Sign-In "error 10" — add your fingerprints

`DEVELOPER_ERROR`. Your debug key isn't registered with Firebase.

1. Firebase console → ⚙ **Project settings** → your Android app → **Add fingerprint**.
2. Add **both**:

```
SHA-1    9D:FA:9A:4A:0F:EC:B6:7A:E6:22:F1:01:10:45:6E:55:84:1B:19:E9
SHA-256  96:27:C3:AD:C8:E1:65:BF:58:91:CE:A7:56:AC:3E:D2:EA:92:2A:74:20:7A:9F:A2:14:E1:A6:39:EC:44:08:2D
```

3. **Download the new `google-services.json`** → replace `app/google-services.json`.
4. **Uninstall the app from the phone** (not just rebuild — the old cert is cached), then reinstall.

> These are the **debug** fingerprints. You must repeat this with the **release** fingerprints later
> (step C3), or Sign-In breaks in the published app while working fine on your machine.

### A4. Check whether your old solutions are recoverable

Firebase console → Realtime Database → open the **`users`** node.

- A child named after **you** (e.g. `users/andi`) → your old work is alive. Recover it:
  ```powershell
  npm install firebase-admin
  # Firebase console -> Project settings -> Service accounts -> Generate new private key
  $env:GOOGLE_APPLICATION_CREDENTIALS="C:\path\to\serviceAccountKey.json"
  node tools/migrate-history-to-uid.js            # dry run — writes nothing
  node tools/migrate-history-to-uid.js --apply    # move it onto your uid
  ```
- Only children that look like `xK3f9...` (28 random chars) → those are already uid-keyed; nothing to
  migrate.
- Nothing at all → the data was never accepted by the server. It is gone. I'm sorry.

### A5. Chat upgrade — republish the DB rules, and turn on Storage for media

The messenger now has replies, reactions, read receipts, typing, online/last-seen presence, edit +
unsend, and photo/voice/file attachments. Two console steps make it live:

1. **Republish `docs/database.rules.json`** (same place as A1). The new version adds the
   `dm_reactions` / `gm_reactions` / `dm_receipts` / `dm_typing` / `gm_typing` / `presence` nodes and
   lets a message's **author** (only) edit or unsend it. Until you republish, those writes are denied
   and the new features silently do nothing. Verified by 67 tests (`cd tools/rules-tests && npm test`).
2. **Cloud Storage for photos/voice/files.** Firebase console → **Storage** → get started (a project
   this age requires **Blaze** — the same plan Part B needs anyway). Then deploy the rules:
   `firebase deploy --only storage` (config is in `firebase.json` → `docs/storage.rules`). If Storage
   is off, text/reactions/etc. still work and media just shows a clear "couldn't send" message.

> App-lock (PIN + fingerprint/face) needs **no** server setup — it's fully on-device. Users enable it
> in Settings → Security, and they're offered it once on first launch.

---

## PART B — The backend. Unlocks AI, payments, groups, usernames.

**Nothing in Part B is optional if you want to publish**, because without it your Gemini API key ships
inside the APK where anyone can extract it (see B4).

### B1. Upgrade Firebase to **Blaze** (pay-as-you-go)

Cloud Functions **cannot be deployed on the free Spark plan.** Blaze is pay-as-you-go and still
includes the free tier — at your traffic the bill is realistically **$0–2/month**.

Firebase console → ⚙ → **Usage and billing** → **Modify plan** → Blaze → attach a card.

**Immediately set a budget alert** (this is the one that stops a bug becoming a $2,000 bill):
Google Cloud console → **Billing → Budgets & alerts** → Create budget → **$10/month**, alert at 50 /
90 / 100 %.

### B2. Deploy the Cloud Functions

`.firebaserc` doesn't exist in your repo — the Firebase CLI was never linked here. So:

```powershell
npm install -g firebase-tools
firebase login
firebase use --add          # pick lemma-37061, give it the alias "default"

cd functions
npm install
cd ..
```

Set the secrets **server-side** (they are stored by Google, never in your repo, never in the APK):

```powershell
firebase functions:secrets:set GEMINI_API_KEY
firebase functions:secrets:set MAIL_USER
firebase functions:secrets:set MAIL_APP_PASSWORD

firebase deploy --only functions
```

> ⚠️ Use a **paid / billing-enabled** Gemini key. A free-tier key rate-limits at ~15 requests/min and
> your users will see random failures under any real load.

### B3. ⛔ Run the migrations — **in this order, before enforcing anything**

```powershell
node tools/backfill-usernames.js            # dry run
node tools/backfill-usernames.js --apply    # every existing user owns their username claim
node tools/migrate-history-to-uid.js --apply   # if A4 showed legacy data
```

### B4. Rotate the leaked secrets

These four are compiled into `BuildConfig` and are **extractable from any APK in about a minute**:

| Secret | Risk if leaked |
|---|---|
| `GEMINI_API_KEY` | someone bills their AI usage to you |
| `GEMINI_BACKUP_KEYS` | same |
| `MAIL_USER` + `MAIL_APP_PASSWORD` | **your Gmail account is compromised** |

After B2 the backend holds these, so:
1. **Revoke and reissue** the Gemini keys (Google AI Studio) and the Gmail **app password**.
2. Put the new values only in `functions:secrets:set` and in `local.properties` (never committed).
3. Once the scanner/OTP paths route through the backend, delete the `buildConfigField` lines from
   `app/build.gradle.kts` so nothing sensitive is left in the APK at all.

### B5. Turn on **App Check** (stops strangers calling your backend directly)

Firebase console → **App Check** → register the Android app with **Play Integrity**.

> **Register your debug token FIRST, then enforce.** If you enforce before adding the debug token,
> your own development builds get locked out of the backend and it looks like the app is broken.
> Run the app once, find the debug token in Logcat, and paste it into App Check → Manage debug tokens.

### B6. Check the Auth providers are actually on

Firebase console → **Authentication → Sign-in method**. Enable **Email/Password** and **Google**.

---

## PART C — Google Play

### C1. Developer account — $25, one-time

play.google.com/console → register as an **individual** → pay $25 → identity verification (1–3 days).

### C2. Payments profile — required before you can receive a single cent

Play Console → **Setup → Payments profile**. Needs legal name + address, a **bank account**, and **tax
info**. Armenia is supported; payouts arrive as a **USD wire from the US**.

Google's cut: **15 %** on your first $1 M/year, 30 % above.

### C3. ⛔ Rename the applicationId — **permanent, cannot be changed after first publish**

Play **rejects** anything starting with `com.example`. You chose `io.github.andi2010p.lemma`.

The build will fail until Firebase knows the new package, so do these **together**:

1. Firebase console → **Add app → Android** → register `io.github.andi2010p.lemma`.
2. Download that app's `google-services.json` → replace `app/google-services.json`.
3. Add the **release** SHA-1 + SHA-256 (from C4's keystore, via `./gradlew signingReport`) — and the
   **Play App Signing** SHA-1 too (Play re-signs your app; get it from Play Console → Setup → App
   signing, *after* your first upload).
4. `app/build.gradle.kts` → `applicationId = "io.github.andi2010p.lemma"`. Leave `namespace` alone.

### C4. Create the release keystore — **and back it up**

Right now a release build silently falls back to the **debug key**, which Play will not accept.

```powershell
keytool -genkey -v -keystore lemma-release.jks -alias lemma -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# local.properties — NEVER commit this file
RELEASE_STORE_FILE=../keystore/lemma-release.jks
RELEASE_STORE_PASSWORD=********
RELEASE_KEY_ALIAS=lemma
RELEASE_KEY_PASSWORD=********
```

> 🔴 **If you lose this file you can never update your app again.** Not "it's annoying" — you would
> have to publish a brand-new listing and abandon every existing user. Back it up in two places.

Then `./gradlew :app:bundleRelease` → upload the **.aab** (Play requires App Bundles, not APKs).

### C5. Create the in-app products — **IDs must match exactly**

The backend (`functions/lib/play.js`) rejects any product ID it doesn't recognise, so a typo means
**the user pays and gets nothing**.

**Subscriptions** — each needs a **monthly** and an **annual** base plan:

| Product ID | Plan |
|---|---|
| `lemma_plus` | Plus — 500 credits/month |
| `lemma_family` | Family — 6 seats |
| `lemma_classroom` | Classroom — 30 seats |

**Credit packs** — must be marked **Consumable**, or they can only ever be bought once:

| Product ID | Grants |
|---|---|
| `lemma_credits_100` | 100 credits |
| `lemma_credits_400` | 400 credits |
| `lemma_credits_1200` | 1,200 credits |

> Products can only be created **after** you have uploaded a build containing the Billing library to
> some track (internal testing counts). So: upload first, then create products.

Test with **licence testers** (Setup → Licence testing) — they buy for free.

### C6. Store listing + policy forms

- **Target audience: 13+. Do NOT tick "Under 13."** The Families programme is incompatible with
  open-ended AI chat *and* user-to-user messaging — ticking it would force you to remove both. Every
  user-visible word already says *students / teachers / grades 7–12*; keep it that way. One stray
  "for kids" in a screenshot caption gives a reviewer grounds to reclassify the app.
- **Data safety form**: declare email/account, photos, messages, and that data goes to third parties
  (Firebase, Google Gemini). It asks for a **data-deletion URL** — use the privacy page.
- **Privacy policy URL**: `https://andi2010p.github.io/Lemm/privacy.html`
- **Content rating** questionnaire.
- Screenshots, feature graphic, short + full description (EN/RU/HY). Drafted in
  `docs/STORE_LISTING.md`.

### C7. ⏳ The 12-testers gate — **budget two extra weeks**

Personal (non-organisation) accounts created after 13 Nov 2023 **cannot publish to production** until
a **closed test has run with ≥12 testers opted in continuously for 14 days**, and they must actually
use the app. Start recruiting testers early — this is the longest pole in the whole process.

---

## PART D — The last thing you do, right before uploading

### D1. Remove guest mode

`StartActivity.GUEST_EMAIL` / `GUEST_PASSWORD` hardcode a **real Google account's password**. Keep it
while developing (it's your demo shortcut) — but it must not ship.

1. Delete the constants and `launchGuestPrefilled()` from `StartActivity`.
2. Delete `btnGuestMode` from `res/layout/activity_start.xml` + the `btnGuest` block in `onCreate`.
3. Delete `try_guest_mode` from all three `strings.xml`.
4. **Change that Google account's password** — it has been sitting in a public repo.

*(`GuestUser_` / `is_guest` elsewhere in the code is a different, local-only feature. It carries no
credentials and stays.)*

### D2. Final checks

- **Device-test the RELEASE build**, not just debug. R8 hides reflection breakage. Exercise: Google
  sign-in, AI solve (typed + photo), **history survives a reinstall**, purchase + restore, credit
  spend + top-up, messaging, report.
- Bump `versionCode` on **every** upload (currently `1`).
- Add **Crashlytics** for field crash reports.

---

## Cheat sheet — order of operations

```
A1 publish rules ──► A2 push terms.html ──► A3 SHA fingerprints ──► A4 recover old data
                                                    │
B1 Blaze + budget ──► B2 deploy functions ──► B3 migrations ──► B4 rotate keys ──► B5 App Check
                                                    │
C1 $25 ──► C2 payments ──► C4 keystore ──► C3 rename id ──► upload build ──► C5 products
                                                    │
C7 closed test, 12 testers, 14 days ──► D1 remove guest mode ──► PUBLISH
```
