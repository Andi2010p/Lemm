# Lemma — Presentation Script (grouped)

A clean, sectioned story for the deck: **what the app is, why it's useful, and how it works.**
Each slide has a title, the on‑slide bullets, and *speaker notes*. Drop your existing app
screenshots into the matching feature group. To generate a `.pptx` from this, run
`build_presentation.py` (see the README at the bottom).

---

## SECTION 1 — Intro

### Slide 1 · Title
**Lemma — Geometry, finally visual.**
AI + CAD geometry tutor for students (grades 7–12).
*Notes: One line — "Lemma helps students understand geometry by solving, drawing and exploring it."*

### Slide 2 · The problem
- Geometry is **abstract** — students struggle to *picture* it.
- Worked solutions are hard to find and rarely **explained step by step**.
- Drawing accurate figures (2D and 3D) on paper is slow and error‑prone.
- Most tools are English‑only — a barrier for many learners.
*Notes: Set up the pain before the solution.*

---

## SECTION 2 — The solution (what Lemma is)

### Slide 3 · One app, three superpowers
- **Solve** — an AI tutor that answers any geometry problem, step by step, with a figure.
- **Draw** — a real CAD editor (2D + 3D) to build and measure shapes.
- **Learn** — a theorem library with clear explanations and quizzes.
*Notes: This is the core positioning. Everything else supports these three.*

### Slide 4 · How it feels to use
- Type, **photograph (OCR)**, or **speak** a problem → get the answer + a 3D figure.
- Sketch a shape → **extrude it into a 3D solid** → tap any part to measure it.
- Pick a grade → study a theorem → take a quick quiz.
*Notes: Walk through the three loops quickly. (Screenshots here.)*

---

## SECTION 3 — Why it's useful

### Slide 5 · Value for students
- **See it, don't just read it** — every problem becomes an interactive figure.
- Learn the *method*, not just the answer (each step names the theorem used).
- Practice with built‑in quizzes; progress saved and synced.
- **Three languages: English, Russian, Armenian** — learn in your own language.
*Notes: Emphasize understanding over memorizing.*

### Slide 6 · Value for teachers & schools
- A ready visual aid for class — generate figures on the fly.
- Covers the curriculum **grades 7–12** with proofs and examples.
- Works **offline**, syncs to the cloud — usable on any device the student logs into.
*Notes: Position for adoption beyond a single student.*

---

## SECTION 4 — Features (grouped)

### Slide 7 · Feature group A — AI Geometry Solver
- Input by **text, photo (OCR), or voice**.
- **Step‑by‑step** solutions; theorem named at each step; clean final answer.
- Auto‑generated **interactive 3D figure** (tap to inspect).
- Powered by **Gemini** with automatic model fallback + multi‑key reliability.
*Notes: Screenshots: input screen, a solved problem, the 3D figure.*

### Slide 8 · Feature group B — 2D CAD Drawing
- Lines, rectangles, circles; **snapping**, **dimensions**, angles; undo/redo.
- Tap a shape to see length / area / angle.
- One‑tap **Extrude** to turn a drawing into a 3D solid.
- Toolbar grouped: **View · Draw · Edit · File**.
*Notes: Screenshots: the 2D board with a dimensioned shape.*

### Slide 9 · Feature group C — 3D CAD Sketcher
- Orbit / pan / zoom; **sketch faces by hand** on a plane.
- **Extrude** profiles into real solids (a rectangle → an 8‑point box).
- Lettered points, edge **midpoints**, **angle arcs with values**, on‑figure **length labels**.
- **Select & edit values** (point X/Y/Z, edge length, angle°), **undo/redo**, save.
*Notes: This is the standout — "a mini‑SolidWorks for students." Screenshots: a box with labels.*

### Slide 10 · Feature group D — Theorems & Quizzes
- **33 theorems**, grades 7–12.
- Each: a **drawing**, the statement, a **simple explanation**, a proof, real‑life uses, and a **quiz**.
- Immediate feedback; learn by doing.
*Notes: Screenshots: a theorem page + quiz.*

### Slide 11 · Feature group E — History & Cloud
- Saved solutions and drawings with **auto‑generated thumbnails**.
- **Firebase** cloud sync with offline support and background sync.
- Open anything again on any device.
*Notes: Screenshot: history list with thumbnails.*

### Slide 12 · Feature group F — Onboarding & Languages
- **Animated** first‑run guide explaining every feature (replayable from Settings).
- In‑app **Help** on each screen ("how to use this screen").
- Full **EN / RU / HY** localization.
*Notes: Screenshot: an onboarding slide.*

---

## SECTION 5 — Under the hood

### Slide 13 · Architecture & tech
- **Android (Java)**; custom CAD engines (JTS for 2D geometry; a hand‑built 3D engine).
- **Gemini** AI; **Firebase** Auth + Realtime DB; **SQLite** (DAO‑split) for local data.
- MVVM (Repository + ViewModel) on the History feature; manual DI container.
- Security: secrets out of source (BuildConfig); **PBKDF2** password hashing.
*Notes: Show you've thought about engineering quality.*

---

## SECTION 6 — Business & users

### Slide 14 · Who it's for / Pro
- **Students 7–12**, teachers, and self‑learners.
- **Free** tier (use your own AI key) + **Pro** tier (built‑in AI, unlimited), synced across devices.
*Notes: Brief monetization.*

---

## SECTION 7 — Close

### Slide 15 · Roadmap
- 3D CAD: sketch on any face, **dimensions & constraints**, revolve.
- More theorems, more languages, teacher dashboards.
- Tests & CI.
*Notes: Show momentum.*

### Slide 16 · Thank you
**Lemma — see geometry, understand geometry.**
*Notes: Call to action: try it / scan the QR / contact.*

---

## How to build the .pptx
On a machine with Python:
```
pip install python-pptx
python build_presentation.py
```
This generates **Lemma_Presentation.pptx** with section‑divider slides, the content above, speaker
notes, and the app's blue theme. Then open it and drop your screenshots into the feature groups
(Section 4) where the notes say "Screenshots here".
