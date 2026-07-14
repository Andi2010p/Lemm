# Lemma — AI + CAD Geometry Tutor

**Lemma** is an Android app that helps students (grades 7–12) understand and solve geometry by
combining an **AI geometry solver** with a **full CAD drawing editor** (2D and 3D), a library of
**theorems with explanations and quizzes**, and **cloud‑synced history** — all available in
**three languages: English, Russian, and Armenian (EN / RU / HY)**.

---

## ✨ Features

### AI Geometry Solver
- Type a problem, **photograph it** (OCR), or **dictate** it by voice.
- Step‑by‑step solutions with the theorem/formula named at each step and a clean final answer.
- Generates an interactive **3D figure** for the problem (points, lines, planes, cones, pyramids,
  cylinders, spheres, prisms, and angle arcs).
- Powered by Google **Gemini** with an automatic model fallback and multi‑key rotation for
  reliability.

### 2D CAD Drawing Editor
- Parametric sketching: lines, rectangles, circles; snapping to vertices/midpoints; dimensions and
  angles; undo/redo via a state stack.
- Tap any shape to inspect it (length, endpoints, angle, slope, area, perimeter…).
- Toolbar grouped into **View / Draw / Edit / File**.
- **Extrude** a closed shape into a 3D solid.

### 3D CAD Sketcher
- Orbit / pan / zoom with an orthographic (parallel) projection so parallels stay parallel.
- **Draw** points/faces by hand on the sketch plane, **+Line**, **+Point**, **Angle** (arc + value),
  **Extrude** (a rectangle becomes a real 8‑point box), **Select** (full element details).
- Every point is auto‑lettered; every edge shows its midpoint.
- **Save / load** 3D drawings (local DB + cloud) with thumbnails in History.

### Theorems & Quizzes
- 33 theorems across grades 7–12, each with a figure, a plain‑language explanation, a proof,
  real‑life hints, and one or more comprehension questions — fully localized.

### History & Cloud Sync
- Saved solutions and drawings with generated **thumbnails**.
- **Firebase Realtime Database** sync with offline support and background sync.

### Onboarding
- An animated, student‑friendly how‑to guide on first launch (Next / Back / Skip with confirm),
  replayable from Settings.

### Accounts
- Email/password and Google sign‑in (Firebase Auth). Local passwords are stored **salted + hashed**
  (PBKDF2). Optional **Pro** tier that unlocks the built‑in AI key across the user's devices.

---

## 🧱 Tech Stack

| Area | Technology |
|------|------------|
| Language | Java (Android) |
| Min / Target SDK | 24 / 36 |
| AI | Google Gemini (`com.google.ai.client.generativeai`) |
| Geometry | JTS Topology Suite (`org.locationtech.jts`) |
| Local storage | SQLite (`DatabaseHelper` + `UserDao` / `HistoryDao` / `DrawingDao`) |
| Cloud | Firebase Realtime Database + Firebase Auth |
| Email | JavaMail (SMTP) for OTP/notification emails |
| UI | Android Views + Material Components; custom `CadGeometryCanvas` (2D) and `GeometryCanvas3D` (3D) |

---

## 📂 Project Structure (high level)

```
app/src/main/java/com/example/lemm/
├── MainActivity, StartActivity, LoginActivity, RegisterActivity   # entry & auth
├── GeometryInputActivity                                          # AI solver + 3D figure
├── GeminiAI                                                       # Gemini wrapper (models, config)
├── DrawingActivity, CadGeometryCanvas, CadEngine2d               # 2D CAD editor
├── Drawing3DActivity, GeometryCanvas3D                            # 3D CAD sketcher
├── TheoremsActivity, TheoremListActivity, GradeCurriculumActivity# theorems + quizzes
├── HistoryActivity                                               # saved solutions/drawings
├── DatabaseHelper, DbSchema, UserDao, HistoryDao, DrawingDao     # local SQLite (facade + DAOs)
├── FirebaseManager, CloudSyncManager                             # cloud sync
├── ProStatusManager, BillingManager                              # subscription
├── PasswordHasher                                                # PBKDF2 password hashing
├── OnboardingActivity, OnboardingAnimationView                   # first-run guide
└── LocaleHelper                                                  # EN / RU / HY switching

app/src/main/res/
├── values/      values-ru/      values-hy/                       # localized strings (3 languages)
└── layout/ drawable/ ...
```

---

## 🚀 Building

### Prerequisites
- Android Studio (recent) / Android SDK 36
- A Firebase project (`google-services.json` in `app/`)
- A Google **Gemini** API key

### 1. Configure secrets in `local.properties`
`local.properties` is **git‑ignored** — keep all secrets here, never in source:

```properties
sdk.dir=/path/to/Android/sdk

# Gemini (Pro users use the app key; free users can add their own in Settings)
GEMINI_API_KEY=YOUR_GEMINI_KEY
GEMINI_BACKUP_KEYS=key2,key3        # optional, comma-separated fallback keys

# Transactional email (SMTP) for OTP / notifications
MAIL_USER=youraddress@gmail.com
MAIL_APP_PASSWORD=xxxx xxxx xxxx xxxx   # a Google "App Password"
```

> These are exposed at build time via `BuildConfig` (see `app/build.gradle.kts`). If a value is
> missing, the related feature degrades gracefully (e.g. email is skipped and logged).

### 2. Add Firebase
Place your `app/google-services.json` from the Firebase console (Realtime Database + Auth enabled).

### 3. Build / run
```bash
./gradlew :app:assembleDebug      # build a debug APK
# or open in Android Studio and Run
```
The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔐 Security notes
- App secrets (Gemini key, SMTP app‑password) are injected from `local.properties` → `BuildConfig`,
  not committed to source.
- Local account passwords are hashed with **PBKDF2** (salted, constant‑time verify); legacy
  plaintext rows are migrated transparently on the next successful login.
- If any secret was ever committed, **rotate it** (it remains in git history).

---

## 🌍 Localization
All user‑facing strings live in `res/values/` (EN), `res/values-ru/` (RU), and `res/values-hy/`
(HY). Language is switched at runtime via `LocaleHelper`.

---

## 🗺️ Roadmap
- 3D CAD: sketch on arbitrary faces/planes, dimensions & constraints (parallel/perpendicular/equal),
  drag‑to‑edit, revolve.
- Architecture: introduce ViewModel/Repository and dependency injection.
- Tests and CI.

---

## 📄 License
Proprietary / educational project. (Add a license here if you intend to open‑source it.)
