"""Cadeias de certificado de teste (raiz → intermediária → folha)."""

from __future__ import annotations

import datetime

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from tizen_poc.signer import SigningIdentity


def _name(cn: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def _cert(subject: str, key, issuer: str, issuer_key, ca: bool, san: list[str] | None = None) -> x509.Certificate:
    now = datetime.datetime.now(datetime.timezone.utc)
    builder = (
        x509.CertificateBuilder()
        .subject_name(_name(subject))
        .issuer_name(_name(issuer))
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
    )
    if san:
        builder = builder.add_extension(
            x509.SubjectAlternativeName([x509.UniformResourceIdentifier(u) for u in san]), critical=False
        )
    return builder.sign(issuer_key, hashes.SHA256())


def make_ca(name: str):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return _cert(name, key, name, key, True), key


def issue(csr: x509.CertificateSigningRequest, ca: x509.Certificate, ca_key) -> x509.Certificate:
    """Emite um certificado a partir do CSR, copiando o SAN, como a CA da Samsung faz."""
    now = datetime.datetime.now(datetime.timezone.utc)
    builder = (
        x509.CertificateBuilder()
        .subject_name(csr.subject)
        .issuer_name(ca.subject)
        .public_key(csr.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
    )
    try:
        san = csr.extensions.get_extension_for_class(x509.SubjectAlternativeName)
        builder = builder.add_extension(san.value, critical=False)
    except x509.ExtensionNotFound:
        pass
    return builder.sign(ca_key, hashes.SHA256())


def identity(prefix: str, san: list[str] | None = None) -> SigningIdentity:
    root_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    inter_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    _cert(f"{prefix} root", root_key, f"{prefix} root", root_key, True)
    inter = _cert(f"{prefix} CA", inter_key, f"{prefix} root", root_key, True)
    leaf = _cert(f"{prefix} leaf", leaf_key, f"{prefix} CA", inter_key, False, san)
    return SigningIdentity(leaf_key, [leaf, inter])
