"""API consumida pelo app Android."""

from __future__ import annotations

import hmac
from datetime import datetime, timedelta
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from ..compat import guess_year, is_compatible
from ..database import get_db
from ..deps import ApiCaller, clients_enabled, require_api
from ..models import PLATFORMS, ApiToken, App, AppVersion, Device, InstallerClient, InstallLog, utcnow
from ..security import new_secret, normalize_code, rate_limit, sha256_hex

router = APIRouter(prefix="/api/v1", tags=["api"])
api_limit = Depends(rate_limit("api", "api_rate_per_minute"))

PlatformName = Literal["tizen", "android_tv", "webos"]


class AuthIn(BaseModel):
    access_code: str | None = Field(default=None, max_length=40)


class AuthOut(BaseModel):
    token: str
    expires_at: datetime


class AppOut(BaseModel):
    id: int
    name: str
    description: str
    platform: str
    icon_url: str | None
    version_id: int
    version: str
    changelog: str
    released_at: datetime
    size: int
    sha256: str
    package_id: str | None
    package_app_id: str | None
    required_version: str | None
    download_url: str


class DeviceIn(BaseModel):
    platform: PlatformName
    duid: str | None = Field(default=None, max_length=128)
    name: str = Field(default="", max_length=200)
    model: str = Field(default="", max_length=80)
    model_name: str = Field(default="", max_length=80)
    year: int | None = Field(default=None, ge=2010, le=2100)
    firmware: str = Field(default="", max_length=80)
    os_version: str = Field(default="", max_length=20)
    local_ip: str = Field(default="", max_length=45)


class DeviceOut(BaseModel):
    id: int


class InstallIn(BaseModel):
    device_id: int | None = None
    app_id: int
    version_id: int | None = None
    status: Literal["success", "failure"]
    stage: str = Field(default="", max_length=40)
    error_code: str = Field(default="", max_length=40)
    message: str = Field(default="", max_length=4000)


class InstallOut(BaseModel):
    id: int


@router.post("/auth", response_model=AuthOut, dependencies=[Depends(rate_limit("auth", "auth_rate_per_minute"))])
def authenticate(
    body: AuthIn,
    request: Request,
    x_app_key: str = Header(default=""),
    db: Session = Depends(get_db),
) -> AuthOut:
    """Troca a chave do app (e o código de acesso, se ligado no painel) por um token."""
    settings = request.app.state.settings
    if not hmac.compare_digest(x_app_key, settings.app_key):
        raise HTTPException(401, "App não autorizado.")
    client = None
    if clients_enabled(db):
        code = normalize_code(body.access_code or "")
        if not code:
            raise HTTPException(401, "Informe o código de acesso.")
        client = db.scalar(select(InstallerClient).where(InstallerClient.code_hash == sha256_hex(code)))
        if client is None:
            raise HTTPException(401, "Código de acesso inválido.")
        if client.blocked:
            raise HTTPException(403, "Acesso bloqueado. Fale com o fornecedor do app.")
        client.last_seen = utcnow()
    token = new_secret()
    expires = utcnow() + timedelta(days=settings.token_days)
    db.add(ApiToken(token_hash=sha256_hex(token), client_id=client.id if client else None, expires_at=expires))
    db.commit()
    return AuthOut(token=token, expires_at=expires)


def _current_versions(db: Session, platform: str) -> list[tuple[App, AppVersion]]:
    apps = db.scalars(
        select(App)
        .where(App.active.is_(True), App.platform == platform)
        .options(selectinload(App.versions).selectinload(AppVersion.compatibility))
        .order_by(App.display_order, App.name)
    ).all()
    return [(app, app.current_version) for app in apps if app.current_version is not None]


@router.get("/apps", response_model=list[AppOut], dependencies=[api_limit])
def list_apps(
    request: Request,
    platform: PlatformName,
    model: str = "",
    year: int | None = None,
    os_version: str = "",
    caller: ApiCaller = Depends(require_api),
    db: Session = Depends(get_db),
) -> list[AppOut]:
    """Apps ativos com versão atual compatível com a TV informada."""
    year = year or guess_year(model)
    result = []
    for app, version in _current_versions(db, platform):
        if not is_compatible(version.compatibility, platform, year, os_version or None):
            continue
        result.append(
            AppOut(
                id=app.id,
                name=app.name,
                description=app.description,
                platform=app.platform,
                icon_url=str(request.url_for("icons", path=app.icon_file)) if app.icon_file else None,
                version_id=version.id,
                version=version.version,
                changelog=version.changelog,
                released_at=version.released_at,
                size=version.size,
                sha256=version.sha256,
                package_id=version.package_id,
                package_app_id=version.package_app_id,
                required_version=version.required_version,
                download_url=str(request.url_for("download_app", app_id=app.id)),
            )
        )
    return result


@router.get("/apps/{app_id}/download", name="download_app", dependencies=[api_limit])
def download_app(
    app_id: int,
    request: Request,
    caller: ApiCaller = Depends(require_api),
    db: Session = Depends(get_db),
) -> FileResponse:
    app = db.get(App, app_id)
    version = app.current_version if app and app.active else None
    if version is None:
        raise HTTPException(404, "App sem versão disponível.")
    path = request.app.state.settings.upload_dir / str(app.id) / version.stored_name
    if not path.is_file():
        raise HTTPException(410, "Arquivo da versão não encontrado no servidor.")
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=version.file_name,
        headers={"X-Content-SHA256": version.sha256, "X-App-Version": version.version, "Cache-Control": "no-store"},
    )


@router.post("/devices", response_model=DeviceOut, dependencies=[api_limit])
def register_device(
    body: DeviceIn,
    caller: ApiCaller = Depends(require_api),
    db: Session = Depends(get_db),
) -> DeviceOut:
    """Registra ou atualiza a TV encontrada. Sem DUID, casa por modelo + IP local."""
    duid = (body.duid or "").strip() or None
    query = select(Device).where(Device.platform == body.platform)
    if duid:
        query = query.where(Device.duid == duid)
    else:
        query = query.where(Device.duid.is_(None), Device.model_name == body.model_name, Device.local_ip == body.local_ip)
    device = db.scalar(query)
    if device is None:
        device = Device(platform=body.platform, duid=duid)
        db.add(device)
    for field in ("name", "model", "model_name", "firmware", "os_version", "local_ip"):
        value = getattr(body, field)
        if value:
            setattr(device, field, value)
    device.year = body.year or guess_year(body.model) or device.year
    device.last_seen = utcnow()
    if caller.client:
        device.client_id = caller.client.id
    db.commit()
    return DeviceOut(id=device.id)


@router.post("/installs", response_model=InstallOut, dependencies=[api_limit])
def report_install(
    body: InstallIn,
    caller: ApiCaller = Depends(require_api),
    db: Session = Depends(get_db),
) -> InstallOut:
    app = db.get(App, body.app_id)
    if app is None:
        raise HTTPException(404, "App desconhecido.")
    version = db.get(AppVersion, body.version_id) if body.version_id else None
    if version is not None and version.app_id != app.id:
        raise HTTPException(422, "Versão não pertence ao app.")
    device = db.get(Device, body.device_id) if body.device_id else None
    if body.device_id and device is None:
        raise HTTPException(404, "TV desconhecida; registre em /devices primeiro.")
    log = InstallLog(
        status=body.status,
        stage=body.stage,
        error_code=body.error_code,
        message=body.message,
        device_id=device.id if device else None,
        app_id=app.id,
        version_id=version.id if version else None,
        client_id=caller.client.id if caller.client else None,
    )
    db.add(log)
    if device is not None:
        device.last_install_at = utcnow()
        device.last_status = body.status
        device.last_app_id = app.id
        device.last_version_id = version.id if version else None
    db.commit()
    return InstallOut(id=log.id)


@router.get("/platforms")
def platforms() -> dict[str, str]:
    return PLATFORMS
