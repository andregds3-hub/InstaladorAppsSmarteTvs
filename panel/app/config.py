"""Configuração por variáveis de ambiente (nada de segredo no repositório)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    return default if value is None else value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    database_url: str
    data_dir: Path
    secret_key: str
    app_key: str
    cookie_secure: bool = True
    require_https: bool = True
    max_upload_mb: int = 200
    max_icon_kb: int = 1024
    token_days: int = 30
    api_rate_per_minute: int = 120
    auth_rate_per_minute: int = 10
    admin_user: str = ""
    admin_password: str = ""

    @property
    def upload_dir(self) -> Path:
        return self.data_dir / "uploads"

    @property
    def icon_dir(self) -> Path:
        return self.data_dir / "icons"


def load_settings() -> Settings:
    secret = os.environ.get("PANEL_SECRET_KEY", "")
    app_key = os.environ.get("PANEL_APP_KEY", "")
    if len(secret) < 32:
        raise RuntimeError("defina PANEL_SECRET_KEY com pelo menos 32 caracteres")
    if len(app_key) < 16:
        raise RuntimeError("defina PANEL_APP_KEY com pelo menos 16 caracteres")
    data_dir = Path(os.environ.get("PANEL_DATA_DIR", "./data")).resolve()
    return Settings(
        database_url=os.environ.get("PANEL_DATABASE_URL", f"sqlite:///{data_dir / 'panel.db'}"),
        data_dir=data_dir,
        secret_key=secret,
        app_key=app_key,
        cookie_secure=_bool("PANEL_COOKIE_SECURE", True),
        require_https=_bool("PANEL_REQUIRE_HTTPS", True),
        max_upload_mb=int(os.environ.get("PANEL_MAX_UPLOAD_MB", "200")),
        token_days=int(os.environ.get("PANEL_TOKEN_DAYS", "30")),
        api_rate_per_minute=int(os.environ.get("PANEL_API_RATE", "120")),
        auth_rate_per_minute=int(os.environ.get("PANEL_AUTH_RATE", "10")),
        admin_user=os.environ.get("PANEL_ADMIN_USER", ""),
        admin_password=os.environ.get("PANEL_ADMIN_PASSWORD", ""),
    )
