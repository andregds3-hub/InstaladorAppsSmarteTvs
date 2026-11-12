"""Assinatura de pacotes .wgt no formato W3C Widget Digital Signature usado pelo Tizen.

Gera ``author-signature.xml`` e ``signature1.xml`` (distribuidor). O XML é escrito
já na forma canônica, então o texto assinado é exatamente o que está no arquivo:

* ``SignedInfo`` usa Exclusive C14N; a única diferença para o arquivo é o
  ``xmlns`` do dsig, que a canonização coloca no próprio elemento.
* ``#prop`` usa C14N 1.1 inclusiva; o ``Object`` herda o ``xmlns`` do dsig.

O formato segue byte a byte o que o Tizen CLI gera (comparado com um pacote assinado por
``tizen package``): RSA-SHA512 e SHA-512, base64 em linhas de 76 caracteres (SignatureValue e
X509Certificate entre quebras de linha), referências e entradas do zip em ordem alfabética, sem
declaração XML e sem quebra de linha final.
"""

from __future__ import annotations

import base64
import hashlib
import re
import zipfile
from dataclasses import dataclass
from urllib.parse import quote, unquote

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.serialization import pkcs12

DSIG_NS = "http://www.w3.org/2000/09/xmldsig#"
DSP_NS = "http://www.w3.org/2009/xmldsig-properties"
EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
C14N11 = "http://www.w3.org/2006/12/xml-c14n11"
RSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"
SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"
DIGESTS = {SHA512: hashlib.sha512, "http://www.w3.org/2001/04/xmlenc#sha256": hashlib.sha256}
WIDGET_DIGSIG = "http://www.w3.org/ns/widgets-digsig"

AUTHOR_FILE = "author-signature.xml"
DISTRIBUTOR_FILE = "signature1.xml"
_SIGNATURE_FILE = re.compile(r"^(author-signature|signature\d+)\.xml$")


class SigningError(Exception):
    """Falha ao assinar ou verificar um pacote."""


@dataclass(frozen=True)
class SigningIdentity:
    key: rsa.RSAPrivateKey
    chain: list[x509.Certificate]


def load_p12(data: bytes, password: str) -> SigningIdentity:
    key, cert, extra = pkcs12.load_key_and_certificates(data, password.encode("utf-8"))
    if not isinstance(key, rsa.RSAPrivateKey) or cert is None:
        raise SigningError("o .p12 precisa ter uma chave RSA e o certificado")
    return SigningIdentity(key, [cert, *extra])


def _attr(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace('"', "&quot;")
        .replace("\t", "&#x9;")
        .replace("\n", "&#xA;")
        .replace("\r", "&#xD;")
    )


def reference_uri(name: str) -> str:
    return quote(name, safe="/")


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _b64_lines(data: bytes) -> str:
    """Base64 em linhas de 76 caracteres, como o encoder MIME do Java usado pelo Tizen."""
    text = _b64(data)
    return "\n".join(text[i:i + 76] for i in range(0, len(text), 76))


def _prop_object(signature_id: str, role: str) -> str:
    def prop(prop_id: str, inner: str) -> str:
        return f'<SignatureProperty Id="{prop_id}" Target="#{signature_id}">{inner}</SignatureProperty>'

    return (
        '<Object Id="prop">'
        f'<SignatureProperties xmlns:dsp="{DSP_NS}">'
        + prop("profile", f'<dsp:Profile URI="{WIDGET_DIGSIG}#profile"></dsp:Profile>')
        + prop("role", f'<dsp:Role URI="{WIDGET_DIGSIG}#role-{role}"></dsp:Role>')
        + prop("identifier", "<dsp:Identifier></dsp:Identifier>")
        + "</SignatureProperties></Object>"
    )


def _with_dsig_ns(element_xml: str, tag: str) -> str:
    return element_xml.replace(f"<{tag}", f'<{tag} xmlns="{DSIG_NS}"', 1)


def _signed_info(references: list[tuple[str, str]], prop_digest: str) -> str:
    lines = [
        "<SignedInfo>",
        f'<CanonicalizationMethod Algorithm="{EXC_C14N}"></CanonicalizationMethod>',
        f'<SignatureMethod Algorithm="{RSA_SHA512}"></SignatureMethod>',
    ]
    for uri, digest in references:
        lines += [
            f'<Reference URI="{_attr(uri)}">',
            f'<DigestMethod Algorithm="{SHA512}"></DigestMethod>',
            f"<DigestValue>{digest}</DigestValue>",
            "</Reference>",
        ]
    lines += [
        '<Reference URI="#prop">',
        f'<Transforms>\n<Transform Algorithm="{C14N11}"></Transform>\n</Transforms>',
        f'<DigestMethod Algorithm="{SHA512}"></DigestMethod>',
        f"<DigestValue>{prop_digest}</DigestValue>",
        "</Reference>",
        "</SignedInfo>",
    ]
    return "\n".join(lines)


def build_signature(files: list[tuple[str, bytes]], identity: SigningIdentity, role: str) -> bytes:
    """Monta o XML de assinatura (``role`` é ``author`` ou ``distributor``)."""
    signature_id = "AuthorSignature" if role == "author" else "DistributorSignature"
    references = [
        (reference_uri(name), _b64_lines(hashlib.sha512(data).digest())) for name, data in sorted(files)
    ]
    prop = _prop_object(signature_id, role)
    prop_digest = _b64_lines(hashlib.sha512(_with_dsig_ns(prop, "Object").encode("utf-8")).digest())
    signed_info = _signed_info(references, prop_digest)
    canonical = _with_dsig_ns(signed_info, "SignedInfo").encode("utf-8")
    signature = identity.key.sign(canonical, padding.PKCS1v15(), hashes.SHA512())
    certs = "\n".join(
        f"<X509Certificate>\n{_b64_lines(cert.public_bytes(serialization.Encoding.DER))}\n</X509Certificate>"
        for cert in identity.chain
    )
    document = "\n".join(
        [
            f'<Signature xmlns="{DSIG_NS}" Id="{signature_id}">',
            signed_info,
            f"<SignatureValue>\n{_b64_lines(signature)}\n</SignatureValue>",
            f"<KeyInfo>\n<X509Data>\n{certs}\n</X509Data>\n</KeyInfo>",
            prop,
            "</Signature>",
        ]
    )
    return document.encode("utf-8")


def _content_files(zf: zipfile.ZipFile) -> list[tuple[str, bytes]]:
    files = []
    for info in zf.infolist():
        name = info.filename
        if info.is_dir() or _SIGNATURE_FILE.match(name):
            continue
        if name.startswith("/") or ".." in name.split("/") or "\\" in name:
            raise SigningError(f"caminho inválido dentro do pacote: {name!r}")
        files.append((name, zf.read(info)))
    if not any(name == "config.xml" for name, _ in files):
        raise SigningError("o pacote não tem config.xml na raiz")
    return files


def sign_wgt(source: str, target: str, author: SigningIdentity, distributor: SigningIdentity) -> None:
    """Reassina ``source`` (assinaturas antigas são descartadas) e grava em ``target``."""
    with zipfile.ZipFile(source) as zf:
        files = _content_files(zf)
    author_xml = build_signature(files, author, "author")
    distributor_xml = build_signature(files + [(AUTHOR_FILE, author_xml)], distributor, "distributor")
    entries = sorted(files + [(AUTHOR_FILE, author_xml), (DISTRIBUTOR_FILE, distributor_xml)])
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as out:
        for name, data in entries:
            out.writestr(name, data)


def check_digests(path: str) -> list[str]:
    """Confere os DigestValue de arquivos das duas assinaturas; devolve os problemas encontrados.

    Não valida a cadeia nem o SignatureValue: isso é papel da TV.
    """
    from xml.etree import ElementTree

    problems: list[str] = []
    ns = {"ds": DSIG_NS}
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())
        for sig_name in (AUTHOR_FILE, DISTRIBUTOR_FILE):
            if sig_name not in names:
                problems.append(f"{sig_name} ausente")
                continue
            root = ElementTree.fromstring(zf.read(sig_name))
            covered = set()
            for ref in root.iterfind("ds:SignedInfo/ds:Reference", ns):
                uri = ref.get("URI", "")
                if uri.startswith("#"):
                    continue
                name = unquote(uri)
                covered.add(name)
                if name not in names:
                    problems.append(f"{sig_name}: {name} não existe no pacote")
                    continue
                expected = "".join(ref.findtext("ds:DigestValue", default="", namespaces=ns).split())
                method = ref.find("ds:DigestMethod", ns)
                digest = DIGESTS.get(method.get("Algorithm", "") if method is not None else "")
                if digest is None:
                    problems.append(f"{sig_name}: algoritmo de digest desconhecido em {name}")
                    continue
                actual = _b64(digest(zf.read(name)).digest())
                if expected != actual:
                    problems.append(f"{sig_name}: digest de {name} não confere")
            for name in names:
                if name.endswith("/") or _SIGNATURE_FILE.match(name) and not (
                    sig_name == DISTRIBUTOR_FILE and name == AUTHOR_FILE
                ):
                    continue
                if name not in covered:
                    problems.append(f"{sig_name}: {name} não está assinado")
    return problems
