"""Upload de pacotes e ícones: extensão, tamanho, SHA-256 e config.xml do .wgt."""

from __future__ import annotations

import hashlib
import re
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

from fastapi import UploadFile

from .models import EXTENSIONS

W3C_NS = "http://www.w3.org/ns/widgets"
TIZEN_NS = "http://tizen.org/ns/widgets"
TIZEN_ID = re.compile(r"^[A-Za-z0-9_.]+$")
ICON_TYPES = {".png", ".jpg", ".jpeg", ".webp"}
CHUNK = 1024 * 1024


class UploadError(Exception):
    """Arquivo recusado; a mensagem vai para o admin."""


@dataclass(frozen=True)
class StoredFile:
    stored_name: str
    size: int
    sha256: str


@dataclass(frozen=True)
class WgtConfig:
    version: str
    package_id: str
    app_id: str
    required_version: str
    name: str


def extension_of(filename: str) -> str:
    return Path(filename or "").suffix.lower()


async def save_upload(upload: UploadFile, directory: Path, extension: str, max_bytes: int) -> StoredFile:
    """Grava em disco em blocos, calculando o SHA-256 e parando no tamanho máximo."""
    directory.mkdir(parents=True, exist_ok=True)
    stored_name = f"{uuid.uuid4().hex}{extension}"
    target = directory / stored_name
    digest = hashlib.sha256()
    size = 0
    try:
        with target.open("wb") as out:
            while chunk := await upload.read(CHUNK):
                size += len(chunk)
                if size > max_bytes:
                    raise UploadError(f"Arquivo maior que o limite de {max_bytes // (1024 * 1024)} MB.")
                digest.update(chunk)
                out.write(chunk)
        if size == 0:
            raise UploadError("Arquivo vazio.")
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    return StoredFile(stored_name, size, digest.hexdigest())


async def save_package(upload: UploadFile, platform: str, directory: Path, max_bytes: int) -> StoredFile:
    expected = EXTENSIONS[platform]
    if extension_of(upload.filename or "") != expected:
        raise UploadError(f"Para esta plataforma o arquivo precisa ser {expected}.")
    return await save_upload(upload, directory, expected, max_bytes)


async def save_icon(upload: UploadFile, directory: Path, max_bytes: int) -> str:
    extension = extension_of(upload.filename or "")
    if extension not in ICON_TYPES:
        raise UploadError("Ícone precisa ser PNG, JPG ou WEBP.")
    return (await save_upload(upload, directory, extension, max_bytes)).stored_name


def read_wgt_config(path: Path) -> WgtConfig:
    try:
        with zipfile.ZipFile(path) as zf:
            try:
                info = zf.getinfo("config.xml")
            except KeyError:
                raise UploadError("O .wgt não tem config.xml na raiz.") from None
            if info.file_size > 1024 * 1024:
                raise UploadError("config.xml grande demais.")
            root = ElementTree.fromstring(zf.read(info))
    except zipfile.BadZipFile:
        raise UploadError("O arquivo não é um .wgt válido (zip).") from None
    except ElementTree.ParseError as exc:
        raise UploadError(f"config.xml inválido: {exc}") from None
    app = root.find(f"{{{TIZEN_NS}}}application")
    if root.tag != f"{{{W3C_NS}}}widget" or app is None:
        raise UploadError("config.xml sem <widget> W3C ou sem <tizen:application>.")
    package_id, app_id = app.get("package", ""), app.get("id", "")
    if not TIZEN_ID.match(package_id) or not TIZEN_ID.match(app_id):
        raise UploadError("package/id do <tizen:application> inválidos.")
    name = root.find(f"{{{W3C_NS}}}name")
    return WgtConfig(
        version=root.get("version", ""),
        package_id=package_id,
        app_id=app_id,
        required_version=app.get("required_version", ""),
        name=(name.text or "").strip() if name is not None else "",
    )
