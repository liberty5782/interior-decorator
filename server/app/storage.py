"""Media files on disk under <data_dir>/media. The DB stores paths relative to that dir."""

import logging
import uuid
from pathlib import Path
from typing import Literal

from fastapi import HTTPException, UploadFile, status

from .config import Settings

log = logging.getLogger(__name__)

MediaKind = Literal["image", "depth", "glb"]

_CHUNK = 1024 * 1024


def _sniff(head: bytes) -> str | None:
    """Return a file extension based on magic bytes, or None if unrecognised."""
    if head.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return ".webp"
    if head.startswith(b"glTF"):
        return ".glb"
    return None


_ALLOWED: dict[MediaKind, set[str]] = {
    "image": {".jpg", ".png", ".webp"},
    # 16-bit PNG, or a raw buffer (e.g. ARCore DEPTH16) described by the snapshot's depth_meta.
    "depth": {".png", ".bin"},
    "glb": {".glb"},
}


def save_upload(upload: UploadFile, kind: MediaKind, folder: str, settings: Settings) -> str:
    """Validate and store an upload; return its path relative to the media dir."""
    src = upload.file
    head = src.read(16)
    ext = _sniff(head)
    if ext is None and kind == "depth":
        ext = ".bin"
    if ext not in _ALLOWED[kind]:
        raise HTTPException(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            f"{upload.filename or 'upload'}: expected {kind} ({', '.join(sorted(_ALLOWED[kind]))})",
        )

    rel = f"{folder}/{uuid.uuid4().hex}{ext}"
    dest = settings.media_dir / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    limit = settings.max_upload_mb * 1024 * 1024
    size = 0
    try:
        with tmp.open("wb") as out:
            chunk = head
            while chunk:
                size += len(chunk)
                if size > limit:
                    raise HTTPException(
                        status.HTTP_413_CONTENT_TOO_LARGE,
                        f"upload exceeds {settings.max_upload_mb} MB",
                    )
                out.write(chunk)
                chunk = src.read(_CHUNK)
        if size == 0:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "empty upload")
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)
    return rel


def delete_media(settings: Settings, *paths: str | None) -> None:
    root = settings.media_dir.resolve()
    for rel in paths:
        if not rel:
            continue
        p = (root / rel).resolve()
        if not p.is_relative_to(root):  # never follow a bad path out of the media dir
            log.warning("refusing to delete %s outside media dir", p)
            continue
        try:
            Path(p).unlink(missing_ok=True)
        except OSError:
            log.exception("could not delete %s", p)
