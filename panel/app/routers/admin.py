"""Painel admin: login, apps, versões, compatibilidade, TVs, logs, clientes e configurações."""

from __future__ import annotations

import shutil
from datetime import datetime

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from ..compat import describe
from ..database import get_db
from ..deps import CLIENTS_ENABLED, clients_enabled, require_admin, set_setting
from ..models import (
    PLATFORMS,
    AdminUser,
    ApiToken,
    App,
    AppVersion,
    Compatibility,
    Device,
    InstallerClient,
    InstallLog,
)
from ..packages import UploadError, read_wgt_config, save_icon, save_package
from ..security import (
    hash_password,
    new_access_code,
    normalize_code,
    rate_limit,
    require_csrf,
    sha256_hex,
    verify_password,
)

router = APIRouter(prefix="/admin", include_in_schema=False)
protected = [Depends(require_admin)]


def render(request: Request, template: str, **context) -> HTMLResponse:
    flashes = request.session.pop("flash", [])
    return request.app.state.templates.TemplateResponse(
        request, template, {"flashes": flashes, "platforms": PLATFORMS, "describe": describe, **context}
    )


def flash(request: Request, message: str, kind: str = "success") -> None:
    # Reatribui a chave: mutação dentro da lista não marca a sessão como alterada e o cookie não seria regravado.
    request.session["flash"] = [*request.session.get("flash", []), {"kind": kind, "message": message}]


def redirect(path: str) -> RedirectResponse:
    return RedirectResponse(path, status_code=303)


def _get_app(db: Session, app_id: int) -> App:
    app = db.get(App, app_id)
    if app is None:
        raise HTTPException(404, "App não encontrado.")
    return app


def _optional_int(value: str) -> int | None:
    value = (value or "").strip()
    return int(value) if value.isdigit() else None


# --- login -------------------------------------------------------------------


@router.get("/login")
def login_form(request: Request) -> HTMLResponse:
    return render(request, "login.html")


@router.post("/login", dependencies=[Depends(require_csrf), Depends(rate_limit("login", "auth_rate_per_minute"))])
def login(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    db: Session = Depends(get_db),
):
    admin = db.scalar(select(AdminUser).where(AdminUser.username == username.strip()))
    if admin is None or not verify_password(password, admin.password_hash):
        flash(request, "Usuário ou senha inválidos.", "danger")
        return redirect("/admin/login")
    csrf = request.session.get("csrf")
    request.session.clear()
    request.session.update({"admin_id": admin.id, "csrf": csrf})
    return redirect("/admin/apps")


@router.post("/logout", dependencies=[Depends(require_csrf)])
def logout(request: Request):
    request.session.clear()
    return redirect("/admin/login")


@router.get("", dependencies=protected)
def home():
    return redirect("/admin/apps")


# --- apps --------------------------------------------------------------------


@router.get("/apps", dependencies=protected)
def list_apps(request: Request, db: Session = Depends(get_db)):
    apps = db.scalars(select(App).options(selectinload(App.versions)).order_by(App.display_order, App.name)).all()
    return render(request, "apps.html", apps=apps)


@router.get("/apps/new", dependencies=protected)
def new_app_form(request: Request):
    return render(request, "app_form.html", app=None)


async def _apply_app_form(request: Request, app: App, name: str, description: str, platform: str, active: bool, display_order: int, icon: UploadFile | None) -> None:
    if platform not in PLATFORMS:
        raise UploadError("Plataforma inválida.")
    if not name.strip():
        raise UploadError("Informe o nome.")
    if app.id and app.versions and platform != app.platform:
        raise UploadError("Não é possível trocar a plataforma de um app que já tem versões.")
    settings = request.app.state.settings
    if icon is not None and icon.filename:
        new_icon = await save_icon(icon, settings.icon_dir, settings.max_icon_kb * 1024)
        if app.icon_file:
            (settings.icon_dir / app.icon_file).unlink(missing_ok=True)
        app.icon_file = new_icon
    app.name = name.strip()[:120]
    app.description = description.strip()
    app.platform = platform
    app.active = active
    app.display_order = display_order


@router.post("/apps/new", dependencies=protected + [Depends(require_csrf)])
async def create_app_route(
    request: Request,
    name: str = Form(...),
    description: str = Form(""),
    platform: str = Form(...),
    active: bool = Form(False),
    display_order: int = Form(0),
    icon: UploadFile | None = File(None),
    db: Session = Depends(get_db),
):
    app = App()
    try:
        await _apply_app_form(request, app, name, description, platform, active, display_order, icon)
    except UploadError as exc:
        flash(request, str(exc), "danger")
        return redirect("/admin/apps/new")
    db.add(app)
    db.commit()
    flash(request, f"App “{app.name}” criado. Envie a primeira versão.")
    return redirect(f"/admin/apps/{app.id}")


@router.get("/apps/{app_id}", dependencies=protected)
def app_detail(request: Request, app_id: int, db: Session = Depends(get_db)):
    app = _get_app(db, app_id)
    return render(request, "app_detail.html", app=app, max_upload_mb=request.app.state.settings.max_upload_mb)


@router.post("/apps/{app_id}", dependencies=protected + [Depends(require_csrf)])
async def update_app(
    request: Request,
    app_id: int,
    name: str = Form(...),
    description: str = Form(""),
    platform: str = Form(...),
    active: bool = Form(False),
    display_order: int = Form(0),
    icon: UploadFile | None = File(None),
    db: Session = Depends(get_db),
):
    app = _get_app(db, app_id)
    try:
        await _apply_app_form(request, app, name, description, platform, active, display_order, icon)
    except UploadError as exc:
        flash(request, str(exc), "danger")
        return redirect(f"/admin/apps/{app_id}")
    db.commit()
    flash(request, "App atualizado.")
    return redirect(f"/admin/apps/{app_id}")


@router.post("/apps/{app_id}/delete", dependencies=protected + [Depends(require_csrf)])
def delete_app(request: Request, app_id: int, db: Session = Depends(get_db)):
    app = _get_app(db, app_id)
    settings = request.app.state.settings
    if app.icon_file:
        (settings.icon_dir / app.icon_file).unlink(missing_ok=True)
    shutil.rmtree(settings.upload_dir / str(app.id), ignore_errors=True)
    name = app.name
    db.delete(app)
    db.commit()
    flash(request, f"App “{name}” e seus arquivos foram excluídos.")
    return redirect("/admin/apps")


# --- versões ------------------------------------------------------------------


@router.post("/apps/{app_id}/versions", dependencies=protected + [Depends(require_csrf)])
async def upload_version(
    request: Request,
    app_id: int,
    package: UploadFile = File(...),
    version: str = Form(""),
    changelog: str = Form(""),
    released_at: str = Form(""),
    make_current: bool = Form(False),
    db: Session = Depends(get_db),
):
    app = _get_app(db, app_id)
    settings = request.app.state.settings
    directory = settings.upload_dir / str(app.id)
    try:
        stored = await save_package(package, app.platform, directory, settings.max_upload_mb * 1024 * 1024)
    except UploadError as exc:
        flash(request, str(exc), "danger")
        return redirect(f"/admin/apps/{app_id}")

    row = AppVersion(
        app_id=app.id,
        file_name=(package.filename or stored.stored_name)[-255:],
        stored_name=stored.stored_name,
        size=stored.size,
        sha256=stored.sha256,
        changelog=changelog.strip(),
    )
    if app.platform == "tizen":
        try:
            config = read_wgt_config(directory / stored.stored_name)
        except UploadError as exc:
            (directory / stored.stored_name).unlink(missing_ok=True)
            flash(request, str(exc), "danger")
            return redirect(f"/admin/apps/{app_id}")
        row.package_id = config.package_id
        row.package_app_id = config.app_id
        row.required_version = config.required_version or None
        version = version.strip() or config.version
    row.version = version.strip()[:40]
    if not row.version:
        (directory / stored.stored_name).unlink(missing_ok=True)
        flash(request, "Informe o número da versão.", "danger")
        return redirect(f"/admin/apps/{app_id}")
    if released_at:
        try:
            row.released_at = datetime.fromisoformat(released_at)
        except ValueError:
            pass
    if make_current or not app.versions:
        for other in app.versions:
            other.is_current = False
        row.is_current = True
    db.add(row)
    db.commit()
    detail = f" — pacote {row.package_id}, app {row.package_app_id}" if row.package_id else ""
    flash(request, f"Versão {row.version} enviada (SHA-256 {row.sha256[:12]}…){detail}.")
    return redirect(f"/admin/apps/{app_id}")


def _get_version(db: Session, version_id: int) -> AppVersion:
    version = db.get(AppVersion, version_id)
    if version is None:
        raise HTTPException(404, "Versão não encontrada.")
    return version


@router.post("/versions/{version_id}/current", dependencies=protected + [Depends(require_csrf)])
def make_current(request: Request, version_id: int, db: Session = Depends(get_db)):
    version = _get_version(db, version_id)
    for other in version.app.versions:
        other.is_current = other.id == version.id
    db.commit()
    flash(request, f"Versão {version.version} agora é a atual.")
    return redirect(f"/admin/apps/{version.app_id}")


@router.post("/versions/{version_id}/delete", dependencies=protected + [Depends(require_csrf)])
def delete_version(request: Request, version_id: int, db: Session = Depends(get_db)):
    version = _get_version(db, version_id)
    app_id = version.app_id
    if version.is_current:
        flash(request, "Escolha outra versão como atual antes de excluir esta.", "warning")
        return redirect(f"/admin/apps/{app_id}")
    (request.app.state.settings.upload_dir / str(app_id) / version.stored_name).unlink(missing_ok=True)
    db.delete(version)
    db.commit()
    flash(request, "Versão excluída.")
    return redirect(f"/admin/apps/{app_id}")


@router.post("/versions/{version_id}/compat", dependencies=protected + [Depends(require_csrf)])
def add_compat(
    request: Request,
    version_id: int,
    min_year: str = Form(""),
    max_year: str = Form(""),
    min_os: str = Form(""),
    max_os: str = Form(""),
    note: str = Form(""),
    db: Session = Depends(get_db),
):
    version = _get_version(db, version_id)
    rule = Compatibility(
        version_id=version.id,
        platform=version.app.platform,
        min_year=_optional_int(min_year),
        max_year=_optional_int(max_year),
        min_os=min_os.strip()[:20] or None,
        max_os=max_os.strip()[:20] or None,
        note=note.strip()[:200],
    )
    db.add(rule)
    db.commit()
    flash(request, f"Compatibilidade adicionada: {describe(rule)}.")
    return redirect(f"/admin/apps/{version.app_id}")


@router.post("/compat/{rule_id}/delete", dependencies=protected + [Depends(require_csrf)])
def delete_compat(request: Request, rule_id: int, db: Session = Depends(get_db)):
    rule = db.get(Compatibility, rule_id)
    if rule is None:
        raise HTTPException(404)
    app_id = rule.version.app_id
    db.delete(rule)
    db.commit()
    return redirect(f"/admin/apps/{app_id}")


# --- TVs e logs ----------------------------------------------------------------


@router.get("/devices", dependencies=protected)
def devices(request: Request, db: Session = Depends(get_db)):
    rows = db.scalars(
        select(Device).options(selectinload(Device.last_app), selectinload(Device.last_version)).order_by(Device.last_seen.desc()).limit(500)
    ).all()
    return render(request, "devices.html", devices=rows)


@router.get("/installs", dependencies=protected)
def installs(request: Request, status: str = "", db: Session = Depends(get_db)):
    query = select(InstallLog).options(
        selectinload(InstallLog.device), selectinload(InstallLog.app), selectinload(InstallLog.version)
    )
    if status in ("success", "failure"):
        query = query.where(InstallLog.status == status)
    rows = db.scalars(query.order_by(InstallLog.created_at.desc()).limit(500)).all()
    totals = dict(db.execute(select(InstallLog.status, func.count()).group_by(InstallLog.status)).all())
    return render(request, "installs.html", logs=rows, status=status, totals=totals)


# --- clientes do instalador -----------------------------------------------------


@router.get("/clients", dependencies=protected)
def clients(request: Request, db: Session = Depends(get_db)):
    rows = db.scalars(select(InstallerClient).order_by(InstallerClient.created_at.desc())).all()
    new_code = request.session.pop("new_code", None)
    return render(request, "clients.html", clients=rows, enabled=clients_enabled(db), new_code=new_code)


@router.post("/clients", dependencies=protected + [Depends(require_csrf)])
def create_client(request: Request, name: str = Form(...), notes: str = Form(""), db: Session = Depends(get_db)):
    code = new_access_code()
    normalized = normalize_code(code)
    db.add(InstallerClient(name=name.strip()[:120] or "Cliente", notes=notes.strip(), code_hash=sha256_hex(normalized), code_hint=normalized[-4:]))
    db.commit()
    request.session["new_code"] = {"name": name.strip(), "code": code}
    return redirect("/admin/clients")


@router.post("/clients/{client_id}/{action}", dependencies=protected + [Depends(require_csrf)])
def client_action(request: Request, client_id: int, action: str, db: Session = Depends(get_db)):
    client = db.get(InstallerClient, client_id)
    if client is None:
        raise HTTPException(404)
    if action == "block":
        client.blocked = True
        flash(request, f"“{client.name}” bloqueado.")
    elif action == "unblock":
        client.blocked = False
        flash(request, f"“{client.name}” desbloqueado.")
    elif action == "regenerate":
        code = new_access_code()
        normalized = normalize_code(code)
        client.code_hash, client.code_hint = sha256_hex(normalized), normalized[-4:]
        # Tokens emitidos com o código antigo deixam de valer.
        db.query(ApiToken).filter(ApiToken.client_id == client.id).delete()
        request.session["new_code"] = {"name": client.name, "code": code}
    elif action == "delete":
        db.delete(client)
        flash(request, "Cliente excluído.")
    else:
        raise HTTPException(404)
    db.commit()
    return redirect("/admin/clients")


# --- configurações -------------------------------------------------------------


@router.get("/settings", dependencies=protected)
def settings_page(request: Request, db: Session = Depends(get_db)):
    return render(request, "settings.html", clients_enabled=clients_enabled(db))


@router.post("/settings", dependencies=protected + [Depends(require_csrf)])
def save_settings(request: Request, clients_on: bool = Form(False), db: Session = Depends(get_db)):
    set_setting(db, CLIENTS_ENABLED, "1" if clients_on else "0")
    db.commit()
    flash(request, "Configurações salvas.")
    return redirect("/admin/settings")


@router.post("/password", dependencies=[Depends(require_csrf)])
def change_password(
    request: Request,
    current: str = Form(...),
    new: str = Form(...),
    confirm: str = Form(...),
    admin: AdminUser = Depends(require_admin),
    db: Session = Depends(get_db),
):
    if not verify_password(current, admin.password_hash):
        flash(request, "Senha atual incorreta.", "danger")
    elif len(new) < 10 or new != confirm:
        flash(request, "A nova senha precisa ter 10+ caracteres e ser igual à confirmação.", "danger")
    else:
        admin.password_hash = hash_password(new)
        db.commit()
        flash(request, "Senha alterada.")
    return redirect("/admin/settings")
