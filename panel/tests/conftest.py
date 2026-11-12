from __future__ import annotations

import io
import re
import zipfile
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

APP_KEY = "test-app-key-0123456789"
ADMIN_PASSWORD = "senha-super-secreta"

CONFIG_XML = """<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://x/p" version="{version}">
  <tizen:application id="AbCdEf1234.Player" package="AbCdEf1234" required_version="2.4"/>
  <name>Player</name>
</widget>"""


def make_wgt(version: str = "1.0.0", extra: bytes = b"") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("config.xml", CONFIG_XML.format(version=version))
        zf.writestr("index.html", b"<html></html>" + extra)
    return buf.getvalue()


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        database_url=f"sqlite:///{tmp_path / 'test.db'}",
        data_dir=tmp_path / "data",
        secret_key="s" * 40,
        app_key=APP_KEY,
        max_upload_mb=1,
        admin_user="admin",
        admin_password=ADMIN_PASSWORD,
    )


@pytest.fixture
def make_client(settings):
    def factory(**overrides) -> TestClient:
        return TestClient(create_app(replace(settings, **overrides)), base_url="https://testserver")

    return factory


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


def csrf(client: TestClient, path: str = "/admin/login") -> str:
    html = client.get(path).text
    match = re.search(r'name="csrf" value="([^"]+)"', html)
    assert match, f"sem csrf em {path}"
    return match.group(1)


@pytest.fixture
def admin(client) -> TestClient:
    token = csrf(client)
    resp = client.post("/admin/login", data={"csrf": token, "username": "admin", "password": ADMIN_PASSWORD}, follow_redirects=False)
    assert resp.headers["location"] == "/admin/apps"
    client.csrf = token  # type: ignore[attr-defined]
    return client


def create_app_with_version(admin: TestClient, name: str = "Player", platform: str = "tizen", version: str = "1.0.0") -> int:
    resp = admin.post(
        "/admin/apps/new",
        data={"csrf": admin.csrf, "name": name, "platform": platform, "active": "true", "display_order": "0"},
        follow_redirects=False,
    )
    app_id = int(resp.headers["location"].rsplit("/", 1)[1])
    if platform == "tizen":
        upload_version(admin, app_id, make_wgt(version), "player.wgt")
    return app_id


def upload_version(admin: TestClient, app_id: int, content: bytes, filename: str, **fields):
    data = {k: v for k, v in {"csrf": admin.csrf, "make_current": "true", **fields}.items() if v is not None}
    return admin.post(
        f"/admin/apps/{app_id}/versions",
        data=data,
        files={"package": (filename, content, "application/octet-stream")},
        follow_redirects=False,
    )


def api_token(client: TestClient, code: str | None = None) -> dict[str, str]:
    resp = client.post("/api/v1/auth", json={"access_code": code}, headers={"X-App-Key": APP_KEY})
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['token']}"}
