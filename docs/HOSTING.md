# Hosting Lemma — the short version

Everything you deploy for Lemma is now a handful of commands. Run them from the repo root.

```bash
npm run doctor     # what's set up, what's still on you (read-only, safe to run any time)
npm run setup      # one-time: log in, install deps, set the server secrets
npm run deploy     # test the rules, lint the functions, then deploy DB + Storage + Functions
```

`npm run deploy` **refuses to ship** if the security-rules tests or the functions lint fail, so a
broken rule or a typo can never reach production.

## First time (once)

1. **Turn on Blaze billing** — Cloud Functions and Storage require it. Firebase console → Usage &
   billing → Modify plan → Blaze. Set a **$10/month budget alert** immediately (Google Cloud → Billing
   → Budgets) so a bug can't become a big bill.
2. `npm run setup` — installs `firebase-tools`, logs you in, installs deps, and prompts for the three
   secrets (`GEMINI_API_KEY`, `MAIL_USER`, `MAIL_APP_PASSWORD`). They're stored by Google, never in the
   repo or the APK.
3. `npm run deploy`.
4. In the console, flip on **Authentication → Email/Password + Google**, and register the **App Check**
   debug token before enforcing (see `RELEASE_CHECKLIST.md`).

The project is already linked (`.firebaserc` → `lemma-37061`), so there's no `firebase use` step.

## Everyday

| I changed… | Run |
|---|---|
| security rules (`docs/database.rules.json`, `docs/storage.rules`) | `npm run deploy:rules` |
| Cloud Functions (`functions/`) | `npm run deploy:functions` |
| both | `npm run deploy` |
| nothing, just checking | `npm run doctor` |

## Hands-off (GitHub Actions)

- **CI** (`.github/workflows/ci.yml`) runs on every push/PR: security-rules tests, functions lint, and
  an Android debug build (the APK is uploaded as a build artifact). Red CI = something's broken before
  it ships.
- **Deploy** (`.github/workflows/deploy.yml`) is a button — Actions tab → **Deploy** → *Run workflow* →
  pick what to deploy. One-time: add a `FIREBASE_SERVICE_ACCOUNT` repo secret (the JSON from Firebase
  console → Project settings → Service accounts → Generate new private key). After that you can deploy
  the backend without a terminal at all.

## What still can't be automated

Google Play (developer account, keystore, the 12-tester closed test) and turning on billing are
account/legal actions only you can do — those live in `RELEASE_CHECKLIST.md`.
