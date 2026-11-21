from typing import Literal

from fastapi import APIRouter, File, HTTPException, Query, UploadFile, status
from sqlmodel import func, select

from ..deps import SessionDep, SettingsDep, get_or_404
from ..models import Item, ItemKind, Placement, utcnow
from ..schemas import ItemCreate, ItemRead, ItemUpdate, media_url
from ..storage import delete_media, save_upload

router = APIRouter(prefix="/items", tags=["items"])


def to_read(item: Item) -> ItemRead:
    return ItemRead(
        **item.model_dump(exclude={"image_path", "model_path"}),
        image_url=media_url(item.image_path),
        model_url=media_url(item.model_path),
    )


@router.get("", response_model=list[ItemRead])
def list_items(session: SessionDep, kind: ItemKind | None = Query(None)):
    q = select(Item).order_by(Item.name)
    if kind is not None:
        q = q.where(Item.kind == kind)
    return [to_read(i) for i in session.exec(q)]


@router.post("", response_model=ItemRead, status_code=status.HTTP_201_CREATED)
def create_item(body: ItemCreate, session: SessionDep):
    item = Item(**body.model_dump())
    session.add(item)
    session.commit()
    session.refresh(item)
    return to_read(item)


@router.get("/{item_id}", response_model=ItemRead)
def get_item(item_id: int, session: SessionDep):
    return to_read(get_or_404(session, Item, item_id))


@router.patch("/{item_id}", response_model=ItemRead)
def update_item(item_id: int, body: ItemUpdate, session: SessionDep):
    item = get_or_404(session, Item, item_id)
    changes = body.model_dump(exclude_unset=True)
    for required in ("name", "kind", "width_m", "depth_m", "height_m"):
        if required in changes and changes[required] is None:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"{required} cannot be null")
    for k, v in changes.items():
        setattr(item, k, v)
    item.updated_at = utcnow()
    session.commit()
    session.refresh(item)
    return to_read(item)


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_item(item_id: int, session: SessionDep, settings: SettingsDep):
    item = get_or_404(session, Item, item_id)
    used = session.exec(
        select(func.count()).select_from(Placement).where(Placement.item_id == item_id)
    ).one()
    if used:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            f"item is used by {used} placement(s); remove them from their layouts first",
        )
    files = (item.image_path, item.model_path)
    session.delete(item)
    session.commit()
    delete_media(settings, *files)


_ASSETS = {"image": ("image", "image_path"), "model": ("glb", "model_path")}


@router.put("/{item_id}/{asset}", response_model=ItemRead)
def upload_item_asset(
    item_id: int,
    asset: Literal["image", "model"],
    session: SessionDep,
    settings: SettingsDep,
    file: UploadFile = File(...),
):
    """Upload or replace the product image (`image`) or a GLB model (`model`)."""
    item = get_or_404(session, Item, item_id)
    kind, attr = _ASSETS[asset]
    old = getattr(item, attr)
    setattr(item, attr, save_upload(file, kind, "items", settings))
    item.updated_at = utcnow()
    session.commit()
    session.refresh(item)
    delete_media(settings, old)
    return to_read(item)


@router.delete("/{item_id}/{asset}", response_model=ItemRead)
def delete_item_asset(
    item_id: int, asset: Literal["image", "model"], session: SessionDep, settings: SettingsDep
):
    item = get_or_404(session, Item, item_id)
    _, attr = _ASSETS[asset]
    old = getattr(item, attr)
    setattr(item, attr, None)
    item.updated_at = utcnow()
    session.commit()
    session.refresh(item)
    delete_media(settings, old)
    return to_read(item)
