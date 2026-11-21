from functools import cached_property
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Server settings, read from ROOMPLANNER_* environment variables."""

    model_config = SettingsConfigDict(env_prefix="ROOMPLANNER_", env_file=".env", extra="ignore")

    # Everything persistent lives under this directory: the SQLite DB and all media.
    data_dir: Path = Path("./data")
    # Override to point at Postgres later, e.g. postgresql+psycopg://user:pw@host/db
    database_url: str | None = None
    max_upload_mb: int = 100
    # Origins allowed to call the API from a browser (the Three.js viewer in dev).
    cors_origins: list[str] = ["*"]

    @cached_property
    def resolved_database_url(self) -> str:
        if self.database_url:
            return self.database_url
        return f"sqlite:///{(self.data_dir / 'roomplanner.db').resolve()}"

    @property
    def media_dir(self) -> Path:
        return self.data_dir / "media"

    def ensure_dirs(self) -> None:
        self.media_dir.mkdir(parents=True, exist_ok=True)
