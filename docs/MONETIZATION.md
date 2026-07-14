# Lemma — Subscriptions

How the plans work, what happens after the money moves, and why each number is what it is.
Written 2026-07-10. Prices verified against Google Play's fee schedule and Gemini's API pricing.

---

## 1. The unit economics, first

Everything else follows from one number.

Gemini 2.5 Flash costs **$0.30 per 1M input tokens** and **$2.50 per 1M output tokens** — output is
**8.3× more expensive than input**. One Lemma solve is roughly:

| | tokens | cost |
|---|---|---|
| input (system prompt + exemplars + the problem) | ~2,600 | $0.00078 |
| output (drawing commands + worked steps) | ~1,500 | $0.00375 |
| | | **≈ $0.0045 — about half a US cent** |

> **A bug this exposed.** The old client-side meter counted `problem + answer` characters ÷ 4. But
> Gemini bills the **entire prompt**, including the multi-thousand-token system prompt and the
> few-shot exemplars, **on every single call**. The app was metering ~1,000 tokens for a call that
> really cost ~4,100 billable tokens. The developer silently ate the difference — roughly **4×**.
> The backend now meters `usageMetadata` from Gemini's own response, and records real micro-dollars
> in `usage/{uid}` and `usage_totals/{month}`.

---

## 2. Why "credits", not "tokens"

Tokens are meaningless to a 14-year-old, and they're a *bad cost proxy* anyway (an output token costs
8.3× an input token). So there are two separate units:

- **Credits** — what the user sees. **1 credit = 1 solved problem.** A tutor chat reply is 0.25.
- **Micro-dollars** — what the developer sees. Never shown, never billed, always recorded.

This is the Duolingo move: hearts and streaks are units a child understands, not server cycles.

---

## 3. The plans

| | Free | **Lemma Plus** | **Family** | **Classroom** |
|---|---|---|---|---|
| Price | — | **$4.99/mo** or **$39.99/yr** | **$9.99/mo** or **$79.99/yr** | **$29.99/mo** |
| Seats | 1 | 1 | **up to 6** | **up to 30** |
| AI allowance | **3 solves/day** | **500 credits/month** | 500 **per seat** | 400 **per seat** |
| AI model | `flash-lite` | **`flash` (smarter)** | `flash` | `flash` |
| Model choice (GPT/Claude) | ✗ | ✓ | ✓ | ✓ |
| Top-up credits | ✓ | ✓ | ✓ | ✓ |

### Why a free tier is affordable — the model tier is the whole trick

The plan picks the **model**, and the models differ enormously in price:

| model | in / out per 1M | cost of one solve |
|---|---|---|
| `gemini-2.5-flash-lite` (free tier) | $0.10 / $0.40 | **$0.00086** |
| `gemini-2.5-flash` (paid tiers) | $0.30 / $2.50 | **$0.0045** — 5.3× more |

A free user who maxes out **3 solves a day, every day, for a month** costs you:

```
3 × 30 × $0.00086 ≈ $0.077   ← under 8 cents a month, worst case
```

So **1,000 fully-maxed free users ≈ $77/month**, and realistically far less because almost nobody
maxes out every day. That is what makes a free tier viable at all — and it is asserted as a test
(`THE FREE TIER IS AFFORDABLE` in `functions/test/backend.test.js`), so it can't silently regress.

It also gives you an **honest** upgrade reason: Plus isn't "the same AI, but we stop blocking you" —
Plus genuinely *thinks with a better model*. Free is fast and cheap; Plus is smart.

**Free is a *daily* allowance, deliberately.** A monthly bucket gets burned in one weekend binge and
the user forgets the app exists. A daily reset builds the return habit — the same reason Duolingo
resets hearts daily.

**Fair-use caps are set from real usage, not from break-even.**

```
Plus @ $4.99  →  $4.24 after Google's 15% fee
500 credits × $0.0045 = $2.25   ← absolute worst case, user burns every credit
                                  ⇒ still ≥47% margin
Typical user: 30–60 solves/month ≈ $0.20 cost  ⇒ ~95% margin
```

A heavy user is **profitable**, not merely survivable. That is the test a fair-use cap must pass.

**Annual base plans matter more than they look.** They convert a churn-prone monthly payer into
twelve months of revenue collected up front, and Play charges the same 15%.

### Top-ups (consumables)

| Product | Credits | Roughly |
|---|---|---|
| `lemma_credits_100` | 100 | 100 problems |
| `lemma_credits_400` | 400 | 400 problems |
| `lemma_credits_1200` | 1200 | 1200 problems |

Purchased credits live in a **separate, non-expiring bucket** and are spent **only after** the
monthly allowance is exhausted. Spending the perishable bucket first is the honest order — the user
keeps what they paid cash for. (Verified: `functions/test/backend.test.js`.)

---

## 4. "The user gives money — then what?"

```
  ┌────────┐  1. buys       ┌───────────┐
  │  User  │───────────────▶│ Google    │  Google takes 15% (first $1M/yr), then 30%
  └────────┘                │ Play      │
       ▲                    └─────┬─────┘
       │                          │ 2. purchaseToken (opaque)
       │                          ▼
       │                    ┌───────────┐
       │                    │  Lemma    │  3. verifyPurchase(token)
       │                    │  app      │─────────────────────────┐
       │                    └───────────┘                         ▼
       │                                                   ┌─────────────┐
       │  6. app reads entitlements/{uid}  ◀───────────────│  Cloud      │
       │     (read-only; it can never write it)            │  Function   │
       │                                                   └──────┬──────┘
       │                                                          │ 4. purchases.subscriptionsv2.get
       │                                                          ▼
       │                                                   ┌─────────────┐
       │                                                   │  Google     │  "this token = lemma_plus,
       │                                                   │  Play API   │   active, expires 2026-08-10"
       │                                                   └──────┬──────┘
       │                                                          │ 5. writes
       │                                                          ▼
       │                                                   entitlements/{uid} = {plan, expiryMs}
       │
       └── Later: Play pays you monthly (USD wire to Armenia). You pay Google for the Gemini API
           out of that. Margin = subscription − Play fee − Gemini cost.
```

**Nothing about the plan is decided by the phone.** The app hands over an opaque token; the server
asks *Google* what it means. `entitlements/{uid}` is `.write: false` for every client — a fact that
is [executed as a test](../tools/rules-tests/rules.test.js), not merely asserted.

### And when they cancel?

That's what **Real-time developer notifications** are for. Play publishes to a Pub/Sub topic on every
renewal, cancellation, grace period, refund and revocation; `playRtdn` re-queries the API and
rewrites the entitlement. **Without RTDN a user cancels, Play stops charging them, and the app keeps
granting Plus forever** because nothing ever revisits the state.

A subscription in `IN_GRACE_PERIOD` (a card that failed and is being retried) **keeps working**.
Cutting off a paying customer mid-re-auth is how you earn a one-star review.

---

## 5. The collective plan

Google Play has **no native subscription-seat sharing** (Apple's Family Sharing does; Play's Family
Library covers apps and one-time purchases, not subscription seats). So the seats are ours to model —
exactly as Duolingo does it.

```
one payer  →  families/{familyId} { owner, plan, seatLimit: 6, expiryMs, seats: {uid: name} }
              │
              ├─ inviteToFamily(username)   → family_invites/{inviteeUid}/{familyId}
              └─ acceptFamilyInvite()       → seat taken atomically, entitlements/{uid} written
```

Three properties, all deliberate:

1. **Invitations require acceptance.** Nobody is silently enrolled into your plan, and nobody can
   drag you into theirs.
2. **Seats are taken inside a transaction.** Six people accepting the last seat simultaneously →
   exactly one succeeds. (Same reason credits are transactional.)
3. **Each seat is a full, independent account** — own uid, own progress, own credit quota. Nothing is
   pooled *except the payment*.

### "What if two of them use the app at the same time, and one has no subscription?"

This is the question the architecture is built around, and the answer is: **entitlement is resolved
per-uid, on the server, on every request.**

- Two family members solving simultaneously → each has their own `wallet/{uid}`. One member burning
  their 500 credits **cannot touch the other's**. Verified by test.
- A seat-holder and a free user in the same group chat → the seat-holder gets Plus limits, the free
  user gets 3/day. Sharing a chat has never shared a subscription.
- Two devices signed into **one** account, both solving at once → the credit charge is an RTDB
  **transaction**, so they cannot both spend the last credit. Verified by test: 10 concurrent charges
  against a 3-credit balance ⇒ exactly 3 succeed.
- Someone with **both** an individual Plus *and* a family seat → resolution takes the better one; they
  are never double-charged and never downgraded.
- The plan lapses → RTDN fires → every seat drops to Free. **No data is deleted.** Their solutions,
  drawings and chats stay; only the AI allowance shrinks.

---

## 6. Good for the user *and* good for you

The two are the same design decision seen from opposite sides.

| Decision | Good for the user | Good for you |
|---|---|---|
| Credits = problems, not tokens | Understandable: "≈ 500 problems" | You meter real cost separately, and can *see* it |
| Free = 3/day, not 30/month | Comes back daily; never hits a wall mid-homework | Habit loop; tail cost capped at ~$0.40/user/mo |
| Fair-use 500, not "unlimited" | Never actually reached by a real student | A heavy user still yields ≥47% margin |
| Allowance spent before purchased credits | Keeps what they paid cash for | Perishable inventory used first |
| Refund on a failed/blocked AI reply | Never charged for an error message | Trust; fewer refund requests and 1-star reviews |
| Grace period keeps working | Doesn't get locked out over an expired card | Recovers the payment instead of losing the customer |
| Family = 6 real accounts | Each child keeps their own progress | One payment, ~6× reach, far lower CAC per seat |
| Annual base plan | ~33% cheaper | Twelve months of revenue up front, churn removed |
| Viewing a shared solution is free | Sharing with friends costs nothing | Free virality; the sharer pays nothing to recruit |

The last row is the growth loop: a Plus user solves a problem, shares it into a group chat, and five
free users see Lemma's output — at **zero marginal cost**, because nothing is re-solved.

---

## 7. What you must set up

**Play Console → Monetise**

1. Subscriptions — create `lemma_plus`, `lemma_family`, `lemma_classroom`. Give each **two base
   plans**, `-monthly` and `-annual` (Play models subscriptions this way since Billing v5).
2. In-app products — `lemma_credits_100 / _400 / _1200`, **type: consumable**.
3. Monetisation setup → **Real-time developer notifications** → topic `play-rtdn`.
4. The app **must** call `BillingFlowParams.setObfuscatedAccountId(firebaseUid)` when launching the
   billing flow. It's how the server proves the purchase belongs to the account redeeming it.

**Firebase**

```bash
firebase functions:secrets:set GEMINI_API_KEY
firebase functions:secrets:set MAIL_USER
firebase functions:secrets:set MAIL_APP_PASSWORD
firebase deploy --only functions
# then, and only then:
node tools/backfill-usernames.js --apply
# then publish docs/database.rules.json
```

Also: a **Google Cloud service account with Play Developer API access** (Play Console → Users and
permissions → invite the service account, grant "View financial data" + "Manage orders and
subscriptions"), and **Blaze billing** — Cloud Functions cannot make outbound calls to Gemini or Play
on the free Spark plan.

**Enable App Check** (Play Integrity) and enforce it on Realtime Database *and* Cloud Functions.
Every callable already sets `enforceAppCheck: true`; the console switch is what makes it bite.

---

## 8. Tunable knobs

All in `functions/lib/plans.js`, one place:

```js
PRICE  = { solve: 1000, chat: 250, scan: 1000 }   // milli-credits per request kind
PLANS.free.dailyCredits      = 3 * MILLI
PLANS.plus.monthlyCredits    = 500 * MILLI
PLANS.family.seats           = 6
COST                         = { INPUT_PER_MTOK: 0.30, OUTPUT_PER_MTOK: 2.50 }
```

Watch `usage_totals/{yyyy-mm}/microUsd` for a month before touching any of them. It is the only
number that tells you whether the plans are priced right, and it did not exist before.
