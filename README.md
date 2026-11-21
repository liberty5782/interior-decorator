# Room Planner

Capture rooms with the phone camera as metric AR snapshots, place true-to-scale furniture and
wall decor, and compare layout options before buying anything.

- Plan & phases: [docs/plan.md](docs/plan.md)
- API & coordinate conventions: [docs/api.md](docs/api.md)

## Repo layout

| dir        | what | status |
|------------|------|--------|
| `server/`  | FastAPI + SQLModel + SQLite backend (uv, Alembic, pytest) | Phase 0 done |
| `android/` | Kotlin + Jetpack Compose + SceneView/ARCore app | Phase 1: capture + photo planner |
| `viewer/`  | Three.js walkthrough viewer | Phase 3 |
| `docs/`    | plan and API contract | |

## Server

### Run with Docker Compose (arrbox)

```sh
mkdir -p data
PUID=$(id -u) PGID=$(id -g) docker compose up -d --build
curl http://localhost:8000/api/health
```

The container applies database migrations on start. Everything persistent (SQLite DB plus
images, depth maps and GLBs) is in `./data`, so back that folder up.
API docs: `http://<arrbox>:8000/docs`. Keep it on the LAN/VPN, because there is no auth.

### Develop locally

```sh
cd server
uv sync
uv run alembic upgrade head                      # creates ./data/roomplanner.db
uv run uvicorn app.main:create_app --factory --reload --host 0.0.0.0
uv run pytest                                    # tests run against the real migrations
uv run ruff check . && uv run ruff format .
```

### Settings (env vars)

| var | default | |
|-----|---------|---|
| `ROOMPLANNER_DATA_DIR` | `./data` (`/data` in Docker) | DB + media root |
| `ROOMPLANNER_DATABASE_URL` | SQLite in data dir | e.g. a Postgres URL later |
| `ROOMPLANNER_MAX_UPLOAD_MB` | `100` | per file |
| `ROOMPLANNER_CORS_ORIGINS` | `["*"]` | JSON list; for the browser viewer |

### Changing the schema

Edit `server/app/models.py`, then:

```sh
uv run alembic revision --autogenerate -m "describe change"   # review the generated file
uv run alembic upgrade head
```

## Android app

The app connects to the server and has three parts:

- **Capture** (needs ARCore): shows detected floor and wall planes and uploads AR snapshots (JPEG +
  pose + intrinsics + planes + depth when the phone supports it). Failed uploads can be retried.
- **Item catalog**: furniture and wall decor with dimensions in cm from the product listing, plus
  an optional image (for decor, the picture that hangs on the wall).
- **Plan** (Phase 1, works without ARCore): shows a saved snapshot with a virtual camera matching
  the real one. Add items from the catalog: furniture is a true-to-scale box standing on the
  detected floor, decor is the product image on a detected wall. Tap to select, drag to move,
  rotate in 15° steps, remove. Each room has layouts ("Option A", "Option B", …) to compare;
  duplicate one to try a variation. Changes save automatically.

Placements line up on photos from the same AR session (same world origin). Capture several
photos of a room in one go to see a layout from different angles.

The app installs on phones without ARCore (`com.google.ar.core` is `optional`). The home screen
reports ARCore support and disables capture where it's unavailable. Only `capture/ArCoreCapture.kt`
and the capture screen touch ARCore; everything else works with `CapturedSnapshot`, so a
non-ARCore capture path can be added later.

### Build

Needs JDK 17+ and the Android SDK with platform 37 (Android Studio installs it), or set
`sdk.dir` in `android/local.properties`.

```sh
cd android
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # with the phone connected over adb
./gradlew :app:testDebugUnitTest    # geometry, API contract, form and planner rendering tests
```

`PlanCanvasRenderTest` renders the planner with a synthetic room on the JVM (Robolectric) and
writes PNGs to `android/app/build/screenshots/`.

Toolchain: Gradle 9.8.2, AGP 9.5.1, Kotlin 2.4.21, SceneView 4.66.0 (pulls in ARCore 1.57),
compileSdk 37, minSdk 26.

### Try it

1. Start the server (above) and note arrbox's LAN/VPN address.
2. In the app, enter e.g. `192.168.1.20:8000`, tap **Connect**, and add a room.
3. Tap **Capture snapshots** and move the phone slowly until floor/wall planes appear.
4. Tap **Capture**. The status line reports the image size, plane count and whether depth was
   included. Take a few photos of the room from different spots.
5. Back on the home screen, open **Item catalog** and add something you're considering, e.g.
   a sofa at 210 × 90 × 80 cm.
6. Open **Plan**, tap **Add item**, pick it and tap the floor. Drag it around, rotate it, and
   flip through the photos (◀ ▶) to check it from other angles.
