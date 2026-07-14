# "Why would I pay for Lemma when Gemini is free?"

This is the question the whole business rests on, and it deserves an honest answer before a single
marketing word is written.

---

## The brutal version first

**If Lemma is "a nicer wrapper around Gemini", nobody will pay — and they shouldn't.**
Gemini is free, it's from Google, it's already on their phone, and it's better funded than you will
ever be. You cannot win that fight, and you must stop trying to.

You are not selling AI. **AI is a component, like the keyboard.** You are selling the things Gemini
*cannot do*, and the AI just makes them possible.

---

## What Gemini genuinely cannot do

Open Gemini and type: *"A circle has a tangent line at point A…"*. You get **paragraphs of text**.
Now do it in Lemma:

| | Gemini | **Lemma** |
|---|---|---|
| Answers a geometry question | ✅ | ✅ |
| **Draws the actual figure** | ❌ text only | ✅ a real 2-D/3-D figure |
| **Lets you rotate / zoom / tap the figure** | ❌ | ✅ |
| **Tap a theorem name → its page, proof and quiz** | ❌ | ✅ (`TheoremLinker`) |
| **Knows the Armenian school curriculum, grades 7–12** | ❌ | ✅ theorem library |
| **Explains like a 13-year-old needs, not like a search engine** | ❌ | ✅ no coordinates, no LaTeX |
| **Full Armenian** | ⚠️ weak | ✅ |
| **Saves your work and syncs it to every device** | ❌ | ✅ |
| **Share a solved problem with a classmate / a study group** | ❌ | ✅ |
| **A teacher can run a class on it** | ❌ | ✅ Classroom plan |

**The figure is the moat.** `GeometryCanvas3D` is ~1,800 lines of real geometry renderer. That is the
thing a competitor can't clone in a weekend and Gemini will never bother to build. A student sees
their problem *drawn*, correct, rotatable — and that's the moment they understand the app is not a
chatbot.

The one-line pitch is therefore **not** "AI that solves geometry". It is:

> **"Lemma draws your geometry problem, then teaches you why."**

---

## The funnel: how a stranger becomes a payer

Nobody pays for something they haven't felt. So the order is fixed, and every step has one job.

### 1. Let them in — no signup, no card, no paywall
Guest mode already works. **Do not put a login in front of the first solve.** Every screen between a
curious kid and their first drawn figure loses half of them.

### 2. The first 60 seconds must produce the "wow"
Their first problem must come back as a **drawn figure**. Not a wall of text. That is the entire
demonstration of why this isn't Gemini, and it has to happen before they get bored.

> Practical: make the empty solver screen offer **one tappable example problem** ("Try one →"), so a
> user who doesn't know what to type still reaches the wow. A blank text box is a conversion killer.

### 3. Give away the thing that costs you nothing
The **theorem library, the drawings, saved history, sharing** — none of that costs you a cent per
use. Never gate it. Gate only the **AI calls**, which are the only thing with a marginal cost.

### 4. Let them hit the limit *while they're winning*
3 solves/day is enough for a curious student and **not** enough for someone with homework due
tomorrow. The people who hit the wall are exactly the people for whom it's worth $4.99.

### 5. The paywall must show value, not a lock
Bad: *"You are out of tokens."*
Good: *"You've solved 47 problems with Lemma this month. Keep going — Plus gives you 500 a month and
our smarter model, for the price of one coffee."*

Show the **count of what they've already done**. Loss aversion beats feature lists.

### 6. Ask the person who actually has the money
A 13-year-old has no card. **The parent and the teacher do.** That's why:
- **Family (6 seats)** — a parent pays once, and every child in the house has their own account and
  their own progress. This is the highest-value plan per rial of ad spend.
- **Classroom (30 seats)** — one teacher brings **thirty students**. Your cheapest possible customer
  acquisition. A single teacher who likes Lemma is worth more than a hundred app-store impressions.

---

## Growth loops that cost nothing

1. **Sharing is free and un-metered.** A Plus student solves a problem and shares it into a group
   chat; five free classmates open it, see a beautiful figure, and *did not consume a credit* (nothing
   is re-solved). You just got five demos at zero marginal cost. **This is deliberate** — never meter
   viewing a shared solution.
2. **Teachers.** Give teachers Classroom free for a term at one school. If it works, you get the
   school. Bottom-up, not top-down.
3. **Armenian.** Google will not localise a geometry curriculum for Armenia. You already have. This
   is a market where you are not competing with Gemini at all — you are the only option that speaks
   the student's language *and* their textbook.

---

## What to do *before* you charge anyone

In order, and don't skip:

1. **Ship it free.** Get 100 real students using it. Watch where they quit.
2. **Look at `usage_totals/{month}/microUsd`.** You now measure your true cost per user. Do not guess.
3. **Only then turn on payments.** By that point you'll know what people actually value, and the
   paywall can be placed where it hurts least and converts most.

Charging on day one, before anyone knows what Lemma is, is the surest way to prove your own fear
right — that they'd rather use free Gemini.

---

## The honest risk

If, after 100 students use it free, they *still* don't want it — the answer is not a better paywall.
The answer is that the drawing and the curriculum aren't yet good enough to matter, and that's where
the work goes. The monetisation machinery is built and tested; it will still be there.
