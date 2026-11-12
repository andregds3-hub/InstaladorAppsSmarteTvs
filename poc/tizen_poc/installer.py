"""Fluxo completo de instalação: conferir hash → conectar → enviar → instalar → abrir."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from .install_output import Diagnosis, Outcome, diagnose_install, diagnose_launch
from .sdb import DEFAULT_SDK_TOOLPATH, SDB_PORT, SdbClient, SdbError, SdbServiceRefused
from .wgt import TIZEN_ID, read_wgt, sha256_file

Log = Callable[[str], None]

# Comandos de abertura, do mais novo para o mais antigo (was_execute em Tizen 2.4/3.0).
LAUNCH_COMMANDS = ("0 execute {app_id}", "0 was_execute {app_id}")
LEGACY_UPLOAD_DIR = "/opt/usr/apps/tmp"
# "Permit to install applications" do Tizen Studio: o perfil de aparelho assinado pela Samsung
# (resposta da /apis/v1/distributors) vai para esta pasta da TV.
PERMIT_DIR = "/home/developer"


def permit_install(client: SdbClient, profile_xml: str, log: Log = print) -> None:
    """Envia o device-profile.xml para a TV, como o Device Manager do Tizen Studio faz."""
    remote = f"{PERMIT_DIR}/device-profile.xml"
    log(f"Permit to install: enviando {profile_xml} para {remote}")
    client.push(profile_xml, remote, mode=0o644)


@dataclass
class InstallReport:
    ok: bool
    stage: str
    message: str
    package_id: str = ""
    app_id: str = ""
    platform_version: str = ""
    install_output: str = ""
    launch_output: str = ""
    diagnosis: Diagnosis | None = None
    capability: dict[str, str] = field(default_factory=dict)


def install_wgt(
    host: str,
    wgt_path: str,
    expected_sha256: str | None = None,
    install_id: str = "package",
    launch: bool = True,
    log: Log = print,
    trace=None,
    port: int = SDB_PORT,
    permit_profile: str | None = None,
) -> InstallReport:
    if expected_sha256:
        actual = sha256_file(wgt_path)
        if actual.lower() != expected_sha256.lower():
            return InstallReport(False, "hash", "O arquivo baixado está corrompido (SHA-256 não confere).")
        log(f"SHA-256 conferido: {actual}")

    info = read_wgt(wgt_path)
    if not (info.has_author_signature and info.has_distributor_signature):
        return InstallReport(False, "assinatura", "O pacote não está assinado (faltam author-signature.xml ou signature1.xml).")
    target = info.package_id if install_id == "package" else info.app_id
    if not TIZEN_ID.match(target):
        return InstallReport(False, "pacote", f"ID inválido no config.xml: {target!r}")

    report = InstallReport(False, "conexão", "", package_id=info.package_id, app_id=info.app_id)
    try:
        with SdbClient(host, port=port, trace=trace) as client:
            log(f"Conectado: {client.banner.raw if client.banner else ''} (versão {client.device_version:#x}, maxdata {client.max_payload})")
            report.stage = "capability"
            report.capability = client.capability()
            report.platform_version = report.capability.get("platform_version", "")
            if permit_profile:
                report.stage = "permissão"
                permit_install(client, permit_profile, log)

            report.stage = "envio"
            remote = ""
            failures = []
            for directory in upload_dirs(report.capability):
                candidate = f"{directory}/{info.package_id}.wgt"
                log(f"Enviando para {candidate}")
                try:
                    client.push(wgt_path, candidate, progress=_percent_logger(log))
                except SdbError as exc:
                    failures.append(f"{directory}: {exc}")
                    continue
                remote = candidate
                break
            if not remote:
                raise SdbError("nenhuma pasta temporária aceitou o pacote (" + "; ".join(failures) + ")")

            report.stage = "instalação"
            command = f"0 vd_appinstall {target} {remote}"
            log(f"$ {command}")
            report.install_output = client.shell(command, timeout=300)
            log(report.install_output.strip())
            report.diagnosis = diagnose_install(report.install_output)
            if report.diagnosis.outcome is Outcome.FAILURE:
                report.message = report.diagnosis.message
                return report

            if launch:
                report.stage = "abertura"
                for template in LAUNCH_COMMANDS:
                    command = template.format(app_id=info.app_id)
                    log(f"$ {command}")
                    try:
                        report.launch_output = client.shell(command, timeout=60)
                    except SdbServiceRefused:
                        # Tizen 2.4 (UN49KU6300) fecha o canal para "0 execute"; só aceita "0 was_execute".
                        log("  comando recusado pela TV; tentando o próximo")
                        continue
                    log(report.launch_output.strip())
                    if diagnose_launch(report.launch_output) is Outcome.SUCCESS:
                        break
    except SdbError as exc:
        report.message = _explain(report.stage, exc)
        return report

    report.ok = report.diagnosis is not None and report.diagnosis.outcome is not Outcome.FAILURE
    report.stage = "concluído"
    report.message = report.diagnosis.message if report.diagnosis else ""
    return report


def upload_dirs(capability: dict[str, str]) -> list[str]:
    """Pastas para o push, na ordem de tentativa.

    TVs novas informam ``sdk_toolpath`` no capability. Uma UN49KU6300 (2016, Tizen 2.4) não
    informa, aceita ``/opt/usr/apps/tmp`` e recusa ``/home/owner/share/tmp/sdk_tools/tmp``.
    """
    dirs = []
    if capability.get("sdk_toolpath"):
        dirs.append(f"{capability['sdk_toolpath'].rstrip('/')}/tmp")
    for fallback in (LEGACY_UPLOAD_DIR, f"{DEFAULT_SDK_TOOLPATH}/tmp"):
        if fallback not in dirs:
            dirs.append(fallback)
    return dirs


def _percent_logger(log: Log):
    last = [-1]

    def progress(sent: int, total: int) -> None:
        percent = sent * 100 // total if total else 100
        if percent // 10 != last[0] // 10:
            last[0] = percent
            log(f"  {percent}% ({sent}/{total} bytes)")

    return progress


def _explain(stage: str, exc: SdbError) -> str:
    if stage == "conexão":
        return (
            f"Não foi possível conectar à TV ({exc}). Confira se o Modo Desenvolvedor está ligado com o IP "
            "deste aparelho, se a TV foi reiniciada depois disso e se o Tizen Studio/sdb no PC está fechado "
            "(a TV aceita uma conexão por vez)."
        )
    return f"Falha na etapa de {stage}: {exc}"
