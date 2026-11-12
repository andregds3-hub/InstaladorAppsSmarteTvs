import hashlib
import re

from conftest import APP_KEY, api_token, create_app_with_version, make_wgt, upload_version


def test_auth_requires_app_key(client):
    assert client.post("/api/v1/auth", json={}).status_code == 401
    assert client.post("/api/v1/auth", json={}, headers={"X-App-Key": "wrong"}).status_code == 401


def test_endpoints_require_token(client):
    assert client.get("/api/v1/apps?platform=tizen").status_code == 401
    assert client.get("/api/v1/apps?platform=tizen", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_list_filters_active_current_and_compat(admin):
    compatible = create_app_with_version(admin, name="A")
    old_tvs_only = create_app_with_version(admin, name="B")
    inactive = create_app_with_version(admin, name="C")
    admin.post("/admin/apps/new", data={"csrf": admin.csrf, "name": "Sem versão", "platform": "tizen", "active": "true"})
    admin.post(f"/admin/apps/{inactive}", data={"csrf": admin.csrf, "name": "C", "platform": "tizen"})
    from app.models import App

    with admin.app.state.session_factory() as db:
        version_b = db.get(App, old_tvs_only).current_version.id
    admin.post(f"/admin/versions/{version_b}/compat", data={"csrf": admin.csrf, "max_year": "2017"})

    headers = api_token(admin)
    names = [a["name"] for a in admin.get("/api/v1/apps?platform=tizen&model=18_KANTM2_QTV", headers=headers).json()]
    assert names == ["A"]
    names = [a["name"] for a in admin.get("/api/v1/apps?platform=tizen&year=2016", headers=headers).json()]
    assert names == ["A", "B"]
    assert admin.get("/api/v1/apps?platform=android_tv", headers=headers).json() == []
    assert admin.get("/api/v1/apps?platform=roku", headers=headers).status_code == 422
    item = admin.get("/api/v1/apps?platform=tizen", headers=headers).json()[0]
    assert item["id"] == compatible
    assert item["package_id"] == "AbCdEf1234"
    assert item["download_url"].startswith("https://")


def test_download_matches_hash(admin):
    app_id = create_app_with_version(admin, version="3.0.0")
    headers = api_token(admin)
    item = admin.get("/api/v1/apps?platform=tizen", headers=headers).json()[0]
    resp = admin.get(f"/api/v1/apps/{app_id}/download", headers=headers)
    assert resp.status_code == 200
    digest = hashlib.sha256(resp.content).hexdigest()
    assert digest == item["sha256"] == resp.headers["x-content-sha256"]
    assert resp.content == make_wgt("3.0.0")


def test_download_follows_current_version(admin):
    app_id = create_app_with_version(admin, version="1.0.0")
    upload_version(admin, app_id, make_wgt("2.0.0"), "p.wgt")
    resp = admin.get(f"/api/v1/apps/{app_id}/download", headers=api_token(admin))
    assert resp.headers["x-app-version"] == "2.0.0"


def test_device_upsert_and_install_log(admin):
    app_id = create_app_with_version(admin)
    headers = api_token(admin)
    device = {"platform": "tizen", "duid": "DUID123", "model": "19_MUSEL_UHD", "model_name": "UN55RU7100", "local_ip": "192.168.0.50"}
    first = admin.post("/api/v1/devices", json=device, headers=headers).json()["id"]
    second = admin.post("/api/v1/devices", json={**device, "local_ip": "192.168.0.51", "os_version": "5.0"}, headers=headers).json()["id"]
    assert first == second

    resp = admin.post(
        "/api/v1/installs",
        json={"device_id": first, "app_id": app_id, "status": "failure", "stage": "instalação", "error_code": "118,-12", "message": "install failed[118, -12]"},
        headers=headers,
    )
    assert resp.status_code == 200
    devices_page = admin.get("/admin/devices").text
    assert "UN55RU7100" in devices_page and "2019" in devices_page and "192.168.0.51" in devices_page
    assert "install failed[118, -12]" in admin.get("/admin/installs?status=failure").text
    assert admin.post("/api/v1/installs", json={"app_id": 999, "status": "success"}, headers=headers).status_code == 404


def test_access_codes(admin):
    create_app_with_version(admin)
    open_headers = api_token(admin)
    admin.post("/admin/settings", data={"csrf": admin.csrf, "clients_on": "true"})
    assert admin.get("/api/v1/apps?platform=tizen", headers=open_headers).status_code == 401
    assert admin.post("/api/v1/auth", json={}, headers={"X-App-Key": APP_KEY}).status_code == 401

    page = admin.post("/admin/clients", data={"csrf": admin.csrf, "name": "Loja"}).text
    code = re.search(r'<code class="fs-5">([A-Z0-9-]+)</code>', page).group(1)
    headers = api_token(admin, code.lower().replace("-", " "))
    assert admin.get("/api/v1/apps?platform=tizen", headers=headers).status_code == 200

    from app.models import InstallerClient

    with admin.app.state.session_factory() as db:
        client_id = db.query(InstallerClient).one().id
    admin.post(f"/admin/clients/{client_id}/block", data={"csrf": admin.csrf})
    assert admin.get("/api/v1/apps?platform=tizen", headers=headers).status_code == 403
    admin.post(f"/admin/clients/{client_id}/unblock", data={"csrf": admin.csrf})
    admin.post(f"/admin/clients/{client_id}/regenerate", data={"csrf": admin.csrf})
    assert admin.get("/api/v1/apps?platform=tizen", headers=headers).status_code == 401


def test_auth_rate_limit(make_client):
    client = make_client(auth_rate_per_minute=3)
    codes = [client.post("/api/v1/auth", json={}, headers={"X-App-Key": "x"}).status_code for _ in range(4)]
    assert codes == [401, 401, 401, 429]
