from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

SERVER_DIR = Path(__file__).resolve().parents[1]

# Minimal valid file headers; storage sniffs magic bytes, not content types.
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
GLB = b"glTF\x02\x00\x00\x00" + b"\x00" * 64
IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def migrate(url: str) -> None:
    cfg = Config(SERVER_DIR / "alembic.ini")
    cfg.set_main_option("script_location", str(SERVER_DIR / "alembic"))
    cfg.set_main_option("sqlalchemy.url", url)
    cfg.attributes["configure_logger"] = False
    command.upgrade(cfg, "head")


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    s = Settings(data_dir=tmp_path / "data", max_upload_mb=1, _env_file=None)
    s.ensure_dirs()
    migrate(s.resolved_database_url)  # tests run against the real migration, not create_all
    return s


@pytest.fixture
def client(settings: Settings):
    with TestClient(create_app(settings)) as c:
        yield c


@pytest.fixture
def room(client) -> dict:
    return client.post("/api/rooms", json={"name": "Living room"}).json()


@pytest.fixture
def sofa(client) -> dict:
    return client.post(
        "/api/items",
        json={"name": "Sofa", "kind": "furniture", "width_m": 2.1, "depth_m": 0.9, "height_m": 0.8},
    ).json()


def snapshot_meta(**overrides) -> dict:
    meta = {
        "pose": IDENTITY,
        "intrinsics": {"fx": 1000, "fy": 1000, "cx": 640, "cy": 360, "width": 1280, "height": 720},
        "planes": [
            {
                "id": "p1",
                "type": "horizontal_up",
                "center_pose": IDENTITY,
                "extent_x": 3.0,
                "extent_z": 4.0,
                "polygon": [[-1.5, -2], [1.5, -2], [1.5, 2], [-1.5, 2]],
                "label": "floor",
            }
        ],
    }
    meta.update(overrides)
    return meta
