"""Leitura de pacotes .wgt: config.xml, IDs, versão e presença de assinatura."""

from __future__ import annotations

import hashlib
import re
import zipfile
from dataclasses import dataclass
from xml.etree import ElementTree

W3C_NS = "http://www.w3.org/ns/widgets"
TIZEN_NS = "http://tizen.org/ns/widgets"

# O sdbd só aceita IDs nesse formato; também impede injeção no comando de shell.
TIZEN_ID = re.compile(r"^[A-Za-z0-9_.]+$")
MAX_CONFIG_SIZE = 1024 * 1024


class WgtError(Exception):
    """Pacote .wgt inválido."""


@dataclass(frozen=True)
class WgtInfo:
    widget_id: str
    version: str
    package_id: str
    app_id: str
    name: str
    required_version: str
    has_author_signature: bool
    has_distributor_signature: bool


def parse_config(xml: bytes) -> WgtInfo:
    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as exc:
        raise WgtError(f"config.xml inválido: {exc}") from exc
    if root.tag != f"{{{W3C_NS}}}widget":
        raise WgtError("config.xml não é um widget W3C")
    app = root.find(f"{{{TIZEN_NS}}}application")
    if app is None:
        raise WgtError("config.xml sem <tizen:application>")
    package_id = app.get("package", "")
    app_id = app.get("id", "")
    for label, value in (("package", package_id), ("id", app_id)):
        if not TIZEN_ID.match(value):
            raise WgtError(f"<tizen:application {label}> inválido: {value!r}")
    name_el = root.find(f"{{{W3C_NS}}}name")
    return WgtInfo(
        widget_id=root.get("id", ""),
        version=root.get("version", ""),
        package_id=package_id,
        app_id=app_id,
        name=(name_el.text or "").strip() if name_el is not None else "",
        required_version=app.get("required_version", ""),
        has_author_signature=False,
        has_distributor_signature=False,
    )


def read_wgt(path: str) -> WgtInfo:
    try:
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
            if "config.xml" not in names:
                raise WgtError("o pacote não tem config.xml na raiz")
            if zf.getinfo("config.xml").file_size > MAX_CONFIG_SIZE:
                raise WgtError("config.xml grande demais")
            info = parse_config(zf.read("config.xml"))
    except zipfile.BadZipFile as exc:
        raise WgtError("o arquivo não é um .wgt (zip) válido") from exc
    return WgtInfo(
        **{
            **info.__dict__,
            "has_author_signature": "author-signature.xml" in names,
            "has_distributor_signature": any(re.fullmatch(r"signature\d+\.xml", n) for n in names),
        }
    )


TEST_CONFIG = """<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://instaladortv.local/teste" version="1.0.0" viewmodes="maximized">
    <tizen:application id="{package}.InstaladorTeste" package="{package}" required_version="2.3"/>
    <content src="index.html"/>
    <feature name="http://tizen.org/feature/screen.size.all"/>
    <icon src="icon.png"/>
    <name>Instalador Teste</name>
    <tizen:profile name="{profile}"/>
    <tizen:setting screen-orientation="landscape" context-menu="enable" background-support="disable" encryption="disable" install-location="auto" hwkey-event="enable"/>
</widget>
"""

TEST_INDEX = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Instalador Teste</title></head>
<body style="background:#123;color:#fff;font:48px sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
Instalado pelo Instalador TV ✔
</body></html>
"""


def _png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    rows = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def make_test_wgt(path: str, profile: str = "tv-samsung", unpacked: str | None = None) -> WgtInfo:
    """App de teste mínimo com package ID aleatório de 10 caracteres (formato do Tizen).

    ``unpacked`` grava também os arquivos soltos numa pasta (para empacotar com o Tizen CLI).
    """
    import secrets
    import string
    from pathlib import Path

    package = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(10))
    files = {
        "config.xml": TEST_CONFIG.format(package=package, profile=profile).encode("utf-8"),
        "index.html": TEST_INDEX.encode("utf-8"),
        "icon.png": _png(117, 117, (0x12, 0x34, 0x56)),
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    if unpacked:
        target = Path(unpacked)
        target.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (target / name).write_bytes(data)
    return read_wgt(path)


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
