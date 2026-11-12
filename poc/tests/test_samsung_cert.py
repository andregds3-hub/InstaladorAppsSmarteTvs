import base64
import json
from urllib.parse import parse_qs, urlencode, urlparse

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.x509.oid import NameOID

from certs import identity, issue, make_ca
from tizen_poc import samsung_cert as sc
from tizen_poc.signer import load_p12


def _callback(code: dict, state: str = sc.STATE) -> bytes:
    return urlencode({"state": state, "code": json.dumps(code)}).encode()


def test_authorize_url():
    url = urlparse(sc.authorize_url())
    query = parse_qs(url.query)
    assert url.netloc == "account.samsung.com"
    assert query["clientId"] == [sc.CLIENT_ID]
    assert query["redirect_uri"] == ["http://localhost:4794/signin/callback"]
    assert query["tokenType"] == ["TOKEN"]


def test_parse_callback():
    auth = sc.parse_callback(_callback({"access_token": "tok", "userId": "u1", "inputEmailID": "a@b.c"}))
    assert (auth.access_token, auth.user_id, auth.email) == ("tok", "u1", "a@b.c")
    assert "tok" not in repr(auth)


def test_parse_callback_rejects_wrong_state():
    with pytest.raises(sc.SamsungCertError, match="state"):
        sc.parse_callback(_callback({"access_token": "t", "userId": "u", "inputEmailID": "e"}, state="other"))


def test_parse_callback_requires_email():
    with pytest.raises(sc.SamsungCertError, match="e-mail"):
        sc.parse_callback(_callback({"access_token": "t", "userId": "u", "inputEmailID": None}))


def test_distributor_csr_has_duids():
    csr = sc.distributor_csr(sc.new_key(), "dev@example.com", ["DUID0001", "DUID0002"])
    san = csr.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    assert san.get_values_for_type(x509.UniformResourceIdentifier) == [
        "URN:tizen:packageid=",
        "URN:tizen:deviceid=DUID0001",
        "URN:tizen:deviceid=DUID0002",
    ]
    assert csr.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value == "TizenSDK"
    assert csr.subject.get_attributes_for_oid(NameOID.EMAIL_ADDRESS)[0].value == "dev@example.com"
    assert csr.is_signature_valid


@pytest.mark.parametrize("duids", [[], ["bad duid"], ["x" * 65]])
def test_distributor_csr_validates_duids(duids):
    with pytest.raises(sc.SamsungCertError):
        sc.distributor_csr(sc.new_key(), "dev@example.com", duids)


def test_multipart():
    body, content_type = sc.multipart({"access_token": "t", "platform": "VD"}, "csr", "author.csr", b"PEM")
    boundary = content_type.split("boundary=")[1]
    assert body.startswith(f"--{boundary}\r\n".encode())
    assert b'name="platform"\r\n\r\nVD\r\n' in body
    assert b'name="csr"; filename="author.csr"' in body
    assert body.endswith(f"--{boundary}--\r\n".encode())


def test_parse_certificates_formats():
    chain = identity("x").chain
    pem = b"".join(c.public_bytes(serialization.Encoding.PEM) for c in chain)
    der = chain[0].public_bytes(serialization.Encoding.DER)
    assert len(sc.parse_certificates(pem)) == 2
    assert sc.parse_certificates(der) == [chain[0]]
    assert sc.parse_certificates(base64.b64encode(der)) == [chain[0]]
    assert sc.parse_certificates(json.dumps({"certificate": pem.decode()}).encode()) == chain
    with pytest.raises(sc.SamsungCertError):
        sc.parse_certificates(b"<error>unauthorized</error>")


def test_find_issuer_checks_name_and_signature(tmp_path):
    chain = identity("x").chain
    impostor = identity("x").chain[1]  # mesmo nome "x CA", outra chave
    (tmp_path / "a-impostor.cer").write_bytes(impostor.public_bytes(serialization.Encoding.DER))
    (tmp_path / "b-lixo.crt").write_bytes(b"nada")
    assert sc.find_issuer(chain[0], [tmp_path], []) is None
    (tmp_path / "c-real.crt").write_bytes(chain[1].public_bytes(serialization.Encoding.PEM))
    assert sc.find_issuer(chain[0], [tmp_path], []) == chain[1]


class FakeSamsung:
    """Emite certificados como a svdca: só a folha, assinada por uma CA de teste."""

    def __init__(self):
        self.author_ca, self.author_ca_key = make_ca("Samsung VD Author CA (teste)")
        self.dist_ca, self.dist_ca_key = make_ca("Samsung VD Distributor CA (teste)")
        self.logins = self.author_requests = self.distributor_requests = 0

    def login(self):
        self.logins += 1
        return sc.SamsungAuth("tok", "u1", "dev@example.com")

    def request_author(self, auth, csr):
        self.author_requests += 1
        return issue(csr, self.author_ca, self.author_ca_key).public_bytes(serialization.Encoding.PEM)

    def request_distributor(self, auth, csr, privilege):
        self.distributor_requests += 1
        return b"<profile/>", issue(csr, self.dist_ca, self.dist_ca_key).public_bytes(serialization.Encoding.PEM)


@pytest.fixture
def samsung(monkeypatch):
    fake = FakeSamsung()
    monkeypatch.setattr(sc, "request_author", fake.request_author)
    monkeypatch.setattr(sc, "request_distributor", fake.request_distributor)
    return fake


def test_profile_missing_ca_then_completed_without_new_requests(tmp_path, samsung):
    out = tmp_path / "perfil"
    first = sc.build_profile(out, "senha", ["DUID0001"], samsung.login, log=lambda _: None)
    assert set(first.missing_ca) == {"author", "distributor"}
    assert "Samsung VD Author CA (teste)" in first.missing_ca["author"]
    assert not (out / "author.p12").exists()
    assert b"ENCRYPTED PRIVATE KEY" in (out / "author-key.pem").read_bytes()
    assert (samsung.logins, samsung.author_requests, samsung.distributor_requests) == (1, 1, 1)

    ca_dir = out / "ca"
    ca_dir.mkdir()
    (ca_dir / "vd_tizen_dev_author_ca.cer").write_bytes(samsung.author_ca.public_bytes(serialization.Encoding.DER))
    (ca_dir / "vd_tizen_dev_public2.crt").write_bytes(samsung.dist_ca.public_bytes(serialization.Encoding.PEM))
    second = sc.build_profile(out, "senha", ["DUID0001"], samsung.login, log=lambda _: None)
    assert second.missing_ca == {}
    assert (samsung.logins, samsung.author_requests, samsung.distributor_requests) == (1, 1, 1)
    author = load_p12((out / "author.p12").read_bytes(), "senha")
    distributor = load_p12((out / "distributor.p12").read_bytes(), "senha")
    assert author.chain[1] == samsung.author_ca
    assert distributor.chain[1] == samsung.dist_ca
    assert sc.certificate_duids(distributor.chain[0]) == ["DUID0001"]


def test_profile_new_duid_requests_only_distributor(tmp_path, samsung):
    out = tmp_path / "perfil"
    ca_dir = out / "ca"
    ca_dir.mkdir(parents=True)
    (ca_dir / "author.cer").write_bytes(samsung.author_ca.public_bytes(serialization.Encoding.PEM))
    (ca_dir / "dist.crt").write_bytes(samsung.dist_ca.public_bytes(serialization.Encoding.PEM))
    sc.build_profile(out, "senha", ["DUID0001"], samsung.login, log=lambda _: None)
    sc.build_profile(out, "senha", ["DUID0001", "DUID0002"], samsung.login, log=lambda _: None)
    assert (samsung.author_requests, samsung.distributor_requests) == (1, 2)
    distributor = load_p12((out / "distributor.p12").read_bytes(), "senha")
    assert sc.certificate_duids(distributor.chain[0]) == ["DUID0001", "DUID0002"]


def test_profile_allow_missing_ca_writes_leaf_only(tmp_path, samsung):
    out = tmp_path / "perfil"
    result = sc.build_profile(out, "senha", ["DUID0001"], samsung.login, allow_missing_ca=True, log=lambda _: None)
    assert set(result.missing_ca) == {"author", "distributor"}
    assert len(load_p12((out / "author.p12").read_bytes(), "senha").chain) == 1
    assert len(load_p12((out / "distributor.p12").read_bytes(), "senha").chain) == 1


def test_leaf_only_author_p12_is_completed_later(tmp_path, samsung):
    out = tmp_path / "perfil"
    sc.build_profile(out, "senha", ["DUID0001"], samsung.login, allow_missing_ca=True, log=lambda _: None)
    leaf = load_p12((out / "author.p12").read_bytes(), "senha").chain[0]
    ca_dir = out / "ca"
    ca_dir.mkdir()
    (ca_dir / "a.cer").write_bytes(samsung.author_ca.public_bytes(serialization.Encoding.PEM))
    (ca_dir / "d.crt").write_bytes(samsung.dist_ca.public_bytes(serialization.Encoding.PEM))
    result = sc.build_profile(out, "senha", ["DUID0001"], samsung.login, log=lambda _: None)
    assert result.missing_ca == {}
    author = load_p12((out / "author.p12").read_bytes(), "senha")
    assert author.chain == [leaf, samsung.author_ca]
    assert samsung.author_requests == 1


def test_profile_wrong_password(tmp_path, samsung):
    out = tmp_path / "perfil"
    sc.build_profile(out, "senha", ["DUID0001"], samsung.login, log=lambda _: None)
    with pytest.raises(sc.SamsungCertError, match="senha"):
        sc.build_profile(out, "outra", ["DUID0001"], samsung.login, log=lambda _: None)


def test_p12_roundtrip_and_duids():
    ident = identity("d", san=["URN:tizen:packageid=", "URN:tizen:deviceid=ABC123"])
    data = sc.export_p12(ident.key, ident.chain, "s3nh@")
    loaded = load_p12(data, "s3nh@")
    assert loaded.chain == ident.chain
    assert sc.certificate_duids(loaded.chain[0]) == ["ABC123"]
