import pytest

from tizen_poc.install_output import Outcome, diagnose_install, diagnose_launch


@pytest.mark.parametrize(
    "output",
    [
        "app_id[Pkg.App] installing[100]\nspend time for pkgcmd is [1520]ms",
        "install completed",
        "Pull skipped or failed\napp_id[Pkg.App] installing[100]",
    ],
)
def test_success(output):
    assert diagnose_install(output).outcome is Outcome.SUCCESS


def test_partial_progress_is_not_success():
    assert diagnose_install("app_id[Pkg.App] installing[88]").outcome is Outcome.UNKNOWN


@pytest.mark.parametrize(
    "output, code, fragment",
    [
        ("app_id[Pkg.App] install failed[118]\ncmd_ret:0", "118", "assinatura ou certificado"),
        ("app_id[Pkg.App] install failed[118, -22]", "118,-22", "bloqueado"),
        ("app_id[Pkg.App] download failed[116]", "116", "espaço"),
        ("install failed[118, -4]", "118,-4", "versão de Tizen"),
        ("install failed[118, -19], reason: Parsing error", "118,-19", "config.xml"),
        ("install failed[118019]", "118019", "config.xml"),
        ("install failed[118, -12]", "118,-12", "DUID"),
        ("install failed[118012]", "118012", "DUID"),
        (
            "install failed[118, -12], reason: Check certificate error : :Package with the same id and different certificate",
            "118,-12",
            "outro certificado",
        ),
        ("install failed[118, -12], reason: Check certificate error : :Certificate in signature is expired.:<-6>", "118,-12", "expirou"),
        ("install failed[118, -12], reason: Check certificate error : :Certificate in signature is not valid yet.:<-7>", "118,-12", "data e a hora"),
        ("install failed[118, -12], reason: Check certificate error : :Invalid format of certificate in signature.:<-2>", "118,-12", "formato inválido"),
    ],
)
def test_failure_codes(output, code, fragment):
    diagnosis = diagnose_install(output)
    assert diagnosis.outcome is Outcome.FAILURE
    assert diagnosis.code == code
    assert fragment in diagnosis.message


@pytest.mark.parametrize("output", ["key[end] val[fail]", "error: transport connection lost", "read: Connection reset by peer"])
def test_other_failures(output):
    assert diagnose_install(output).outcome is Outcome.FAILURE


def test_launch():
    assert diagnose_launch("... successfully launched pid = 1234") is Outcome.SUCCESS
    assert diagnose_launch("launch failed") is Outcome.FAILURE
    assert diagnose_launch("") is Outcome.UNKNOWN
