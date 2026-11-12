import hashlib

from conftest import ADMIN_PASSWORD, create_app_with_version, csrf, make_wgt, upload_version


def test_admin_pages_require_login(client):
    resp = client.get("/admin/apps", follow_redirects=False)
    assert resp.status_code == 303
    assert resp.headers["location"] == "/admin/login"


def test_wrong_password(client):
    token = csrf(client)
    client.post("/admin/login", data={"csrf": token, "username": "admin", "password": "x"})
    assert client.get("/admin/apps", follow_redirects=False).status_code == 303


def test_post_without_csrf_is_rejected(admin):
    resp = admin.post("/admin/apps/new", data={"name": "X", "platform": "tizen"})
    assert resp.status_code == 400


def test_session_cookie_is_secure(client):
    resp = client.get("/admin/login")
    cookie = resp.headers["set-cookie"].lower()
    assert "secure" in cookie and "httponly" in cookie and "samesite=lax" in cookie


def test_plain_http_redirects_to_https(make_client):
    from fastapi.testclient import TestClient

    plain = TestClient(make_client().app, base_url="http://testserver")
    resp = plain.get("/admin/login", follow_redirects=False)
    assert resp.status_code == 308
    assert resp.headers["location"].startswith("https://")
    assert plain.get("/api/v1/apps?platform=tizen").status_code == 403


def test_upload_wgt_reads_config_and_hash(admin, settings):
    app_id = create_app_with_version(admin, version="2.3.4")
    page = admin.get(f"/admin/apps/{app_id}").text
    expected_sha = hashlib.sha256(make_wgt("2.3.4")).hexdigest()
    assert "2.3.4" in page
    assert expected_sha in page
    assert "AbCdEf1234.Player" in page
    stored = list((settings.data_dir / "uploads" / str(app_id)).iterdir())
    assert len(stored) == 1 and stored[0].suffix == ".wgt"


def test_upload_rejects_wrong_extension_and_bad_wgt(admin):
    app_id = create_app_with_version(admin)
    upload_version(admin, app_id, b"apk", "player.apk")
    assert "precisa ser .wgt" in admin.get(f"/admin/apps/{app_id}").text
    upload_version(admin, app_id, b"not a zip", "broken.wgt")
    assert "não é um .wgt válido" in admin.get(f"/admin/apps/{app_id}").text


def test_upload_size_limit(admin, settings):
    app_id = create_app_with_version(admin)
    big = make_wgt("9.9.9", extra=b"x" * (1024 * 1024 + 10))
    upload_version(admin, app_id, big, "big.wgt")
    assert "maior que o limite" in admin.get(f"/admin/apps/{app_id}").text
    assert len(list((settings.data_dir / "uploads" / str(app_id)).iterdir())) == 1


def test_version_management_and_compat(admin):
    app_id = create_app_with_version(admin, version="1.0.0")
    upload_version(admin, app_id, make_wgt("1.1.0"), "p.wgt", make_current=None)
    from app.models import AppVersion

    with admin.app.state.session_factory() as db:
        versions = {v.version: v for v in db.query(AppVersion).all()}
    assert versions["1.0.0"].is_current and not versions["1.1.0"].is_current
    admin.post(f"/admin/versions/{versions['1.1.0'].id}/current", data={"csrf": admin.csrf})
    resp = admin.post(f"/admin/versions/{versions['1.1.0'].id}/delete", data={"csrf": admin.csrf})
    assert "Escolha outra versão" in resp.text
    admin.post(
        f"/admin/versions/{versions['1.1.0'].id}/compat",
        data={"csrf": admin.csrf, "min_year": "2016", "min_os": "2.4"},
    )
    assert "TVs 2016–…, Tizen 2.4–…" in admin.get(f"/admin/apps/{app_id}").text


def test_platform_locked_after_versions(admin):
    app_id = create_app_with_version(admin)
    resp = admin.post(f"/admin/apps/{app_id}", data={"csrf": admin.csrf, "name": "P", "platform": "android_tv"})
    assert "trocar a plataforma" in resp.text


def test_delete_app_removes_files(admin, settings):
    app_id = create_app_with_version(admin)
    admin.post(f"/admin/apps/{app_id}/delete", data={"csrf": admin.csrf})
    assert not (settings.data_dir / "uploads" / str(app_id)).exists()


def test_client_code_shown_once(admin):
    page = admin.post("/admin/clients", data={"csrf": admin.csrf, "name": "Loja 1"}).text
    assert "Anote agora" in page
    assert "Anote agora" not in admin.get("/admin/clients").text


def test_change_password(admin):
    admin.post("/admin/password", data={"csrf": admin.csrf, "current": ADMIN_PASSWORD, "new": "nova-senha-123", "confirm": "nova-senha-123"})
    admin.post("/admin/logout", data={"csrf": admin.csrf})
    token = csrf(admin)
    resp = admin.post("/admin/login", data={"csrf": token, "username": "admin", "password": "nova-senha-123"}, follow_redirects=False)
    assert resp.headers["location"] == "/admin/apps"
