"""Senhas (bcrypt), tokens da API, CSRF dos formulários e rate limit."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import threading
import time
from collections import defaultdict, deque

import bcrypt
from fastapi import Form, HTTPException, Request


def _password_bytes(password: str) -> bytes:
    # O bcrypt só usa 72 bytes (e o bcrypt 5 recusa mais): truncamos sempre, no hash e na verificação.
    return password.encode("utf-8")[:72]


def hash_password(password: str) -> str:
    return bcrypt.hashpw(_password_bytes(password), bcrypt.gensalt()).decode("ascii")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(_password_bytes(password), password_hash.encode("ascii"))
    except ValueError:
        return False


def new_secret(nbytes: int = 32) -> str:
    return secrets.token_urlsafe(nbytes)


def new_access_code() -> str:
    """Código de acesso para digitar no celular: 12 caracteres sem ambíguos, em grupos de 4."""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(12))
    return "-".join(raw[i:i + 4] for i in range(0, 12, 4))


def normalize_code(code: str) -> str:
    return "".join(c for c in code.upper() if c.isalnum())


def sha256_hex(value: str) -> str:
    """Hash de tokens e códigos aleatórios (alta entropia: SHA-256 basta, sem bcrypt)."""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def csrf_token(request: Request) -> str:
    token = request.session.get("csrf")
    if not token:
        token = new_secret()
        request.session["csrf"] = token
    return token


def require_csrf(request: Request, csrf: str = Form("")) -> None:
    expected = request.session.get("csrf", "")
    if not expected or not hmac.compare_digest(csrf, expected):
        raise HTTPException(400, "Sessão expirada. Recarregue a página e tente de novo.")


class RateLimiter:
    """Janela deslizante em memória, por chave. Serve para um processo (o compose roda um worker)."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str, limit: int, window: float = 60.0) -> bool:
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] > window:
                hits.popleft()
            if len(hits) >= limit:
                return False
            hits.append(now)
            return True


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "?"


def rate_limit(bucket: str, setting: str):
    def dependency(request: Request) -> None:
        limit = getattr(request.app.state.settings, setting)
        if not request.app.state.limiter.allow(f"{bucket}:{client_ip(request)}", limit):
            raise HTTPException(429, "Muitas requisições. Tente de novo em um minuto.", headers={"Retry-After": "60"})

    return dependency
