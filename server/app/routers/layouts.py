from fastapi import APIRouter, HTTPException, status
from sqlmodel import Session, select

from ..deps import SessionDep, get_or_404
from ..models import Item, Layout, Placement, Room, Snapshot, utcnow
from ..schemas import (
    LayoutCreate,
    LayoutDetail,
    LayoutDuplicate,
    LayoutRead,
    LayoutUpdate,
    PlacementCreate,
    PlacementRead,
    PlacementUpdate,
)

router = APIRouter(tags=["layouts"])


def _placements(session: Session, layout_id: int) -> list[Placement]:
    q = select(Placement).where(Placement.layout_id == layout_id).order_by(Placement.id)
    return list(session.exec(q))


def to_detail(session: Session, layout: Layout) -> LayoutDetail:
    return LayoutDetail(
        **layout.model_dump(),
        placements=[PlacementRead(**p.model_dump()) for p in _placements(session, layout.id)],
    )


def _check_refs(
    session: Session, layout: Layout, item_id: int | None, snapshot_id: int | None
) -> None:
    if item_id is not None and session.get(Item, item_id) is None:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"Item {item_id} not found")
    if snapshot_id is not None:
        snap = session.get(Snapshot, snapshot_id)
        if snap is None or snap.room_id != layout.room_id:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_CONTENT,
                f"Snapshot {snapshot_id} not found in room {layout.room_id}",
            )


# ---------------------------------------------------------------- layouts


@router.get("/rooms/{room_id}/layouts", response_model=list[LayoutRead])
def list_layouts(room_id: int, session: SessionDep):
    get_or_404(session, Room, room_id)
    return session.exec(select(Layout).where(Layout.room_id == room_id).order_by(Layout.name)).all()


@router.post(
    "/rooms/{room_id}/layouts", response_model=LayoutRead, status_code=status.HTTP_201_CREATED
)
def create_layout(room_id: int, body: LayoutCreate, session: SessionDep):
    get_or_404(session, Room, room_id)
    layout = Layout(room_id=room_id, **body.model_dump())
    session.add(layout)
    session.commit()
    session.refresh(layout)
    return layout


@router.get("/layouts/{layout_id}", response_model=LayoutDetail)
def get_layout(layout_id: int, session: SessionDep):
    """A layout with all its placements: everything a renderer needs besides the item catalog."""
    return to_detail(session, get_or_404(session, Layout, layout_id))


@router.patch("/layouts/{layout_id}", response_model=LayoutRead)
def update_layout(layout_id: int, body: LayoutUpdate, session: SessionDep):
    layout = get_or_404(session, Layout, layout_id)
    changes = body.model_dump(exclude_unset=True)
    if "name" in changes and changes["name"] is None:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "name cannot be null")
    for k, v in changes.items():
        setattr(layout, k, v)
    layout.updated_at = utcnow()
    session.commit()
    session.refresh(layout)
    return layout


@router.delete("/layouts/{layout_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_layout(layout_id: int, session: SessionDep):
    session.delete(get_or_404(session, Layout, layout_id))
    session.commit()


@router.post(
    "/layouts/{layout_id}/duplicate",
    response_model=LayoutDetail,
    status_code=status.HTTP_201_CREATED,
)
def duplicate_layout(layout_id: int, body: LayoutDuplicate, session: SessionDep):
    """Copy a layout and its placements, e.g. to turn option A into option B."""
    src = get_or_404(session, Layout, layout_id)
    copy = Layout(room_id=src.room_id, name=body.name, notes=src.notes)
    session.add(copy)
    session.flush()
    for p in _placements(session, src.id):
        session.add(
            Placement(
                layout_id=copy.id,
                item_id=p.item_id,
                transform=list(p.transform),
                snapshot_id=p.snapshot_id,
            )
        )
    session.commit()
    session.refresh(copy)
    return to_detail(session, copy)


# ---------------------------------------------------------------- placements


@router.post(
    "/layouts/{layout_id}/placements",
    response_model=PlacementRead,
    status_code=status.HTTP_201_CREATED,
)
def create_placement(layout_id: int, body: PlacementCreate, session: SessionDep):
    layout = get_or_404(session, Layout, layout_id)
    _check_refs(session, layout, body.item_id, body.snapshot_id)
    placement = Placement(layout_id=layout_id, **body.model_dump())
    session.add(placement)
    layout.updated_at = utcnow()
    session.commit()
    session.refresh(placement)
    return placement


@router.put("/layouts/{layout_id}/placements", response_model=LayoutDetail)
def replace_placements(layout_id: int, body: list[PlacementCreate], session: SessionDep):
    """Replace all placements in one go. Handy for "save" from the app's editor."""
    layout = get_or_404(session, Layout, layout_id)
    for p in body:
        _check_refs(session, layout, p.item_id, p.snapshot_id)
    for old in _placements(session, layout_id):
        session.delete(old)
    for p in body:
        session.add(Placement(layout_id=layout_id, **p.model_dump()))
    layout.updated_at = utcnow()
    session.commit()
    session.refresh(layout)
    return to_detail(session, layout)


@router.patch("/placements/{placement_id}", response_model=PlacementRead)
def update_placement(placement_id: int, body: PlacementUpdate, session: SessionDep):
    placement = get_or_404(session, Placement, placement_id)
    layout = session.get(Layout, placement.layout_id)
    changes = body.model_dump(exclude_unset=True)
    for required in ("item_id", "transform"):
        if required in changes and changes[required] is None:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"{required} cannot be null")
    _check_refs(session, layout, changes.get("item_id"), changes.get("snapshot_id"))
    for k, v in changes.items():
        setattr(placement, k, v)
    layout.updated_at = utcnow()
    session.commit()
    session.refresh(placement)
    return placement


@router.delete("/placements/{placement_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_placement(placement_id: int, session: SessionDep):
    placement = get_or_404(session, Placement, placement_id)
    layout = session.get(Layout, placement.layout_id)
    session.delete(placement)
    layout.updated_at = utcnow()
    session.commit()
