from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from sqlmodel import Session, SQLModel

from .config import Settings
from .db import get_session

SessionDep = Annotated[Session, Depends(get_session)]


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_or_404[T: SQLModel](session: Session, model: type[T], obj_id: int) -> T:
    obj = session.get(model, obj_id)
    if obj is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"{model.__name__} {obj_id} not found")
    return obj
