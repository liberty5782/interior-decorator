from fastapi import APIRouter, File, UploadFile, status
from sqlmodel import select

from ..deps import SessionDep, SettingsDep, get_or_404
from ..models import Room, Snapshot, utcnow
from ..schemas import RoomCreate, RoomRead, RoomUpdate, media_url
from ..storage import delete_media, save_upload

router = APIRouter(prefix="/rooms", tags=["rooms"])


def to_read(room: Room) -> RoomRead:
    return RoomRead(
        id=room.id,
        name=room.name,
        model_url=media_url(room.model_path),
        created_at=room.created_at,
        updated_at=room.updated_at,
    )


@router.get("", response_model=list[RoomRead])
def list_rooms(session: SessionDep):
    return [to_read(r) for r in session.exec(select(Room).order_by(Room.name))]


@router.post("", response_model=RoomRead, status_code=status.HTTP_201_CREATED)
def create_room(body: RoomCreate, session: SessionDep):
    room = Room(name=body.name)
    session.add(room)
    session.commit()
    session.refresh(room)
    return to_read(room)


@router.get("/{room_id}", response_model=RoomRead)
def get_room(room_id: int, session: SessionDep):
    return to_read(get_or_404(session, Room, room_id))


@router.patch("/{room_id}", response_model=RoomRead)
def update_room(room_id: int, body: RoomUpdate, session: SessionDep):
    room = get_or_404(session, Room, room_id)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(room, k, v)
    room.updated_at = utcnow()
    session.commit()
    session.refresh(room)
    return to_read(room)


@router.delete("/{room_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_room(room_id: int, session: SessionDep, settings: SettingsDep):
    """Deletes the room with its snapshots, layouts and placements (DB cascade) and their files."""
    room = get_or_404(session, Room, room_id)
    files = [room.model_path]
    for snap in session.exec(select(Snapshot).where(Snapshot.room_id == room_id)):
        files += [snap.image_path, snap.depth_path]
    session.delete(room)
    session.commit()
    delete_media(settings, *files)


@router.put("/{room_id}/model", response_model=RoomRead)
def upload_room_model(
    room_id: int, session: SessionDep, settings: SettingsDep, file: UploadFile = File(...)
):
    """Upload or replace the room's box-room GLB."""
    room = get_or_404(session, Room, room_id)
    old = room.model_path
    room.model_path = save_upload(file, "glb", "rooms", settings)
    room.updated_at = utcnow()
    session.commit()
    session.refresh(room)
    delete_media(settings, old)
    return to_read(room)


@router.delete("/{room_id}/model", response_model=RoomRead)
def delete_room_model(room_id: int, session: SessionDep, settings: SettingsDep):
    room = get_or_404(session, Room, room_id)
    old, room.model_path = room.model_path, None
    room.updated_at = utcnow()
    session.commit()
    session.refresh(room)
    delete_media(settings, old)
    return to_read(room)
