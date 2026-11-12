"""Procura as CAs intermediárias da Samsung numa instalação do Tizen Studio, inclusive dentro de .jar."""

from __future__ import annotations

import io
import os
import re
import warnings
import zipfile
from collections.abc import Iterator
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization

from .samsung_cert import CA_SUFFIXES, SamsungCertError, parse_certificates

ARCHIVES = {".jar", ".zip"}
MAX_CERT_FILE = 64 * 1024
MAX_NESTED_ARCHIVE = 50 * 1024 * 1024


def _is_ca(cert: x509.Certificate) -> bool:
    try:
        return bool(cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca)
    except (x509.ExtensionNotFound, ValueError):
        # Sem BasicConstraints, ou extensões malformadas (há certificados assim no Tizen Studio): decide pelo nome.
        return " CA" in _subject(cert)


def _subject(cert: x509.Certificate) -> str:
    try:
        return cert.subject.rfc4514_string()
    except ValueError:
        return ""


def _certs(data: bytes) -> list[x509.Certificate]:
    try:
        return parse_certificates(data)
    except (SamsungCertError, ValueError):
        return []


def scan(root: Path, name_filter: str = "Samsung") -> Iterator[tuple[str, x509.Certificate]]:
    """Devolve (origem, certificado) de cada CA cujo subject contém ``name_filter``."""
    with warnings.catch_warnings():
        # Certificados antigos do SDK com serial negativo geram CryptographyDeprecationWarning.
        warnings.simplefilter("ignore")
        yield from _scan(root, name_filter)


def _scan(root: Path, name_filter: str) -> Iterator[tuple[str, x509.Certificate]]:
    for directory, _dirs, files in os.walk(root, onerror=lambda _e: None):
        for name in files:
            path = Path(directory) / name
            suffix = path.suffix.lower()
            found: list[tuple[str, bytes]] = []
            try:
                if suffix in CA_SUFFIXES and path.stat().st_size <= MAX_CERT_FILE:
                    found.append((str(path), path.read_bytes()))
                elif suffix in ARCHIVES:
                    with zipfile.ZipFile(path) as zf:
                        found += _archive_entries(zf, str(path), depth=0)
            except (OSError, zipfile.BadZipFile):
                continue
            for origin, data in found:
                for cert in _certs(data):
                    if _is_ca(cert) and name_filter.lower() in _subject(cert).lower():
                        yield origin, cert


def _archive_entries(zf: zipfile.ZipFile, origin: str, depth: int) -> list[tuple[str, bytes]]:
    """Certificados dentro do arquivo e, um nível abaixo, dentro de .jar/.zip aninhados."""
    found = []
    for info in zf.infolist():
        suffix = Path(info.filename).suffix.lower()
        where = f"{origin}!{info.filename}"
        if suffix in CA_SUFFIXES and info.file_size <= MAX_CERT_FILE:
            found.append((where, zf.read(info)))
        elif suffix in ARCHIVES and depth == 0 and info.file_size <= MAX_NESTED_ARCHIVE:
            try:
                with zipfile.ZipFile(io.BytesIO(zf.read(info))) as inner:
                    found += _archive_entries(inner, where, depth + 1)
            except zipfile.BadZipFile:
                continue
    return found


def export(certs: list[x509.Certificate], target: Path) -> list[Path]:
    """Grava cada CA (sem repetir) como ``<CN>.cer`` em DER."""
    target.mkdir(parents=True, exist_ok=True)
    written, seen = [], set()
    for cert in certs:
        fingerprint = cert.fingerprint(hashes.SHA256())
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        cn = cert.subject.get_attributes_for_oid(x509.oid.NameOID.COMMON_NAME)
        base = re.sub(r"[^A-Za-z0-9._-]+", "_", str(cn[0].value) if cn else fingerprint.hex()[:16])
        path = target / f"{base}-{fingerprint.hex()[:8]}.cer"
        path.write_bytes(cert.public_bytes(serialization.Encoding.DER))
        written.append(path)
    return written
