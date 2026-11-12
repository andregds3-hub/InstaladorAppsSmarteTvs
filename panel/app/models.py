from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base

PLATFORMS = {"tizen": "Samsung Tizen", "android_tv": "Android TV", "webos": "LG webOS (previsto)"}
EXTENSIONS = {"tizen": ".wgt", "android_tv": ".apk", "webos": ".ipk"}


def utcnow() -> datetime:
    """UTC sem fuso: o mesmo valor no SQLite e no PostgreSQL."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


class AdminUser(Base):
    __tablename__ = "admin_users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True)
    password_hash: Mapped[str] = mapped_column(String(128))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class App(Base):
    __tablename__ = "apps"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120))
    description: Mapped[str] = mapped_column(Text, default="")
    icon_file: Mapped[str | None] = mapped_column(String(255))
    platform: Mapped[str] = mapped_column(String(20), index=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    display_order: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)

    versions: Mapped[list[AppVersion]] = relationship(
        back_populates="app", cascade="all, delete-orphan", order_by="AppVersion.created_at.desc()"
    )

    @property
    def current_version(self) -> AppVersion | None:
        return next((v for v in self.versions if v.is_current), None)


class AppVersion(Base):
    __tablename__ = "app_versions"

    id: Mapped[int] = mapped_column(primary_key=True)
    app_id: Mapped[int] = mapped_column(ForeignKey("apps.id", ondelete="CASCADE"), index=True)
    version: Mapped[str] = mapped_column(String(40))
    changelog: Mapped[str] = mapped_column(Text, default="")
    released_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    file_name: Mapped[str] = mapped_column(String(255))
    stored_name: Mapped[str] = mapped_column(String(255))
    size: Mapped[int] = mapped_column(Integer)
    sha256: Mapped[str] = mapped_column(String(64))
    is_current: Mapped[bool] = mapped_column(Boolean, default=False)
    package_id: Mapped[str | None] = mapped_column(String(64))
    package_app_id: Mapped[str | None] = mapped_column(String(128))
    required_version: Mapped[str | None] = mapped_column(String(20))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)

    app: Mapped[App] = relationship(back_populates="versions")
    compatibility: Mapped[list[Compatibility]] = relationship(
        back_populates="version", cascade="all, delete-orphan", order_by="Compatibility.id"
    )


class Compatibility(Base):
    """Uma regra; a versão é compatível se qualquer regra bater (sem regras: toda a plataforma)."""

    __tablename__ = "compatibility"

    id: Mapped[int] = mapped_column(primary_key=True)
    version_id: Mapped[int] = mapped_column(ForeignKey("app_versions.id", ondelete="CASCADE"), index=True)
    platform: Mapped[str] = mapped_column(String(20))
    min_year: Mapped[int | None] = mapped_column(Integer)
    max_year: Mapped[int | None] = mapped_column(Integer)
    min_os: Mapped[str | None] = mapped_column(String(20))
    max_os: Mapped[str | None] = mapped_column(String(20))
    note: Mapped[str] = mapped_column(String(200), default="")

    version: Mapped[AppVersion] = relationship(back_populates="compatibility")


class InstallerClient(Base):
    __tablename__ = "installer_clients"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120))
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    code_hint: Mapped[str] = mapped_column(String(8))
    blocked: Mapped[bool] = mapped_column(Boolean, default=False)
    notes: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_seen: Mapped[datetime | None] = mapped_column(DateTime)


class ApiToken(Base):
    __tablename__ = "api_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    client_id: Mapped[int | None] = mapped_column(ForeignKey("installer_clients.id", ondelete="CASCADE"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime)

    client: Mapped[InstallerClient | None] = relationship()


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("platform", "duid", name="uq_device_platform_duid"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    platform: Mapped[str] = mapped_column(String(20))
    duid: Mapped[str | None] = mapped_column(String(128))
    name: Mapped[str] = mapped_column(String(200), default="")
    model: Mapped[str] = mapped_column(String(80), default="")
    model_name: Mapped[str] = mapped_column(String(80), default="")
    year: Mapped[int | None] = mapped_column(Integer)
    firmware: Mapped[str] = mapped_column(String(80), default="")
    os_version: Mapped[str] = mapped_column(String(20), default="")
    local_ip: Mapped[str] = mapped_column(String(45), default="")
    first_seen: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_seen: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_install_at: Mapped[datetime | None] = mapped_column(DateTime)
    last_status: Mapped[str] = mapped_column(String(20), default="")
    last_app_id: Mapped[int | None] = mapped_column(ForeignKey("apps.id", ondelete="SET NULL"))
    last_version_id: Mapped[int | None] = mapped_column(ForeignKey("app_versions.id", ondelete="SET NULL"))
    client_id: Mapped[int | None] = mapped_column(ForeignKey("installer_clients.id", ondelete="SET NULL"))

    last_app: Mapped[App | None] = relationship()
    last_version: Mapped[AppVersion | None] = relationship()


class InstallLog(Base):
    __tablename__ = "install_logs"

    id: Mapped[int] = mapped_column(primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    status: Mapped[str] = mapped_column(String(20), index=True)
    stage: Mapped[str] = mapped_column(String(40), default="")
    error_code: Mapped[str] = mapped_column(String(40), default="")
    message: Mapped[str] = mapped_column(Text, default="")
    device_id: Mapped[int | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"))
    app_id: Mapped[int | None] = mapped_column(ForeignKey("apps.id", ondelete="SET NULL"))
    version_id: Mapped[int | None] = mapped_column(ForeignKey("app_versions.id", ondelete="SET NULL"))
    client_id: Mapped[int | None] = mapped_column(ForeignKey("installer_clients.id", ondelete="SET NULL"))

    device: Mapped[Device | None] = relationship()
    app: Mapped[App | None] = relationship()
    version: Mapped[AppVersion | None] = relationship()
    client: Mapped[InstallerClient | None] = relationship()


class Setting(Base):
    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text, default="")
