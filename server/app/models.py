"""Database tables.

Matrix convention (shared with the Android app and the Three.js viewer): every 4x4 transform
is stored as 16 floats in column-major order, the same layout ARCore's Pose.toMatrix() produces
and Three.js Matrix4.fromArray() reads. Units are metres; +Y is up (ARCore world space).
"""

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from sqlalchemy import JSON, Column
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(UTC)


class ItemKind(StrEnum):
    furniture = "furniture"
    wall_decor = "wall_decor"


class CaptureSource(StrEnum):
    # Captured inside an ARCore session: pose, intrinsics and planes are metric.
    arcore = "arcore"
    # Reserved for a non-ARCore fallback (e.g. de-Googled phones): scale set by hand.
    manual = "manual"


class Room(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    name: str
    model_path: str | None = None  # box-room GLB (Phase 3)
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)


class Snapshot(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    room_id: int = Field(foreign_key="room.id", ondelete="CASCADE", index=True)
    image_path: str
    depth_path: str | None = None
    # World-from-camera pose of the camera that took the image.
    pose: list[float] = Field(sa_column=Column(JSON, nullable=False))
    intrinsics: dict[str, Any] = Field(sa_column=Column(JSON, nullable=False))
    planes: list[dict[str, Any]] = Field(
        default_factory=list, sa_column=Column(JSON, nullable=False)
    )
    depth_meta: dict[str, Any] | None = Field(default=None, sa_column=Column(JSON))
    capture_source: CaptureSource = CaptureSource.arcore
    created_at: datetime = Field(default_factory=utcnow)


class Item(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    name: str
    kind: ItemKind
    width_m: float
    depth_m: float
    height_m: float
    model_path: str | None = None
    image_path: str | None = None
    source_url: str | None = None
    price: float | None = None
    notes: str | None = None
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)


class Layout(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    room_id: int = Field(foreign_key="room.id", ondelete="CASCADE", index=True)
    name: str
    notes: str | None = None
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)


class Placement(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    layout_id: int = Field(foreign_key="layout.id", ondelete="CASCADE", index=True)
    item_id: int = Field(foreign_key="item.id", ondelete="RESTRICT", index=True)
    # Item-to-world transform in room/world space (see module docstring).
    transform: list[float] = Field(sa_column=Column(JSON, nullable=False))
    # Snapshot the placement was made on, if any. Purely informational.
    snapshot_id: int | None = Field(
        default=None, foreign_key="snapshot.id", ondelete="SET NULL", index=True
    )
