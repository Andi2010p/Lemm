# Firebase / Google setup — exactly what to do, in order

Project: **lemma-37061**. Do these top to bottom; later steps depend on earlier ones.

---

## Does the $25 Google Play fee gate any of this?

**No — almost nothing.** This trips people up, so be clear:

| You want to… | Needs the $25 Play account? |
|---|---|
| Run the app, AI, chat, drawing, sync, **the free tier** | **No** |
| Fix Google Sign-In (SHA-1) | **No** |
| Deploy Cloud Functions, use Gemini | **No** |
| App Check / Play Integrity | **No** (works from a locally-installed build) |
| Test with friends via a shared APK | **No** |
| **Create in-app products / take real money** | **Yes** |
| **Publish on the Play Store** | **Yes** |
| Subscription verification + cancel notifications (RTDN) | **Yes** (Play Developer API) |

So: **build the whole app, run the free tier, and get real students using it — all without paying
Google a cent.** Pay the $25 only when you're ready to *sell*. (But remember the 12-testers /
14-days rule adds ~2 weeks before you can go live, so start that clock a couple of weeks before you
actually want to launch.)

---

## 1. Fix Google Sign-In (do this first — it's broken right now)

Firebase console → ⚙ **Project settings** → **Your apps** → Android app → **Add fingerprint**:

```
SHA-1  : 9D:FA:9A:4A:0F:EC:B6:7A:E6:22:F1:01:10:45:6E:55:84:1B:19:E9
SHA-256: 96:27:C3:AD:C8:E1:65:BF:58:91:CE:A7:56:AC:3E:D2:EA:92:2A:74:20:7A:9F:A2:14:E1:A6:39:EC:44:08:2D
```

Then **download the new `google-services.json`** into `app/`, **uninstall** the app from the device,
rebuild, reinstall. (Play Services caches sign-in config per package+certificate — a reinstall over
the top often keeps failing.)

Also: **Authentication → Sign-in method** → make sure **Google** and **Email/Password** are enabled.

---

## 2. Upgrade to Blaze — and what it actually costs

**Blaze is required** for Cloud Functions to make **outbound network calls**. On the free Spark plan
a function literally cannot reach `generativelanguage.googleapis.com`, so the whole AI backend is
impossible without it. There is no way around this.

**Blaze is pay-as-you-go, not a subscription.** The free quotas stay — you only pay past them:

| | Free every month | Then |
|---|---|---|
| Cloud Functions invocations | 2,000,000 | $0.40 / million |
| Realtime Database storage | 1 GB | $5 / GB |
| Realtime Database egress | 10 GB | $1 / GB |
| Cloud Functions egress | 5 GB | ~$0.12 / GB |

For a few hundred students, **you will almost certainly pay $0** for Firebase itself. Your real bill
is the **Gemini API**, which is separate (below).

### ⚠️ Do this the same day you enable Blaze

**Set a budget alert, or a bug can bankrupt you.** A runaway loop calling Gemini is a real risk.

1. Google Cloud console → **Billing → Budgets & alerts → Create budget**.
2. Set a monthly amount you're willing to lose (start at **$20**).
3. Set alert thresholds at 50% / 90% / 100%, emailed to you.

Budgets **alert**, they don't hard-stop. For a true kill-switch, cap it in Firebase too:
**Functions → ⋮ → Edit** → set **max instances** (the code already sets `maxInstances: 20`) and keep
Gemini spend visible via `usage_totals/{month}/microUsd` in your database.

---

## 3. Gemini API key — must be a *paid* key

The key currently compiled into the APK is a **free-tier** key. Free-tier Gemini is rate-limited per
project and **cannot serve a paying user base** — one busy evening exhausts it for everyone.

1. Google Cloud console → same project → **enable billing** (that makes your Gemini key a *paid* key;
   the free-tier limits lift and you pay per token).
2. Google AI Studio → create an API key **on that billed project**.

You'll be billed roughly:
- **Free-tier user** (`gemini-2.5-flash-lite`): ~$0.00086 per solved problem → **under 8¢/month** even
  if they max out 3 solves a day, every day.
- **Paid user** (`gemini-2.5-flash`): ~$0.0045 per solve → ~$2.25/month if they burn all 500 credits
  (they won't).

---

## 4. Deploy the backend

```bash
cd d:/Code/App/Lemma

firebase functions:secrets:set GEMINI_API_KEY        # the PAID key from step 3
firebase functions:secrets:set MAIL_USER             # lemmaofficial13@gmail.com
firebase functions:secrets:set MAIL_APP_PASSWORD     # a NEW Gmail app password (rotate the old one!)

firebase deploy --only functions
```

Then, **in this order** (it matters):

```bash
node tools/backfill-usernames.js            # dry run — read the report
node tools/backfill-usernames.js --apply    # claims every existing username for its real owner
```

…and **only then** paste `docs/database.rules.json` into
**Realtime Database → Rules → Publish**.

> Publishing the rules before the backfill locks existing users out of their own history, because the
> `users/` node's ownership is proven by the username claim the backfill creates.

---

## 5. Turn on Cloud AI in the app

Settings → **AI model & provider** → tick **"Use Lemma Cloud AI"**.

From then on every solve and chat runs on the server with your paid key and is metered against the
user's plan. Verify one solve works, then you can **delete the keys from `local.properties`** and the
`buildConfigField` lines — the app no longer needs them.

(Still to wire before the keys can go entirely: the **scanner OCR** and the **email OTP**.)

---

## 6. App Check — do this LAST

`google-services.json` ships inside every APK, so anyone can extract it and hit your database and
backend with a script. App Check proves the caller is a genuine Lemma install.

1. Firebase console → **App Check** → register the Android app with **Play Integrity**.
2. Run your debug build; it prints an App Check **debug token** in logcat. Register it:
   **App Check → Apps → ⋮ → Manage debug tokens**.
3. **Only then** click **Enforce** on **Realtime Database** and **Cloud Functions**.

> Enforce *before* registering the debug token and your own debug build stops working — every read
> fails, and it looks exactly like a broken app. Register first, enforce second.

---

## Order of operations, condensed

```
1. SHA-1 fingerprint          → fixes Google Sign-In        (free)
2. Blaze + budget alert       → unblocks Cloud Functions    (≈$0 for small scale)
3. Billing on Gemini project  → a key that can serve users  (pay per token)
4. Deploy functions → backfill usernames → publish rules
5. Toggle "Cloud AI" in the app, verify a solve
6. App Check: register debug token → enforce
────────────────────────────────────────────────────────────
   ↑ everything above needs NO Play account
7. ($25) Play Console → products, RTDN, closed test, launch
```
