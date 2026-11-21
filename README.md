# Room Planner

Capture rooms with the phone camera as metric AR snapshots, place true-to-scale furniture and
wall decor, and compare layout options before buying anything.

- Plan & phases: [docs/plan.md](docs/plan.md)
- API & coordinate conventions: [docs/api.md](docs/api.md)

## Repo layout

| dir        | what | status |
|------------|------|--------|
| `server/`  | FastAPI + SQLModel + SQLite backend (uv, Alembic, pytest) | Phase 0 done |
| `android/` | Kotlin + Jetpack Compose + SceneView/ARCore app | next |
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
