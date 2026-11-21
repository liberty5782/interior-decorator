"""Request/response models for the REST API."""

import math
from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field

from .models import CaptureSource, ItemKind


def _finite(values: list[float]) -> list[float]:
    if not all(math.isfinite(v) for v in values):
        raise ValueError("matrix values must be finite numbers")
    return values


# 4x4 matrix, 16 floats, column-major. See app/models.py.
Matrix4 = Annotated[list[float], Field(min_length=16, max_length=16), AfterValidator(_finite)]

IDENTITY: list[float] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def media_url(path: str | None) -> str | None:
    return f"/media/{path}" if path else None


# ---------------------------------------------------------------- rooms


class RoomCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)


class RoomUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)


class RoomRead(BaseModel):
    id: int
    name: str
    model_url: str | None
    created_at: datetime
    updated_at: datetime


# ---------------------------------------------------------------- snapshots


class Intrinsics(BaseModel):
    """Pinhole intrinsics in pixels, for an image of `width` x `height`."""

    fx: float = Field(gt=0)
    fy: float = Field(gt=0)
    cx: float
    cy: float
    width: int = Field(gt=0)
    height: int = Field(gt=0)


class Plane(BaseModel):
    """A detected plane, mirroring ARCore's Plane (center pose, extents, boundary polygon)."""

    model_config = ConfigDict(extra="allow")

    id: str | None = None
    type: Literal["horizontal_up", "horizontal_down", "vertical"]
    # Plane-to-world; the plane's local Y axis is its normal.
    center_pose: Matrix4
    extent_x: float = Field(ge=0)
    extent_z: float = Field(ge=0)
    # Boundary as (x, z) pairs in the plane's local frame.
    polygon: list[Annotated[list[float], Field(min_length=2, max_length=2)]] | None = None
    # Free-form label from the app, e.g. "floor", "wall".
    label: str | None = None


class SnapshotMeta(BaseModel):
    """JSON sent in the `meta` form field when uploading a snapshot."""

    pose: Matrix4
    intrinsics: Intrinsics
    planes: list[Plane] = []
    # e.g. {"format": "uint16_mm", "width": 160, "height": 120}
    depth_meta: dict[str, Any] | None = None
    capture_source: CaptureSource = CaptureSource.arcore


class SnapshotRead(BaseModel):
    id: int
    room_id: int
    image_url: str
    depth_url: str | None
    pose: list[float]
    intrinsics: Intrinsics
    planes: list[Plane]
    depth_meta: dict[str, Any] | None
    capture_source: CaptureSource
    created_at: datetime


# ---------------------------------------------------------------- items


class ItemCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    kind: ItemKind
    width_m: float = Field(gt=0, le=50)
    depth_m: float = Field(ge=0, le=50)  # may be ~0 for flat wall decor
    height_m: float = Field(gt=0, le=50)
    source_url: str | None = None
    price: float | None = Field(default=None, ge=0)
    notes: str | None = None


class ItemUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    kind: ItemKind | None = None
    width_m: float | None = Field(default=None, gt=0, le=50)
    depth_m: float | None = Field(default=None, ge=0, le=50)
    height_m: float | None = Field(default=None, gt=0, le=50)
    source_url: str | None = None
    price: float | None = Field(default=None, ge=0)
    notes: str | None = None


class ItemRead(BaseModel):
    id: int
    name: str
    kind: ItemKind
    width_m: float
    depth_m: float
    height_m: float
    image_url: str | None
    model_url: str | None
    source_url: str | None
    price: float | None
    notes: str | None
    created_at: datetime
    updated_at: datetime


# ---------------------------------------------------------------- layouts & placements


class PlacementCreate(BaseModel):
    item_id: int
    transform: Matrix4 = Field(default_factory=lambda: list(IDENTITY))
    snapshot_id: int | None = None


class PlacementUpdate(BaseModel):
    item_id: int | None = None
    transform: Matrix4 | None = None
    snapshot_id: int | None = None


class PlacementRead(BaseModel):
    id: int
    layout_id: int
    item_id: int
    transform: list[float]
    snapshot_id: int | None


class LayoutCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    notes: str | None = None


class LayoutUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    notes: str | None = None


class LayoutDuplicate(BaseModel):
    name: str = Field(min_length=1, max_length=200)


class LayoutRead(BaseModel):
    id: int
    room_id: int
    name: str
    notes: str | None
    created_at: datetime
    updated_at: datetime


class LayoutDetail(LayoutRead):
    placements: list[PlacementRead]
