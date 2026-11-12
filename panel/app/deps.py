"""Autenticação do admin (sessão) e do app Android (token Bearer)."""

from __future__ import annotations

from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from .database import get_db
from .models import AdminUser, ApiToken, InstallerClient, Setting, utcnow
from .security import sha256_hex

CLIENTS_ENABLED = "clients_enabled"


class NotAuthenticated(Exception):
    """Sem sessão de admin: o handler redireciona para o login."""


def get_setting(db: Session, key: str, default: str = "") -> str:
    row = db.get(Setting, key)
    return row.value if row else default


def set_setting(db: Session, key: str, value: str) -> None:
    row = db.get(Setting, key)
    if row:
        row.value = value
    else:
        db.add(Setting(key=key, value=value))


def clients_enabled(db: Session) -> bool:
    return get_setting(db, CLIENTS_ENABLED, "0") == "1"


def require_admin(request: Request, db: Session = Depends(get_db)) -> AdminUser:
    admin_id = request.session.get("admin_id")
    admin = db.get(AdminUser, admin_id) if isinstance(admin_id, int) else None
    if admin is None:
        raise NotAuthenticated()
    return admin


@dataclass
class ApiCaller:
    token: ApiToken
    client: InstallerClient | None


def require_api(request: Request, db: Session = Depends(get_db)) -> ApiCaller:
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value:
        raise HTTPException(401, "Token ausente.", headers={"WWW-Authenticate": "Bearer"})
    token = db.scalar(select(ApiToken).where(ApiToken.token_hash == sha256_hex(value.strip())))
    if token is None or token.expires_at < utcnow():
        raise HTTPException(401, "Token inválido ou expirado.", headers={"WWW-Authenticate": "Bearer"})
    client = token.client
    if client is None and clients_enabled(db):
        raise HTTPException(401, "Este instalador agora exige código de acesso.", headers={"WWW-Authenticate": "Bearer"})
    if client is not None:
        if client.blocked:
            raise HTTPException(403, "Acesso bloqueado. Fale com o fornecedor do app.")
        client.last_seen = utcnow()
        db.commit()
    return ApiCaller(token, client)
