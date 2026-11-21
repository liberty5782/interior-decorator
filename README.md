# Room Planner

Capture rooms with the phone camera as metric AR snapshots, place true-to-scale furniture and
wall decor, and compare layout options before buying anything.

- Plan & phases: [docs/plan.md](docs/plan.md)
- API & coordinate conventions: [docs/api.md](docs/api.md)

## Repo layout

| dir        | what | status |
|------------|------|--------|
| `server/`  | FastAPI + SQLModel + SQLite backend (uv, Alembic, pytest) | Phase 0 done |
| `android/` | Kotlin + Jetpack Compose + SceneView/ARCore app | AR capture spike |
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

The first build is a device check and capture test. It connects to the server, picks or creates a
room, opens an ARCore session showing detected floor and wall planes, and uploads AR snapshots
(JPEG + pose + intrinsics + planes + depth when the phone supports it).

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
```

Toolchain: Gradle 9.8.2, AGP 9.5.1, Kotlin 2.4.21, SceneView 4.66.0 (pulls in ARCore 1.57),
compileSdk 37, minSdk 26.

### Try it

1. Start the server (above) and note arrbox's LAN/VPN address.
2. In the app, enter e.g. `192.168.1.20:8000`, tap **Connect**, and add a room.
3. Tap **Capture snapshots** and move the phone slowly until floor/wall planes appear.
4. Tap **Capture**. The status line reports the image size, plane count and whether depth was
   included. Check it on the server: `curl http://<arrbox>:8000/api/rooms/<id>/snapshots`.
