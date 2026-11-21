from fastapi import APIRouter, File, Form, UploadFile, status
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from sqlmodel import select

from ..deps import SessionDep, SettingsDep, get_or_404
from ..models import Room, Snapshot
from ..schemas import SnapshotMeta, SnapshotRead, media_url
from ..storage import delete_media, save_upload

router = APIRouter(tags=["snapshots"])


def to_read(s: Snapshot) -> SnapshotRead:
    return SnapshotRead(
        id=s.id,
        room_id=s.room_id,
        image_url=media_url(s.image_path),
        depth_url=media_url(s.depth_path),
        pose=s.pose,
        intrinsics=s.intrinsics,
        planes=s.planes,
        depth_meta=s.depth_meta,
        capture_source=s.capture_source,
        session_id=s.session_id,
        created_at=s.created_at,
    )


@router.get("/rooms/{room_id}/snapshots", response_model=list[SnapshotRead])
def list_snapshots(room_id: int, session: SessionDep):
    get_or_404(session, Room, room_id)
    q = select(Snapshot).where(Snapshot.room_id == room_id).order_by(Snapshot.created_at)
    return [to_read(s) for s in session.exec(q)]


@router.post(
    "/rooms/{room_id}/snapshots",
    response_model=SnapshotRead,
    status_code=status.HTTP_201_CREATED,
)
def create_snapshot(
    room_id: int,
    session: SessionDep,
    settings: SettingsDep,
    meta: str = Form(..., description="SnapshotMeta as a JSON string"),
    image: UploadFile = File(..., description="Camera image (JPEG/PNG/WebP)"),
    depth: UploadFile | None = File(None, description="Depth map (16-bit PNG or raw .bin)"),
):
    """Upload an AR snapshot: multipart with `meta` (JSON), `image` and optional `depth`."""
    get_or_404(session, Room, room_id)
    try:
        parsed = SnapshotMeta.model_validate_json(meta)
    except ValidationError as e:
        raise RequestValidationError(
            [{**err, "loc": ("body", "meta", *err["loc"])} for err in e.errors(include_url=False)]
        ) from None

    image_path = save_upload(image, "image", "snapshots", settings)
    depth_path = None
    try:
        if depth is not None:
            depth_path = save_upload(depth, "depth", "snapshots", settings)
        snap = Snapshot(
            room_id=room_id,
            image_path=image_path,
            depth_path=depth_path,
            pose=parsed.pose,
            intrinsics=parsed.intrinsics.model_dump(),
            planes=[p.model_dump(exclude_none=True) for p in parsed.planes],
            depth_meta=parsed.depth_meta,
            capture_source=parsed.capture_source,
            session_id=parsed.session_id,
        )
        session.add(snap)
        session.commit()
    except BaseException:
        delete_media(settings, image_path, depth_path)
        raise
    session.refresh(snap)
    return to_read(snap)


@router.get("/snapshots/{snapshot_id}", response_model=SnapshotRead)
def get_snapshot(snapshot_id: int, session: SessionDep):
    return to_read(get_or_404(session, Snapshot, snapshot_id))


@router.delete("/snapshots/{snapshot_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_snapshot(snapshot_id: int, session: SessionDep, settings: SettingsDep):
    """Placements made on this snapshot keep their transform; their snapshot_id becomes null."""
    snap = get_or_404(session, Snapshot, snapshot_id)
    files = (snap.image_path, snap.depth_path)
    session.delete(snap)
    session.commit()
    delete_media(settings, *files)
