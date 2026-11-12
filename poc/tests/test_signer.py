import base64
import hashlib
import zipfile

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding

from certs import identity
from helpers import make_wgt
from tizen_poc.signer import AUTHOR_FILE, DISTRIBUTOR_FILE, SigningError, check_digests, sign_wgt

etree = pytest.importorskip("lxml.etree")
NS = {"ds": "http://www.w3.org/2000/09/xmldsig#"}


@pytest.fixture(scope="module")
def identities():
    return identity("author"), identity("distributor", san=["URN:tizen:packageid=", "URN:tizen:deviceid=TESTDUID01"])


@pytest.fixture
def signed(tmp_path, identities):
    source = make_wgt(tmp_path / "in.wgt", signed=True, extra={"img/my icon.png": b"\x89PNG..."})
    target = tmp_path / "out.wgt"
    sign_wgt(str(source), str(target), *identities)
    return target


def _verify_independently(xml: bytes, files: dict[str, bytes], identity) -> list[str]:
    """Verifica com a canonização do libxml2, sem reaproveitar o código do signer."""
    root = etree.fromstring(xml)
    signed_info = root.find("ds:SignedInfo", NS)
    canonical = etree.tostring(signed_info, method="c14n", exclusive=True, with_tail=False)
    assert signed_info.find("ds:SignatureMethod", NS).get("Algorithm").endswith("#rsa-sha512")
    signature = base64.b64decode(root.findtext("ds:SignatureValue", namespaces=NS))
    identity.chain[0].public_key().verify(signature, canonical, padding.PKCS1v15(), hashes.SHA512())
    uris = []
    for ref in signed_info.findall("ds:Reference", NS):
        uri = ref.get("URI")
        uris.append(uri)
        if uri == "#prop":
            # C14N inclusiva do subconjunto = C14N do Object como documento com os namespaces em escopo
            # (o tostring(c14n) de um subelemento do lxml emite xmlns="" espúrios nos netos).
            target = root.find("ds:Object[@Id='prop']", NS)
            standalone = etree.fromstring(etree.tostring(target, with_tail=False))
            data = etree.tostring(standalone, method="c14n", exclusive=False)
        else:
            from urllib.parse import unquote

            data = files[unquote(uri)]
        assert ref.find("ds:DigestMethod", NS).get("Algorithm").endswith("#sha512")
        digest = base64.b64encode(hashlib.sha512(data).digest()).decode()
        assert "".join(ref.findtext("ds:DigestValue", namespaces=NS).split()) == digest, uri
    certs = root.findall("ds:KeyInfo/ds:X509Data/ds:X509Certificate", NS)
    assert len(certs) == 2
    return uris


def test_both_signatures_verify(signed, identities):
    author, distributor = identities
    with zipfile.ZipFile(signed) as zf:
        files = {n: zf.read(n) for n in zf.namelist() if not n.endswith("/")}
    author_uris = _verify_independently(files[AUTHOR_FILE], files, author)
    distributor_uris = _verify_independently(files[DISTRIBUTOR_FILE], files, distributor)
    assert "img/my%20icon.png" in author_uris
    assert AUTHOR_FILE not in author_uris
    assert AUTHOR_FILE in distributor_uris
    assert DISTRIBUTOR_FILE not in distributor_uris
    assert author_uris[-1] == distributor_uris[-1] == "#prop"


def test_layout_matches_tizen_cli(signed):
    """Formato observado num pacote assinado pelo ``tizen package`` (Tizen Studio)."""
    import re

    with zipfile.ZipFile(signed) as zf:
        names = zf.namelist()
        xml = zf.read(AUTHOR_FILE).decode()
        distributor = zf.read(DISTRIBUTOR_FILE).decode()
    assert names == sorted(names)
    assert xml.startswith('<Signature xmlns="http://www.w3.org/2000/09/xmldsig#" Id="AuthorSignature">\n<SignedInfo>')
    assert xml.endswith("</Object>\n</Signature>")
    digest = re.search(r"<DigestValue>(.*?)</DigestValue>", xml, re.S).group(1)
    assert [len(line) for line in digest.split("\n")] == [76, 12]
    for tag in ("SignatureValue", "X509Certificate"):
        body = re.search(f"<{tag}>(.*?)</{tag}>", xml, re.S).group(1)
        lines = body.split("\n")
        assert lines[0] == "" and lines[-1] == "" and all(len(line) == 76 for line in lines[1:-2])
    uris = re.findall(r'<Reference URI="([^"]+)">', distributor)
    assert uris[:-1] == sorted(uris[:-1]) and uris[-1] == "#prop"


def test_roles_and_ids(signed):
    with zipfile.ZipFile(signed) as zf:
        author = etree.fromstring(zf.read(AUTHOR_FILE))
        distributor = etree.fromstring(zf.read(DISTRIBUTOR_FILE))
    assert author.get("Id") == "AuthorSignature"
    assert distributor.get("Id") == "DistributorSignature"
    dsp = {"dsp": "http://www.w3.org/2009/xmldsig-properties"}
    assert author.find(".//dsp:Role", dsp).get("URI").endswith("#role-author")
    assert distributor.find(".//dsp:Role", dsp).get("URI").endswith("#role-distributor")


def test_old_signatures_are_replaced_and_digests_match(signed):
    assert check_digests(str(signed)) == []
    with zipfile.ZipFile(signed) as zf:
        names = zf.namelist()
    assert names.count(AUTHOR_FILE) == 1
    assert names.count(DISTRIBUTOR_FILE) == 1
    assert b"<Signature/>" not in zipfile.ZipFile(signed).read(AUTHOR_FILE)


def test_tampered_file_detected(signed, tmp_path):
    tampered = tmp_path / "tampered.wgt"
    with zipfile.ZipFile(signed) as src, zipfile.ZipFile(tampered, "w") as dst:
        for info in src.infolist():
            data = src.read(info)
            dst.writestr(info, b"changed" if info.filename == "index.html" else data)
    assert any("index.html" in p for p in check_digests(str(tampered)))


def test_path_traversal_rejected(tmp_path, identities):
    source = make_wgt(tmp_path / "evil.wgt", extra={"../evil.txt": b"x"})
    with pytest.raises(SigningError):
        sign_wgt(str(source), str(tmp_path / "out.wgt"), *identities)
