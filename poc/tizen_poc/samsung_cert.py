"""Estratégia A: certificados Samsung gerados com a conta Samsung do próprio usuário.

A API não é documentada. Os endereços e campos abaixo são os que a Samsung
Certificate Extension usa, conforme reproduzido pelo projeto Apps2Samsung
(github.com/Apps2Samsung/Apps2Samsung). Ver docs/certificados-tizen.md.
"""

from __future__ import annotations

import json
import re
import secrets
import threading
import urllib.error
import urllib.request
import webbrowser
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

SIGN_IN_GATE = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate"
CLIENT_ID = "v285zxnl3h"
STATE = "accountcheckdogeneratedstatetext"
CALLBACK_HOST = "localhost"
CALLBACK_PORT = 4794
CALLBACK_PATH = "/signin/callback"

AUTHOR_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
DISTRIBUTOR_V1_URL = "https://svdca.samsungqbe.com/apis/v1/distributors"
DISTRIBUTOR_V3_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"
PLATFORM = "VD"

DUID = re.compile(r"^[A-Za-z0-9._:-]{4,64}$")


class SamsungCertError(Exception):
    """Falha na geração de certificado pela Samsung."""


@dataclass(frozen=True)
class SamsungAuth:
    access_token: str = field(repr=False)
    user_id: str
    email: str


def redirect_uri() -> str:
    return f"http://{CALLBACK_HOST}:{CALLBACK_PORT}{CALLBACK_PATH}"


def authorize_url() -> str:
    query = urlencode(
        {"locale": "", "clientId": CLIENT_ID, "redirect_uri": redirect_uri(), "state": STATE, "tokenType": "TOKEN"}
    )
    return f"{SIGN_IN_GATE}?{query}"


def parse_callback(body: bytes) -> SamsungAuth:
    """O login devolve um POST de formulário com ``state`` e ``code`` (um JSON)."""
    form = parse_qs(body.decode("utf-8", errors="replace"))
    state = (form.get("state") or [""])[0]
    if state and state != STATE:
        raise SamsungCertError("resposta de login com state diferente do enviado")
    try:
        code = json.loads((form.get("code") or [""])[0])
    except ValueError as exc:
        raise SamsungCertError("resposta de login sem o campo code") from exc
    token = str(code.get("access_token") or "")
    user_id = str(code.get("userId") or "")
    email = str(code.get("inputEmailID") or "")
    if not token or not user_id:
        raise SamsungCertError("a Samsung não devolveu token de acesso")
    if not email:
        raise SamsungCertError(
            "a conta Samsung não tem e-mail cadastrado; o certificado de distribuidor exige um e-mail"
        )
    return SamsungAuth(token, user_id, email)


def login_with_browser(timeout: float = 300.0) -> SamsungAuth:
    """Abre o login da Samsung no navegador e espera o retorno em localhost:4794."""
    result: dict[str, object] = {}
    done = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            if not self.path.startswith(CALLBACK_PATH):
                self.send_error(404)
                return
            size = min(int(self.headers.get("Content-Length") or 0), 64 * 1024)
            try:
                result["auth"] = parse_callback(self.rfile.read(size))
                self._reply(200, "Login concluído. Pode fechar esta aba.")
            except SamsungCertError as exc:
                result["error"] = exc
                self._reply(400, f"Falha no login: {exc}")
            done.set()

        def _reply(self, status: int, text: str) -> None:
            body = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args: object) -> None:
            pass  # o corpo da requisição tem o token: nada vai para o log

    server = HTTPServer(("127.0.0.1", CALLBACK_PORT), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        webbrowser.open(authorize_url())
        if not done.wait(timeout):
            raise SamsungCertError("tempo esgotado esperando o login da Samsung")
    finally:
        server.shutdown()
        server.server_close()
    if "error" in result:
        raise result["error"]  # type: ignore[misc]
    return result["auth"]  # type: ignore[return-value]


def new_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def author_csr(key: rsa.RSAPrivateKey, name: str) -> x509.CertificateSigningRequest:
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    return x509.CertificateSigningRequestBuilder().subject_name(subject).sign(key, hashes.SHA256())


def distributor_csr(key: rsa.RSAPrivateKey, email: str, duids: list[str]) -> x509.CertificateSigningRequest:
    if not duids:
        raise SamsungCertError("informe ao menos um DUID")
    for duid in duids:
        if not DUID.match(duid):
            raise SamsungCertError(f"DUID inválido: {duid!r}")
    subject = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "TizenSDK"),
            x509.NameAttribute(NameOID.EMAIL_ADDRESS, email),
        ]
    )
    names = [x509.UniformResourceIdentifier("URN:tizen:packageid=")]
    names += [x509.UniformResourceIdentifier(f"URN:tizen:deviceid={duid}") for duid in duids]
    return (
        x509.CertificateSigningRequestBuilder()
        .subject_name(subject)
        .add_extension(x509.SubjectAlternativeName(names), critical=False)
        .sign(key, hashes.SHA256())
    )


def multipart(fields: dict[str, str], file_field: str, filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = "----tizenpoc" + secrets.token_hex(12)
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8")
        )
    parts.append(
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8")
        + content
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def _post(url: str, fields: dict[str, str], filename: str, csr: x509.CertificateSigningRequest) -> bytes:
    body, content_type = multipart(fields, "csr", filename, csr.public_bytes(serialization.Encoding.PEM))
    request = urllib.request.Request(url, data=body, method="POST", headers={"Content-Type": content_type})
    try:
        with urllib.request.urlopen(request, timeout=60) as resp:
            return resp.read(1024 * 1024)
    except urllib.error.HTTPError as exc:
        detail = exc.read(2048).decode("utf-8", errors="replace")
        raise SamsungCertError(f"{url} respondeu {exc.code}: {detail}") from None
    except urllib.error.URLError as exc:
        raise SamsungCertError(f"sem acesso a {url}: {exc.reason}") from None


def request_author(auth: SamsungAuth, csr: x509.CertificateSigningRequest) -> bytes:
    fields = {"access_token": auth.access_token, "user_id": auth.user_id, "platform": PLATFORM}
    return _post(AUTHOR_URL, fields, "author.csr", csr)


def request_distributor(
    auth: SamsungAuth, csr: x509.CertificateSigningRequest, privilege: str = "public"
) -> tuple[bytes, bytes]:
    """Devolve (device-profile.xml da v1, certificado da v3)."""
    # A svdca só entende o nível em minúsculas; "Partner" gera um certificado public sem avisar.
    fields = {
        "access_token": auth.access_token,
        "user_id": auth.user_id,
        "privilege_level": privilege.lower(),
        "developer_type": "Individual",
        "platform": PLATFORM,
    }
    profile = _post(DISTRIBUTOR_V1_URL, fields, "distributor.csr", csr)
    cert = _post(DISTRIBUTOR_V3_URL, fields, "distributor.csr", csr)
    return profile, cert


def parse_certificates(data: bytes) -> list[x509.Certificate]:
    """Aceita PEM (um ou vários), DER, DER em base64 ou JSON com um desses dentro."""
    text = data.decode("utf-8", errors="replace").strip()
    if text.startswith("{"):
        try:
            payload = json.loads(text)
        except ValueError:
            payload = None
        if isinstance(payload, dict):
            for value in payload.values():
                if isinstance(value, str) and len(value) > 100:
                    try:
                        return parse_certificates(value.encode("utf-8"))
                    except SamsungCertError:
                        continue
    blocks = re.findall(r"-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----", text, re.DOTALL)
    if blocks:
        return x509.load_pem_x509_certificates("\n".join(blocks).encode("ascii", errors="ignore"))
    try:
        return [x509.load_der_x509_certificate(data)]
    except ValueError:
        pass
    try:
        import base64

        return [x509.load_der_x509_certificate(base64.b64decode(text, validate=False))]
    except ValueError:
        pass
    raise SamsungCertError(f"resposta da Samsung não contém certificado reconhecível: {text[:200]!r}")


def export_p12(key: rsa.RSAPrivateKey, chain: list[x509.Certificate], password: str) -> bytes:
    """PKCS#12 com 3DES/SHA1, o que o Tizen Studio (Java) consegue abrir."""
    encryption = (
        serialization.PrivateFormat.PKCS12.encryption_builder()
        .kdf_rounds(2048)
        .key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
        .hmac_hash(hashes.SHA1())
        .build(password.encode("utf-8"))
    )
    return pkcs12.serialize_key_and_certificates(b"usercertificate", key, chain[0], chain[1:] or None, encryption)


CA_SUFFIXES = {".cer", ".crt", ".pem", ".der"}


def save_key(path: Path, key: rsa.RSAPrivateKey, password: str) -> None:
    """Chave privada em PKCS#8 criptografada com a senha do perfil."""
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password.encode("utf-8")),
        )
    )


def load_key(path: Path, password: str) -> rsa.RSAPrivateKey:
    try:
        key = serialization.load_pem_private_key(path.read_bytes(), password.encode("utf-8"))
    except ValueError as exc:
        raise SamsungCertError(f"não foi possível abrir {path.name}: senha errada?") from exc
    if not isinstance(key, rsa.RSAPrivateKey):
        raise SamsungCertError(f"{path.name} não é uma chave RSA")
    return key


def find_issuer(cert: x509.Certificate, ca_dirs: list[Path], ca_files: list[str | None]) -> x509.Certificate | None:
    """Procura a CA que emitiu ``cert`` (mesmo nome e assinatura válida) nos arquivos e pastas dados."""
    candidates: list[x509.Certificate] = []
    paths = [Path(f) for f in ca_files if f]
    for directory in ca_dirs:
        if directory.is_dir():
            paths += sorted(p for p in directory.iterdir() if p.suffix.lower() in CA_SUFFIXES)
    for path in paths:
        try:
            candidates += parse_certificates(path.read_bytes())
        except (SamsungCertError, OSError):
            continue
    for ca in candidates:
        if ca.subject != cert.issuer:
            continue
        try:
            cert.verify_directly_issued_by(ca)
        except (ValueError, TypeError, InvalidSignature):
            continue
        return ca
    return None


@dataclass
class ProfileResult:
    author: list[x509.Certificate] | None = None
    distributor: list[x509.Certificate] | None = None
    issued: dict[str, x509.Certificate] = field(default_factory=dict)
    missing_ca: dict[str, str] = field(default_factory=dict)


def build_profile(
    out: Path,
    password: str,
    duids: list[str],
    login,
    author_name: str = "InstaladorTV",
    privilege: str = "public",
    ca_dir: Path | None = None,
    author_ca: str | None = None,
    distributor_ca: str | None = None,
    allow_missing_ca: bool = False,
    log=print,
) -> ProfileResult:
    """Gera ou completa o perfil em ``out`` (author.p12 + distributor.p12).

    Cada chave é salva criptografada antes do pedido à Samsung e cada resposta é guardada:
    se faltar a CA intermediária, a próxima execução reaproveita tudo sem pedir de novo.
    O login só acontece se for preciso pedir algum certificado. Com ``allow_missing_ca`` o .p12 sai
    só com a folha (experimento: ver se a TV completa a cadeia sozinha).
    """
    out.mkdir(parents=True, exist_ok=True)
    ca_dirs = [ca_dir or out / "ca"]
    session: list[SamsungAuth] = []

    def auth() -> SamsungAuth:
        if not session:
            session.append(login())
            log(f"Conta: {session[0].email}")
        return session[0]

    def distributor_request(key: rsa.RSAPrivateKey) -> bytes:
        profile, cert = request_distributor(auth(), distributor_csr(key, auth().email, duids), privilege)
        (out / "device-profile.xml").write_bytes(profile)
        return cert

    result = ProfileResult()
    kinds = (
        ("author", lambda key: request_author(auth(), author_csr(key, author_name)), lambda leaf: True, author_ca),
        ("distributor", distributor_request, lambda leaf: set(duids) <= set(certificate_duids(leaf)), distributor_ca),
    )
    for kind, request, reusable, extra_ca in kinds:
        p12_path = out / f"{kind}.p12"
        # O autor nunca é trocado: a TV só aceita atualizar um app assinado pelo mesmo autor.
        if p12_path.exists() and kind == "author":
            from .signer import load_p12

            identity = load_p12(p12_path.read_bytes(), password)
            chain = identity.chain
            log("Reaproveitando author.p12.")
            if len(chain) == 1:
                # author.p12 montado sem a CA (--allow-missing-ca): completa com a mesma chave e folha.
                ca = find_issuer(chain[0], ca_dirs, [extra_ca])
                if ca is not None:
                    chain = [chain[0], ca]
                    p12_path.write_bytes(export_p12(identity.key, chain, password))
                else:
                    result.missing_ca[kind] = chain[0].issuer.rfc4514_string()
                    if not allow_missing_ca:
                        continue
        else:
            key, response = _issued(out, kind, password, request, reusable, log)
            certs = parse_certificates(response)
            result.issued[kind] = certs[0]
            if len(certs) == 1:
                ca = find_issuer(certs[0], ca_dirs, [extra_ca])
                if ca is None:
                    result.missing_ca[kind] = certs[0].issuer.rfc4514_string()
                    if not allow_missing_ca:
                        continue
                else:
                    certs.append(ca)
            chain = certs
            p12_path.write_bytes(export_p12(key, chain, password))
        setattr(result, kind, chain)
    return result


def _issued(out: Path, kind: str, password: str, request, reusable, log) -> tuple[rsa.RSAPrivateKey, bytes]:
    key_path, response_path = out / f"{kind}-key.pem", out / f"{kind}-response.bin"
    if key_path.exists() and response_path.exists():
        key = load_key(key_path, password)
        response = response_path.read_bytes()
        leaf = parse_certificates(response)[0]
        still_valid = leaf.not_valid_after_utc > datetime.now(timezone.utc)
        if leaf.public_key().public_numbers() == key.public_key().public_numbers() and still_valid and reusable(leaf):
            log(f"Reaproveitando o certificado de {kind} já emitido.")
            return key, response
    key = new_key()
    save_key(key_path, key, password)
    response = request(key)
    response_path.write_bytes(response)
    log(f"Certificado de {kind} emitido pela Samsung.")
    return key, response


def certificate_duids(cert: x509.Certificate) -> list[str]:
    try:
        san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    except x509.ExtensionNotFound:
        return []
    prefix = "URN:tizen:deviceid="
    return [uri[len(prefix):] for uri in san.get_values_for_type(x509.UniformResourceIdentifier) if uri.startswith(prefix)]
