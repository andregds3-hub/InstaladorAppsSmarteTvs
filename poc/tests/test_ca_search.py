import zipfile

from cryptography.hazmat.primitives import serialization

from certs import make_ca
from tizen_poc import ca_search


def test_finds_samsung_cas_in_files_and_jars(tmp_path):
    author, _ = make_ca("Samsung VD Author CA")
    public, _ = make_ca("Samsung VD Public Distributor CA")
    other, _ = make_ca("Outra CA qualquer")
    plugins = tmp_path / "tizen-studio" / "plugins"
    plugins.mkdir(parents=True)
    with zipfile.ZipFile(plugins / "com.samsung.cert.jar", "w") as jar:
        jar.writestr("res/ca/vd_tizen_dev_author_ca.cer", author.public_bytes(serialization.Encoding.DER))
        jar.writestr("res/ca/other.crt", other.public_bytes(serialization.Encoding.PEM))
        jar.writestr("Main.class", b"\xca\xfe")
    (plugins / "vd_tizen_dev_public2.crt").write_bytes(public.public_bytes(serialization.Encoding.PEM))
    (plugins / "broken.jar").write_bytes(b"not a zip")

    found = list(ca_search.scan(tmp_path))
    assert {cert.subject.rfc4514_string() for _, cert in found} == {
        "CN=Samsung VD Author CA",
        "CN=Samsung VD Public Distributor CA",
    }
    assert any(origin.endswith("!res/ca/vd_tizen_dev_author_ca.cer") for origin, _ in found)

    written = ca_search.export([cert for _, cert in found] * 2, tmp_path / "ca")
    assert len(written) == 2


def test_finds_ca_in_jar_inside_zip(tmp_path):
    import io

    ca, _ = make_ca("VD DEVELOPER Public CA Class")
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as jar:
        jar.writestr("certs/vd_tizen_dev_public2.crt", ca.public_bytes(serialization.Encoding.PEM))
    with zipfile.ZipFile(tmp_path / "extension.zip", "w") as outer:
        outer.writestr("plugins/com.samsung.cert.jar", inner.getvalue())
    found = list(ca_search.scan(tmp_path, name_filter="VD DEVELOPER"))
    assert [origin.split("!", 1)[1] for origin, _ in found] == ["plugins/com.samsung.cert.jar!certs/vd_tizen_dev_public2.crt"]


class _BrokenExtensions:
    """Imita um certificado do SDK cujas extensões não decodificam."""

    def __init__(self, subject):
        self.subject = subject

    @property
    def extensions(self):
        raise ValueError("error parsing asn1 value: PolicyInformation::policy_identifier")


def test_malformed_extensions_do_not_abort():
    ca, _ = make_ca("Samsung VD Author CA")
    leaf, _ = make_ca("Samsung leaf")
    assert ca_search._is_ca(_BrokenExtensions(ca.subject))
    assert not ca_search._is_ca(_BrokenExtensions(leaf.subject))
