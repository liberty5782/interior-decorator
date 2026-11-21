# CLAUDE.md

Household room planner: capture rooms as metric AR snapshots on an Android phone, place
true-to-scale furniture and wall decor, and compare layout options before buying.
The full plan and phases are in `docs/plan.md`; the API and coordinate contract is in `docs/api.md`.
Read both before changing anything that crosses the app/server boundary.

## Repo layout

- `server/`: FastAPI + SQLModel + SQLite (uv, Alembic, pytest). Self-hosted on the home server
  ("arrbox") via `docker-compose.yml` at the repo root. LAN/VPN only, **no auth by design**.
- `android/`: Kotlin + Jetpack Compose app (SceneView/ARCore for capture). Package `app.roomplanner`.
  - `api/`: OkHttp + kotlinx.serialization client mirroring `server/app/schemas.py`.
  - `capture/`: the **only** ARCore-dependent code besides `ui/CaptureScreen.kt`. Produces a
    platform-neutral `CapturedSnapshot`.
  - `geometry/`: pure Kotlin math (no Android/ARCore types): `Mat4`, `SnapshotCamera`,
    `PlacementRules`, `ImageFit`. Shared by the photo planner and the future live-AR mode.
  - `planner/`: Phase 1 photo planner. `SnapshotScene` holds the logic (unit-tested);
    `PlannerScreen`/`PlanCanvas` hold the Compose UI and drawing.
  - `ui/`: home, capture, catalog screens; `ItemForm` (validation, cm↔m).
- `viewer/`: Three.js walkthrough viewer (Phase 3, not started).

## Commands

Server (from `server/`):
```sh
uv sync
uv run pytest                         # runs against the real Alembic migrations
uv run ruff check . && uv run ruff format .
uv run alembic upgrade head && uv run uvicorn app.main:create_app --factory --reload --host 0.0.0.0
```

Android (from `android/`; needs JDK 17+ and Android SDK platform 37, `sdk.dir` in `local.properties`):
```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest      # unit + Robolectric render tests
./gradlew :app:lintDebug              # keep it at zero issues
```

- `PlanCanvasRenderTest` renders the planner with a synthetic room and writes PNGs to
  `android/app/build/screenshots/`. Look at them after changing drawing or geometry code.
- `ServerRoundTripTest` runs the real API client against a live server; it is skipped unless
  `ROOMPLANNER_TEST_SERVER=host:port` is set. Use a throwaway server (`ROOMPLANNER_DATA_DIR` pointing at a temp dir).
- If Gradle keeps failing on a download that works with curl, rerun with `--no-configuration-cache`
  (the configuration cache can replay an old resolution failure).

## Conventions that must not drift

- **Units and axes:** metres, +Y up (ARCore world space). Matrices are 16 floats, **column-major**
  (ARCore `Pose.toMatrix`, Three.js `Matrix4.fromArray`); translation at indices 12–14.
- **Snapshots:** image is ARCore's CPU image in **sensor orientation** (not display-rotated); `pose`
  is `Camera.getPose()` (world-from-camera, OpenGL camera looking down -Z); `intrinsics` from
  `Camera.getImageIntrinsics()`. The planner derives the upright rotation from the pose.
  Depth is `uint16_le_mm` (ARCore DEPTH16).
- **Item frames:** furniture origin = centre of footprint on the floor; wall decor origin = centre
  of back face on the wall; +Z = item front / out of the wall.
- **AR sessions:** each ARCore session has its own world origin. Snapshots carry `session_id`;
  a placement only lines up on snapshots with the same `session_id` as the snapshot it was placed on.
- **ARCore is optional:** the app must install and run (catalog, planner) on phones without
  Google Play Services for AR. Keep ARCore imports confined to `capture/` and `CaptureScreen`.
- **Schema changes:** edit `server/app/models.py`, then
  `uv run alembic revision --autogenerate -m "..."`, review the file, and add tests. Update
  `ApiModels.kt` and `docs/api.md` in the same change.
- **Dependency versions are pinned on purpose**, each release at least two weeks old (SceneView
  ships every few days). Lint's "newer version" checks are disabled for that reason; bump deliberately.

## Decisions already made (don't relitigate without a reason)

- Integer IDs, online-only capture (no local queue yet; failed uploads can be retried in-session).
  Revisit if Wi-Fi dead zones make uploads fail in practice; that would mean UUID IDs + a sync queue.
- The photo planner draws with Compose Canvas (2D perspective), not SceneView/Filament, so it is
  testable on the JVM and runs without ARCore. Live AR (Phase 2) will use SceneView nodes but the
  same `geometry/` code.
- Cleartext HTTP is allowed in the app because the server lives on the LAN at a typed-in address.

## Status and next steps

- Phase 0 (server CRUD, AR capture) and Phase 1 (catalog, photo planner, layouts) are built and
  tested off-device. **Nothing has been verified on a real phone yet**: first confirm captures upload,
  boxes sit on the floor where tapped, and portrait captures display upright.
- Phase 1 leftovers: occlusion using the saved depth map (optional).
- Phase 2: live AR placement reusing `geometry/`, load a saved layout live, persistent room anchors.
- Phase 3: room box model from corner taps, GLB export, Three.js viewer.
