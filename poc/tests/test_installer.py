from fake_sdbd import FakeSdbd
from helpers import make_wgt
from tizen_poc.installer import install_wgt
from tizen_poc.wgt import sha256_file

REMOTE = "/home/owner/share/tmp/sdk_tools/tmp/AbCdEf1234.wgt"
INSTALL = f"0 vd_appinstall AbCdEf1234 {REMOTE}"


def test_full_install_flow(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    replies = {
        INSTALL: "app_id[AbCdEf1234.Player] installing[50]\napp_id[AbCdEf1234.Player] installing[100]\n",
        "0 execute AbCdEf1234.Player": "... successfully launched pid = 4242\n",
    }
    with FakeSdbd(shell_replies=replies) as tv:
        report = install_wgt("127.0.0.1", str(package), expected_sha256=sha256_file(str(package)), log=lambda _: None, port=tv.port)
    assert report.ok, report.message
    assert report.platform_version == "6.5"
    assert tv.files[REMOTE][1] == package.read_bytes()
    assert tv.commands == [INSTALL, "0 execute AbCdEf1234.Player"]


def test_tizen_24_without_sdk_toolpath_uses_legacy_dir(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    legacy = "/opt/usr/apps/tmp/AbCdEf1234.wgt"
    capability = {"platform_version": "2.4.0", "cpu_arch": "armv7"}
    with FakeSdbd(capability=capability, shell_replies={f"0 vd_appinstall AbCdEf1234 {legacy}": "install completed"}) as tv:
        report = install_wgt("127.0.0.1", str(package), launch=False, log=lambda _: None, port=tv.port)
    assert report.ok, report.message
    assert legacy in tv.files


def test_push_falls_back_when_directory_refused(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    legacy = "/opt/usr/apps/tmp/AbCdEf1234.wgt"
    with FakeSdbd(fail_dirs=("/home/owner/",), shell_replies={f"0 vd_appinstall AbCdEf1234 {legacy}": "install completed"}) as tv:
        report = install_wgt("127.0.0.1", str(package), launch=False, log=lambda _: None, port=tv.port)
    assert report.ok, report.message
    assert list(tv.files) == [legacy]


def test_refused_execute_falls_back_to_was_execute(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    replies = {INSTALL: "app_id[AbCdEf1234] install completed", "0 was_execute AbCdEf1234.Player": "app_id[AbCdEf1234] launched"}
    with FakeSdbd(shell_replies=replies, refuse={"shell:0 execute AbCdEf1234.Player"}) as tv:
        report = install_wgt("127.0.0.1", str(package), log=lambda _: None, port=tv.port)
    assert report.ok, report.message
    assert "launched" in report.launch_output
    assert tv.commands == [INSTALL, "0 was_execute AbCdEf1234.Player"]


def test_permit_pushes_device_profile_before_package(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    profile = tmp_path / "device-profile.xml"
    profile.write_bytes(b"<Profile><TestDeviceInfo><TestDevice>DUID</TestDevice></TestDeviceInfo></Profile>")
    with FakeSdbd(shell_replies={INSTALL: "install completed"}) as tv:
        report = install_wgt("127.0.0.1", str(package), launch=False, log=lambda _: None, port=tv.port, permit_profile=str(profile))
    assert report.ok, report.message
    assert list(tv.files) == ["/home/developer/device-profile.xml", REMOTE]
    assert tv.files["/home/developer/device-profile.xml"] == (0o644, profile.read_bytes())


def test_launch_falls_back_to_was_execute(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    replies = {INSTALL: "install completed", "0 was_execute AbCdEf1234.Player": "launched"}
    with FakeSdbd(shell_replies=replies) as tv:
        report = install_wgt("127.0.0.1", str(package), log=lambda _: None, port=tv.port)
    assert report.ok
    assert tv.commands[1:] == ["0 execute AbCdEf1234.Player", "0 was_execute AbCdEf1234.Player"]


def test_install_by_app_id(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    with FakeSdbd() as tv:
        install_wgt("127.0.0.1", str(package), install_id="app", launch=False, log=lambda _: None, port=tv.port)
    assert tv.commands == [f"0 vd_appinstall AbCdEf1234.Player {REMOTE}"]


def test_certificate_rejection_stops_before_launch(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    with FakeSdbd(shell_replies={INSTALL: "app_id[AbCdEf1234.Player] install failed[118, -12]"}) as tv:
        report = install_wgt("127.0.0.1", str(package), log=lambda _: None, port=tv.port)
    assert not report.ok
    assert "DUID" in report.message
    assert tv.commands == [INSTALL]


def test_hash_mismatch_never_connects(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    report = install_wgt("127.0.0.1", str(package), expected_sha256="0" * 64, log=lambda _: None, port=1)
    assert not report.ok
    assert report.stage == "hash"


def test_unsigned_package_rejected(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=False)
    report = install_wgt("127.0.0.1", str(package), log=lambda _: None, port=1)
    assert report.stage == "assinatura"


def test_unreachable_tv_explains_developer_mode(tmp_path):
    package = make_wgt(tmp_path / "app.wgt", signed=True)
    report = install_wgt("127.0.0.1", str(package), log=lambda _: None, port=1)
    assert not report.ok
    assert "Modo Desenvolvedor" in report.message
