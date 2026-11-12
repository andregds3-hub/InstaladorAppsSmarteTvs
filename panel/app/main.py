from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.orm import sessionmaker
from starlette.middleware.sessions import SessionMiddleware

from .config import Settings, load_settings
from .database import Base, make_engine
from .deps import NotAuthenticated
from .models import AdminUser
from .routers import admin, api_v1
from .security import RateLimiter, csrf_token, hash_password

TEMPLATES_DIR = Path(__file__).parent / "templates"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    for directory in (settings.data_dir, settings.upload_dir, settings.icon_dir):
        directory.mkdir(parents=True, exist_ok=True)

    engine = make_engine(settings.database_url)
    # Esquema criado na partida; quando houver mudanças de esquema em produção, migrar com Alembic.
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(engine, expire_on_commit=False)
    _bootstrap_admin(session_factory, settings)

    app = FastAPI(title="Instalador TV", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = session_factory
    app.state.limiter = RateLimiter()
    templates = Jinja2Templates(directory=str(TEMPLATES_DIR))
    templates.env.globals["csrf_token"] = csrf_token
    app.state.templates = templates

    @app.middleware("http")
    async def https_and_headers(request: Request, call_next):
        if settings.require_https and request.url.scheme != "https" and request.url.path != "/healthz":
            if request.url.path.startswith("/api/"):
                return JSONResponse({"detail": "HTTPS obrigatório."}, status_code=403)
            return RedirectResponse(str(request.url.replace(scheme="https")), status_code=308)
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        if settings.require_https:
            response.headers.setdefault("Strict-Transport-Security", "max-age=31536000")
        return response

    # Adicionado depois do middleware acima, fica por fora: a sessão já existe quando as rotas rodam.
    app.add_middleware(
        SessionMiddleware,
        secret_key=settings.secret_key,
        session_cookie="panel_session",
        max_age=8 * 3600,
        same_site="lax",
        https_only=settings.cookie_secure,
    )

    @app.exception_handler(NotAuthenticated)
    async def to_login(_request: Request, _exc: NotAuthenticated):
        return RedirectResponse("/admin/login", status_code=303)

    @app.get("/healthz", include_in_schema=False)
    def healthz() -> PlainTextResponse:
        return PlainTextResponse("ok")

    @app.get("/", include_in_schema=False)
    def root() -> RedirectResponse:
        return RedirectResponse("/admin/apps", status_code=303)

    app.mount("/media/icons", StaticFiles(directory=str(settings.icon_dir)), name="icons")
    app.include_router(api_v1.router)
    app.include_router(admin.router)
    return app


def _bootstrap_admin(session_factory, settings: Settings) -> None:
    """Cria o primeiro admin a partir de PANEL_ADMIN_USER/PANEL_ADMIN_PASSWORD, se não houver nenhum."""
    if not (settings.admin_user and settings.admin_password):
        return
    with session_factory() as db:
        if db.scalar(select(AdminUser.id).limit(1)) is None:
            db.add(AdminUser(username=settings.admin_user, password_hash=hash_password(settings.admin_password)))
            db.commit()
