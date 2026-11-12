"""Interpretação da saída de ``0 vd_appinstall`` e ``0 execute`` em mensagens claras.

As marcas foram reunidas de saídas reais publicadas (Apps2Samsung, free-my-tv,
react-native-tizen). Confirme e complete com o que a sua TV devolver na Fase 0.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum


class Outcome(str, Enum):
    SUCCESS = "success"
    FAILURE = "failure"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Diagnosis:
    outcome: Outcome
    code: str | None
    message: str


_INSTALL_FAILED = re.compile(r"install failed\[(\d+)(?:,\s*(-?\d+))?\]", re.IGNORECASE)
_DOWNLOAD_FAILED = re.compile(r"download failed\[(\d+)\]", re.IGNORECASE)
_CONNECTION_LOST = re.compile(r"transport connection lost|connection reset by peer", re.IGNORECASE)
_KEY_END_FAIL = re.compile(r"key\[end\]\s*val\[fail\]", re.IGNORECASE)
_SUCCESS = re.compile(r"installing\[100\]|install completed", re.IGNORECASE)


def diagnose_install(output: str) -> Diagnosis:
    text = output or ""
    match = _INSTALL_FAILED.search(text)
    if match:
        return _diagnose_code(match.group(1), match.group(2), text)
    match = _DOWNLOAD_FAILED.search(text)
    if match:
        if match.group(1) == "116":
            return Diagnosis(Outcome.FAILURE, "116", "A TV está sem espaço livre. Desinstale outros apps e tente de novo.")
        return Diagnosis(Outcome.FAILURE, match.group(1), f"A TV não conseguiu receber o pacote (código {match.group(1)}).")
    if _CONNECTION_LOST.search(text):
        return Diagnosis(Outcome.FAILURE, None, "A conexão com a TV caiu durante a instalação. Confira o Wi-Fi e tente de novo.")
    if _KEY_END_FAIL.search(text):
        return Diagnosis(Outcome.FAILURE, None, "A TV informou falha na instalação.")
    if _SUCCESS.search(text):
        return Diagnosis(Outcome.SUCCESS, None, "App instalado.")
    return Diagnosis(Outcome.UNKNOWN, None, "A TV não confirmou a instalação. Veja a saída completa no log.")


def _diagnose_code(code: str, detail: str | None, text: str) -> Diagnosis:
    full = f"{code},{detail}" if detail else code
    lower = text.lower()
    if code == "116":
        return Diagnosis(Outcome.FAILURE, full, "A TV está sem espaço livre. Desinstale outros apps e tente de novo.")
    if (code == "118" and detail == "-12") or code == "118012":
        if "different certificate" in lower:
            message = (
                "Já existe uma versão deste app na TV assinada com outro certificado. "
                "Desinstale o app da TV e instale de novo."
            )
        elif "expired" in lower:
            message = "O certificado que assinou o pacote expirou. Gere um certificado novo."
        elif "not valid yet" in lower:
            message = "O certificado ainda não é válido. Confira a data e a hora da TV."
        elif "invalid format" in lower:
            message = "O certificado da assinatura está em formato inválido."
        else:
            message = (
                "A TV recusou a assinatura do pacote. Confira se o certificado de distribuidor "
                "contém o DUID desta TV."
            )
        return Diagnosis(Outcome.FAILURE, full, message)
    if (code == "118" and detail == "-19") or code == "118019":
        return Diagnosis(Outcome.FAILURE, full, "A TV não conseguiu ler o config.xml do pacote.")
    if code == "118" and detail == "-4":
        return Diagnosis(
            Outcome.FAILURE,
            full,
            "A TV não permite este pacote: a versão de Tizen exigida pelo app é maior que a da TV, "
            "ou falta permissão (privilégio) no certificado.",
        )
    if code == "118" and detail == "-22":
        return Diagnosis(Outcome.FAILURE, full, "O ID do pacote está bloqueado ou em conflito com um app de sistema da TV.")
    if code == "118" and detail is None:
        # TVs 2016 (Tizen 2.4) devolvem 118 sem detalhe até para assinatura com cadeia incompleta.
        return Diagnosis(
            Outcome.FAILURE,
            full,
            "A TV recusou o pacote (código 118, sem detalhe). Causas comuns: assinatura ou certificado "
            "inválido para esta TV, ou o app já instalado com outro certificado (nesse caso, desinstale-o).",
        )
    return Diagnosis(Outcome.FAILURE, full, f"A TV recusou a instalação (código {full}).")


_LAUNCH_OK = re.compile(r"success|launched", re.IGNORECASE)
_LAUNCH_FAIL = re.compile(r"fail|error|not found|invalid", re.IGNORECASE)


def diagnose_launch(output: str) -> Outcome:
    text = output or ""
    if _LAUNCH_FAIL.search(text):
        return Outcome.FAILURE
    if _LAUNCH_OK.search(text):
        return Outcome.SUCCESS
    return Outcome.UNKNOWN
